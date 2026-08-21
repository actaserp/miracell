package mes.app.production.service;

import mes.domain.entity.*;
import mes.domain.model.AjaxResult;
import mes.domain.repository.*;
import mes.domain.services.DateUtil;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 세척(bsc01) 서비스.
 *
 * 세척은 작업지시/라우팅/BOM 을 쓰지 않는다.
 *   - 대상 = material."WashYN" = 'Y' 이면서 생산창고(S-7, 17)에 재고가 있는 모든 품목
 *   - 동작 = 생산창고 → 클린룸창고(S-3, 5) 재고 이동 (품목 동일, 로트번호 유지)
 *   - 실적 = wash_work(하루×작업자×세척기) + wash_work_item(품목별)
 *   - 작업 상태/시작/종료는 저장하지 않고 품목 행에서 파생 (작업 완료 버튼 없음)
 *   - 품목 행 완료 시점에 재고 이동 + equ_run 1건
 *
 * ★ 트리거 주의
 *   mat_lot_cons_after_tri  : mat_lot_cons INSERT/DELETE → mat_lot."CurrentStock"/"OutQtySum" 자동 갱신
 *   matinout_tri            : mat_inout INSERT/DELETE     → 재고 집계 자동 갱신
 *   → 코드에서 CurrentStock 을 직접 UPDATE 하지 말 것 (이중 차감).
 *   → 완료취소는 mat_lot_cons / mat_inout 행을 DELETE 하면 트리거가 롤백해 준다.
 */
@Service
public class WashService {

    /** 생산창고 S-7 (불출 자재 보관) — 세척 투입 */
    public static final int STORE_PROD    = 17;
    /** 클린룸창고 S-3 — 세척 산출 */
    public static final int STORE_CLEAN   = 5;
    /** 부적합품창고 S-4 — 세척 불량 */
    public static final int STORE_DEFECT  = 2;

    private static final String SRC_TABLE = "wash_work_item";

    @Autowired SqlRunner sqlRunner;
    @Autowired WorkMemberService workMemberService;
    @Autowired MaterialRepository materialRepository;
    @Autowired MatLotRepository matLotRepository;
    @Autowired MatLotConsRepository matLotConsRepository;
    @Autowired MatInoutRepository matInoutRepository;
    @Autowired EquRunRepository equRunRepository;

    // =================================================================
    // 조회
    // =================================================================

    /**
     * 세척 공정 컨텍스트 — 공정↔워크센터 1:1 전제.
     * process."Code"='bsc01' 인 공정의 워크센터를 찾아준다.
     * 설비 콤보(/api/common/combo?combo_type=equipment&cond3=…)의 cond3 로 쓴다.
     * 공장이 여러 개면 factoryId 로 좁힌다.
     */
    public Map<String, Object> getContext(Integer factoryId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("factoryId", factoryId);
        String sql = """
            SELECT p.id            AS process_id
                 , p."Code"        AS process_code
                 , p."Name"        AS process_name
                 , wc.id           AS workcenter_id
                 , wc."Name"       AS workcenter_name
                 , wc."Factory_id" AS factory_id
              FROM process p
              LEFT JOIN work_center wc ON wc."Process_id" = p.id
             WHERE p."Code" = 'bsc01'
               AND (CAST(:factoryId AS INTEGER) IS NULL OR wc."Factory_id" = CAST(:factoryId AS INTEGER))
             ORDER BY wc.id
             LIMIT 1
            """;
        Map<String, Object> row = this.sqlRunner.getRow(sql, p);
        Map<String, Object> data = new HashMap<>();
        if (row != null) data.putAll(row);
        data.put("in_store_id", STORE_PROD);
        data.put("out_store_id", STORE_CLEAN);
        return data;
    }

