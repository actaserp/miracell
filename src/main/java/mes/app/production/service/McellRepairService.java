package mes.app.production.service;

import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * M-CELL 수리 서비스 (2공장, 공정 mc04).
 *
 * 축 : 수리 접수(작지 1건) → 유닛(1대 = 원 로트 1건) → 자재 ＋− 가감
 *      조립(mc01)과 달리 BOM 계층 스텝이 없다. 유닛 1개 = 작업 1건.
 *
 * 핵심 규칙
 *   - 접수 화면이 반품입고를 겸한다. 별도 입고 화면이 없으므로 여기서 재고를 만든다.
 *       · 로트 있고 재고>0  → 창고 이동(수리창고 S-10).  재고 순증 0
 *       · 로트 있고 재고=0  → 같은 로트번호로 신규 입고.  재고 +1
 *       · 로트 없음         → 품목을 골라 신규 입고.      재고 +1
 *     ★ 이 셋을 안 나누면 재고가 2배로 부푼다(§5 사고 재현).
 *   - 수리 = 원 로트 1대 소비 → 결과 로트 1대 산출. 순증 0.
 *       LotMode='keep' 이면 결과 로트번호 = 원 로트번호 (창고만 바뀜)
 *       LotMode='new'  이면 원로트 + '-R1' (재수리면 -R2 …)
 *     즉 keep/new 는 lotNumber 문자열만 다르고 경로가 같다.
 *   - 산출창고 = 생산창고(17). 검사 recalcUnit 의 moveLot(17→19)이 그대로 돌게 하려는 것.
 *   - 유닛을 mcell_unit 에 만들고 State='inspect_wait' 로 두면
 *     검사 화면(mc02)이 코드 수정 없이 잡는다. (검사 wo_queue 에 공정 필터가 없음)
 *
 * 실적 생성은 ProductionCreateService 에 위임한다(1공장·조립과 동일 알맹이).
 * 단 원 로트 투입만은 consumeLot(패치5) 으로 직접 지정한다 —
 * resolveSourceStore 가 InspectYN='Y' 를 보고 창고 19 로 오판하기 때문.
 */
@Service
public class McellRepairService {

    @Autowired SqlRunner sqlRunner;
    @Autowired ProductionCreateService productionCreateService;
    @Autowired WorkMemberService workMemberService;

    /** 산출창고 = 생산창고. 검사가 17 → 19 로 옮기므로 반드시 17. */
    public static final int    STORE_PROD         = 17;
    /** 수리 대기(반품 입고) 창고. id 는 코드로 찾는다 — 환경마다 다를 수 있어서. */
    public static final String STORE_REPAIR_CODE  = "S-10";
    public static final String PROCESS_CODE       = "mc04";

    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyMM");

    private Integer cachedRepairStore;
    private Integer cachedWorkCenter;

    // =====================================================================
    // 기준 id 해석 (상수 하드코딩 대신 코드로 조회 → 패치 누락 사고 방지)
    // =====================================================================

    private int storeRepair() {
        if (cachedRepairStore != null) return cachedRepairStore;
        Map<String, Object> row = this.sqlRunner.getRow(
                "SELECT id FROM store_house WHERE \"Code\" = :code ORDER BY id LIMIT 1",
                new MapSqlParameterSource().addValue("code", STORE_REPAIR_CODE));
        if (row == null || row.get("id") == null) {
            throw new IllegalStateException("수리창고(" + STORE_REPAIR_CODE + ")가 없습니다. db_setup_repair.sql 을 먼저 실행하세요.");
        }
        cachedRepairStore = ((Number) row.get("id")).intValue();
        return cachedRepairStore;
    }

    private int workCenter() {
        if (cachedWorkCenter != null) return cachedWorkCenter;
        Map<String, Object> row = this.sqlRunner.getRow("""
                SELECT wc.id FROM work_center wc
                  JOIN process p ON p.id = wc."Process_id"
                 WHERE p."Code" = :code
                 ORDER BY wc.id LIMIT 1
                """, new MapSqlParameterSource().addValue("code", PROCESS_CODE));
        if (row == null || row.get("id") == null) {
            throw new IllegalStateException("수리 워크센터(공정 " + PROCESS_CODE + ")가 없습니다. db_setup_repair.sql 을 먼저 실행하세요.");
        }
        cachedWorkCenter = ((Number) row.get("id")).intValue();
        return cachedWorkCenter;
    }

    // =====================================================================
    // 조회
    // =====================================================================