    /**
     * 날짜 카드 목록. 작업/품목에서 상태·수량을 파생시킨다.
     * ★ 당일 빈 카드는 DB에 만들지 않는다 — 화면에서 ensureToday() 로 띄우고,
     *   첫 작업 시작 시 wash_work 행이 생긴다.
     */
    public List<Map<String, Object>> getDayList(String dateFrom, String dateTo, String spjangcd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("dateFrom", LocalDate.parse(dateFrom));
        p.addValue("dateTo",   LocalDate.parse(dateTo));
        p.addValue("spjangcd", spjangcd);

        String sql = """
            SELECT ww."WashDate"                                       AS wash_date
                 , to_char(ww."WashDate", 'yyyy-mm-dd')                AS wash_date_str
                 , COUNT(DISTINCT ww.id)                               AS work_cnt
                 , COUNT(wwi.id)                                       AS item_cnt
                 , COUNT(wwi.id) FILTER (WHERE wwi."State" = 'done')   AS item_done_cnt
                 , COUNT(wwi.id) FILTER (WHERE wwi."State" = 'working') AS item_working_cnt
                 , COUNT(wwi.id) FILTER (WHERE wwi."State" = 'wait')   AS item_wait_cnt
                 , COALESCE(SUM(wwi."Qty")       FILTER (WHERE wwi."State" = 'done'), 0) AS good_qty
                 , COALESCE(SUM(wwi."DefectQty") FILTER (WHERE wwi."State" = 'done'), 0) AS defect_qty
                 , CASE WHEN COUNT(wwi.id) = 0 THEN 'wait'
                        WHEN COUNT(wwi.id) = COUNT(wwi.id) FILTER (WHERE wwi."State" = 'done') THEN 'done'
                        ELSE 'working' END                             AS state
              FROM wash_work ww
              LEFT JOIN wash_work_item wwi
                     ON wwi."WashWork_id" = ww.id AND wwi."_status" = 'a'
             WHERE ww."_status" = 'a'
               AND ww.spjangcd  = :spjangcd
               AND ww."WashDate" BETWEEN :dateFrom AND :dateTo
             GROUP BY ww."WashDate"
             ORDER BY ww."WashDate" DESC
            """;
        return this.sqlRunner.getRows(sql, p);
    }

    /** 특정 날짜의 작업(작업자 카드) 목록 — 상태/시작/종료는 품목에서 파생 */
    public List<Map<String, Object>> getWorkList(String date, String spjangcd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("date", LocalDate.parse(date));
        p.addValue("spjangcd", spjangcd);

        String sql = """
            SELECT ww.id                                    AS work_id
                 , ww."Actor_id"                            AS actor_id
                 , pr."Name"                                AS worker_name
                 , ${MEMBER_NAMES}                           AS members
                 , ${MEMBER_IDS}                             AS member_ids
                 , ${MEMBER_CNT}                             AS member_cnt
                 , ww."Equipment_id"                        AS equipment_id
                 , e."Name"                                 AS equipment_name
                 , e."Code"                                 AS equipment_code
                 , ww."InStoreHouse_id"                     AS in_store_id
                 , ww."OutStoreHouse_id"                    AS out_store_id
                 , ww."Description"                         AS description
                 , to_char(MIN(wwi."StartTime"), 'yyyy-mm-dd hh24:mi')   AS start_time
                 , CASE WHEN COUNT(wwi.id) > 0
                         AND COUNT(wwi.id) = COUNT(wwi.id) FILTER (WHERE wwi."State" = 'done')
                        THEN to_char(MAX(wwi."EndTime"), 'yyyy-mm-dd hh24:mi')
                        ELSE NULL END                       AS end_time
                 , COALESCE(SUM(wwi."Qty") FILTER (WHERE wwi."State" = 'done'), 0) AS good_qty
                 , COUNT(wwi.id)                                        AS item_cnt
                 , COUNT(wwi.id) FILTER (WHERE wwi."State" = 'done')    AS item_done_cnt
                 , CASE WHEN COUNT(wwi.id) = 0 THEN 'wait'
                        WHEN COUNT(wwi.id) = COUNT(wwi.id) FILTER (WHERE wwi."State" = 'done') THEN 'done'
                        ELSE 'working' END                  AS state
              FROM wash_work ww
              LEFT JOIN wash_work_item wwi
                     ON wwi."WashWork_id" = ww.id AND wwi."_status" = 'a'
              LEFT JOIN person pr ON pr.id = ww."Actor_id"
              LEFT JOIN equ e     ON e.id  = ww."Equipment_id"
             WHERE ww."_status" = 'a'
               AND ww.spjangcd  = :spjangcd
               AND ww."WashDate" = :date
             GROUP BY ww.id, pr."Name", e."Name", e."Code"
             ORDER BY MIN(wwi."StartTime") NULLS LAST, ww.id
            """
                /* ★ 조원은 스칼라 서브쿼리로 붙인다. LATERAL 로 조인하면 조원 수만큼
                      행이 복제되어 위의 COUNT/SUM 이 배로 부풀어 오른다. */
                .replace("${MEMBER_NAMES}", WorkMemberService.namesSql("wash_work", "ww.id"))
                .replace("${MEMBER_IDS}",   WorkMemberService.idsSql("wash_work", "ww.id"))
                .replace("${MEMBER_CNT}",   WorkMemberService.countSql("wash_work", "ww.id"));
        return this.sqlRunner.getRows(sql, p);
    }

    /** 작업의 품목 행 목록 (현재 생산창고 재고 포함) */
    public List<Map<String, Object>> getItemList(Integer workId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("workId", workId);
        p.addValue("storeProd", STORE_PROD);

        String sql = """
            SELECT wwi.id                        AS item_id
                 , wwi."Material_id"             AS mat_id
                 , m."Code"                      AS mat_code
                 , m."Name"                      AS mat_name
                 , u."Name"                      AS unit
                 , COALESCE(wwi."Qty", 0)        AS qty
                 , COALESCE(wwi."DefectQty", 0)  AS defect_qty
                 , wwi."State"                   AS state
                 , to_char(wwi."StartTime", 'yyyy-mm-dd hh24:mi') AS start_time
                 , to_char(wwi."EndTime",   'yyyy-mm-dd hh24:mi') AS end_time
                 , wwi."Description"             AS description
                 , COALESCE((SELECT SUM(ml."CurrentStock") FROM mat_lot ml
                              WHERE ml."Material_id" = wwi."Material_id"
                                AND ml."StoreHouse_id" = :storeProd
                                AND ml."CurrentStock" > 0), 0) AS stock
              FROM wash_work_item wwi
              LEFT JOIN material m ON m.id = wwi."Material_id"
              LEFT JOIN unit u     ON u.id = m."Unit_id"
             WHERE wwi."WashWork_id" = :workId
               AND wwi."_status" = 'a'
             ORDER BY wwi.id
            """;
        return this.sqlRunner.getRows(sql, p);
    }

    /**
     * 세척 대상 품목 = WashYN='Y' + 생산창고 재고 > 0.
     * 작지/BOM 전개 없음. 이미 작업에 담긴 품목은 제외.
     */
    public List<Map<String, Object>> getTargetMaterials(Integer workId, String keyword, String spjangcd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("workId", workId);
        p.addValue("storeProd", STORE_PROD);
        p.addValue("spjangcd", spjangcd);
        p.addValue("keyword", (keyword == null || keyword.isBlank()) ? null : "%" + keyword + "%");

        String sql = """
            SELECT m.id                          AS mat_id
                 , m."Code"                      AS mat_code
                 , m."Name"                      AS mat_name
                 , u."Name"                      AS unit
                 , COALESCE(SUM(ml."CurrentStock"), 0) AS stock
              FROM material m
              JOIN mat_lot ml ON ml."Material_id" = m.id
                             AND ml."StoreHouse_id" = :storeProd
                             AND ml."CurrentStock" > 0
              LEFT JOIN unit u ON u.id = m."Unit_id"
             WHERE COALESCE(m."WashYN", 'N') = 'Y'
               AND m."_status" = 'a'
               AND (CAST(:keyword AS VARCHAR) IS NULL
                    OR m."Name" LIKE CAST(:keyword AS VARCHAR)
                    OR m."Code" LIKE CAST(:keyword AS VARCHAR))
             GROUP BY m.id, m."Code", m."Name", u."Name"
             ORDER BY m."Code"
            """;
        return this.sqlRunner.getRows(sql, p);
    }

    // =================================================================
    // 작업
    // =================================================================

    /** 작업 시작 — 같은 날/작업자/세척기면 기존 작업 재사용 */
    @Transactional
    public AjaxResult workStart(String date, Integer actorId, String memberIds, Integer equipmentId,
                                String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        if (actorId == null)     { r.success = false; r.message = "작업자를 선택해주세요."; return r; }
        if (equipmentId == null) { r.success = false; r.message = "세척기를 선택해주세요."; return r; }

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("date", LocalDate.parse(date));
        p.addValue("actorId", actorId);
        p.addValue("equipmentId", equipmentId);
        p.addValue("spjangcd", spjangcd);

        Map<String, Object> exist = this.sqlRunner.getRow("""
            SELECT id FROM wash_work
             WHERE "WashDate" = :date AND "Actor_id" = :actorId
               AND "Equipment_id" = :equipmentId AND spjangcd = :spjangcd
               AND "_status" = 'a'
            """, p);
        if (exist != null && exist.get("id") != null) {
            int reusedId = ((Number) exist.get("id")).intValue();
            // 재사용이어도 조 편성은 갱신한다. 안 그러면 조원을 바꿔 다시 열었을 때 옛 명단이 남는다.
            this.workMemberService.save(WorkMemberService.SRC_WASH_WORK,
                    reusedId, actorId, memberIds, user, spjangcd);
            r.data = Map.of("work_id", reusedId, "reused", true);
            return r;
        }

        p.addValue("inStore",  STORE_PROD);
        p.addValue("outStore", STORE_CLEAN);
        p.addValue("userId", user.getId());
        Map<String, Object> row = this.sqlRunner.getRow("""
            INSERT INTO wash_work
                ("WashDate", "Actor_id", "Equipment_id", "InStoreHouse_id", "OutStoreHouse_id",
                 "_status", "_created", "_creater_id", spjangcd)
            VALUES (:date, :actorId, :equipmentId, :inStore, :outStore,
                    'a', now(), :userId, :spjangcd)
            RETURNING id
            """, p);

        int workId = ((Number) row.get("id")).intValue();
        this.workMemberService.save(WorkMemberService.SRC_WASH_WORK,
                workId, actorId, memberIds, user, spjangcd);

        r.data = Map.of("work_id", workId, "reused", false);
        return r;
    }