    public Map<String, Object> getContext() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("process_code", PROCESS_CODE);
        ctx.put("workcenter_id", workCenter());
        ctx.put("store_repair_id", storeRepair());
        ctx.put("store_prod_id", STORE_PROD);
        return ctx;
    }

    /**
     * A화면 — 수리 큐.
     *
     * ★ 접수가 아니라 '유닛' 단위로 뽑는다.
     *   수리는 원 로트 1개 = 유닛 1대라서 접수와 유닛이 사실상 1:1 이고,
     *   중간에 유닛 목록 화면을 한 단계 두면 카드 한 장짜리 화면을 거치게 된다.
     *   유닛으로 펼쳐 두면 나중에 한 접수에 여러 대를 묶더라도(배치 반품)
     *   카드가 그 대수만큼 뜨는 것으로 자연히 처리된다 — 스키마는 1:N 그대로.
     */
    public List<Map<String, Object>> getWoQueue(String spjangcd, String cat, String flag,
                                                String dateFrom, String dateTo) {
        // cat 은 유형(return/spec), flag 는 상태 필터.
        //   flag='reject'  검사 불합격 — 재수리 대상
        //   flag='open'    아직 검사로 안 넘어간 것 (수리대기 · 수리중)
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("spjangcd", spjangcd)
                .addValue("cat", (cat == null || cat.isBlank() || "all".equals(cat)) ? null : cat)
                .addValue("flag", (flag == null || flag.isBlank() || "all".equals(flag)) ? null : flag)
                .addValue("dateFrom", (dateFrom == null || dateFrom.isBlank()) ? null : LocalDate.parse(dateFrom))
                .addValue("dateTo",   (dateTo   == null || dateTo.isBlank())   ? null : LocalDate.parse(dateTo));
        return this.sqlRunner.getRows(("""
                SELECT mu.id                                       AS unit_id
                     , mu."UnitNo"                                 AS unit_no
                     , mu."State"                                  AS unit_state
                     , mu."LotMode"                                AS lot_mode
                     , mu."SrcLotNumber"                           AS src_lot
                     , mu."LotNumber"                              AS result_lot
                     , mu."RejectReason"                           AS reject_reason
                     , mu."MatProduce_id"                          AS mat_produce_id
                     , pe."Name"                                   AS actor_name
                     , ${MEMBER_NAMES}                             AS member_names
                     , eq."Name"                                   AS equipment_name
                     , to_char(mu."StartTime",'yyyy-mm-dd hh24:mi') AS start_time
                     , to_char(mu."EndTime",'yyyy-mm-dd hh24:mi')   AS end_time
                     , r.id                                        AS repair_id
                     , r."RepairNo"                                AS repair_no
                     , r."Cat"                                     AS cat
                     , to_char(r."ReceiptDate",'yyyy-mm-dd')       AS receipt_date
                     , r."SrcMakerLotNo"                           AS src_maker_lot
                     , r."IntakeType"                              AS intake_type
                     , r."Reason"                                  AS reason
                     , r."JobResponse_id"                          AS job_res_id
                     , jr."WorkOrderNumber"                        AS order_num
                     , sm."Code"  AS src_code,  sm."Name"  AS src_name
                     , tm."Code"  AS tgt_code,  tm."Name"  AS tgt_name
                     , m."Code"   AS mat_code,  m."Name"   AS mat_name
                     , COALESCE(mt.plus_cnt,0)  AS plus_cnt
                     , COALESCE(mt.minus_cnt,0) AS minus_cnt
                     , (SELECT COUNT(*) FROM mcell_unit s
                         WHERE s."McellRepair_id" = r.id AND COALESCE(s."_status",'a')='a') AS sibling_cnt
                  FROM mcell_unit mu
                  JOIN mcell_repair r ON r.id = mu."McellRepair_id"
                  LEFT JOIN job_res  jr ON jr.id = r."JobResponse_id"
                  LEFT JOIN material sm ON sm.id = r."SrcMaterial_id"
                  LEFT JOIN material tm ON tm.id = r."TargetMaterial_id"
                  LEFT JOIN material m  ON m.id  = mu."Material_id"
                  LEFT JOIN person   pe ON pe.id = mu."Actor_id"
                  LEFT JOIN equ      eq ON eq.id = mu."Equipment_id"
                  LEFT JOIN LATERAL (
                        SELECT COUNT(*) FILTER (WHERE rm."Dir"='+') AS plus_cnt
                             , COUNT(*) FILTER (WHERE rm."Dir"='-') AS minus_cnt
                          FROM mcell_repair_mat rm
                         WHERE rm."McellUnit_id" = mu.id AND COALESCE(rm."_status",'a')='a'
                  ) mt ON true
                 WHERE r.spjangcd = :spjangcd
                   AND COALESCE(r."_status",'a') = 'a'
                   AND COALESCE(mu."_status",'a') = 'a'
                   AND (CAST(:cat AS varchar) IS NULL OR r."Cat" = CAST(:cat AS varchar))
                   AND (CAST(:flag AS varchar) IS NULL
                        OR (CAST(:flag AS varchar) = 'reject' AND mu."State" = 'reject')
                        OR (CAST(:flag AS varchar) = 'open'   AND mu."State" IN ('wait','repairing','reject')))
                   AND (CAST(:dateFrom AS date) IS NULL OR r."ReceiptDate" >= CAST(:dateFrom AS date))
                   AND (CAST(:dateTo   AS date) IS NULL OR r."ReceiptDate" <= CAST(:dateTo   AS date))
                 ORDER BY r."ReceiptDate" DESC, r.id DESC, mu."UnitNo"
                 LIMIT 200
                """
                /* 조원은 스칼라 서브쿼리. LATERAL 로 붙이면 조원 수만큼 유닛 행이 복제된다. */
                .replace("${MEMBER_NAMES}", WorkMemberService.namesSql("mcell_unit", "mu.id"))), p);
    }

    /** C화면 — 유닛 1건 상세 (헤더 + 원 로트 재고 + 자재 가감) */
    public Map<String, Object> getUnitDetail(Integer unitId) {
        Map<String, Object> unit = getUnitRow(unitId);
        if (unit == null) throw new IllegalArgumentException("유닛을 찾을 수 없습니다.");

        Map<String, Object> out = new LinkedHashMap<>(unit);
        // 배정 시트를 다시 열 때 선택 상태를 복원하기 위한 조원 정보
        out.put("member_ids",   this.workMemberService.idsOf(WorkMemberService.SRC_MCELL_UNIT, unitId));
        out.put("member_names", this.workMemberService.namesOf(WorkMemberService.SRC_MCELL_UNIT, unitId));
        out.put("mats", getUnitMats(unitId));
        out.put("next_lot", previewResultLot(unit));
        // 같은 접수에 몇 대가 묶여 있는지 — 접수 취소 안내 문구가 이걸 본다
        Map<String, Object> sib = this.sqlRunner.getRow("""
                SELECT COUNT(*) AS c FROM mcell_unit
                 WHERE "McellRepair_id" = :rid AND COALESCE("_status",'a')='a'
                """, new MapSqlParameterSource().addValue("rid", unit.get("repair_id")));
        out.put("sibling_cnt", sib == null ? 1 : asInt(sib.get("c")));
        // 검사가 이미 시작됐으면 되돌리기를 막아야 한다(화면에서 버튼 비활성)
        Map<String, Object> insp = this.sqlRunner.getRow(
                "SELECT COUNT(*) AS c FROM insp_result WHERE \"McellUnit_id\" = :id",
                new MapSqlParameterSource().addValue("id", unitId));
        out.put("insp_cnt", insp == null ? 0 : asInt(insp.get("c")));
        return out;
    }

    /** 유닛의 자재 가감 목록 (＋ 먼저, − 나중) */
    public List<Map<String, Object>> getUnitMats(Integer unitId) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("uid", unitId).addValue("store", STORE_PROD);
        return this.sqlRunner.getRows("""
                SELECT rm.id            AS rmat_id
                     , rm."Material_id" AS mat_id
                     , m."Code"         AS mat_code
                     , m."Name"         AS mat_name
                     , un."Name"        AS unit
                     , rm."Dir"         AS dir
                     , rm."Qty"         AS qty
                     , rm."State"       AS state
                     , COALESCE(s.stock,0) AS stock
                     , rm."SrcMatLot_id" AS src_mat_lot_id
                     , sl."LotNumber"    AS src_lot_number   -- 뜯어낸 부품의 원 로트
                     , uq.q             AS used_qty   -- −회수 상한. NULL = 조립 이력 없음(상한 없음)
                     /* ★ 포장 자재인가 — 접수 때 박스를 풀며 자동으로 담긴 것.
                          이것들은 조립이 아니라 포장에서 투입되므로 usedQtyOf(조립 스텝 기준)가
                          영원히 NULL 이다. 그걸 「조립 이력이 없다」로 표시하면
                          근거 없는 회수처럼 보이지만, 실제로는 포장 BOM 이 근거다.
                          원 완제품의 BOM 에 그 품목이 있으면 포장 자재로 본다. */
                     , EXISTS (
                           SELECT 1
                             FROM mcell_repair r2
                             JOIN bom b2 ON b2."Material_id" = r2."SrcMaterial_id"
                                        AND COALESCE(b2."_status",'a') = 'a'
                             JOIN bom_comp bc2 ON bc2."BOM_id" = b2.id
                                              AND COALESCE(bc2."_status",'a') = 'a'
                                              AND bc2."Material_id" = rm."Material_id"
                            WHERE r2.id = cu."McellRepair_id"
                       )                AS from_pack_bom
                  FROM mcell_repair_mat rm
                  JOIN mcell_unit cu ON cu.id = rm."McellUnit_id"
                  JOIN material m  ON m.id = rm."Material_id"
                  LEFT JOIN unit  un ON un.id = m."Unit_id"
                  LEFT JOIN mat_lot sl ON sl.id = rm."SrcMatLot_id"
                  LEFT JOIN LATERAL (
                        SELECT SUM(ml."CurrentStock") AS stock FROM mat_lot ml
                         WHERE ml."Material_id" = rm."Material_id" AND ml."StoreHouse_id" = :store
                  ) s ON true
                  -- 원 M-CELL 조립 시 이 자재가 몇 개 들어갔는지 (＋행은 계산하지 않는다)
                  --   원 부품로트가 지정돼 있으면 그 로트분만, 아니면 품목 전체
                  LEFT JOIN LATERAL (
                        SELECT SUM(COALESCE(mlc."OutputQty",0)) AS q
                          FROM mcell_unit ou
                          JOIN mcell_unit_step st ON st."McellUnit_id" = ou.id
                                                 AND st."MatProduce_id" IS NOT NULL
                          JOIN mat_produce mp ON mp.id = st."MatProduce_id"
                                             AND COALESCE(mp."_status",'a') = 'a'
                          JOIN mat_lot_cons mlc ON mlc."SourceTableName" = 'mat_produce'
                                               AND mlc."SourceDataPk"    = mp.id
                                               AND COALESCE(mlc."_status",'a') = 'a'
                          JOIN mat_lot cl ON cl.id = mlc."MaterialLot_id"
                                         AND cl."Material_id" = rm."Material_id"
                         WHERE ou."LotNumber" = cu."SrcLotNumber"
                           AND COALESCE(ou."_status",'a') = 'a'
                           AND (rm."SrcMatLot_id" IS NULL
                                OR mlc."MaterialLot_id" = rm."SrcMatLot_id")
                  ) uq ON rm."Dir" = '-'
                 WHERE rm."McellUnit_id" = :uid AND COALESCE(rm."_status",'a')='a'
                 ORDER BY rm."Dir" ASC, rm.id ASC
                """, p);
    }

    /**
     * −회수 상한 = 원 로트에 실제로 투입된 수량.
     * 조립 이력이 없으면 null → 상한을 걸지 않는다(구형·외부 유입 장비).
     *
     * ※ 이 값은 '들어간 개수'가 아니라 '소비된 개수'다.
     *   조립 중 1개를 불량으로 버리고 1개를 장착했다면 소비는 2, 실물은 1이다.
     *   투입별 불량 수량은 따로 기록되지 않아 더 좁힐 수 없다.
     *   그래도 "쓴 것보다 많이 뺄 수는 없다"는 선은 이걸로 지켜진다.
     */
    private Double usedQtyOf(Integer unitId, Integer matId, Integer srcMatLotId) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("uid", unitId).addValue("matId", matId).addValue("srcLotId", srcMatLotId);
        // 원 부품 로트를 지정했으면 그 로트에서 소비된 만큼, 아니면 품목 전체 소비량
        Map<String, Object> row = this.sqlRunner.getRow("""
                SELECT SUM(COALESCE(mlc."OutputQty",0)) AS q
                  FROM mcell_unit cu
                  JOIN mcell_unit ou ON ou."LotNumber" = cu."SrcLotNumber"
                                    AND COALESCE(ou."_status",'a') = 'a'
                  JOIN mcell_unit_step st ON st."McellUnit_id" = ou.id
                                         AND st."MatProduce_id" IS NOT NULL
                  JOIN mat_produce mp ON mp.id = st."MatProduce_id"
                                     AND COALESCE(mp."_status",'a') = 'a'
                  JOIN mat_lot_cons mlc ON mlc."SourceTableName" = 'mat_produce'
                                       AND mlc."SourceDataPk"    = mp.id
                                       AND COALESCE(mlc."_status",'a') = 'a'
                  JOIN mat_lot sl ON sl.id = mlc."MaterialLot_id"
                                 AND sl."Material_id" = :matId
                 WHERE cu.id = :uid
                   AND (CAST(:srcLotId AS integer) IS NULL
                        OR mlc."MaterialLot_id" = CAST(:srcLotId AS integer))
                """, p);
        if (row != null && row.get("q") != null && toD(row.get("q")) > 0) return toD(row.get("q"));

        // 로트 소비 이력이 없는 옛 데이터 → 품목 단위 소비량으로 대체
        Map<String, Object> alt = this.sqlRunner.getRow("""
                SELECT SUM(COALESCE(mc."ConsumedQty",0)) AS q
                  FROM mcell_unit cu
                  JOIN mcell_unit ou ON ou."LotNumber" = cu."SrcLotNumber"
                                    AND COALESCE(ou."_status",'a') = 'a'
                  JOIN mcell_unit_step st ON st."McellUnit_id" = ou.id
                                         AND st."MatProduce_id" IS NOT NULL
                  JOIN mat_produce mp ON mp.id = st."MatProduce_id"
                                     AND COALESCE(mp."_status",'a') = 'a'
                  JOIN mat_consu mc ON mc."JobResponse_id" = mp."JobResponse_id"
                                   AND mc."LotIndex"       = mp."LotIndex"
                                   AND mc."Material_id"    = :matId
                                   AND COALESCE(mc."_status",'a') = 'a'
                 WHERE cu.id = :uid
                """, p);
        if (alt == null || alt.get("q") == null) return null;
        return toD(alt.get("q"));
    }

    /** 생산창고(17)의 가용 재고 */
    private double stockOf(Integer matId) {
        Map<String, Object> row = this.sqlRunner.getRow("""
                SELECT COALESCE(SUM(ml."CurrentStock"),0) AS q
                  FROM mat_lot ml
                 WHERE ml."Material_id" = :matId AND ml."StoreHouse_id" = :store
                """, new MapSqlParameterSource().addValue("matId", matId).addValue("store", STORE_PROD));
        return (row == null) ? 0d : toD(row.get("q"));
    }

    /**
     * ＋투입 수량 검증 — 생산창고에 있는 만큼만 담을 수 있다.
     * 담을 때 안 막으면 「수리 완료」에서 consumeBomList 가 터지는데,
     * 그 시점엔 이미 실적 생성이 시작된 뒤라 롤백으로 되돌아간다.
     * 사용자 입장에선 한참 뒤에 이유를 알게 되므로 여기서 먼저 막는다.
     */
    private AjaxResult guardInputQty(Integer matId, float qty) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        double have = stockOf(matId);
        if (qty > have + 1e-6) {
            r.success = false;
            r.message = (have <= 0)
                    ? "생산창고에 재고가 없습니다."
                    : "생산창고 재고가 부족합니다. (보유 " + fmtNum(have) + ")";
        }
        return r;
    }

    /** −회수 수량 상한 검증. 상한이 없으면(이력 없음) 통과. */
    private AjaxResult guardReturnQty(Integer unitId, Integer matId, Integer srcMatLotId, float qty) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        Double cap = usedQtyOf(unitId, matId, srcMatLotId);
        if (cap == null) return r;
        if (qty > cap + 1e-6) {
            r.success = false;
            r.message = "원 M-CELL 에 투입된 수량은 " + fmtNum(cap) + " 입니다. 그보다 많이 회수할 수 없습니다.";
        }
        return r;
    }

    /** 1.0 → "1", 1.5 → "1.5" */
    private static String fmtNum(double d) {
        return (d % 1 == 0) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /**
     * 접수 모달 — 스캔/입력값으로 원 M-CELL 을 찾는다.
     * 사내 로트번호와 외부 라벨(MakerLotNo) 양쪽을 본다.
     * 결과가 없으면 빈 배열 → 화면이 '품목 직접 선택' 모드로 전환한다.
     */
    public List<Map<String, Object>> searchSrcLot(String key) {
        if (key == null || key.isBlank()) return new ArrayList<>();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("key", key.trim())
                .addValue("repairStore", storeRepair());
        List<Map<String, Object>> rows = this.sqlRunner.getRows("""
                SELECT ml.id                              AS mat_lot_id
                     , ml."LotNumber"                     AS lot_number
                     , ml."MakerLotNo"                    AS maker_lot_no
                     , ml."Material_id"                   AS mat_id
                     , m."Code"  AS mat_code, m."Name" AS mat_name
                     , ml."StoreHouse_id"                 AS store_id
                     , sh."Name"                          AS store_name
                     , COALESCE(ml."CurrentStock",0)      AS stock
                     , to_char(ml."InputDateTime",'yyyy-mm-dd') AS input_date
                     -- move : 재고가 남아 있으니 창고만 옮긴다 (재고 순증 0)
                     -- new  : 이미 출하되어 0 이므로 반품으로 새로 받는다 (재고 +1)
                     , CASE WHEN COALESCE(ml."CurrentStock",0) > 0 THEN 'move' ELSE 'new' END AS intake_type
                     , (ml."StoreHouse_id" = :repairStore) AS already_in_repair
                     -- 그 로트의 유닛 상태 (없으면 null = 사내 조립 이력이 없는 물건)
                     , u.id       AS unit_id
                     , u."State"  AS unit_state
                     , u."McellRepair_id" AS unit_repair_id
                  FROM mat_lot ml
                  JOIN material m ON m.id = ml."Material_id"
                  LEFT JOIN store_house sh ON sh.id = ml."StoreHouse_id"
                  LEFT JOIN LATERAL (
                        SELECT mu.id, mu."State", mu."McellRepair_id"
                          FROM mcell_unit mu
                         WHERE mu."LotNumber" = ml."LotNumber"
                           AND COALESCE(mu."_status",'a') = 'a'
                         ORDER BY mu.id DESC LIMIT 1
                  ) u ON true
                 WHERE ml."LotNumber" = :key OR ml."MakerLotNo" = :key
                 ORDER BY COALESCE(ml."CurrentStock",0) DESC, ml.id DESC
                 LIMIT 20
                """, p);

        // 유형별 접수 가능 여부를 계산해서 같이 내린다(화면 표시용).
        // 최종 판단은 regist 가 다시 한다 — 화면만 막으면 API 로 뚫린다.
        for (Map<String, Object> row : rows) {
            Map<String, Object> v = intakeVerdict(row);
            row.putAll(v);

            /* ★ 「접수하면 무엇이 되는가」를 함께 내린다.
                 완제품 로트를 스캔하면 재고로 잡히는 것은 완제품이 아니라
                 그 안의 재공품이다(박스를 풀어 꺼내므로).
                 화면이 재고 수량 대신 이걸 보여줘야 작업자가 헷갈리지 않는다 —
                 재고 0/1 은 출하 여부일 뿐 접수 가능 여부와 무관하다. */
            Map<String, Object> semi = findPackedSemi(asInt(row.get("mat_id")));
            row.put("unpack", semi != null);
            row.put("intake_mat_id",   semi != null ? asInt(semi.get("semi_mat_id")) : asInt(row.get("mat_id")));
            row.put("intake_mat_code", semi != null ? str(semi.get("semi_code")) : str(row.get("mat_code")));
            row.put("intake_mat_name", semi != null ? str(semi.get("semi_name")) : str(row.get("mat_name")));
        }
        return rows;
    }

    /**
     * 접수 가능 여부 판정.
     *
     *   차단(유형 무관) : 조립·검사·수리가 진행 중인 유닛
     *       그 유닛은 아직 원래 작업지시 안에서 살아 있다. 수리로 빼가면 두 화면이
     *       같은 유닛의 State 를 서로 다르게 바꾼다. 사양변경이라도 이 경우는
     *       작업지시부터 다시 내는 게 맞다.
     *   반품만 차단 : 검사 전 사내 재고(생산창고)
     *       반품은 '완성되어 나갔던 물건이 돌아오는 것'이다. 검사도 안 받은 물건이
     *       반품일 수 없다. 조립 중 문제면 조립 화면의 «분해» 가 정식 경로다.
     *   허용 : 재고 0(출하됨) · 제품창고(포장완료) · 검사완료창고(검사까지 끝)
     *          유닛 이력이 없는 물건(구형·외부 유입)
     */
    private Map<String, Object> intakeVerdict(Map<String, Object> row) {
        String unitState = str(row.get("unit_state"));
        Integer storeId  = asInt(row.get("store_id"));
        double stock     = toD(row.get("stock"));

        boolean allowReturn = true, allowSpec = true;
        String note = null;

        if (unitState != null && IN_PROGRESS_STATES.contains(unitState)) {
            allowReturn = false; allowSpec = false;
            // ★ 왜 예외를 두지 않는가
            //   접수는 원 유닛을 옮겨오지 않고 수리용 유닛을 새로 만든다.
            //   그래서 원 유닛은 그 상태로 영구히 남고, 검사 큐에는 로트가 사라진
            //   유닛이 계속 뜨며 원 작지의 진행률도 끝나지 않는다.
            //   먼저 다른 상태로 정리된 뒤에 원래 규칙을 적용하는 것이 맞다.
            note = intakeBlockNote(unitState);
        } else if (storeId != null && storeId == storeRepair() && stock > 0) {
            allowReturn = false; allowSpec = false;
            note = "이미 수리 접수된 로트입니다.";
        } else if (stock > 0) {
            /* ★ 사내 재고가 남아 있으면 반품일 수 없다 — 나간 적이 없는 물건이다.
                 창고를 가리지 않는다. 제품창고에 서 있는 완제품도,
                 검사완료창고의 재공품도 아직 출고 전이라는 점은 같다.
                 반면 사양변경은 가지고 있는 재고를 바꾸는 것이 정상 경로다.
               (이전에는 생산창고만 막았는데, 그러면 제품창고 재고를
                반품으로 접수해 재고가 하나 더 생겨버린다) */
            allowReturn = false;
            note = "아직 " + (storeId != null && storeId == STORE_PROD ? "검사 전 " : "")
                    + "사내 재고입니다. 출고되지 않았으므로 반품은 안 되고 사양변경만 가능합니다.";
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("allow_return", allowReturn);
        out.put("allow_spec", allowSpec);
        out.put("verdict_note", note);
        return out;
    }

    /** 아직 원래 작업지시 안에서 살아 있는 상태들 */
    private static final Set<String> IN_PROGRESS_STATES =
            Set.of("wait", "assembling", "repairing", "inspect_wait", "reject");

    /** 차단 사유 — 무엇을 먼저 해야 하는지까지 알려준다 */
    private static String intakeBlockNote(String st) {
        switch (st) {
            case "wait":
            case "assembling":
                return "아직 조립 중입니다. 사양이 바뀌었다면 작업지시를 조정하세요 "
                        + "(품목이 바뀌면 작지도 갈려야 합니다).";
            case "inspect_wait":
                return "검사 대기 상태입니다. 검사를 합격시킨 뒤 접수하거나, "
                        + "조립 화면에서 «분해» 해 작업지시를 조정하세요.";
            case "reject":
                return "검사 불합격 유닛입니다. 조립 화면에서 재작업해 주세요.";
            case "repairing":
                return "다른 수리 건이 진행 중입니다. 그 건을 먼저 마치세요.";
            default:
                return "작업이 진행 중인 유닛입니다 (" + unitLabel(st) + ").";
        }
    }

    private static String unitLabel(String st) {
        if (st == null) return "-";
        switch (st) {
            case "wait":         return "조립 대기";
            case "assembling":   return "조립 중";
            case "repairing":    return "수리 중";
            case "inspect_wait": return "검사 대기";
            case "reject":       return "재작업 대상";
            case "pass":         return "검사 합격";
            case "packed":       return "포장 완료";
            default:             return st;
        }
    }

    /** 자재 시트 — 생산창고(17) 재고. 조립과 같은 모양. */
    public List<Map<String, Object>> getStockList(String keyword) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("store", STORE_PROD)
                .addValue("kw", (keyword == null || keyword.isBlank()) ? null : "%" + keyword.trim() + "%");
        return this.sqlRunner.getRows("""
                SELECT m.id AS mat_id, m."Code" AS mat_code, m."Name" AS mat_name,
                       un."Name" AS unit, COALESCE(SUM(ml."CurrentStock"),0) AS stock
                  FROM material m
                  LEFT JOIN unit un ON un.id = m."Unit_id"
                  LEFT JOIN mat_lot ml ON ml."Material_id" = m.id AND ml."StoreHouse_id" = :store
                 WHERE COALESCE(m."Factory_id",0) = 2
                   -- 유닛 품목(=검사 대상 완제품)은 자재가 아니다. 수리 결과물이 17 에
                   -- 들어오므로 걸러주지 않으면 투입 자재 후보로 섞여 보인다.
                   AND COALESCE(m."InspectYN",'N') <> 'Y'
                   AND (CAST(:kw AS varchar) IS NULL OR m."Code" ILIKE :kw OR m."Name" ILIKE :kw)
                 GROUP BY m.id, m."Code", m."Name", un."Name"
                 ORDER BY m."Code"
                 LIMIT 300
                """, p);
    }

    /**
     * −회수 후보 — 원 로트를 조립할 때 실제로 투입된 자재.
     *
     * 뜯어낼 수 있는 건 그 안에 들어 있던 것뿐이다. 생산창고 전체를 보여주면
     * 들어가지도 않은 부품을 회수했다고 기록할 수 있어 계보가 깨진다.
     *
     * 원 유닛(mcell_unit.LotNumber = 원 로트) → 스텝들 → 각 스텝의 mat_produce
     * → mat_consu 로 역추적한다. 하위 모듈까지 스텝으로 남아 있으므로
     * 계층 전체의 투입 자재가 한 번에 모인다.
     *
     * 조립 이력이 없는 경우(구형 장비 · 시스템 도입 전 생산 · 외부 유입)에는
     * 빈 배열이 돌아온다. 그때는 화면이 '전체 자재'로 넘어간다.
     */
    public List<Map<String, Object>> getConsumedList(Integer unitId) {
        Map<String, Object> u = getUnitRow(unitId);
        if (u == null) throw new IllegalArgumentException("유닛을 찾을 수 없습니다.");

        String srcLot = str(u.get("src_lot"));
        if (srcLot == null || srcLot.isBlank()) return new ArrayList<>();

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("srcLot", srcLot)
                .addValue("store", STORE_PROD);

        // ── 1순위 : 로트 단위 ──────────────────────────────
        //   mat_lot_cons 가 '어느 로트를 몇 개 썼는지' 의 진실이다(§5).
        //   같은 품목이 두 로트에서 나눠 들어갔으면 두 행으로 뜨고,
        //   작업자가 뜯어낸 쪽을 고른다.
        List<Map<String, Object>> rows = this.sqlRunner.getRows("""
                SELECT c.mat_id                     AS mat_id
                     , c.src_mat_lot_id             AS src_mat_lot_id
                     , c.src_lot_number             AS src_lot_number
                     , c.used_qty                   AS used_qty
                     , m."Code"                     AS mat_code
                     , m."Name"                     AS mat_name
                     , un."Name"                    AS unit
                     , COALESCE(s.stock,0)          AS stock
                  FROM (
                        SELECT sl."Material_id"       AS mat_id
                             , mlc."MaterialLot_id"   AS src_mat_lot_id
                             , sl."LotNumber"         AS src_lot_number
                             , SUM(COALESCE(mlc."OutputQty",0)) AS used_qty
                          FROM mcell_unit ou
                          JOIN mcell_unit_step st ON st."McellUnit_id" = ou.id
                                                 AND st."MatProduce_id" IS NOT NULL
                          JOIN mat_produce mp ON mp.id = st."MatProduce_id"
                                             AND COALESCE(mp."_status",'a') = 'a'
                          JOIN mat_lot_cons mlc ON mlc."SourceTableName" = 'mat_produce'
                                               AND mlc."SourceDataPk"    = mp.id
                                               AND COALESCE(mlc."_status",'a') = 'a'
                          JOIN mat_lot sl ON sl.id = mlc."MaterialLot_id"
                         WHERE ou."LotNumber" = CAST(:srcLot AS varchar)
                           AND COALESCE(ou."_status",'a') = 'a'
                         GROUP BY sl."Material_id", mlc."MaterialLot_id", sl."LotNumber"
                        HAVING SUM(COALESCE(mlc."OutputQty",0)) > 0
                  ) c
                  JOIN material m ON m.id = c.mat_id
                  LEFT JOIN unit un ON un.id = m."Unit_id"
                  LEFT JOIN LATERAL (
                        SELECT SUM(ml."CurrentStock") AS stock FROM mat_lot ml
                         WHERE ml."Material_id" = c.mat_id AND ml."StoreHouse_id" = :store
                  ) s ON true
                 ORDER BY m."Code", c.src_lot_number
                """, p);
        if (!rows.isEmpty()) return rows;

        // ── 2순위 : 품목 단위 ──────────────────────────────
        //   로트 소비 이력이 없는 옛 데이터. 로트는 못 밝히고 수량만 잡는다.
        return this.sqlRunner.getRows("""
                SELECT c.mat_id                     AS mat_id
                     , NULL::integer                AS src_mat_lot_id
                     , NULL::varchar                AS src_lot_number
                     , c.used_qty                   AS used_qty
                     , m."Code"                     AS mat_code
                     , m."Name"                     AS mat_name
                     , un."Name"                    AS unit
                     , COALESCE(s.stock,0)          AS stock
                  FROM (
                        SELECT mc."Material_id" AS mat_id
                             , SUM(COALESCE(mc."ConsumedQty",0)) AS used_qty
                          FROM mcell_unit ou
                          JOIN mcell_unit_step st ON st."McellUnit_id" = ou.id
                                                 AND st."MatProduce_id" IS NOT NULL
                          JOIN mat_produce mp ON mp.id = st."MatProduce_id"
                                             AND COALESCE(mp."_status",'a') = 'a'
                          JOIN mat_consu mc ON mc."JobResponse_id" = mp."JobResponse_id"
                                           AND mc."LotIndex"       = mp."LotIndex"
                                           AND COALESCE(mc."_status",'a') = 'a'
                         WHERE ou."LotNumber" = CAST(:srcLot AS varchar)
                           AND COALESCE(ou."_status",'a') = 'a'
                         GROUP BY mc."Material_id"
                  ) c
                  JOIN material m ON m.id = c.mat_id
                  LEFT JOIN unit un ON un.id = m."Unit_id"
                  LEFT JOIN LATERAL (
                        SELECT SUM(ml."CurrentStock") AS stock FROM mat_lot ml
                         WHERE ml."Material_id" = c.mat_id AND ml."StoreHouse_id" = :store
                  ) s ON true
                 ORDER BY m."Code"
                """, p);
    }

    /** 사양변경 대상 품목 후보 (완제품 / 유닛 품목) */
    /**
     * 품목 후보. 쓰임이 둘이고 성격이 반대라 scope 로 가른다.
     *
     *   spec (기본) — 사양변경 대상 = 「바꿔서 만들 것」.
     *       검사 대상 반제품만. 완제품을 넣으면 결과 로트가
     *       「박스 없는 완제품」이 되어 재고가 어긋난다.
     *       완제품 로트를 스캔한 경우는 findPackedSemi 가 포장 BOM 을
     *       내려가 반제품을 자동으로 잡으므로 여기 있을 필요도 없다.
     *
     *   src — 미등록 로트의 원품목 = 「들어온 물건이 무엇인가」.
     *       우리 MES 에서 만든 적 없는 물건(구형·외주·이관 전 생산분)이
     *       반품으로 들어오면 사람이 지정해야 한다.
     *       그 물건은 대개 박스째 오므로 완제품이 반드시 후보에 있어야 한다.
     *       ★ 여기서 완제품을 골라도 접수는 반제품으로 잡힌다 —
     *         regist 가 findPackedSemi 로 박스를 풀고 회수 목록까지 깐다.
     */
    public List<Map<String, Object>> getTargetMaterials(String keyword, String scope) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("kw", (keyword == null || keyword.isBlank()) ? null : "%" + keyword.trim() + "%");
        /* 조건을 문자열로 이어붙이지 않는다 — text block 을 쪼개면 읽기 어렵고
           여는 """ 뒤에 내용이 붙어 컴파일도 안 된다. 플래그 하나로 처리한다. */
        p.addValue("srcScope", "src".equals(scope));
        return this.sqlRunner.getRows("""
                SELECT m.id AS mat_id, m."Code" AS mat_code, m."Name" AS mat_name
                     , mg."MaterialType" AS mat_type
                  FROM material m
                  LEFT JOIN mat_grp mg ON mg.id = m."MaterialGroup_id"
                 WHERE COALESCE(m."Factory_id",0) = 2
                   AND COALESCE(m."_status",'a') = 'a'
                   AND (COALESCE(m."InspectYN",'N') = 'Y'
                        OR (CAST(:srcScope AS boolean)
                            AND mg."MaterialType" IN ('product','semi')))
                   AND (CAST(:kw AS varchar) IS NULL OR m."Code" ILIKE :kw OR m."Name" ILIKE :kw)
                 ORDER BY mg."MaterialType" DESC, m."Code"
                 LIMIT 200
                """, p);
    }

    public List<Map<String, Object>> getWorkers() {
        return this.sqlRunner.getRows("""
                SELECT p.id AS actor_id, p."Name" AS actor_name
                  FROM person p
                 WHERE COALESCE(p."_status",'a') = 'a'
                 ORDER BY p."Name"
                """, new MapSqlParameterSource());
    }

    /**
     * 수리 설비 목록.
     * ★ 수리 워크센터로 필터한다. equ."WorkCenter_id" 가 단일 FK 라
     *   조립 설비(wc 52)는 여기 안 나온다 — 의도된 동작이고,
     *   그래서 db_setup_repair_equ.sql 이 수리 전용 설비를 따로 등록한다.
     */
    public List<Map<String, Object>> getEquipments() {
        return this.sqlRunner.getRows("""
                SELECT e.id AS equipment_id, e."Code" AS equipment_code, e."Name" AS equipment_name
                  FROM equ e
                 WHERE COALESCE(e."_status",'a') = 'a'
                   AND e."WorkCenter_id" = :wcId
                 ORDER BY e."Code"
                """, new MapSqlParameterSource().addValue("wcId", workCenter()));
    }

    // =====================================================================
    // 접수 — 작지 + 유닛 + 반품 재고 확보를 한 번에
    // =====================================================================

    /**
     * 수리 접수.
     *
     * @param scanKey    스캔/입력값 (사내 로트 또는 외부 라벨)
     * @param matLotId   화면이 searchSrcLot 결과에서 고른 로트. null 이면 미등록 취급
     * @param materialId 미등록일 때만 필수 (어떤 품목인지 사람이 지정)
     */
    @Transactional
    public AjaxResult regist(String cat, String scanKey, Integer matLotId, Integer materialId,
                             Integer targetMaterialId, String reason, String receiptDate,
                             String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        if (cat == null || !(cat.equals("return") || cat.equals("spec"))) {
            r.success = false; r.message = "수리 유형이 올바르지 않습니다."; return r;
        }
        String key = (scanKey == null) ? "" : scanKey.trim().toUpperCase();
        if (key.isBlank()) { r.success = false; r.message = "원 M-CELL 로트를 입력하거나 스캔하세요."; return r; }

        LocalDate rDate = (receiptDate == null || receiptDate.isBlank())
                ? LocalDate.now() : LocalDate.parse(receiptDate);

        // ── 1. 원 로트 확정 ──────────────────────────────────────
        Map<String, Object> lot = null;
        if (matLotId != null) {
            lot = this.sqlRunner.getRow("""
                    SELECT ml.id, ml."LotNumber" AS lot_number, ml."MakerLotNo" AS maker_lot_no,
                           ml."Material_id" AS mat_id, ml."StoreHouse_id" AS store_id,
                           COALESCE(ml."CurrentStock",0) AS stock,
                           u."State" AS unit_state
                      FROM mat_lot ml
                      LEFT JOIN LATERAL (
                            SELECT mu."State" FROM mcell_unit mu
                             WHERE mu."LotNumber" = ml."LotNumber"
                               AND COALESCE(mu."_status",'a') = 'a'
                             ORDER BY mu.id DESC LIMIT 1
                      ) u ON true
                     WHERE ml.id = :id
                    """, new MapSqlParameterSource().addValue("id", matLotId));
            if (lot == null) { r.success = false; r.message = "선택한 로트를 찾을 수 없습니다."; return r; }

            // ★ 화면에서 이미 막지만 서버가 최종 판단한다. 화면만 막으면 API 로 뚫린다.
            Map<String, Object> v = intakeVerdict(lot);
            boolean ok = "spec".equals(cat)
                    ? Boolean.TRUE.equals(v.get("allow_spec"))
                    : Boolean.TRUE.equals(v.get("allow_return"));
            if (!ok) {
                r.success = false;
                r.message = str(v.get("verdict_note")) != null
                        ? str(v.get("verdict_note"))
                        : "이 로트는 " + ("spec".equals(cat) ? "사양변경" : "반품") + " 접수 대상이 아닙니다.";
                return r;
            }
        }

        Integer srcMatId  = (lot != null) ? asInt(lot.get("mat_id")) : materialId;
        if (srcMatId == null) {
            r.success = false;
            r.message = "등록되지 않은 로트입니다. 어떤 품목인지 선택해 주세요.";
            return r;
        }
        String srcLotNo   = (lot != null) ? str(lot.get("lot_number")) : key;
        String makerLotNo = (lot != null) ? str(lot.get("maker_lot_no")) : key;
        float  stock      = (lot != null) ? (float) toD(lot.get("stock")) : 0f;
        /* ★ 유형에 따라 현물을 확보하는 방법이 다르다.
             반품 : 언제나 신규 입고.
                   나갔던 물건이 돌아오는 것이므로 사내 재고가 있을 수 없다.
                   재고가 남아 있는데 반품이라면 그건 「나간 적 없는 물건의 반품」이라
                   앞뒤가 맞지 않는다 — 아래에서 막는다.
             사양변경 : 가지고 있는 재고를 바꾸는 경우가 정상이다.
                   재고가 있으면 그것을 쓰고(순증 0), 없으면 출하품이 돌아온 것이니 신규 입고. */
        String intakeType;
        if ("spec".equals(cat)) {
            intakeType = (stock > 0) ? "move" : "new";
        } else {
            intakeType = "new";
            if (stock > 0) {
                r.success = false;
                r.message = "이 로트는 아직 사내 재고(" + str(lot.get("store_name")) + ")에 있습니다. "
                        + "출고되지 않은 물건은 반품이 아닙니다. "
                        + "재고 상태에서 사양을 바꾸는 것이라면 «사양변경» 으로 접수하세요.";
                return r;
            }
        }

        /* ── 1-b. 완제품(포장완료)이면 박스를 푼다 ────────────────
             반품은 대개 박스째 들어오지만 수리하는 물건은 포장 전 반제품이다.
             포장 BOM 을 한 단 내려가면 그 대응이 그대로 있다 :
               semi 구성품 하나  = 수리 대상 (조립·검사 품목, InspectYN='Y')
               나머지 sub_mat 들 = 박스·폼·케이블·어댑터 → 회수 대상
             국내/해외가 완제품 코드로 갈리고 재공품은 공용이므로,
             반드시 스캔한 완제품에서 정방향으로 내려가야 한다.
             (재공품에서 역으로 올라가면 국내용인지 수출용인지 알 수 없다) */
        Map<String, Object> unpack = findPackedSemi(srcMatId);
        Integer semiMatId = (unpack == null) ? null : asInt(unpack.get("semi_mat_id"));

        // ── 2. 산출품목 = 사양변경 대상 > 박스 푼 반제품 > 원 품목 ───
        Integer outMatId = (targetMaterialId != null) ? targetMaterialId
                : (semiMatId != null ? semiMatId : srcMatId);

        // ── 3. 수리 작지 (mat_produce."JobResponse_id" NOT NULL 이라 반드시 필요) ──
        MapSqlParameterSource jp = new MapSqlParameterSource()
                .addValue("matId", outMatId)
                .addValue("prodDate", java.sql.Date.valueOf(rDate))
                .addValue("wcId", workCenter())
                .addValue("userId", user.getId())
                .addValue("spjangcd", spjangcd);
        Map<String, Object> jrRow = this.sqlRunner.getRow("""
                INSERT INTO job_res ("Material_id","ProductionDate","ProductionPlanDate","OrderQty",
                                     "WorkCenter_id","FirstWorkCenter_id","LotCount","ProcessCount",
                                     "State","_status","_created","_creater_id",spjangcd)
                VALUES (:matId, :prodDate, :prodDate, 1,
                        :wcId, :wcId, 1, 1,
                        'ordered','a',now(),:userId,:spjangcd)
                RETURNING id, "WorkOrderNumber"
                """, jp);
        Integer jrId = asInt(jrRow.get("id"));

        // ── 4. 접수 헤더 ────────────────────────────────────────
        String repairNo = nextRepairNo(rDate, spjangcd);
        MapSqlParameterSource rp = new MapSqlParameterSource()
                .addValue("no", repairNo).addValue("cat", cat)
                .addValue("rdate", java.sql.Date.valueOf(rDate))
                .addValue("scanKey", key)
                .addValue("srcLot", srcLotNo).addValue("makerLot", makerLotNo)
                .addValue("srcMat", srcMatId).addValue("tgtMat", targetMaterialId)
                .addValue("intake", intakeType)
                .addValue("reason", (reason == null || reason.isBlank()) ? null : reason.trim())
                .addValue("jrId", jrId)
                .addValue("userId", user.getId()).addValue("spjangcd", spjangcd);
        Map<String, Object> rRow = this.sqlRunner.getRow("""
                INSERT INTO mcell_repair ("RepairNo","Cat","ReceiptDate","ScanKey","SrcLotNumber",
                                          "SrcMakerLotNo","SrcMaterial_id","TargetMaterial_id",
                                          "IntakeType","Reason","JobResponse_id","State",
                                          "_status","_created","_creater_id",spjangcd)
                VALUES (:no,:cat,:rdate,:scanKey,:srcLot,:makerLot,:srcMat,:tgtMat,
                        :intake,:reason,:jrId,'wait','a',now(),:userId,:spjangcd)
                RETURNING id
                """, rp);
        Integer repairId = asInt(rRow.get("id"));

        /* ── 5. 수리창고에 현물 확보 ─────────────────────────────
             move : 이미 있는 재고를 옮긴다(순증 0).  new : 반품으로 새로 받는다(+1).

           ★ 박스를 푼 경우는 둘 다 아니다.
             스캔한 것은 완제품이지만 수리대에 올라가는 물건은 그 안의 재공품이다.
             완제품 로트를 그대로 옮기면 수리창고에 「완제품」재고가 서고,
             원 로트가 재공품 번호가 아니게 되어 usedQtyOf 가 조립 이력을 못 찾는다
             (그 조회는 mcell_unit."LotNumber" = 원로트번호 로 조립 유닛을 찾는다).
             그래서 재공품으로 새 로트를 받고, 완제품 재고가 남아 있으면 그만큼 뺀다. */
        Integer srcMatLotId;
        if (semiMatId != null) {
            // 박스 해체 — 완제품 재고가 남아 있으면 차감하고 재공품으로 받는다.
            // (사양변경으로 제품창고 재고를 헐 때가 여기다. 반품이면 stock=0 이라 차감 없이 입고)
            srcMatLotId = unpackIntake(lot, semiMatId, srcLotNo, makerLotNo,
                    repairId, repairNo, spjangcd, user);
        } else if ("move".equals(intakeType)) {
            Integer fromStore = asInt(lot.get("store_id"));
            srcMatLotId = asInt(lot.get("id"));
            if (fromStore != null && !fromStore.equals(storeRepair())) {
                moveLot(srcMatLotId, fromStore, storeRepair(), repairId,
                        "수리 접수 · 수리창고 입고", spjangcd, user);
            }
        } else {
            AjaxResult in = this.productionCreateService.receiveLot(
                    srcMatId, srcLotNo, makerLotNo, 1f, storeRepair(),
                    "mcell_repair", repairId, "반품 입고 · " + repairNo, user, spjangcd);
            if (!in.success) throw new IllegalStateException(in.message);
            srcMatLotId = asInt(((Map<?, ?>) in.data).get("mat_lot_id"));
        }

        // 원 창고는 별도로 저장하지 않는다 — moveLot 이 남긴 mat_inout out 행이 그 정보다.
        // (그 이력은 어디서도 삭제하지 않으므로 취소 시 거기서 찾으면 된다)
        this.sqlRunner.execute("""
                UPDATE mcell_repair SET "SrcMatLot_id"=:mlId, "_modified"=now(), "_modifier_id"=:userId
                 WHERE id=:rid
                """, new MapSqlParameterSource().addValue("mlId", srcMatLotId)
                .addValue("rid", repairId).addValue("userId", user.getId()));

        // ── 6. 유닛 1대 ─────────────────────────────────────────
        /*   기본 로트 모드 : 반품=원 로트 유지, 사양변경=새 로트 발번 (화면에서 바꿀 수 있음)
             ★ 박스를 푼 경우도 새 로트다. 품목이 완제품에서 반제품으로 바뀌므로
               같은 로트번호를 물려주면 한 번호가 두 품목을 가리키게 된다. */
        /* 박스를 푼 경우는 원 로트(재공품 번호)를 그대로 이어간다 —
           방금 그 번호로 재고를 받았고, 조립 이력도 그 번호에 달려 있다.
           사양변경만 새 번호를 딴다(품목이 바뀌므로). */
        String lotMode = "spec".equals(cat) ? "new" : "keep";
        MapSqlParameterSource up = new MapSqlParameterSource()
                .addValue("jrId", jrId).addValue("matId", outMatId)
                .addValue("rid", repairId).addValue("lotMode", lotMode)
                .addValue("srcLot", srcLotNo).addValue("srcMatLot", srcMatLotId)
                .addValue("userId", user.getId()).addValue("spjangcd", spjangcd);
        Map<String, Object> uRow = this.sqlRunner.getRow("""
                INSERT INTO mcell_unit ("JobResponse_id","Material_id","UnitNo","State",
                                        "McellRepair_id","LotMode","SrcLotNumber","SrcMatLot_id",
                                        "_status","_created","_creater_id",spjangcd)
                VALUES (:jrId,:matId,1,'wait',:rid,:lotMode,:srcLot,:srcMatLot,
                        'a',now(),:userId,:spjangcd)
                RETURNING id
                """, up);

        Integer unitId = asInt(uRow.get("id"));

        /* ── 7. 포장 자재 회수 목록 자동 등록 ────────────────────
             박스를 풀었으면 그 안에 들어 있던 것들이 전부 회수 후보다.
             박스·폼도 멀쩡하면 다시 쓰므로 전부 담고,
             실제로 회수 못 하는 것만 작업자가 mat_del 로 지운다.
             (수량도 mat_qty 로 줄일 수 있다 — IN BOX 2개 중 1개만 성한 경우)

             ★ 여기서 실패해도 접수는 살린다. 회수 목록은 사람이 다시 담을 수 있지만
               접수가 통째로 롤백되면 현물은 이미 창고에 있는데 전표가 없어진다. */
        int recovered = 0;
        if (semiMatId != null) {
            recovered = seedReturnMats(unitId, srcMatId, semiMatId, spjangcd, user);
        }

        r.data = Map.of("repair_id", repairId, "repair_no", repairNo,
                "job_res_id", jrId, "unit_id", unitId,
                "intake_type", intakeType, "src_lot", srcLotNo,
                "unpacked", semiMatId != null, "return_mat_cnt", recovered);
        r.message = ("move".equals(intakeType)
                ? "기존 재고를 수리창고로 이동했습니다."
                : "반품으로 신규 입고했습니다.")
                + (semiMatId != null
                ? " 박스를 풀어 " + str(unpack.get("semi_code")) + " 로 접수했습니다."
                + (recovered > 0 ? " 포장자재 " + recovered + "건이 회수 목록에 담겼습니다." : "")
                : "");
        return r;
    }

    /**
     * 박스를 풀어 재공품으로 접수한다.
     *
     * 실제로 일어나는 일 그대로를 재고에 적는다 :
     *   완제품 재고가 남아 있으면  → 그만큼 차감 (박스를 헐었으므로 완제품이 아니다)
     *   재공품                    → 수리창고에 +1 (수리대에 올라갈 물건)
     *   포장 자재                  → seedReturnMats 가 회수 목록에 담고,
     *                               실제 입고는 unitFinish 가 한다(회수 여부 확정 후)
     *
     * ★ 로트번호는 원 번호를 그대로 쓴다.
     *   완제품과 재공품이 같은 번호를 쓰는 것이 이 공장의 규칙이고
     *   (MC4144-260812-001 이 양쪽에 다 있다), 무엇보다 usedQtyOf 가
     *   mcell_unit."LotNumber" = 원로트번호 로 조립 이력을 찾는다.
     *   새 번호를 따면 조립 때 쓰인 자재를 영영 못 본다.
     *
     * ★ 완제품 재고가 0 이어도 정상이다 — 출하되었다가 돌아온 물건이다.
     *   그때는 차감 없이 재공품만 받는다.
     */
    private Integer unpackIntake(Map<String, Object> lot, Integer semiMatId,
                                 String srcLotNo, String makerLotNo,
                                 Integer repairId, String repairNo,
                                 String spjangcd, User user) {

        // 1) 완제품 재고가 남아 있으면 헐어 없앤다
        if (lot != null && toD(lot.get("stock")) > 0) {
            MapSqlParameterSource dp = new MapSqlParameterSource()
                    .addValue("id", asInt(lot.get("id")))
                    .addValue("matId", asInt(lot.get("mat_id")))
                    .addValue("store", asInt(lot.get("store_id")))
                    .addValue("lot", srcLotNo)
                    .addValue("rid", repairId)
                    .addValue("memo", "수리 접수 · 박스 해체 · " + repairNo)
                    .addValue("userId", user.getId()).addValue("spjangcd", spjangcd);
            this.sqlRunner.execute("""
                    UPDATE mat_lot
                       SET "CurrentStock" = GREATEST(COALESCE("CurrentStock",0) - 1, 0),
                           "_modified" = now(), "_modifier_id" = :userId
                     WHERE id = :id
                    """, dp);
            this.sqlRunner.execute("""
                    INSERT INTO mat_inout ("Material_id","StoreHouse_id","LotNumber","InoutDate","InoutTime",
                                           "InOut","InputQty","OutputQty","InputType","SourceTableName","SourceDataPk",
                                           "State","Description","_status","_created","_creater_id",spjangcd)
                    VALUES (:matId,:store,:lot,CURRENT_DATE,LOCALTIME,'out',NULL,1,'move',
                            'mcell_repair',:rid,'confirmed',:memo,'a',now(),:userId,:spjangcd)
                    """, dp);
        }

        // 2) 재공품을 수리창고로 받는다
        AjaxResult in = this.productionCreateService.receiveLot(
                semiMatId, srcLotNo, makerLotNo, 1f, storeRepair(),
                "mcell_repair", repairId, "수리 접수 · 박스 해체 입고 · " + repairNo, user, spjangcd);
        if (!in.success) throw new IllegalStateException(in.message);
        return asInt(((Map<?, ?>) in.data).get("mat_lot_id"));
    }

    /**
     * 완제품 → 그 안의 반제품(수리 대상) 찾기.
     *
     * 포장 BOM 구성품 중 MaterialType='semi' 인 것 하나를 집는다.
     * M-CELL 완제품 BOM 은 semi 하나 + sub_mat 여럿(박스·폼·케이블·어댑터) 구조라
     * semi 는 항상 하나다. 혹시 둘 이상이면 _order 가 앞선 것(=본체)을 쓴다.
     *
     * ★ 완제품이 아니면 null. 포장 전 반제품이 그대로 들어온 경우이며,
     *   그때는 박스를 풀 것도 회수할 것도 없다.
     * ★ bom_comp 는 "BOM_id"(대문자), 수량은 "Amount" 다. "Bom_id"·"Qty" 가 아니다.
     */
    private Map<String, Object> findPackedSemi(Integer productMatId) {
        if (productMatId == null) return null;
        return this.sqlRunner.getRow("""
                SELECT cm.id       AS semi_mat_id
                     , cm."Code"   AS semi_code
                     , cm."Name"   AS semi_name
                     , b.id        AS bom_id
                  FROM material pm
                  JOIN mat_grp  pmg ON pmg.id = pm."MaterialGroup_id"
                  JOIN bom      b   ON b."Material_id" = pm.id
                                   AND COALESCE(b."_status",'a') = 'a'
                  JOIN bom_comp bc  ON bc."BOM_id" = b.id
                                   AND COALESCE(bc."_status",'a') = 'a'
                  JOIN material cm  ON cm.id = bc."Material_id"
                  JOIN mat_grp  cmg ON cmg.id = cm."MaterialGroup_id"
                 WHERE pm.id = :matId
                   AND pmg."MaterialType" = 'product'
                   AND cmg."MaterialType" = 'semi'
                 ORDER BY bc."_order", bc.id
                 LIMIT 1
                """, new MapSqlParameterSource().addValue("matId", productMatId));
    }

    /**
     * 포장 자재를 −회수 목록에 미리 담는다.
     *
     * 담기는 것 : 포장 BOM 구성품 중 semi 를 뺀 전부(박스·폼·케이블·어댑터).
     * 수량      : BOM Amount 그대로. 반품 1대 기준이라 배수 계산이 없다.
     *
     * ★ 회수 여부를 여기서 판단하지 않는다.
     *   박스가 찌그러졌는지, 어댑터가 살았는지는 물건을 열어봐야 안다.
     *   전부 담아두고 못 쓰는 것만 지우는 편이,
     *   빠진 것을 나중에 기억해 담는 것보다 훨씬 덜 샌다.
     *
     * ★ matAdd 를 거치지 않고 직접 INSERT 하는 이유 :
     *   matAdd 의 guardReturnQty 는 「원 M-CELL 에 투입된 수량」을 상한으로 두는데,
     *   그 계산이 조립 스텝(mcell_unit_step)의 소비 이력만 본다.
     *   포장 자재는 조립에서 투입된 적이 없어 상한이 0 으로 잡힌다.
     *   BOM 이 곧 근거이므로 여기서는 그 검사를 건너뛴다.
     *   (사람이 화면에서 더 담을 때는 종전대로 matAdd 를 타고 검사를 받는다)
     *
     * @return 담긴 건수
     */
    private int seedReturnMats(Integer unitId, Integer productMatId, Integer semiMatId,
                               String spjangcd, User user) {
        List<Map<String, Object>> comps = this.sqlRunner.getRows("""
                SELECT cm.id AS mat_id, COALESCE(bc."Amount",1) AS amount
                  FROM bom b
                  JOIN bom_comp bc ON bc."BOM_id" = b.id
                                  AND COALESCE(bc."_status",'a') = 'a'
                  JOIN material cm ON cm.id = bc."Material_id"
                  JOIN mat_grp  cmg ON cmg.id = cm."MaterialGroup_id"
                 WHERE b."Material_id" = :matId
                   AND COALESCE(b."_status",'a') = 'a'
                   AND cmg."MaterialType" <> 'semi'
                   AND cm.id <> :semiId
                 ORDER BY bc."_order", bc.id
                """, new MapSqlParameterSource()
                .addValue("matId", productMatId).addValue("semiId", semiMatId));

        int n = 0;
        for (Map<String, Object> c : comps) {
            double amt = toD(c.get("amount"));
            if (amt <= 0) continue;
            this.sqlRunner.execute("""
                    INSERT INTO mcell_repair_mat ("McellUnit_id","Material_id","Dir","Qty","State",
                                                  "_status","_created","_creater_id",spjangcd)
                    VALUES (:uid,:matId,'-',:qty,'plan','a',now(),:userId,:spjangcd)
                    """, new MapSqlParameterSource()
                    .addValue("uid", unitId).addValue("matId", asInt(c.get("mat_id")))
                    .addValue("qty", (float) amt)
                    .addValue("userId", user.getId()).addValue("spjangcd", spjangcd));
            n++;
        }
        return n;
    }

    /** 접수 취소 (수리 시작 전에만). 확보한 재고도 되돌린다. */
    @Transactional
    public AjaxResult registCancel(Integer repairId, String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        Map<String, Object> rep = getRepairRow(repairId);
        if (rep == null) { r.success = false; r.message = "접수 건을 찾을 수 없습니다."; return r; }

        Map<String, Object> busy = this.sqlRunner.getRow("""
                SELECT COUNT(*) AS c FROM mcell_unit
                 WHERE "McellRepair_id"=:rid AND COALESCE("_status",'a')='a'
                   AND "State" <> 'wait'
                """, new MapSqlParameterSource().addValue("rid", repairId));
        if (busy != null && asInt(busy.get("c")) > 0) {
            r.success = false; r.message = "이미 수리가 시작되어 접수를 취소할 수 없습니다."; return r;
        }

        Integer mlId = asInt(rep.get("src_mat_lot_id"));
        if (mlId != null) {
            if ("new".equals(str(rep.get("intake_type")))) {
                // 반품 신규 입고분 → 로트째 제거. 이미 쓰였으면 차단.
                Map<String, Object> used = this.sqlRunner.getRow("""
                        SELECT COALESCE("CurrentStock",0) AS cs, COALESCE("InputQty",0) AS iq
                          FROM mat_lot WHERE id=:id
                        """, new MapSqlParameterSource().addValue("id", mlId));
                if (used != null && toD(used.get("cs")) < toD(used.get("iq"))) {
                    r.success = false; r.message = "이 로트가 이미 사용되어 취소할 수 없습니다."; return r;
                }
                MapSqlParameterSource dp = new MapSqlParameterSource().addValue("id", mlId);
                this.sqlRunner.execute("DELETE FROM mat_inout WHERE \"SourceTableName\"='mat_lot' AND \"SourceDataPk\"=:id", dp);
                this.sqlRunner.execute("DELETE FROM mat_lot WHERE id=:id", dp);
            } else {
                // 옮겨온 것 → 원래 창고로 되돌린다.
                //   원 창고는 접수 때 moveLot 이 남긴 mat_inout out 행이 들고 있다.
                //   못 찾으면 임의로 넣지 않고 막는다 — 모르는 자리에 재고를 쌓는 게 더 나쁘다.
                Integer back = intakeFromStore(repairId);
                if (back == null) {
                    r.success = false;
                    r.message = "접수 때의 원래 창고 이력을 찾을 수 없어 되돌릴 수 없습니다. "
                            + "재고를 직접 확인해 주세요.";
                    return r;
                }
                moveLot(mlId, storeRepair(), back, repairId,
                        "수리 접수 취소 · 원 창고 복귀", spjangcd, user);
            }
        }

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("rid", repairId).addValue("userId", user.getId());

        /* ★ 접수 취소는 「없던 일로 한다」는 뜻이므로 행을 남기지 않는다.
             자식부터 순서대로 지운다 — 회수목록 → 유닛 → 접수 → 작지.
             (mcell_repair."JobResponse_id" 가 job_res 를 참조하므로
              접수 행을 남긴 채 작지를 지우면 FK 로 막힌다) */
        this.sqlRunner.execute("""
                DELETE FROM mcell_repair_mat rm
                 USING mcell_unit mu
                 WHERE mu.id = rm."McellUnit_id"
                   AND mu."McellRepair_id" = :rid
                """, p);
        this.sqlRunner.execute("DELETE FROM mcell_unit   WHERE \"McellRepair_id\"=:rid", p);
        this.sqlRunner.execute("DELETE FROM mcell_repair WHERE id=:rid", p);

        /* ★ 접수 때 만든 수리 작지도 지운다.
             regist 가 job_res 를 직접 INSERT 한다(mat_produce."JobResponse_id" 가
             NOT NULL 이라 실적을 담을 그릇이 필요했다).
             접수를 취소하면 그 그릇도 쓸 데가 없는데, 남겨두면
             조립·수리 큐에 「지시 1 · 실적 0」짜리 작지가 영영 떠 있는다.

             ★ 진짜로 지운다. _status='d' 로 두지 않는다.
               job_res 를 읽는 곳이 대시보드·실적현황·작업일보·공정화면까지 여럿인데,
               그 쿼리들이 전부 _status 조건을 걸고 있지는 않다.
               하나라도 빠지면 취소한 작지가 그 화면에만 계속 보인다.
               접수 취소는 「없던 일로 한다」는 뜻이므로 행을 남길 이유가 없다.

             ★ 실적(mat_produce)이 붙어 있으면 지우지 않는다.
               수리 시작 전에만 취소되므로 원래 없어야 하지만,
               있다면 그건 이 작지가 다른 용도로도 쓰였다는 뜻이라
               조용히 지우는 쪽이 더 위험하다.
               FK 로도 막히므로 DELETE 가 실패하는 것보다 미리 거르는 편이 낫다. */
        Integer jrId = asInt(rep.get("job_res_id"));
        if (jrId != null) {
            this.sqlRunner.execute("""
                    DELETE FROM job_res
                     WHERE id = :jrId
                       AND NOT EXISTS (SELECT 1 FROM mat_produce mp
                                        WHERE mp."JobResponse_id" = :jrId)
                    """, new MapSqlParameterSource().addValue("jrId", jrId));
        }
        return r;
    }

    // =====================================================================
    // 유닛 설정
    // =====================================================================

    /** 결과 로트 방식 (keep = 원 로트 유지 / new = 새 로트 발번) */
    @Transactional
    public AjaxResult setLotMode(Integer unitId, String mode, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        if (!"keep".equals(mode) && !"new".equals(mode)) {
            r.success = false; r.message = "로트 방식이 올바르지 않습니다."; return r;
        }
        Map<String, Object> u = getUnitRow(unitId);
        if (u == null) { r.success = false; r.message = "유닛을 찾을 수 없습니다."; return r; }
        if (u.get("mat_produce_id") != null) {
            r.success = false; r.message = "수리가 완료된 유닛은 로트 방식을 바꿀 수 없습니다."; return r;
        }
        this.sqlRunner.execute("""
                UPDATE mcell_unit SET "LotMode"=:m, "_modified"=now(), "_modifier_id"=:userId WHERE id=:id
                """, new MapSqlParameterSource().addValue("m", mode)
                .addValue("id", unitId).addValue("userId", user.getId()));
        r.data = Map.of("lot_mode", mode, "next_lot", previewResultLot(getUnitRow(unitId)));
        return r;
    }

    /**
     * 사양변경 대상 품목 지정.
     * ★ job_res."Material_id" 도 같이 바꿔야 한다.
     *   startProduction 이 mp.MaterialId 를 작지에서 가져오기 때문에,
     *   여기를 빠뜨리면 산출품목이 원 품목으로 나온다.
     */
    @Transactional
    public AjaxResult setTargetMaterial(Integer repairId, Integer targetMatId, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        Map<String, Object> rep = getRepairRow(repairId);
        if (rep == null) { r.success = false; r.message = "접수 건을 찾을 수 없습니다."; return r; }

        Map<String, Object> busy = this.sqlRunner.getRow("""
                SELECT COUNT(*) AS c FROM mcell_unit
                 WHERE "McellRepair_id"=:rid AND COALESCE("_status",'a')='a' AND "State" <> 'wait'
                """, new MapSqlParameterSource().addValue("rid", repairId));
        if (busy != null && asInt(busy.get("c")) > 0) {
            r.success = false; r.message = "수리 시작 후에는 대상 품목을 바꿀 수 없습니다."; return r;
        }

        Integer outMatId = (targetMatId != null) ? targetMatId : asInt(rep.get("src_material_id"));
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("rid", repairId).addValue("tgt", targetMatId)
                .addValue("out", outMatId).addValue("jrId", asInt(rep.get("job_res_id")))
                .addValue("userId", user.getId());
        this.sqlRunner.execute("""
                UPDATE mcell_repair SET "TargetMaterial_id"=:tgt,
                       "_modified"=now(), "_modifier_id"=:userId WHERE id=:rid
                """, p);
        this.sqlRunner.execute("UPDATE job_res SET \"Material_id\"=:out WHERE id=:jrId", p);
        this.sqlRunner.execute("""
                UPDATE mcell_unit SET "Material_id"=:out, "_modified"=now(), "_modifier_id"=:userId
                 WHERE "McellRepair_id"=:rid AND COALESCE("_status",'a')='a'
                """, p);
        return r;
    }

    // =====================================================================
    // 자재 가감 (＋투입 / −회수)
    // =====================================================================

    @Transactional
    public AjaxResult matAdd(Integer unitId, Integer matId, String dir, Float qty,
                             Integer srcMatLotId, String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        if (!"+".equals(dir) && !"-".equals(dir)) {
            r.success = false; r.message = "가감 방향이 올바르지 않습니다."; return r;
        }
        AjaxResult guard = guardEditable(unitId);
        if (!guard.success) return guard;

        // ＋투입은 원 부품로트 개념이 없다
        Integer srcLotId = "-".equals(dir) ? srcMatLotId : null;

        float q = (qty == null || qty <= 0) ? 1f : qty;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("uid", unitId).addValue("matId", matId).addValue("dir", dir)
                .addValue("qty", q).addValue("srcLotId", srcLotId)
                .addValue("userId", user.getId()).addValue("spjangcd", spjangcd);

        // 같은 품목·방향·원로트가 이미 있으면 수량만 올린다.
        // ★ 로트까지 봐야 한다 — 같은 하네스라도 로트가 다르면 별개 회수 건이다.
        Map<String, Object> ex = this.sqlRunner.getRow("""
                SELECT id, "Qty" AS qty FROM mcell_repair_mat
                 WHERE "McellUnit_id"=:uid AND "Material_id"=:matId AND "Dir"=:dir
                   AND "SrcMatLot_id" IS NOT DISTINCT FROM CAST(:srcLotId AS integer)
                   AND COALESCE("State",'plan')='plan'   -- 반영된 행은 이력, 합치지 않는다
                   AND COALESCE("_status",'a')='a' LIMIT 1
                """, p);

        // 쓴 것보다 많이 뺄 수는 없고, 없는 걸 넣을 수도 없다
        float after = q + (ex == null ? 0f : (float) toD(ex.get("qty")));
        if ("-".equals(dir)) {
            AjaxResult cap = guardReturnQty(unitId, matId, srcLotId, after);
            if (!cap.success) return cap;
        } else {
            AjaxResult stk = guardInputQty(matId, after);
            if (!stk.success) return stk;
        }

        if (ex != null) {
            this.sqlRunner.execute("""
                    UPDATE mcell_repair_mat SET "Qty"="Qty"+:qty,
                           "_modified"=now(), "_modifier_id"=:userId
                     WHERE id=:id
                    """, new MapSqlParameterSource().addValue("qty", q)
                    .addValue("id", asInt(ex.get("id"))).addValue("userId", user.getId()));
            r.data = Map.of("rmat_id", asInt(ex.get("id")));
            return r;
        }

        Map<String, Object> ins = this.sqlRunner.getRow("""
                INSERT INTO mcell_repair_mat ("McellUnit_id","Material_id","Dir","Qty","SrcMatLot_id","State",
                                              "_status","_created","_creater_id",spjangcd)
                VALUES (:uid,:matId,:dir,:qty,CAST(:srcLotId AS integer),'plan','a',now(),:userId,:spjangcd)
                RETURNING id
                """, p);
        r.data = Map.of("rmat_id", asInt(ins.get("id")));
        return r;
    }

    @Transactional
    public AjaxResult matQty(Integer rmatId, Float qty, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        if (qty == null || qty <= 0) { r.success = false; r.message = "수량은 1 이상이어야 합니다."; return r; }

        Map<String, Object> rm = getRmatRow(rmatId);
        Integer unitId = asInt(rm.get("unit_id"));
        AjaxResult guard = guardEditable(unitId);
        if (!guard.success) return guard;
        AjaxResult rowGuard = guardRmatEditable(rm);
        if (!rowGuard.success) return rowGuard;

        if ("-".equals(str(rm.get("dir")))) {
            AjaxResult cap = guardReturnQty(unitId, asInt(rm.get("mat_id")),
                    asInt(rm.get("src_mat_lot_id")), qty);
            if (!cap.success) return cap;
        } else {
            AjaxResult stk = guardInputQty(asInt(rm.get("mat_id")), qty);
            if (!stk.success) return stk;
        }

        this.sqlRunner.execute("""
                UPDATE mcell_repair_mat SET "Qty"=:qty, "_modified"=now(), "_modifier_id"=:userId
                 WHERE id=:id
                """, new MapSqlParameterSource().addValue("qty", qty)
                .addValue("id", rmatId).addValue("userId", user.getId()));
        return r;
    }

    @Transactional
    public AjaxResult matDel(Integer rmatId, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        Map<String, Object> rm = getRmatRow(rmatId);
        AjaxResult guard = guardEditable(asInt(rm.get("unit_id")));
        if (!guard.success) return guard;
        AjaxResult rowGuard = guardRmatEditable(rm);
        if (!rowGuard.success) return rowGuard;

        this.sqlRunner.execute("""
                UPDATE mcell_repair_mat SET "_status"='d', "_modified"=now(), "_modifier_id"=:userId
                 WHERE id=:id
                """, new MapSqlParameterSource().addValue("id", rmatId).addValue("userId", user.getId()));
        return r;
    }

    // =====================================================================
    // 수리 시작 / 취소 / 완료 / 복귀
    // =====================================================================

    @Transactional
    public AjaxResult unitStart(Integer unitId, Integer actorId, String memberIds, Integer equipmentId,
                                String startTime, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        Map<String, Object> u = getUnitRow(unitId);
        if (u == null) { r.success = false; r.message = "유닛을 찾을 수 없습니다."; return r; }
        String st = str(u.get("state"));
        // repairing 도 허용 — 수리중 작업자/설비 교체가 필요하다
        if (!"wait".equals(st) && !"repairing".equals(st) && !"reject".equals(st)) {
            r.success = false; r.message = "수리를 시작할 수 없는 상태입니다. (" + st + ")"; return r;
        }
        if (actorId == null) { r.success = false; r.message = "작업자를 배정하세요."; return r; }

        boolean already = "repairing".equals(st);   // 재배정이면 상태·시작시각을 건드리지 않는다
        boolean rework  = "reject".equals(st) && u.get("mat_produce_id") != null;

        // ★ 재작업은 되돌리기도 새 회차도 아니다. 1차 실적을 이어서 고친다.
        //   조립의 분해와 다른 점 : 여기서는 실물이 갈라지지 않는다.
        //   손에 있는 건 여전히 그 결과 로트 하나이고, 자재를 더 넣어 계속 손보는 것이다.
        //   그래서 MatProduce_id / LotNumber 를 그대로 둔다 —
        //   결과 로트는 삭제되지 않고, 추가로 담는 자재만 완료 시 소비된다.
        //   이미 소비된(applied) 자재 행은 이력이라 수정·삭제하지 못한다.
        String reworkNote = rework
                ? "재작업 · 추가로 쓴 자재만 담고 다시 «수리 완료» 를 누르세요. "
                + "결과 로트 " + str(u.get("result_lot")) + " 는 그대로 유지됩니다."
                : null;

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", unitId).addValue("actorId", actorId)
                .addValue("equipId", equipmentId)
                .addValue("st", (startTime == null || startTime.isBlank()) ? null : startTime)
                .addValue("keep", already)
                .addValue("userId", user.getId());
        this.sqlRunner.execute("""
                UPDATE mcell_unit
                   SET "State"        = CASE WHEN :keep THEN "State" ELSE 'repairing' END,
                       "Actor_id"     = :actorId,
                       "Equipment_id" = COALESCE(:equipId, "Equipment_id"),
                       "StartTime"    = COALESCE(CAST(:st AS timestamp), "StartTime", LOCALTIMESTAMP),
                       "RejectReason" = NULL, "RejectInspNo" = NULL, "RejectAt" = NULL,
                       "_modified"=now(), "_modifier_id"=:userId
                 WHERE id=:id
                """, p);

        // 조원 명단. 대표(Actor_id)는 위에서 mcell_unit 에 저장했고, 여기엔 대표도 1행 들어간다.
        this.workMemberService.save(WorkMemberService.SRC_MCELL_UNIT,
                unitId, actorId, memberIds, user, "ZZ");

        touchRepairState(asInt(u.get("repair_id")), user);

        if (reworkNote != null) r.message = reworkNote;
        r.data = Map.of("state", "repairing", "next_lot", previewResultLot(getUnitRow(unitId)));
        return r;
    }

    /** 시작취소 — 아직 실적이 없을 때만 */
    @Transactional
    public AjaxResult unitCancel(Integer unitId, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        Map<String, Object> u = getUnitRow(unitId);
        if (u == null) { r.success = false; r.message = "유닛을 찾을 수 없습니다."; return r; }
        if (u.get("mat_produce_id") != null) {
            r.success = false; r.message = "수리 완료된 유닛입니다. «검사취소 · 수리 복귀»를 쓰세요."; return r;
        }
        this.sqlRunner.execute("""
                UPDATE mcell_unit SET "State"='wait', "StartTime"=NULL,
                       "_modified"=now(), "_modifier_id"=:userId WHERE id=:id
                """, new MapSqlParameterSource().addValue("id", unitId).addValue("userId", user.getId()));
        this.workMemberService.clear(WorkMemberService.SRC_MCELL_UNIT, unitId);
        touchRepairState(asInt(u.get("repair_id")), user);
        return r;
    }

    /**
     * ★ 수리 완료 — 이 메서드가 알맹이.
     *
     *   startProduction        mat_produce 생성 (재고 안 움직임)
     *   consumeLot             수리창고의 원 로트 1대 소비          ← 지정 로트(패치5)
     *   finishProduction       ＋자재 FIFO 소비 + 결과 로트 17 입고
     *   receiveLot × n         −자재(뜯어낸 부품) 생산창고 재입고
     *   유닛 → inspect_wait     검사 화면(mc02)이 여기서부터 잡는다
     *
     * 소비 1대 / 산출 1대라 재고 순증은 0. keep 이든 new 든 경로가 같고
     * 결과 로트번호 문자열만 다르다.
     */
    @Transactional
    public AjaxResult unitFinish(Integer unitId, String startTime, String endTime,
                                 String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        Map<String, Object> u = getUnitRow(unitId);
        if (u == null) { r.success = false; r.message = "유닛을 찾을 수 없습니다."; return r; }
        if (!"repairing".equals(str(u.get("state")))) {
            r.success = false; r.message = "수리중인 유닛만 완료할 수 있습니다."; return r;
        }
        Integer actorId = asInt(u.get("actor_id"));
        if (actorId == null) { r.success = false; r.message = "작업자가 배정되지 않았습니다."; return r; }

        // ── 재작업 : 1차 실적을 이어서 마감 ─────────────────
        //   실물은 여전히 그 결과 로트 하나다. 새 로트를 만들지도, 되돌리지도 않는다.
        //   추가로 담은(plan) 자재만 그 차수에 더 소비하고 다시 검사로 보낸다.
        if (u.get("mat_produce_id") != null) {
            return finishRework(unitId, u, endTime, spjangcd, user);
        }

        Integer srcMatLotId = asInt(u.get("src_mat_lot_id"));
        if (srcMatLotId == null) {
            r.success = false; r.message = "투입할 원 로트 재고가 지정되지 않았습니다. 접수를 확인하세요."; return r;
        }

        Integer jrId     = asInt(u.get("job_res_id"));
        Integer outMatId = asInt(u.get("mat_id"));
        String  resultLot = previewResultLot(u);

        // ── 자재 가감 목록 ──
        List<Map<String, Object>> mats = getUnitMats(unitId);
        List<ProductionCreateService.BomInput> plus = new ArrayList<>();
        List<Map<String, Object>> minus = new ArrayList<>();
        for (Map<String, Object> m : mats) {
            if ("+".equals(str(m.get("dir")))) {
                plus.add(new ProductionCreateService.BomInput(asInt(m.get("mat_id")),
                        (float) toD(m.get("qty"))));
            } else {
                minus.add(m);
            }
        }

        ProductionCreateService.CreateReq req = new ProductionCreateService.CreateReq();
        req.jobResId       = jrId;
        req.materialId     = outMatId;
        req.workCenterId   = workCenter();
        req.actorId        = actorId;
        // 설비를 골랐으면 equ_run(가동 구간)이 남는다. 안 골랐으면 finishProduction 이 건너뛴다.
        req.equipmentId    = asInt(u.get("equipment_id"));
        req.memberIds      = null;              // 2공장 1인 작업
        req.shiftCode      = null;
        req.goodQty        = 1f;
        req.defectQty      = 0f;
        req.productionDate = LocalDate.now().toString();
        req.startTime      = (startTime != null && !startTime.isBlank()) ? startTime : str(u.get("start_time"));
        req.endTime        = endTime;
        req.bomList        = plus;              // ＋자재만. 원 로트는 아래에서 따로.
        req.cleanStore     = STORE_PROD;
        req.lotNumber      = resultLot;         // ★ keep/new 차이는 여기 한 줄뿐
        req.spjangcd       = spjangcd;

        // 1) 실적 껍데기
        AjaxResult started = this.productionCreateService.startProduction(req, user);
        if (!started.success) return started;
        Integer mpId = asInt(((Map<?, ?>) started.data).get("mat_produce_id"));

        // 2) 원 M-CELL 로트 투입 (지정 로트 · 수리창고)
        AjaxResult cons = this.productionCreateService.consumeLot(mpId, srcMatLotId, 1f, user, spjangcd);
        if (!cons.success) throw new IllegalStateException(cons.message);   // 롤백

        // 3) ＋자재 소비 + 결과 로트 생산창고(17) 입고
        AjaxResult fin = this.productionCreateService.finishProduction(mpId, req, user);
        if (!fin.success) throw new IllegalStateException(fin.message);

        // ★ 조원을 mat_produce 로 승계한다.
        //   수리도 완료 시점에야 mat_produce 가 생긴다. 작업일보·실적현황이 mat_produce 축으로
        //   조원을 읽으므로 여기서 한 번 더 심어야 수리 조원이 보인다.
        this.workMemberService.save(WorkMemberService.SRC_MAT_PRODUCE, mpId,
                asInt(u.get("actor_id")),
                this.workMemberService.idsOf(WorkMemberService.SRC_MCELL_UNIT, unitId),
                user, spjangcd);

        // 4) −자재(회수 부품) 생산창고 재입고
        //    ★ 원 부품로트를 알면 그 번호를 이어받는다 : {원부품로트}-RC{n}
        //      번호만 잇고 재고는 새 로트로 분리한다. 원 로트에 합치면
        //      중고가 신품 재고에 섞여 FIFO 로 그대로 나가버린다.
        for (Map<String, Object> m : minus) {
            Integer rmatId = asInt(m.get("rmat_id"));
            String base = str(m.get("src_lot_number"));
            String recLot = (base != null && !base.isBlank())
                    ? nextSuffixLot(base, "-RC")
                    : resultLot + "-RC" + rmatId;
            /* ★ 이 비고는 「회수된 부품」의 입고 이력에 붙는다.
                 결과 로트(resultLot)는 수리된 M-CELL 번호라 이 부품과 무관하다 —
                 나중에 부품 로트를 조회하면 남의 번호가 박혀 있어 오해를 부른다.
                 어느 수리에서 나왔는지는 수리번호로 남긴다. */
            String rNo = str(u.get("repair_no"));
            String memo = "수리 회수 · " + ((rNo != null && !rNo.isBlank()) ? rNo : ("유닛#" + unitId))
                    + (base != null && !base.isBlank() ? " · 원 부품로트 " + base : "");
            AjaxResult in = this.productionCreateService.receiveLot(
                    asInt(m.get("mat_id")), recLot, null, (float) toD(m.get("qty")),
                    STORE_PROD, "mcell_repair_mat", rmatId, memo, user, spjangcd);
            if (!in.success) throw new IllegalStateException(in.message);
            this.sqlRunner.execute("""
                    UPDATE mcell_repair_mat SET "MatLot_id"=:mlId, "State"='applied',
                           "_modified"=now(), "_modifier_id"=:userId WHERE id=:id
                    """, new MapSqlParameterSource()
                    .addValue("mlId", asInt(((Map<?, ?>) in.data).get("mat_lot_id")))
                    .addValue("id", rmatId).addValue("userId", user.getId()));
        }
        this.sqlRunner.execute("""
                UPDATE mcell_repair_mat SET "State"='applied'
                 WHERE "McellUnit_id"=:uid AND "Dir"='+' AND COALESCE("_status",'a')='a'
                """, new MapSqlParameterSource().addValue("uid", unitId));

        // 5) 유닛 → 검사대기 (여기서부터 검사 화면이 잡는다)
        MapSqlParameterSource up = new MapSqlParameterSource()
                .addValue("id", unitId).addValue("mpId", mpId).addValue("lot", resultLot)
                .addValue("et", (endTime == null || endTime.isBlank()) ? null : endTime)
                .addValue("userId", user.getId());
        this.sqlRunner.execute("""
                UPDATE mcell_unit
                   SET "State"='inspect_wait', "MatProduce_id"=:mpId, "LotNumber"=:lot,
                       "EndTime"=COALESCE(CAST(:et AS timestamp), LOCALTIMESTAMP),
                       "RejectReason"=NULL, "RejectInspNo"=NULL, "RejectAt"=NULL,
                       "_modified"=now(), "_modifier_id"=:userId
                 WHERE id=:id
                """, up);
        touchRepairState(asInt(u.get("repair_id")), user);

        r.data = Map.of("mat_produce_id", mpId, "lot_number", resultLot,
                "plus_cnt", plus.size(), "minus_cnt", minus.size());
        return r;
    }

    /**
     * 재작업 마감 — 기존 차수에 추가 자재만 얹고 다시 검사로.
     *
     * 결과 로트도 mat_produce 도 그대로 둔다. 실물이 하나이기 때문이다.
     * 소비 이력은 1차분에 더해 쌓이므로 "A 를 2개 썼다" 가 정확히 남는다.
     * (제품에 든 건 1개, 버린 건 1개 — 폐기분은 «불량 등록» 에서 기록한다)
     */
    private AjaxResult finishRework(Integer unitId, Map<String, Object> u,
                                    String endTime, String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        Integer mpId = asInt(u.get("mat_produce_id"));
        String resultLot = str(u.get("result_lot"));

        // 아직 반영되지 않은(plan) 가감만 처리
        List<Map<String, Object>> pend = this.sqlRunner.getRows("""
                SELECT rm.id AS rmat_id, rm."Material_id" AS mat_id, rm."Dir" AS dir,
                       rm."Qty" AS qty, sl."LotNumber" AS src_lot_number
                  FROM mcell_repair_mat rm
                  LEFT JOIN mat_lot sl ON sl.id = rm."SrcMatLot_id"
                 WHERE rm."McellUnit_id" = :uid
                   AND COALESCE(rm."State",'plan') = 'plan'
                   AND COALESCE(rm."_status",'a') = 'a'
                """, new MapSqlParameterSource().addValue("uid", unitId));

        List<ProductionCreateService.BomInput> plus = new ArrayList<>();
        List<Map<String, Object>> minus = new ArrayList<>();
        for (Map<String, Object> m : pend) {
            if ("+".equals(str(m.get("dir")))) {
                plus.add(new ProductionCreateService.BomInput(asInt(m.get("mat_id")),
                        (float) toD(m.get("qty"))));
            } else {
                minus.add(m);
            }
        }
        // ★ 자재 없이 끝내는 재작업을 막지 않는다.
        //   검사 불합격이 늘 부품 교체로 이어지는 건 아니다 —
        //   조립 상태만 바로잡거나, 체결을 다시 하거나, 배선을 정리해서
        //   통과하는 경우가 실제로 많다. 그때는 재고 인·아웃이 아예 없다.
        //   여기서 막으면 작업자는 쓰지도 않은 자재를 담아 재고를 틀리게 만든다.
        //   (1차 실적·소비는 그대로 남아 있으므로 이력이 비는 것도 아니다)
        boolean noMat = plus.isEmpty() && minus.isEmpty();

        // 1) 추가 투입 → 기존 차수에 소비 더하기 (없으면 건너뛴다)
        if (!plus.isEmpty()) {
            AjaxResult add = this.productionCreateService.consumeAdditional(
                    mpId, plus, STORE_PROD, user, spjangcd);
            if (!add.success) throw new IllegalStateException(add.message);
        }

        // 2) 회수(재사용품) → 생산창고 재입고
        for (Map<String, Object> m : minus) {
            Integer rmatId = asInt(m.get("rmat_id"));
            String base = str(m.get("src_lot_number"));
            String recLot = (base != null && !base.isBlank())
                    ? nextSuffixLot(base, "-RC")
                    : resultLot + "-RC" + rmatId;
            AjaxResult in = this.productionCreateService.receiveLot(
                    asInt(m.get("mat_id")), recLot, null, (float) toD(m.get("qty")),
                    STORE_PROD, "mcell_repair_mat", rmatId,
                    "수리 회수(재작업) · " + resultLot
                            + (base != null && !base.isBlank() ? " · 원 부품로트 " + base : ""),
                    user, spjangcd);
            if (!in.success) throw new IllegalStateException(in.message);
            this.sqlRunner.execute("""
                    UPDATE mcell_repair_mat SET "MatLot_id"=:mlId, "State"='applied',
                           "_modified"=now(), "_modifier_id"=:userId WHERE id=:id
                    """, new MapSqlParameterSource()
                    .addValue("mlId", asInt(((Map<?, ?>) in.data).get("mat_lot_id")))
                    .addValue("id", rmatId).addValue("userId", user.getId()));
        }
        this.sqlRunner.execute("""
                UPDATE mcell_repair_mat SET "State"='applied',
                       "_modified"=now(), "_modifier_id"=:userId
                 WHERE "McellUnit_id"=:uid AND "Dir"='+'
                   AND COALESCE("State",'plan')='plan' AND COALESCE("_status",'a')='a'
                """, new MapSqlParameterSource().addValue("uid", unitId).addValue("userId", user.getId()));

        // 3) 다시 검사대기로. 결과 로트·차수는 그대로.
        MapSqlParameterSource up = new MapSqlParameterSource()
                .addValue("id", unitId).addValue("userId", user.getId())
                .addValue("et", (endTime == null || endTime.isBlank()) ? null : endTime);
        this.sqlRunner.execute("""
                UPDATE mcell_unit
                   SET "State"='inspect_wait',
                       "EndTime"=COALESCE(CAST(:et AS timestamp), LOCALTIMESTAMP),
                       "RejectReason"=NULL, "RejectInspNo"=NULL, "RejectAt"=NULL,
                       "_modified"=now(), "_modifier_id"=:userId
                 WHERE id=:id
                """, up);
        this.sqlRunner.execute("""
                UPDATE mat_produce SET "EndTime"=COALESCE(CAST(:et AS timestamp), LOCALTIMESTAMP)
                 WHERE id=:mpId
                """, new MapSqlParameterSource().addValue("mpId", mpId)
                .addValue("et", (endTime == null || endTime.isBlank()) ? null : endTime));
        touchRepairState(asInt(u.get("repair_id")), user);

        r.message = noMat
                ? ("재작업 완료 · 검사로 다시 전달했습니다. 자재 변동 없음 · 로트 " + resultLot + " 유지")
                : ("재작업 반영 · 검사로 다시 전달했습니다. 로트 " + resultLot + " 유지");
        r.data = Map.of("mat_produce_id", mpId, "lot_number", resultLot,
                "plus_cnt", plus.size(), "minus_cnt", minus.size());
        return r;
    }

    /**
     * 검사취소 · 수리 복귀.
     * 검사가 이미 시작(insp_result 존재)됐으면 막는다 —
     * 검사 화면에서 회차를 먼저 지워야 이력이 어긋나지 않는다.
     */
    @Transactional
    public AjaxResult unitReopen(Integer unitId, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        Map<String, Object> u = getUnitRow(unitId);
        if (u == null) { r.success = false; r.message = "유닛을 찾을 수 없습니다."; return r; }
        String st = str(u.get("state"));
        if (!"inspect_wait".equals(st) && !"reject".equals(st)) {
            r.success = false; r.message = "검사 대기/재작업 상태에서만 되돌릴 수 있습니다."; return r;
        }

        // 검사가 시작된 뒤의 '검사취소'는 이력이 어긋나므로 막는다.
        //   단 reject(재작업)는 예외 — 불합격 회차가 있는 게 정상이고,
        //   그 이력은 남긴 채 실적만 되돌린다. 「수리 시작」이 그 경로를 탄다.
        if (!"reject".equals(st)) {
            Map<String, Object> insp = this.sqlRunner.getRow(
                    "SELECT COUNT(*) AS c FROM insp_result WHERE \"McellUnit_id\"=:id",
                    new MapSqlParameterSource().addValue("id", unitId));
            if (insp != null && asInt(insp.get("c")) > 0) {
                r.success = false;
                r.message = "검사 회차가 있어 되돌릴 수 없습니다. 검사 화면에서 회차를 먼저 삭제하세요.";
                return r;
            }
        }

        AjaxResult rb = rollbackRepairWork(unitId, u, user);
        if (!rb.success) return rb;

        this.sqlRunner.execute("""
                UPDATE mcell_unit
                   SET "State"='repairing', "MatProduce_id"=NULL, "LotNumber"=NULL, "EndTime"=NULL,
                       "RejectReason"=NULL, "RejectInspNo"=NULL, "RejectAt"=NULL,
                       "_modified"=now(), "_modifier_id"=:userId
                 WHERE id=:id
                """, new MapSqlParameterSource().addValue("id", unitId).addValue("userId", user.getId()));
        touchRepairState(asInt(u.get("repair_id")), user);
        return r;
    }

    /**
     * 수리 실적 되돌리기 (검사 회차는 건드리지 않는다).
     *   회수 부품 로트 제거 → 산출 실적 롤백 → 자재 가감을 'plan' 으로
     * 회수 로트가 이미 다른 곳에 소비됐으면 실패로 되돌린다.
     */
    private AjaxResult rollbackRepairWork(Integer unitId, Map<String, Object> u, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        // 회수 부품 로트 먼저 정리 (산출 로트보다 먼저 — 이미 소비됐으면 여기서 걸린다)
        List<Map<String, Object>> recs = this.sqlRunner.getRows("""
                SELECT rm.id, rm."MatLot_id" AS ml_id,
                       COALESCE(ml."CurrentStock",0) AS cs, COALESCE(ml."InputQty",0) AS iq
                  FROM mcell_repair_mat rm
                  LEFT JOIN mat_lot ml ON ml.id = rm."MatLot_id"
                 WHERE rm."McellUnit_id"=:uid AND rm."Dir"='-' AND rm."MatLot_id" IS NOT NULL
                """, new MapSqlParameterSource().addValue("uid", unitId));
        for (Map<String, Object> rec : recs) {
            if (toD(rec.get("cs")) < toD(rec.get("iq"))) {
                r.success = false;
                r.message = "회수한 부품이 이미 다른 작업에 쓰여서 되돌릴 수 없습니다.";
                return r;
            }
        }
        for (Map<String, Object> rec : recs) {
            MapSqlParameterSource dp = new MapSqlParameterSource().addValue("id", asInt(rec.get("ml_id")));
            this.sqlRunner.execute("DELETE FROM mat_inout WHERE \"SourceTableName\"='mat_lot' AND \"SourceDataPk\"=:id", dp);
            this.sqlRunner.execute("DELETE FROM mat_lot WHERE id=:id", dp);
        }

        AjaxResult rb = rollbackProduce(asInt(u.get("mat_produce_id")), str(u.get("result_lot")), user);
        if (!rb.success) return rb;

        this.sqlRunner.execute("""
                UPDATE mcell_repair_mat SET "State"='plan', "MatLot_id"=NULL
                 WHERE "McellUnit_id"=:id AND COALESCE("_status",'a')='a'
                """, new MapSqlParameterSource().addValue("id", unitId));
        return r;
    }

    /**
     * 산출 실적 되돌리기.
     * McellAssemblyService.rollbackProduce 와 같은 절차 —
     * 그쪽이 private 이라 복제했다. 한쪽을 고치면 다른 쪽도 같이 봐야 한다.
     */
    private AjaxResult rollbackProduce(Integer mpId, String lotNumber, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        if (mpId == null) return r;

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("mpId", mpId).addValue("userId", user.getId());

        Map<String, Object> used = this.sqlRunner.getRow("""
                SELECT COUNT(*) AS c FROM mat_lot ml
                 WHERE ml."SourceTableName"='mat_produce' AND ml."SourceDataPk"=:mpId
                   AND COALESCE(ml."CurrentStock",0) < COALESCE(ml."InputQty",0)
                """, p);
        if (used != null && asInt(used.get("c")) != null && asInt(used.get("c")) > 0) {
            r.success = false;
            r.message = "이 수리 로트가 다른 곳에서 사용되어 취소할 수 없습니다.";
            return r;
        }

        this.sqlRunner.execute("DELETE FROM mat_lot   WHERE \"SourceTableName\"='mat_produce' AND \"SourceDataPk\"=:mpId", p);
        this.sqlRunner.execute("DELETE FROM mat_inout WHERE \"SourceTableName\"='mat_produce' AND \"SourceDataPk\"=:mpId", p);
        this.sqlRunner.execute("""
                DELETE FROM mat_inout
                 WHERE "SourceTableName"='mat_consu'
                   AND "SourceDataPk" IN (SELECT id FROM mat_consu
                        WHERE "JobResponse_id"=(SELECT "JobResponse_id" FROM mat_produce WHERE id=:mpId)
                          AND "LotIndex"=(SELECT "LotIndex" FROM mat_produce WHERE id=:mpId))
                """, p);
        // mat_lot_cons 삭제 → 트리거가 원 로트 CurrentStock 을 복원한다
        this.sqlRunner.execute("DELETE FROM mat_lot_cons WHERE \"SourceTableName\"='mat_produce' AND \"SourceDataPk\"=:mpId", p);
        this.sqlRunner.execute("""
                DELETE FROM mat_consu
                 WHERE "JobResponse_id"=(SELECT "JobResponse_id" FROM mat_produce WHERE id=:mpId)
                   AND "LotIndex"=(SELECT "LotIndex" FROM mat_produce WHERE id=:mpId)
                """, p);
        this.sqlRunner.execute("""
                UPDATE mat_produce SET "_status"='d', "State"='wait',
                       "_modified"=now(), "_modifier_id"=:userId WHERE id=:mpId
                """, p);

        // 검사가 남긴 창고이동 이력도 정리 (안 하면 유령 재고)
        if (lotNumber != null && !lotNumber.isBlank()) {
            this.sqlRunner.execute("""
                    DELETE FROM mat_inout WHERE "SourceTableName"='mcell_unit' AND "LotNumber"=:lot
                    """, new MapSqlParameterSource().addValue("lot", lotNumber));
        }
        // ★ 롤백 후 작지 롤업 재실행 (수정 3 과 같은 이유)
        Map<String, Object> jrRow = this.sqlRunner.getRow(
                "SELECT \"JobResponse_id\" AS jr_id FROM mat_produce WHERE id = :mpId", p);
        if (jrRow != null && jrRow.get("jr_id") != null) {
            this.productionCreateService.recalcJobRes(
                    ((Number) jrRow.get("jr_id")).intValue(), user);
        }
        return r;
    }

    /**
     * 로트 창고 이동. McellInspectService.moveLot 과 같은 절차(로트 id 로 지정하는 점만 다름).
     * ★ out 은 OutputQty, in 은 InputQty (§5).
     */
    private void moveLot(Integer matLotId, int from, int to, Integer repairId,
                         String memo, String spjangcd, User user) {
        Map<String, Object> ml = this.sqlRunner.getRow("""
                SELECT ml.id, ml."Material_id" AS mat_id, ml."LotNumber" AS lot_no,
                       COALESCE(ml."CurrentStock",0) AS qty
                  FROM mat_lot ml WHERE ml.id=:id
                """, new MapSqlParameterSource().addValue("id", matLotId));
        if (ml == null) return;

        this.sqlRunner.execute("UPDATE mat_lot SET \"StoreHouse_id\"=:dst WHERE id=:id",
                new MapSqlParameterSource().addValue("id", matLotId).addValue("dst", to));

        MapSqlParameterSource io = new MapSqlParameterSource()
                .addValue("matId", asInt(ml.get("mat_id")))
                .addValue("lot", str(ml.get("lot_no")))
                .addValue("qty", toD(ml.get("qty")))
                .addValue("from", from).addValue("to", to).addValue("memo", memo)
                .addValue("rid", repairId).addValue("userId", user.getId())
                .addValue("spjangcd", spjangcd);
        this.sqlRunner.execute("""
                INSERT INTO mat_inout ("Material_id","StoreHouse_id","LotNumber","InoutDate","InoutTime",
                                       "InOut","InputQty","OutputQty","InputType","SourceTableName","SourceDataPk",
                                       "State","Description","_status","_created","_creater_id",spjangcd)
                VALUES (:matId,:from,:lot,CURRENT_DATE,LOCALTIME,'out',NULL,:qty,'move',
                        'mcell_repair',:rid,'confirmed',:memo,'a',now(),:userId,:spjangcd),
                       (:matId,:to,:lot,CURRENT_DATE,LOCALTIME,'in',:qty,NULL,'move',
                        'mcell_repair',:rid,'confirmed',:memo,'a',now(),:userId,:spjangcd)
                """, io);
    }

    /** {base}{suffix}{n} — 같은 접두가 이미 있으면 다음 번호로 */
    private String nextSuffixLot(String base, String suffix) {
        Map<String, Object> row = this.sqlRunner.getRow(
                "SELECT COUNT(*) AS c FROM mat_lot WHERE \"LotNumber\" LIKE :pre",
                new MapSqlParameterSource().addValue("pre", base + suffix + "%"));
        int n = (row == null ? 0 : asInt(row.get("c"))) + 1;
        return base + suffix + n;
    }

    /** 결과 로트 미리보기 — keep 이면 원 로트, new 면 원로트+'-R{n}' */
    private String previewResultLot(Map<String, Object> unit) {
        if (unit == null) return null;
        String src = str(unit.get("src_lot"));
        if (src == null || src.isBlank()) return null;
        if (!"new".equals(str(unit.get("lot_mode")))) return src;

        String done = str(unit.get("result_lot"));
        if (done != null && !done.isBlank()) return done;   // 이미 확정된 건 그대로

        Map<String, Object> row = this.sqlRunner.getRow("""
                SELECT COUNT(*) AS c FROM mat_lot WHERE "LotNumber" LIKE :pre
                """, new MapSqlParameterSource().addValue("pre", src + "-R%"));
        int n = (row == null ? 0 : asInt(row.get("c"))) + 1;
        return src + "-R" + n;
    }

    /** 접수 헤더 상태를 유닛 상태에서 파생 */
    private void touchRepairState(Integer repairId, User user) {
        if (repairId == null) return;
        this.sqlRunner.execute("""
                UPDATE mcell_repair r
                   SET "State" = s.st, "_modified"=now(), "_modifier_id"=:userId
                  FROM (SELECT CASE
                                 WHEN COUNT(*) FILTER (WHERE mu."State" IN ('inspect_wait','pass','packed')) = COUNT(*)
                                      AND COUNT(*) > 0 THEN 'done'
                                 WHEN COUNT(*) FILTER (WHERE mu."State" <> 'wait') > 0 THEN 'working'
                                 ELSE 'wait' END AS st
                          FROM mcell_unit mu
                         WHERE mu."McellRepair_id" = :rid AND COALESCE(mu."_status",'a')='a') s
                 WHERE r.id = :rid
                """, new MapSqlParameterSource().addValue("rid", repairId).addValue("userId", user.getId()));
    }

    /**
     * 시작 / 완료 시각 수정.
     * 조립(setStepTime)과 같은 절차 — 유닛의 시각을 고치고,
     * 이미 실적이 만들어졌으면 mat_produce 시각도 함께 맞춘다.
     * 여기를 안 맞추면 작업일보·생산실적의 시각이 화면과 어긋난다.
     *
     * @param which start | end
     * @param value 'yyyy-MM-dd HH:mm'
     */
    @Transactional
    public AjaxResult setUnitTime(Integer unitId, String which, String value, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        if (value == null || value.isBlank()) { r.success = false; r.message = "시각이 비었습니다."; return r; }
        boolean isEnd = "end".equals(which);
        String col = isEnd ? "EndTime" : "StartTime";

        Map<String, Object> u = getUnitRow(unitId);
        if (u == null) { r.success = false; r.message = "유닛을 찾을 수 없습니다."; return r; }
        if ("pass".equals(str(u.get("state"))) || "packed".equals(str(u.get("state")))) {
            r.success = false; r.message = "검사 합격 이후에는 시각을 수정할 수 없습니다."; return r;
        }

        // 앞뒤 관계 검증 — 화면에서도 막지만 서버가 최종 판단한다
        String other = isEnd ? str(u.get("start_time")) : str(u.get("end_time"));
        if (other != null && !other.isBlank()) {
            boolean bad = isEnd ? (value.compareTo(other) < 0) : (value.compareTo(other) > 0);
            if (bad) {
                r.success = false;
                r.message = isEnd ? "완료가 시작보다 빠를 수 없습니다." : "시작이 완료보다 늦을 수 없습니다.";
                return r;
            }
        }

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", unitId).addValue("val", value).addValue("userId", user.getId());
        this.sqlRunner.execute("UPDATE mcell_unit SET \"" + col + "\"=CAST(:val AS timestamp), "
                + "\"_modified\"=now(), \"_modifier_id\"=:userId WHERE id=:id", p);

        Integer mpId = asInt(u.get("mat_produce_id"));
        if (mpId != null) {
            MapSqlParameterSource mp = new MapSqlParameterSource()
                    .addValue("mpId", mpId).addValue("val", value);
            this.sqlRunner.execute("UPDATE mat_produce SET \"" + col + "\"=CAST(:val AS timestamp) WHERE id=:mpId", mp);
        }
        // equ_run(설비 가동 구간)은 건드리지 않는다. 조립도 같다.
        //   가동 이력은 '실제로 돌아간 구간'이라 사후 보정 대상이 아니라고 본 것.
        //   나중에 가동률을 시각 수정에 맞춰야 하면 여기서 함께 갱신하면 된다.

        r.data = Map.of("which", isEnd ? "end" : "start", "value", value);
        return r;
    }

    /** 자재 편집 가능 여부 — 완료된 유닛은 못 건드린다 */
    private AjaxResult guardEditable(Integer unitId) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        Map<String, Object> u = getUnitRow(unitId);
        if (u == null) { r.success = false; r.message = "유닛을 찾을 수 없습니다."; return r; }
        // ★ 실적 유무가 아니라 '검사로 넘어갔는지' 로 판단한다.
        //   재작업(reject → repairing)은 실적이 이미 있는 상태에서 자재를 더 담아야 한다.
        String st = str(u.get("state"));
        if (!"wait".equals(st) && !"repairing".equals(st) && !"reject".equals(st)) {
            r.success = false;
            r.message = "검사로 넘어간 유닛의 자재는 수정할 수 없습니다.";
            return r;
        }
        return r;
    }

    /** 이미 소비된(applied) 자재 행은 이력이라 손대지 못한다 */
    private AjaxResult guardRmatEditable(Map<String, Object> rm) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        if ("applied".equals(str(rm.get("state")))) {
            r.success = false;
            r.message = "이미 반영된 자재는 수정·삭제할 수 없습니다. 추가로 쓴 만큼 새로 담아 주세요.";
        }
        return r;
    }

    private Map<String, Object> getUnitRow(Integer unitId) {
        return this.sqlRunner.getRow("""
                SELECT mu.id, mu."JobResponse_id" AS job_res_id, mu."Material_id" AS mat_id,
                       mu."UnitNo" AS unit_no, mu."State" AS state,
                       mu."Actor_id" AS actor_id, mu."Equipment_id" AS equipment_id,
                       mu."McellRepair_id" AS repair_id, mu."LotMode" AS lot_mode,
                       mu."SrcLotNumber" AS src_lot, mu."SrcMatLot_id" AS src_mat_lot_id,
                       mu."LotNumber" AS result_lot, mu."MatProduce_id" AS mat_produce_id,
                       mu."RejectReason" AS reject_reason,
                       to_char(mu."StartTime",'yyyy-mm-dd hh24:mi') AS start_time,
                       to_char(mu."EndTime",'yyyy-mm-dd hh24:mi')   AS end_time,
                       m."Code" AS mat_code, m."Name" AS mat_name,
                       pe."Name" AS actor_name, eq."Name" AS equipment_name,
                       r."Cat" AS cat, r."RepairNo" AS repair_no, r."Reason" AS reason,
                       r."TargetMaterial_id" AS target_mat_id, r."IntakeType" AS intake_type,
                       COALESCE(sl."CurrentStock",0) AS src_stock
                  FROM mcell_unit mu
                  LEFT JOIN material m  ON m.id  = mu."Material_id"
                  LEFT JOIN person   pe ON pe.id = mu."Actor_id"
                  LEFT JOIN equ      eq ON eq.id = mu."Equipment_id"
                  LEFT JOIN mcell_repair r ON r.id = mu."McellRepair_id"
                  LEFT JOIN mat_lot sl ON sl.id = mu."SrcMatLot_id"
                 WHERE mu.id = :id
                """, new MapSqlParameterSource().addValue("id", unitId));
    }

    private Map<String, Object> getRepairRow(Integer repairId) {
        return this.sqlRunner.getRow("""
                SELECT r.id, r."RepairNo" AS repair_no, r."Cat" AS cat, r."State" AS state,
                       r."SrcLotNumber" AS src_lot, r."SrcMatLot_id" AS src_mat_lot_id,
                       r."SrcMaterial_id" AS src_material_id, r."TargetMaterial_id" AS target_material_id,
                       r."IntakeType" AS intake_type, r."JobResponse_id" AS job_res_id
                  FROM mcell_repair r WHERE r.id = :id
                """, new MapSqlParameterSource().addValue("id", repairId));
    }

    /**
     * 접수 때 재고를 가져온 원 창고.
     * moveLot 이 남긴 mat_inout out 행에서 찾는다 — 컬럼을 따로 두지 않는 이유는
     * 이 이력이 어디서도 삭제되지 않기 때문(rollbackProduce 는 mat_produce/mat_consu/
     * mcell_unit 출처만, registCancel 은 mat_lot 출처만 지운다).
     * 'new'(반품 신규입고) 접수는 이 행이 없으므로 null 이 정상이다.
     */
    private Integer intakeFromStore(Integer repairId) {
        Map<String, Object> row = this.sqlRunner.getRow("""
                SELECT mi."StoreHouse_id" AS store
                  FROM mat_inout mi
                 WHERE mi."SourceTableName" = 'mcell_repair'
                   AND mi."SourceDataPk"    = :rid
                   AND mi."InOut" = 'out'
                 ORDER BY mi.id LIMIT 1
                """, new MapSqlParameterSource().addValue("rid", repairId));
        return (row == null) ? null : asInt(row.get("store"));
    }

    private Map<String, Object> getRmatRow(Integer rmatId) {
        Map<String, Object> row = this.sqlRunner.getRow("""
                SELECT id, "McellUnit_id" AS unit_id, "Material_id" AS mat_id,
                       "Dir" AS dir, "Qty" AS qty, "State" AS state,
                       "SrcMatLot_id" AS src_mat_lot_id
                  FROM mcell_repair_mat WHERE id=:id
                """, new MapSqlParameterSource().addValue("id", rmatId));
        if (row == null) throw new IllegalArgumentException("자재 행을 찾을 수 없습니다.");
        return row;
    }

    /** RP-yyMM-nnn */
    private String nextRepairNo(LocalDate d, String spjangcd) {
        String prefix = "RP-" + d.format(YYMM) + "-";
        Map<String, Object> row = this.sqlRunner.getRow("""
                SELECT COALESCE(MAX(CAST(NULLIF(regexp_replace("RepairNo", '^.*-', ''), '') AS integer)),0) AS mx
                  FROM mcell_repair
                 WHERE "RepairNo" LIKE :pre AND spjangcd = :spjangcd
                """, new MapSqlParameterSource().addValue("pre", prefix + "%").addValue("spjangcd", spjangcd));
        int next = (row == null ? 0 : asInt(row.get("mx"))) + 1;
        return prefix + String.format("%03d", next);
    }

    private static Integer asInt(Object o) { return o == null ? null : ((Number) o).intValue(); }
    private static double  toD(Object o)   { return o == null ? 0d : ((Number) o).doubleValue(); }
    private static String  str(Object o)   { return o == null ? null : String.valueOf(o); }
}