    /** 작업 삭제 — 완료된 품목이 하나라도 있으면 불가 */
    @Transactional
    public AjaxResult workDelete(Integer workId, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        MapSqlParameterSource p = new MapSqlParameterSource().addValue("workId", workId);
        Map<String, Object> cnt = this.sqlRunner.getRow("""
            SELECT COUNT(*) AS c FROM wash_work_item
             WHERE "WashWork_id" = :workId AND "_status" = 'a' AND "State" = 'done'
            """, p);
        if (cnt != null && ((Number) cnt.get("c")).intValue() > 0) {
            r.success = false;
            r.message = "완료된 세척 품목이 있어 삭제할 수 없습니다. 품목 완료취소 후 삭제하세요.";
            return r;
        }
        p.addValue("userId", user.getId());
        this.sqlRunner.execute("""
            UPDATE wash_work_item SET "_status" = 'd', "_modified" = now(), "_modifier_id" = :userId
             WHERE "WashWork_id" = :workId
            """, p);
        this.sqlRunner.execute("""
            UPDATE wash_work SET "_status" = 'd', "_modified" = now(), "_modifier_id" = :userId
             WHERE id = :workId
            """, p);
        this.workMemberService.clear(WorkMemberService.SRC_WASH_WORK, workId);
        return r;
    }

    // =================================================================
    // 품목 행
    // =================================================================

    /** 품목 행 추가 (수량 0, wait) */
    @Transactional
    public AjaxResult itemAdd(Integer workId, Integer matId, String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("workId", workId);
        p.addValue("matId", matId);
        p.addValue("spjangcd", spjangcd);
        p.addValue("userId", user.getId());

        // 같은 품목을 여러 번 담을 수 있다(세척은 배치별로 같은 품목을 나눠 담기도 함). 중복 차단 없음.
        Map<String, Object> row = this.sqlRunner.getRow("""
            INSERT INTO wash_work_item
                ("WashWork_id", "Material_id", "Qty", "DefectQty", "State",
                 "_status", "_created", "_creater_id", spjangcd)
            VALUES (:workId, :matId, 0, 0, 'wait', 'a', now(), :userId, :spjangcd)
            RETURNING id
            """, p);
        r.data = Map.of("item_id", ((Number) row.get("id")).intValue());
        return r;
    }

    /** 수량/비고 저장 (완료 전) */
    @Transactional
    public AjaxResult itemSave(Integer itemId, Float qty, Float defectQty, String description, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        Map<String, Object> it = getItemRow(itemId);
        if (it == null)                          { r.success = false; r.message = "품목을 찾을 수 없습니다."; return r; }
        if ("done".equals(it.get("State")))      { r.success = false; r.message = "완료된 품목은 수정할 수 없습니다. 완료취소 후 수정하세요."; return r; }

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("itemId", itemId);
        p.addValue("qty", qty == null ? 0f : qty);
        p.addValue("defectQty", defectQty == null ? 0f : defectQty);
        p.addValue("description", description);
        p.addValue("userId", user.getId());
        this.sqlRunner.execute("""
            UPDATE wash_work_item
               SET "Qty" = :qty, "DefectQty" = :defectQty, "Description" = :description,
                   "State" = CASE WHEN "State" = 'wait' AND :qty > 0 THEN 'working' ELSE "State" END,
                   "StartTime" = COALESCE("StartTime", CASE WHEN :qty > 0 THEN now()::timestamp ELSE NULL END),
                   "_modified" = now(), "_modifier_id" = :userId
             WHERE id = :itemId
            """, p);
        return r;
    }

    /**
     * 품목 행 시작 (세척기 투입).
     * 수량은 시작 전/후 언제든 입력 가능하므로, 화면에서 들고 있던 값을 같이 받아 저장한다.
     * (화면은 스텝퍼/패드 조작마다 서버를 부르지 않는다 — 시작/완료 시점에만 보낸다)
     */
    @Transactional
    public AjaxResult itemStart(Integer itemId, Float qty, Float defectQty, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("itemId", itemId);
        p.addValue("qty", qty);
        p.addValue("defectQty", defectQty);
        p.addValue("userId", user.getId());
        // ★ 시작시각 = 작업일(WashDate) 날짜 + 현재 시각.
        //   작업일이 과거면 그 날짜로 기록해야 종료(= 같은 작업일 기준)보다 앞서지 않는다.
        this.sqlRunner.execute("""
            UPDATE wash_work_item wwi
               SET "State" = 'working',
                   "StartTime" = COALESCE(wwi."StartTime",
                        (SELECT ww."WashDate" + CURRENT_TIME
                           FROM wash_work ww WHERE ww.id = wwi."WashWork_id")::timestamp),
                   "Qty"       = COALESCE(CAST(:qty AS DOUBLE PRECISION), wwi."Qty"),
                   "DefectQty" = COALESCE(CAST(:defectQty AS DOUBLE PRECISION), wwi."DefectQty"),
                   "_modified" = now(), "_modifier_id" = :userId
             WHERE wwi.id = :itemId AND wwi."State" <> 'done'
            """, p);
        return r;
    }

    /** 품목 행 삭제 (완료 전만) */
    @Transactional
    public AjaxResult itemDelete(Integer itemId, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        Map<String, Object> it = getItemRow(itemId);
        if (it == null)                     { r.success = false; r.message = "품목을 찾을 수 없습니다."; return r; }
        if ("done".equals(it.get("State"))) { r.success = false; r.message = "완료된 품목은 삭제할 수 없습니다."; return r; }

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("itemId", itemId);
        p.addValue("userId", user.getId());
        this.sqlRunner.execute("""
            UPDATE wash_work_item SET "_status" = 'd', "_modified" = now(), "_modifier_id" = :userId
             WHERE id = :itemId
            """, p);
        return r;
    }

    // =================================================================
    // ★ 품목 행 완료 = 재고 이동 + equ_run
    // =================================================================

    /**
     * 품목 행 완료.
     *  1) 생산창고(17) mat_lot FIFO 차감 → mat_lot_cons  (트리거가 CurrentStock 갱신)
     *  2) 클린룸(5)에 같은 LotNumber·같은 Material 로 mat_lot 신규 (소스 로트별 1행)
     *  3) 불량분은 부적합품창고(2)로 동일 방식 입고
     *  4) mat_inout out/in 기록
     *  5) equ_run 1건 (start=StartTime, end=EndTime, complete)
     */
    @Transactional
    public AjaxResult itemFinish(Integer itemId, Float qty, Float defectQty,
                                 String startTimeStr, String endTimeStr,
                                 User user, String spjangcd) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        Map<String, Object> it = getItemRow(itemId);
        if (it == null)                     { r.success = false; r.message = "품목을 찾을 수 없습니다."; return r; }
        if ("done".equals(it.get("State"))) { r.success = false; r.message = "이미 완료된 품목입니다."; return r; }

        float good = (qty       == null) ? toF(it.get("Qty"))       : qty;
        float def  = (defectQty == null) ? toF(it.get("DefectQty")) : defectQty;
        float consume = good + def;
        if (consume <= 0) { r.success = false; r.message = "세척 수량을 입력해주세요."; return r; }

        Integer matId   = ((Number) it.get("Material_id")).intValue();
        Integer workId  = ((Number) it.get("WashWork_id")).intValue();
        Map<String, Object> work = getWorkRow(workId);
        Integer inStore  = ((Number) work.get("InStoreHouse_id")).intValue();
        Integer outStore = ((Number) work.get("OutStoreHouse_id")).intValue();
        Integer equipId  = ((Number) work.get("Equipment_id")).intValue();
        Integer actorId  = ((Number) work.get("Actor_id")).intValue();

        DateTimeFormatter dtm = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        Timestamp now = DateUtil.getNowTimeStamp();

        // 시작 폴백만 작업일(WashDate) 기준. 종료는 실제 완료 시각(now) — 며칠 뒤 완료도 정상.
        java.sql.Date washDate = (java.sql.Date) work.get("WashDate");
        LocalDateTime washStart = (washDate != null)
                ? LocalDateTime.of(washDate.toLocalDate(), LocalTime.now().withNano(0))
                : now.toLocalDateTime();

        Timestamp startTs = (startTimeStr != null && !startTimeStr.isBlank())
                ? Timestamp.valueOf(LocalDateTime.parse(startTimeStr, dtm))
                : (it.get("StartTime") != null ? (Timestamp) it.get("StartTime") : Timestamp.valueOf(washStart));
        Timestamp endTs = (endTimeStr != null && !endTimeStr.isBlank())
                ? Timestamp.valueOf(LocalDateTime.parse(endTimeStr, dtm))
                : now;

        // 사용자가 직접 넣은 종료가 시작보다 앞서면 오류. (폴백 now 는 시작보다 앞설 수 없다)
        if (endTs.before(startTs)) {
            r.success = false; r.message = "종료시각이 시작시각보다 빠릅니다."; return r;
        }

        Material mat = this.materialRepository.getMaterialById(matId);
        LocalDate today = LocalDate.now();
        LocalTime nowT  = LocalTime.now();

        // ── (1) 생산창고 FIFO 로트 조회 ──
        MapSqlParameterSource lp = new MapSqlParameterSource();
        lp.addValue("matId", matId);
        lp.addValue("inStore", inStore);
        List<Map<String, Object>> lots = this.sqlRunner.getRows("""
            SELECT ml.id AS ml_id, ml."LotNumber" AS lot_no, ml."CurrentStock" AS cs
              FROM mat_lot ml
             WHERE ml."Material_id" = :matId
               AND ml."StoreHouse_id" = :inStore
               AND ml."CurrentStock" > 0
             ORDER BY ml."InputDateTime" ASC, ml.id ASC
            """, lp);

        float remainGood = good;
        float remainDef  = def;
        float remain     = consume;

        for (Map<String, Object> lot : lots) {
            if (remain <= 0) break;
            int mlId    = ((Number) lot.get("ml_id")).intValue();
            String lotNo = String.valueOf(lot.get("lot_no"));
            float cs    = toF(lot.get("cs"));
            float take  = Math.min(cs, remain);
            if (take <= 0) continue;

            // 생산창고 차감 (트리거가 mat_lot.CurrentStock 갱신)
            MatLotCons mlc = new MatLotCons();
            mlc.setMaterialLotId(mlId);
            mlc.setOutputDateTime(now);
            mlc.setSourceDataPk(itemId);
            mlc.setSourceTableName(SRC_TABLE);
            mlc.setCurrentStock(cs);
            mlc.setOutputQty(take);
            mlc.set_audit(user);
            mlc.setSpjangcd(spjangcd);
            this.matLotConsRepository.save(mlc);

            // 이 소스 로트에서 나온 양품/불량 배분 (양품 우선)
            float g = Math.min(take, remainGood);
            float d = take - g;
            remainGood -= g;
            remainDef  -= d;
            remain     -= take;

            // 클린룸 입고 — 로트번호 유지, 새 채번 없음
            if (g > 0) newLot(lotNo, matId, outStore, g, itemId, "세척완료 입고(클린룸)", user, spjangcd, now);
            // 불량 → 부적합품창고
            if (d > 0) newLot(lotNo, matId, STORE_DEFECT, d, itemId, "세척 불량(부적합품창고)", user, spjangcd, now);
        }

        if (remain > 0) {
            r.success = false;
            r.message = "생산창고 재고가 부족합니다. (" + (mat != null ? mat.getName() : ("자재" + matId))
                    + ", 부족수량: " + remain + ")";
            throw new IllegalStateException(r.message);   // 롤백
        }

        // ── (4) mat_inout : 생산창고 출고 ──
        MaterialInout out = new MaterialInout();
        out.setMaterialId(matId);
        out.setStoreHouseId(inStore);
        out.setInOut("out");
        out.setOutputType("move_out");
        out.setOutputQty(consume);
        out.setInoutDate(today);
        out.setInoutTime(nowT);
        out.setDescription("세척 투입(생산창고 출고)");
        out.setState("confirmed");
        out.set_status("a");
        out.setSourceDataPk(itemId);
        out.setSourceTableName(SRC_TABLE);
        out.setActorId(actorId);
        out.set_audit(user);
        out.setSpjangcd(spjangcd);
        this.matInoutRepository.save(out);

        // 클린룸 입고
        if (good > 0) {
            MaterialInout in = new MaterialInout();
            in.setMaterialId(matId);
            in.setStoreHouseId(outStore);
            in.setInOut("in");
            in.setInputType("move_in");
            in.setInputQty(good);
            in.setInoutDate(today);
            in.setInoutTime(nowT);
            in.setDescription("세척 완료(클린룸창고 입고)");
            in.setState("confirmed");
            in.set_status("a");
            in.setSourceDataPk(itemId);
            in.setSourceTableName(SRC_TABLE);
            in.setActorId(actorId);
            in.set_audit(user);
            in.setSpjangcd(spjangcd);
            this.matInoutRepository.save(in);
        }
        // 부적합품창고 입고
        if (def > 0) {
            MaterialInout dfin = new MaterialInout();
            dfin.setMaterialId(matId);
            dfin.setStoreHouseId(STORE_DEFECT);
            dfin.setInOut("in");
            dfin.setInputType("move_in");
            dfin.setInputQty(def);
            dfin.setDefectQty(def);
            dfin.setInoutDate(today);
            dfin.setInoutTime(nowT);
            dfin.setDescription("세척 불량(부적합품창고 입고)");
            dfin.setState("confirmed");
            dfin.set_status("a");
            dfin.setSourceDataPk(itemId);
            dfin.setSourceTableName(SRC_TABLE);
            dfin.setActorId(actorId);
            dfin.set_audit(user);
            dfin.setSpjangcd(spjangcd);
            this.matInoutRepository.save(dfin);
        }

        // ── (5) equ_run : 세척기 가동 1건 (작지 없이 insert — 전 컬럼 nullable 확인됨) ──
        EquRun er = new EquRun();
        er.setEquipmentId(equipId);
        er.setStartDate(startTs);
        er.setEndDate(endTs);
        er.setRunState("complete");
        er.setActorId(actorId);
        er.setDescription("세척 - " + (mat != null ? mat.getName() : ""));
        er.set_audit(user);
        er.setSpjangcd(spjangcd);
        er = this.equRunRepository.save(er);

        // ── 품목 행 완료 ──
        MapSqlParameterSource up = new MapSqlParameterSource();
        up.addValue("itemId", itemId);
        up.addValue("qty", good);
        up.addValue("defectQty", def);
        up.addValue("startTs", startTs);
        up.addValue("endTs", endTs);
        up.addValue("equRunId", er.getId());
        up.addValue("userId", user.getId());
        this.sqlRunner.execute("""
            UPDATE wash_work_item
               SET "Qty" = :qty, "DefectQty" = :defectQty, "State" = 'done',
                   "StartTime" = :startTs, "EndTime" = :endTs, "EquRun_id" = :equRunId,
                   "_modified" = now(), "_modifier_id" = :userId
             WHERE id = :itemId
            """, up);

        r.data = Map.of("item_id", itemId, "qty", good, "defect_qty", def);
        return r;
    }

    /**
     * 품목 행 완료취소 — 클린룸 로트가 아직 소진되지 않았을 때만.
     * mat_lot_cons / mat_inout 을 DELETE 하면 트리거가 재고를 되돌린다.
     */
    @Transactional
    public AjaxResult itemCancel(Integer itemId, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        Map<String, Object> it = getItemRow(itemId);
        if (it == null)                      { r.success = false; r.message = "품목을 찾을 수 없습니다."; return r; }
        if (!"done".equals(it.get("State"))) { r.success = false; r.message = "완료된 품목이 아닙니다."; return r; }

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("itemId", itemId);
        p.addValue("srcTable", SRC_TABLE);

        // 이 세척으로 만든 클린룸/부적합 로트가 이미 소진되었는지 확인 (조립에서 썼으면 취소 불가)
        Map<String, Object> used = this.sqlRunner.getRow("""
            SELECT COUNT(*) AS c
              FROM mat_lot ml
             WHERE ml."SourceTableName" = :srcTable AND ml."SourceDataPk" = :itemId
               AND COALESCE(ml."CurrentStock", 0) < COALESCE(ml."InputQty", 0)
            """, p);
        if (used != null && ((Number) used.get("c")).intValue() > 0) {
            r.success = false;
            r.message = "세척된 자재가 이미 후속 공정에서 사용되어 완료취소할 수 없습니다.";
            return r;
        }

        // 산출 로트 삭제 → 트리거가 재고 집계 정리
        this.sqlRunner.execute("""
            DELETE FROM mat_lot WHERE "SourceTableName" = :srcTable AND "SourceDataPk" = :itemId
            """, p);
        // 투입 차감 취소 → 트리거가 생산창고 CurrentStock 복구
        this.sqlRunner.execute("""
            DELETE FROM mat_lot_cons WHERE "SourceTableName" = :srcTable AND "SourceDataPk" = :itemId
            """, p);
        // 입출고 이력 삭제
        this.sqlRunner.execute("""
            DELETE FROM mat_inout WHERE "SourceTableName" = :srcTable AND "SourceDataPk" = :itemId
            """, p);
        // equ_run 삭제
        if (it.get("EquRun_id") != null) {
            MapSqlParameterSource ep = new MapSqlParameterSource()
                    .addValue("equRunId", ((Number) it.get("EquRun_id")).intValue());
            this.sqlRunner.execute("DELETE FROM equ_run WHERE id = :equRunId", ep);
        }

        p.addValue("userId", user.getId());
        this.sqlRunner.execute("""
            UPDATE wash_work_item
               SET "State" = 'working', "EndTime" = NULL, "EquRun_id" = NULL,
                   "_modified" = now(), "_modifier_id" = :userId
             WHERE id = :itemId
            """, p);
        return r;
    }

    /** 완료된 품목의 시간 수정 (PC 전용) */
    @Transactional
    public AjaxResult itemUpdateTime(Integer itemId, String startTimeStr, String endTimeStr, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        DateTimeFormatter dtm = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        Map<String, Object> it = getItemRow(itemId);
        if (it == null) { r.success = false; r.message = "품목을 찾을 수 없습니다."; return r; }

        Timestamp s = (startTimeStr == null || startTimeStr.isBlank()) ? null
                : Timestamp.valueOf(LocalDateTime.parse(startTimeStr, dtm));
        Timestamp e = (endTimeStr == null || endTimeStr.isBlank()) ? null
                : Timestamp.valueOf(LocalDateTime.parse(endTimeStr, dtm));

        // ★ 한쪽만 수정해도 역전이 생기지 않도록, 수정 후 '최종' 시작/종료 조합으로 검증한다.
        //   넘어오지 않은 쪽은 DB 의 기존 값을 사용.
        Timestamp finalS = (s != null) ? s : (Timestamp) it.get("StartTime");
        Timestamp finalE = (e != null) ? e : (Timestamp) it.get("EndTime");
        if (finalS != null && finalE != null && finalE.before(finalS)) {
            r.success = false;
            r.message = (e != null && s == null)
                    ? "종료시각이 시작시각(" + finalS.toString().substring(0, 16) + ")보다 빠릅니다."
                    : (s != null && e == null)
                    ? "시작시각이 종료시각(" + finalE.toString().substring(0, 16) + ")보다 늦습니다."
                    : "종료시각이 시작시각보다 빠릅니다.";
            return r;
        }

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("itemId", itemId);
        p.addValue("s", s);
        p.addValue("e", e);
        p.addValue("userId", user.getId());
        this.sqlRunner.execute("""
            UPDATE wash_work_item
               SET "StartTime" = COALESCE(:s, "StartTime"), "EndTime" = COALESCE(:e, "EndTime"),
                   "_modified" = now(), "_modifier_id" = :userId
             WHERE id = :itemId
            """, p);

        // equ_run 시간도 함께 보정
        if (it.get("EquRun_id") != null) {
            MapSqlParameterSource ep = new MapSqlParameterSource();
            ep.addValue("equRunId", ((Number) it.get("EquRun_id")).intValue());
            ep.addValue("s", s);
            ep.addValue("e", e);
            this.sqlRunner.execute("""
                UPDATE equ_run
                   SET "StartDate" = COALESCE(:s, "StartDate"), "EndDate" = COALESCE(:e, "EndDate")
                 WHERE id = :equRunId
                """, ep);
        }
        return r;
    }

    // =================================================================
    // 내부 유틸
    // =================================================================

    private void newLot(String lotNo, Integer matId, Integer storeId, float qty,
                        Integer itemId, String desc, User user, String spjangcd, Timestamp now) {
        MaterialLot ml = new MaterialLot();
        ml.setLotNumber(lotNo);          // ★ 원 로트번호 유지 — 새 채번 안 함
        ml.setMaterialId(matId);
        ml.setInputDateTime(now);
        ml.setInputQty(qty);
        ml.setCurrentStock(qty);
        ml.setDescription(desc);
        ml.setSourceDataPk(itemId);
        ml.setSourceTableName(SRC_TABLE);
        ml.setStoreHouseId(storeId);
        ml.set_audit(user);
        ml.setSpjangcd(spjangcd);
        this.matLotRepository.save(ml);
    }

    private Map<String, Object> getItemRow(Integer itemId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("itemId", itemId);
        return this.sqlRunner.getRow("""
            SELECT id, "WashWork_id", "Material_id", "Qty", "DefectQty",
                   "State", "StartTime", "EndTime", "EquRun_id"
              FROM wash_work_item WHERE id = :itemId AND "_status" = 'a'
            """, p);
    }

    private Map<String, Object> getWorkRow(Integer workId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("workId", workId);
        return this.sqlRunner.getRow("""
            SELECT id, "WashDate", "Actor_id", "Equipment_id",
                   "InStoreHouse_id", "OutStoreHouse_id", spjangcd
              FROM wash_work WHERE id = :workId AND "_status" = 'a'
            """, p);
    }

    private float toF(Object o) {
        return (o == null) ? 0f : Float.parseFloat(String.valueOf(o));
    }
}