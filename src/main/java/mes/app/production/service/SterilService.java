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
import java.time.LocalTime;
import java.util.*;

/**
 * 멸균(EO, bsc04) 서비스 — 세척과 같은 "창고 이동" 개념 + 배치/판정/폐기.
 *
 * 동작 요약:
 *   - 대상 = 클린룸(5)에 있는 SterilizationYN='Y' 로트 (PK = 블리스터 산출 / 필터백 = 융착 산출)
 *   - 배치(steril_batch) = EO 1회 런. 배치에 클린룸 로트를 담고(steril_batch_item, 로트별 수량),
 *     멸균일지(steril_batch_log 10단계) 작성 후 BI 판정.
 *   - PASS → 담은 로트 클린룸(5) → 멸균창고(18) 이동(로트번호 유지) = '멸균필'
 *   - FAIL → 배치 전량 폐기(클린룸 재고 차감, 이동 없음)
 *   - 종류 고정(2번안): 첫 로트 담기면 그 종류(pk|bag)로 고정, 전부 빼면 해제. 섞기 금지.
 *
 * 재고 트리거(세척과 동일): mat_lot_cons / mat_inout INSERT·DELETE 로 mat_lot.CurrentStock 자동 갱신.
 *   → CurrentStock 직접 UPDATE 금지. 롤백은 해당 행 DELETE.
 */
@Service
public class SterilService {

    /** 클린룸 S-3 (투입) */
    public static final int STORE_CLEAN  = 5;
    /** 멸균창고 S-? (산출) */
    public static final int STORE_STERIL = 18;
    /** 부적합품창고 (현재 미사용 — FAIL 은 창고 이동 없이 폐기 출고로 소멸) */
    public static final int STORE_DEFECT = 2;

    private static final String SRC_TABLE = "steril_batch_item";

    @Autowired SqlRunner sqlRunner;
    @Autowired MaterialRepository materialRepository;
    @Autowired MatLotRepository matLotRepository;
    @Autowired MatLotConsRepository matLotConsRepository;
    @Autowired MatInoutRepository matInoutRepository;
    @Autowired EquRunRepository equRunRepository;

    // =================================================================
    // 조회
    // =================================================================

    /** 멸균 공정(bsc04) 컨텍스트 — 워크센터/설비 콤보용 + 투입·산출 창고 */
    public Map<String, Object> getContext(Integer factoryId) {
        Map<String, Object> data = new HashMap<>();
        Map<String, Object> wc = this.sqlRunner.getRow("""
            SELECT wc.id AS wc_id, wc."Process_id" AS process_id
              FROM work_center wc
              JOIN process p ON p.id = wc."Process_id"
             WHERE p."Code" = 'bsc04' AND COALESCE(wc."_status",'a')='a'
             ORDER BY wc.id LIMIT 1
            """, new MapSqlParameterSource());
        if (wc != null) {
            data.put("work_center_id", wc.get("wc_id"));
            data.put("process_id", wc.get("process_id"));
        }
        data.put("in_store_id", STORE_CLEAN);
        data.put("out_store_id", STORE_STERIL);
        return data;
    }

    /** 배치 큐 — 날짜범위 + 상태 필터 */
    public List<Map<String, Object>> getBatchList(String dateFrom, String dateTo,
                                                  String state, String spjangcd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("dateFrom", LocalDate.parse(dateFrom));
        p.addValue("dateTo", LocalDate.parse(dateTo));
        p.addValue("state", (state == null || state.isBlank()) ? null : state);
        p.addValue("spjangcd", spjangcd);
        String sql = """
            SELECT sb.id                                   AS batch_id
                 , to_char(sb."SterilDate",'yyyy-mm-dd')   AS steril_date
                 , sb."BatchNo"                            AS batch_no
                 , sb."ItemType"                           AS item_type
                 , sb."BiResult"                           AS bi_result
                 , sb."State"                              AS state
                 , sb."MakerLotNo"                         AS maker_lot_no
                 , sb."Actor_id"                           AS actor_id
                 , e."Name"                                AS equip_name
                 , to_char(sb."StartTime",'yyyy-mm-dd hh24:mi') AS start_time
                 , to_char(sb."EndTime",'yyyy-mm-dd hh24:mi')   AS end_time
                 , COALESCE(agg.item_cnt,0)                AS item_cnt
                 , COALESCE(agg.qty,0)                     AS total_qty
                 , COALESCE(logc.log_cnt,0)                AS log_cnt
              FROM steril_batch sb
              LEFT JOIN equ e     ON e.id  = sb."Equipment_id"
              LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS item_cnt, SUM("Qty") AS qty
                      FROM steril_batch_item WHERE "SterilBatch_id"=sb.id AND "_status"='a'
              ) agg ON true
              LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS log_cnt
                      FROM steril_batch_log WHERE "SterilBatch_id"=sb.id AND "_status"='a'
              ) logc ON true
             WHERE COALESCE(sb."_status",'a')='a'
               AND sb.spjangcd = :spjangcd
               AND sb."SterilDate" BETWEEN :dateFrom AND :dateTo
               AND (CAST(:state AS varchar) IS NULL OR sb."State" = CAST(:state AS varchar))
             ORDER BY sb."SterilDate" DESC, sb.id DESC
            """;
        return this.sqlRunner.getRows(sql, p);
    }

    /**
     * 멸균 대기 목록 = 클린룸(5)에 있는 SterilizationYN='Y' 로트 (가용 재고>0).
     *   종류(PK/필터백) 구분 없이 느슨하게 — 멸균 대상이면 모두 노출.
     */
    public List<Map<String, Object>> getTargetLots(String itemType, String keyword, String spjangcd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("cleanStore", STORE_CLEAN);
        p.addValue("itemType", (itemType == null || itemType.isBlank()) ? null : itemType);
        p.addValue("keyword", (keyword == null || keyword.isBlank()) ? null : "%" + keyword + "%");
        String sql = """
            SELECT ml.id                                   AS mat_lot_id
                 , ml."LotNumber"                          AS lot_no
                 , ml."Material_id"                        AS mat_id
                 , m."Code"                                AS mat_code
                 , m."Name"                                AS mat_name
                 , u."Name"                                AS unit
                 , GREATEST(0, COALESCE(ml."CurrentStock",0) - res.rq) AS avail
                 , to_char(ml."InputDateTime",'yyyy-mm-dd') AS in_date
              FROM mat_lot ml
              JOIN material m ON m.id = ml."Material_id"
              LEFT JOIN unit u ON u.id = m."Unit_id"
              LEFT JOIN LATERAL (
                    SELECT COALESCE(SUM(x."Qty"),0) AS rq
                      FROM steril_batch_item x
                      JOIN steril_batch b ON b.id = x."SterilBatch_id"
                     WHERE x."MatLot_id" = ml.id AND x."_status"='a'
                       AND b."State" IN ('wait','working') AND COALESCE(b."_status",'a')='a'
              ) res ON true
             WHERE ml."StoreHouse_id" = :cleanStore
               AND COALESCE(m."SterilizationYN",'N') = 'Y'
               AND (COALESCE(ml."CurrentStock",0) - res.rq) > 0
               AND (CAST(:keyword AS varchar) IS NULL
                    OR m."Name" LIKE CAST(:keyword AS varchar)
                    OR m."Code" LIKE CAST(:keyword AS varchar)
                    OR ml."LotNumber" LIKE CAST(:keyword AS varchar))
             ORDER BY ml."InputDateTime" ASC, ml.id ASC
            """;
        return this.sqlRunner.getRows(sql, p);
    }

    /** 배치 상세 = 배치 + 담긴 로트 + 멸균일지 */
    public Map<String, Object> getBatchDetail(Integer batchId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("batchId", batchId);
        Map<String, Object> batch = this.sqlRunner.getRow("""
            SELECT sb.id AS batch_id, to_char(sb."SterilDate",'yyyy-mm-dd') AS steril_date,
                   sb."BatchNo" AS batch_no, sb."ItemType" AS item_type,
                   sb."BiResult" AS bi_result, sb."State" AS state, sb."MakerLotNo" AS maker_lot_no,
                   sb."Actor_id" AS actor_id,
                   sb."Equipment_id" AS equipment_id, e."Name" AS equip_name,
                   to_char(sb."StartTime",'yyyy-mm-dd hh24:mi') AS start_time,
                   to_char(sb."EndTime",'yyyy-mm-dd hh24:mi')   AS end_time
              FROM steril_batch sb
              LEFT JOIN equ e     ON e.id  = sb."Equipment_id"
             WHERE sb.id = :batchId AND COALESCE(sb."_status",'a')='a'
            """, p);
        if (batch == null) return null;

        List<Map<String, Object>> items = this.sqlRunner.getRows("""
            SELECT sbi.id AS item_id, sbi."MatLot_id" AS mat_lot_id, sbi."Material_id" AS mat_id,
                   sbi."LotNumber" AS lot_no, sbi."Qty" AS qty, sbi."State" AS state, sbi."MakerLotNo" AS maker_lot_no,
                   m."Code" AS mat_code, m."Name" AS mat_name, u."Name" AS unit,
                   GREATEST(0, COALESCE(ml."CurrentStock",0) - res.rq) AS lot_avail
              FROM steril_batch_item sbi
              JOIN material m ON m.id = sbi."Material_id"
              LEFT JOIN unit u  ON u.id = m."Unit_id"
              LEFT JOIN mat_lot ml ON ml.id = sbi."MatLot_id"
              LEFT JOIN LATERAL (
                    SELECT COALESCE(SUM(x."Qty"),0) AS rq
                      FROM steril_batch_item x
                      JOIN steril_batch b ON b.id = x."SterilBatch_id"
                     WHERE x."MatLot_id" = sbi."MatLot_id" AND x."_status"='a'
                       AND b."State" IN ('wait','working') AND COALESCE(b."_status",'a')='a'
                       AND b.id <> :batchId
              ) res ON true
             WHERE sbi."SterilBatch_id" = :batchId AND sbi."_status"='a'
             ORDER BY sbi.id
            """, p);

        List<Map<String, Object>> log = this.sqlRunner.getRows("""
            SELECT "StepNo" AS step_no, "StepName" AS step_name, "Data" AS data
              FROM steril_batch_log
             WHERE "SterilBatch_id" = :batchId AND "_status"='a'
             ORDER BY "StepNo"
            """, p);

        batch.put("items", items);
        batch.put("log", log);
        // ★ 멸균필 로트가 다음 공정(포장 등)에서 이미 쓰였나 — 쓰였으면 판정취소 불가(화면이 버튼을 막는다)
        Map<String, Object> used = this.sqlRunner.getRow("""
            SELECT COUNT(DISTINCT l.id) AS n
              FROM steril_batch_item i
              JOIN mat_lot l ON l."SourceTableName" = 'steril_batch_item' AND l."SourceDataPk" = i.id
              JOIN mat_lot_cons c ON c."MaterialLot_id" = l.id AND COALESCE(c."OutputQty",0) > 0
             WHERE i."SterilBatch_id" = :batchId AND i."_status" = 'a'
            """, p);
        batch.put("used_cnt", used == null ? 0 : ((Number) used.get("n")).intValue());
        return batch;
    }

    // =================================================================
    // 배치 · 로트
    // =================================================================

    /** 새 배치 시작 — 날짜/멸균기/담당자. 배치번호 채번. */
    @Transactional
    public AjaxResult batchStart(String date, Integer actorId, Integer equipmentId,
                                 String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("date", LocalDate.parse(date));
        p.addValue("actorId", actorId);
        p.addValue("equipmentId", equipmentId);
        p.addValue("inStore", STORE_CLEAN);
        p.addValue("outStore", STORE_STERIL);
        p.addValue("batchNo", nextBatchNo(date, spjangcd));
        p.addValue("userId", user.getId());
        p.addValue("spjangcd", spjangcd);
        Map<String, Object> row = this.sqlRunner.getRow("""
            INSERT INTO steril_batch
                ("SterilDate","BatchNo","Actor_id","Equipment_id","InStoreHouse_id","OutStoreHouse_id",
                 "State","_status","_created","_creater_id",spjangcd)
            VALUES (:date,:batchNo,:actorId,:equipmentId,:inStore,:outStore,
                    'wait', 'a', now(), :userId, :spjangcd)
            RETURNING id
            """, p);
        r.data = Map.of("batch_id", ((Number) row.get("id")).intValue(),
                "batch_no", String.valueOf(p.getValue("batchNo")));
        return r;
    }

    /** 배치번호 EO-yyyymmdd-#### (당일 순번) */
    private String nextBatchNo(String date, String spjangcd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("date", LocalDate.parse(date));
        p.addValue("spjangcd", spjangcd);
        Map<String, Object> c = this.sqlRunner.getRow("""
            SELECT COUNT(*) AS c FROM steril_batch
             WHERE "SterilDate" = :date AND spjangcd = :spjangcd
            """, p);
        int seq = (c != null ? ((Number) c.get("c")).intValue() : 0) + 1;
        return "EO-" + date.replace("-", "") + "-" + String.format("%04d", seq);
    }

    /**
     * 배치에 클린룸 로트 담기.
     *   - 종류 고정(2번안): 배치에 로트가 없으면 이 로트의 type 으로 ItemType 설정.
     *                      있으면 같은 type 만 허용(다르면 거부).
     *   - 같은 로트 중복 담기 금지(이미 담겼으면 수량만 갱신).
     */
    @Transactional
    public AjaxResult lotAdd(Integer batchId, Integer matLotId, Float qty, String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        Map<String, Object> batch = getBatchRaw(batchId);
        if (batch == null) { r.success = false; r.message = "배치를 찾을 수 없습니다."; return r; }
        if (!isEditable(batch)) {
            r.success = false; r.message = "이미 판정/마감된 배치입니다."; return r;
        }

        Map<String, Object> lot = this.sqlRunner.getRow("""
            SELECT ml.id, ml."LotNumber" AS lot_no, ml."Material_id" AS mat_id,
                   COALESCE(ml."CurrentStock",0) AS cur, ml."StoreHouse_id" AS sh,
                   COALESCE(m."SterilizationYN",'N') AS steril_yn,
                   COALESCE((SELECT SUM(x."Qty") FROM steril_batch_item x
                              JOIN steril_batch b ON b.id = x."SterilBatch_id"
                             WHERE x."MatLot_id" = ml.id AND x."_status"='a'
                               AND b."State" IN ('wait','working') AND COALESCE(b."_status",'a')='a'
                               AND b.id <> :batchId), 0) AS reserved_other
              FROM mat_lot ml JOIN material m ON m.id = ml."Material_id"
             WHERE ml.id = :matLotId
            """, new MapSqlParameterSource().addValue("matLotId", matLotId).addValue("batchId", batchId));
        if (lot == null) { r.success = false; r.message = "로트를 찾을 수 없습니다."; return r; }
        if (!"Y".equals(lot.get("steril_yn"))) { r.success = false; r.message = "멸균 대상 품목이 아닙니다."; return r; }
        if (((Number) lot.get("sh")).intValue() != STORE_CLEAN) {
            r.success = false; r.message = "클린룸 재고 로트만 담을 수 있습니다."; return r;
        }

        // 종류 구분 없이 느슨하게 + 나눠 담기 허용.
        //   이 배치가 담을 수 있는 상한 = 현재고 − (다른 진행중 배치에 예약된 같은 로트 수량)
        float capacity = toF(lot.get("cur")) - toF(lot.get("reserved_other"));
        if (capacity < 0) capacity = 0;
        float q = (qty == null || qty <= 0) ? capacity : qty;
        if (q > capacity) { r.success = false; r.message = "담을 수 있는 수량(" + fmt(capacity) + ")을 초과했습니다. (다른 배치 예약분 제외)"; return r; }

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("batchId", batchId);
        p.addValue("matLotId", matLotId);
        // 이미 담긴 로트면 수량만 갱신
        Map<String, Object> dup = this.sqlRunner.getRow("""
            SELECT id FROM steril_batch_item
             WHERE "SterilBatch_id"=:batchId AND "MatLot_id"=:matLotId AND "_status"='a'
            """, p);
        if (dup != null && dup.get("id") != null) {
            MapSqlParameterSource up = new MapSqlParameterSource()
                    .addValue("id", ((Number) dup.get("id")).intValue()).addValue("qty", q);
            this.sqlRunner.execute("UPDATE steril_batch_item SET \"Qty\"=:qty WHERE id=:id", up);
        } else {
            MapSqlParameterSource ip = new MapSqlParameterSource();
            ip.addValue("batchId", batchId);
            ip.addValue("matLotId", matLotId);
            ip.addValue("matId", ((Number) lot.get("mat_id")).intValue());
            ip.addValue("lotNo", String.valueOf(lot.get("lot_no")));
            ip.addValue("qty", q);
            ip.addValue("userId", user.getId());
            ip.addValue("spjangcd", spjangcd);
            this.sqlRunner.execute("""
                INSERT INTO steril_batch_item
                    ("SterilBatch_id","MatLot_id","Material_id","LotNumber","Qty",
                     "State","_status","_created","_creater_id",spjangcd)
                VALUES (:batchId,:matLotId,:matId,:lotNo,:qty,'a','a',now(),:userId,:spjangcd)
                """, ip);
        }
        return r;
    }

    /** 로트 빼기 — 전부 빠지면 배치 종류(ItemType) 해제 */
    @Transactional
    public AjaxResult lotRemove(Integer itemId, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        Map<String, Object> item = this.sqlRunner.getRow("""
            SELECT "SterilBatch_id" AS batch_id FROM steril_batch_item WHERE id=:id AND "_status"='a'
            """, new MapSqlParameterSource().addValue("id", itemId));
        if (item == null) { r.success = false; r.message = "담긴 로트를 찾을 수 없습니다."; return r; }
        Integer batchId = ((Number) item.get("batch_id")).intValue();
        Map<String, Object> batch = getBatchRaw(batchId);
        if (batch != null && !isEditable(batch)) {
            r.success = false; r.message = "이미 판정/마감된 배치입니다."; return r;
        }
        this.sqlRunner.execute("UPDATE steril_batch_item SET \"_status\"='d' WHERE id=:id",
                new MapSqlParameterSource().addValue("id", itemId));
        return r;
    }

    /** 멸균일지 단계 저장(upsert) */
    @Transactional
    public AjaxResult logSave(Integer batchId, Integer stepNo, String stepName, String data, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("batchId", batchId);
        p.addValue("stepNo", stepNo);
        p.addValue("stepName", stepName);
        p.addValue("data", data);
        p.addValue("userId", user.getId());
        // uq_sbl_batch_step 로 upsert
        this.sqlRunner.execute("""
            INSERT INTO steril_batch_log ("SterilBatch_id","StepNo","StepName","Data","_status","_created",spjangcd)
            VALUES (:batchId,:stepNo,:stepName,:data,'a',now(),'ZZ')
            ON CONFLICT ("SterilBatch_id","StepNo")
            DO UPDATE SET "StepName"=EXCLUDED."StepName", "Data"=EXCLUDED."Data",
                          "_modified"=now(), "_modifier_id"=:userId, "_status"='a'
            """, p);
        return r;
    }

    /**
     * BI 판정.
     *   pass → 담은 로트 클린룸(5)→멸균창고(18) 이동(로트번호 유지) + equ_run + 배치 done
     *   fail → 배치 전량 폐기(클린룸 차감 + 부적합창고 이관) + 배치 scrapped
     */
    @Transactional
    public AjaxResult biJudge(Integer batchId, String result, String makerLotNo, String makerLotsJson,
                              String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        if (!"pass".equals(result) && !"fail".equals(result)) {
            r.success = false; r.message = "판정 값이 올바르지 않습니다."; return r;
        }
        Map<String, Object> batch = getBatchRaw(batchId);
        if (batch == null) { r.success = false; r.message = "배치를 찾을 수 없습니다."; return r; }
        String bState = String.valueOf(batch.get("State"));
        if ("done".equals(bState) || "scrapped".equals(bState)) {
            r.success = false; r.message = "이미 판정된 배치입니다."; return r;
        }
        if (!"working".equals(bState)) {
            r.success = false; r.message = "시작하지 않은 배치입니다. [시작]을 먼저 누르세요."; return r;
        }
        Integer actorId = batch.get("Actor_id") != null ? ((Number) batch.get("Actor_id")).intValue() : null;
        Integer equipId = batch.get("Equipment_id") != null ? ((Number) batch.get("Equipment_id")).intValue() : null;
        Timestamp startTs = (Timestamp) batch.get("StartTime");

        List<Map<String, Object>> items = this.sqlRunner.getRows("""
            SELECT id AS item_id, "MatLot_id" AS mat_lot_id, "Material_id" AS mat_id,
                   "LotNumber" AS lot_no, "Qty" AS qty
              FROM steril_batch_item WHERE "SterilBatch_id"=:batchId AND "_status"='a'
            """, new MapSqlParameterSource().addValue("batchId", batchId));
        if (items.isEmpty()) { r.success = false; r.message = "담긴 로트가 없습니다."; return r; }

        // ★ 품목별 UDI 로트번호 — 품목이 달라도 같이 멸균하므로 품목마다 따로 받는다.
        //   maker_lots 에 없는 품목은 maker_lot_no(공통값)를 쓴다. 합격인데 비어 있으면 거부.
        Map<String, String> makerByMat = new HashMap<>();
        if (makerLotsJson != null && !makerLotsJson.isBlank()) {
            try {
                Map<?, ?> m = new com.fasterxml.jackson.databind.ObjectMapper().readValue(makerLotsJson, Map.class);
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    String v = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
                    if (!v.isEmpty()) makerByMat.put(String.valueOf(e.getKey()).trim(), v);
                }
            } catch (Exception e) {
                r.success = false; r.message = "UDI 로트번호 형식 오류"; return r;
            }
        }
        // UDI 로트번호는 선택 — 비워 두고 판정한 뒤 «수정»으로 나중에 넣어도 된다

        Timestamp now = DateUtil.getNowTimeStamp();
        LocalDate today = LocalDate.now();
        LocalTime nowT = LocalTime.now();
        boolean pass = "pass".equals(result);

        for (Map<String, Object> it : items) {
            int itemId = ((Number) it.get("item_id")).intValue();
            int matLotId = ((Number) it.get("mat_lot_id")).intValue();
            int matId = ((Number) it.get("mat_id")).intValue();
            String lotNo = String.valueOf(it.get("lot_no"));
            float qty = toF(it.get("qty"));
            if (qty <= 0) continue;
            String itemMaker = makerByMat.getOrDefault(String.valueOf(matId), makerLotNo == null ? "" : makerLotNo.trim());

            // 클린룸 로트 현재고 확인
            Map<String, Object> lot = this.sqlRunner.getRow("""
                SELECT COALESCE("CurrentStock",0) AS cs FROM mat_lot WHERE id=:id
                """, new MapSqlParameterSource().addValue("id", matLotId));
            float cs = (lot != null) ? toF(lot.get("cs")) : 0f;
            if (cs < qty) {
                r.success = false;
                r.message = "클린룸 재고 부족: 로트 " + lotNo + " (필요 " + fmt(qty) + " / 가용 " + fmt(cs) + ")";
                throw new IllegalStateException(r.message);   // 롤백
            }

            // 클린룸 차감 (트리거가 CurrentStock 갱신)
            MatLotCons mlc = new MatLotCons();
            mlc.setMaterialLotId(matLotId);
            mlc.setOutputDateTime(now);
            mlc.setSourceDataPk(itemId);
            mlc.setSourceTableName(SRC_TABLE);
            mlc.setCurrentStock(cs);
            mlc.setOutputQty(qty);
            mlc.set_audit(user);
            mlc.setSpjangcd(spjangcd);
            this.matLotConsRepository.save(mlc);

            // 클린룸 출고 이력
            MaterialInout out = new MaterialInout();
            out.setMaterialId(matId);
            out.setStoreHouseId(STORE_CLEAN);
            out.setInOut("out");
            out.setOutputType(pass ? "move_out" : "disposal_out");
            out.setOutputQty(qty);
            out.setInoutDate(today);
            out.setInoutTime(nowT);
            out.setDescription(pass ? "멸균 이동(클린룸 출고)" : "멸균 FAIL 폐기(클린룸 재고 소멸)");
            if (!pass) out.setDefectQty(qty);
            out.setState("confirmed");
            out.set_status("a");
            out.setSourceDataPk(itemId);
            out.setSourceTableName(SRC_TABLE);
            out.setActorId(actorId);
            out.set_audit(user);
            out.setSpjangcd(spjangcd);
            this.matInoutRepository.save(out);

            if (pass) {
                // 멸균창고 입고 — 로트번호 유지(멸균필)
                MaterialLot ml = new MaterialLot();
                ml.setLotNumber(lotNo);
                ml.setMaterialId(matId);
                ml.setInputDateTime(now);
                ml.setInputQty(qty);
                ml.setCurrentStock(qty);
                ml.setDescription("멸균필 입고(멸균창고)");
                ml.setSourceDataPk(itemId);
                ml.setSourceTableName(SRC_TABLE);
                ml.setStoreHouseId(STORE_STERIL);
                ml.set_audit(user);
                ml.setSpjangcd(spjangcd);
                this.matLotRepository.save(ml);

                // 업체 로트번호 기록(엔티티 미보유 컬럼 → SQL). 방금 만든 멸균창고 로트를 source로 특정.
                if (!itemMaker.isEmpty()) {   // 비었으면 로트에 안 넣는다
                    this.sqlRunner.execute("""
                        UPDATE mat_lot SET "MakerLotNo"=:mk
                         WHERE "SourceTableName"=:src AND "SourceDataPk"=:pk AND "StoreHouse_id"=:store
                        """, new MapSqlParameterSource()
                            .addValue("mk", itemMaker).addValue("src", SRC_TABLE)
                            .addValue("pk", itemId).addValue("store", STORE_STERIL));
                }

                MaterialInout in = new MaterialInout();
                in.setMaterialId(matId);
                in.setStoreHouseId(STORE_STERIL);
                in.setInOut("in");
                in.setInputType("move_in");
                in.setInputQty(qty);
                in.setInoutDate(today);
                in.setInoutTime(nowT);
                in.setDescription("멸균필 입고(멸균창고)");
                in.setState("confirmed");
                in.set_status("a");
                in.setSourceDataPk(itemId);
                in.setSourceTableName(SRC_TABLE);
                in.setActorId(actorId);
                in.set_audit(user);
                in.setSpjangcd(spjangcd);
                this.matInoutRepository.save(in);

                this.sqlRunner.execute("UPDATE steril_batch_item SET \"State\"='moved', \"MakerLotNo\"=:mk WHERE id=:id",
                        new MapSqlParameterSource().addValue("id", itemId).addValue("mk", itemMaker.isEmpty() ? null : itemMaker));
            } else {
                // FAIL — 폐기. 자재폐기(mat_disposal) 방식과 동일하게 창고 이동 없이 소멸시킨다.
                //   클린룸 차감(mat_lot_cons) + 폐기 출고(mat_inout out, OutputType='disposal_out')만 남기고
                //   새 로트(부적합창고 입고)는 만들지 않는다.
                this.sqlRunner.execute("UPDATE steril_batch_item SET \"State\"='scrapped' WHERE id=:id",
                        new MapSqlParameterSource().addValue("id", itemId));
            }
        }

        // equ_run 1건 (멸균기 가동)
        Integer equRunId = null;
        if (equipId != null) {
            EquRun er = new EquRun();
            er.setEquipmentId(equipId);
            er.setStartDate(startTs != null ? startTs : now);
            er.setEndDate(now);
            er.setRunState("complete");
            er.setActorId(actorId);
            er.setDescription("멸균(EO) - " + (pass ? "PASS" : "FAIL"));
            er.set_audit(user);
            er.setSpjangcd(spjangcd);
            er = this.equRunRepository.save(er);
            equRunId = er.getId();
        }

        // 배치 상태
        MapSqlParameterSource up = new MapSqlParameterSource();
        up.addValue("batchId", batchId);
        up.addValue("bi", result);
        up.addValue("state", pass ? "done" : "scrapped");
        up.addValue("equRunId", equRunId);
        up.addValue("pass", pass);
        up.addValue("userId", user.getId());
        // 배치 MakerLotNo = 품목별 번호를 모아 보여주기용(목록 표시) — 실제 값은 품목(steril_batch_item)·로트(mat_lot)에
        this.sqlRunner.execute("""
            UPDATE steril_batch
               SET "BiResult"=:bi, "State"=:state, "BiJudgeAt"=now(), "EndTime"=now(),
                   "EquRun_id"=:equRunId,
                   "MakerLotNo"=CASE WHEN :pass THEN
                        (SELECT string_agg(DISTINCT x."MakerLotNo", ', ') FROM steril_batch_item x
                          WHERE x."SterilBatch_id"=:batchId AND x."_status"='a' AND x."MakerLotNo" IS NOT NULL) END,
                   "_modified"=now(), "_modifier_id"=:userId
             WHERE id=:batchId
            """, up);

        r.data = Map.of("batch_id", batchId, "result", result, "moved", pass);
        return r;
    }

    /**
     * 판정 취소 — 이 배치의 재고 이동/폐기를 롤백하고 배치를 '작업중'으로 되돌린다.
     *   PASS 취소: 멸균창고 로트 삭제 + 클린룸 차감 삭제(재고 복원). 단 멸균필 로트가 이미
     *              소비(포장 등)됐으면 차단.
     *   FAIL 취소: 부적합창고 폐기 로트 삭제 + 클린룸 차감 삭제(재고 복원).
     *   업체 로트번호(MakerLotNo)는 비운다 → 재판정 시 다시 입력.
     * 재고는 트리거로 갱신되므로 행 DELETE 로 롤백한다.
     */
    @Transactional
    public AjaxResult biCancel(Integer batchId, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        Map<String, Object> batch = getBatchRaw(batchId);
        if (batch == null) { r.success = false; r.message = "배치를 찾을 수 없습니다."; return r; }
        String state = String.valueOf(batch.get("State"));
        if (!"done".equals(state) && !"scrapped".equals(state)) {
            r.success = false; r.message = "판정 전 배치입니다."; return r;
        }

        List<Map<String, Object>> items = this.sqlRunner.getRows("""
            SELECT id AS item_id FROM steril_batch_item
             WHERE "SterilBatch_id"=:batchId AND "_status"='a'
            """, new MapSqlParameterSource().addValue("batchId", batchId));
        if (items.isEmpty()) { r.success = false; r.message = "담긴 로트가 없습니다."; return r; }

        // ★ 먼저 전부 확인 — 후속 공정에서 쓴 로트가 있으면 아무것도 건드리지 않고 이유를 돌려준다
        //   (예외로 던지면 화면에는 «에러발생» 만 떠서 왜 안 되는지 알 수 없었다)
        List<Map<String, Object>> usedLots = this.sqlRunner.getRows("""
            SELECT l."LotNumber" AS lot_no, m."Name" AS mat_name, SUM(c."OutputQty") AS q
              FROM steril_batch_item i
              JOIN mat_lot l ON l."SourceTableName" = :src AND l."SourceDataPk" = i.id
              JOIN mat_lot_cons c ON c."MaterialLot_id" = l.id
              JOIN material m ON m.id = l."Material_id"
             WHERE i."SterilBatch_id" = :batchId AND i."_status" = 'a'
             GROUP BY l."LotNumber", m."Name"
            HAVING SUM(c."OutputQty") > 0
            """, new MapSqlParameterSource().addValue("src", SRC_TABLE).addValue("batchId", batchId));
        if (!usedLots.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> u : usedLots) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(u.get("mat_name")).append(" ").append(u.get("lot_no")).append(" (").append(fmt(toF(u.get("q")))).append(")");
            }
            r.success = false;
            r.message = "멸균필 로트가 이미 다음 공정(포장 등)에서 사용되어 판정을 취소할 수 없습니다.\n" + sb
                    + "\nUDI 로트번호만 고치려면 판정취소 없이 «UDI 로트번호 수정»을 쓰세요.";
            return r;
        }

        for (Map<String, Object> it : items) {
            int itemId = ((Number) it.get("item_id")).intValue();
            MapSqlParameterSource p = new MapSqlParameterSource()
                    .addValue("src", SRC_TABLE).addValue("pk", itemId);

            // 산출 로트 삭제(멸균필 / 폐기) → 재고 제거
            this.sqlRunner.execute("""
                DELETE FROM mat_lot WHERE "SourceTableName"=:src AND "SourceDataPk"=:pk
                """, p);
            // 입출고 이력 삭제
            this.sqlRunner.execute("""
                DELETE FROM mat_inout WHERE "SourceTableName"=:src AND "SourceDataPk"=:pk
                """, p);
            // 클린룸 차감 삭제 → 트리거가 클린룸 CurrentStock 복원
            this.sqlRunner.execute("""
                DELETE FROM mat_lot_cons WHERE "SourceTableName"=:src AND "SourceDataPk"=:pk
                """, p);

            this.sqlRunner.execute("UPDATE steril_batch_item SET \"State\"='a', \"MakerLotNo\"=NULL WHERE id=:id",
                    new MapSqlParameterSource().addValue("id", itemId));
        }

        // 배치 원복 (업체 로트번호 비움 → 재판정 시 재입력)
        MapSqlParameterSource up = new MapSqlParameterSource()
                .addValue("batchId", batchId).addValue("userId", user.getId());
        this.sqlRunner.execute("""
            UPDATE steril_batch
               SET "State"='working', "BiResult"=NULL, "BiJudgeAt"=NULL, "EndTime"=NULL,
                   "MakerLotNo"=NULL, "_modified"=now(), "_modifier_id"=:userId
             WHERE id=:batchId
            """, up);
        return r;
    }

    /**
     * ★ UDI 로트번호 수정 (판정 후) — 판정취소 없이 품목별 번호만 고친다.
     *   멸균필 로트(mat_lot.MakerLotNo) · 담긴 품목(steril_batch_item) · 배치 요약을 함께 바꾼다.
     *   후속 공정에서 이미 쓴 로트여도 번호 표기만 바뀌므로 허용한다.
     */
    @Transactional
    public AjaxResult saveMakerLots(Integer batchId, String makerLotsJson, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        Map<String, Object> batch = getBatchRaw(batchId);
        if (batch == null) { r.success = false; r.message = "배치를 찾을 수 없습니다."; return r; }
        if (!"done".equals(String.valueOf(batch.get("State")))) {
            r.success = false; r.message = "합격 판정된 배치만 수정할 수 있습니다."; return r;
        }
        Map<?, ?> m;
        try { m = new com.fasterxml.jackson.databind.ObjectMapper().readValue(makerLotsJson, Map.class); }
        catch (Exception e) { r.success = false; r.message = "UDI 로트번호 형식 오류"; return r; }
        int n = 0;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String v = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
            MapSqlParameterSource p = new MapSqlParameterSource().addValue("batchId", batchId)
                    .addValue("matId", Integer.parseInt(String.valueOf(e.getKey()).trim())).addValue("mk", v.isEmpty() ? null : v)
                    .addValue("src", SRC_TABLE).addValue("store", STORE_STERIL);
            this.sqlRunner.execute("""
                UPDATE mat_lot l SET "MakerLotNo" = :mk
                  FROM steril_batch_item i
                 WHERE l."SourceTableName" = :src AND l."SourceDataPk" = i.id AND l."StoreHouse_id" = :store
                   AND i."SterilBatch_id" = :batchId AND i."Material_id" = :matId AND i."_status" = 'a'
                """, p);
            this.sqlRunner.execute("""
                UPDATE steril_batch_item SET "MakerLotNo" = :mk
                 WHERE "SterilBatch_id" = :batchId AND "Material_id" = :matId AND "_status" = 'a'
                """, p);
            n++;
        }
        this.sqlRunner.execute("""
            UPDATE steril_batch SET "MakerLotNo" =
                   (SELECT string_agg(DISTINCT x."MakerLotNo", ', ') FROM steril_batch_item x
                     WHERE x."SterilBatch_id" = :batchId AND x."_status" = 'a' AND x."MakerLotNo" IS NOT NULL),
                   "_modified" = now(), "_modifier_id" = :userId
             WHERE id = :batchId
            """, new MapSqlParameterSource().addValue("batchId", batchId).addValue("userId", user.getId()));
        r.message = "UDI 로트번호 " + n + "품목 수정";
        return r;
    }

    /** 배치 기본정보 저장 — 시작/종료 시간 + 멸균기 (yyyy-MM-dd HH:mm) */
    @Transactional
    public AjaxResult setBatchTime(Integer batchId, String startTime, String endTime,
                                   Integer equipmentId, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("batchId", batchId);
        p.addValue("start", (startTime == null || startTime.isBlank()) ? null : Timestamp.valueOf(startTime.length() == 16 ? startTime + ":00" : startTime));
        p.addValue("end", (endTime == null || endTime.isBlank()) ? null : Timestamp.valueOf(endTime.length() == 16 ? endTime + ":00" : endTime));
        p.addValue("equipId", equipmentId);
        p.addValue("userId", user.getId());
        this.sqlRunner.execute("""
            UPDATE steril_batch
               SET "StartTime"=COALESCE(:start,"StartTime"), "EndTime"=:end,
                   "Equipment_id"=COALESCE(:equipId,"Equipment_id"),
                   "State"=(CASE WHEN "State"='wait' AND COALESCE(:start,"StartTime") IS NOT NULL
                                 THEN 'working' ELSE "State" END),
                   "_modified"=now(), "_modifier_id"=:userId
             WHERE id=:batchId
            """, p);
        return r;
    }

    /** 배치 삭제 — working 상태만 (판정 완료/폐기 배치는 불가) */
    @Transactional
    public AjaxResult batchDelete(Integer batchId, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        Map<String, Object> batch = getBatchRaw(batchId);
        if (batch == null) { r.success = false; r.message = "배치를 찾을 수 없습니다."; return r; }
        if (!isEditable(batch)) {
            r.success = false; r.message = "판정 완료/폐기된 배치는 삭제할 수 없습니다."; return r;
        }
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("batchId", batchId);
        this.sqlRunner.execute("UPDATE steril_batch_item SET \"_status\"='d' WHERE \"SterilBatch_id\"=:batchId", p);
        this.sqlRunner.execute("UPDATE steril_batch_log  SET \"_status\"='d' WHERE \"SterilBatch_id\"=:batchId", p);
        this.sqlRunner.execute("UPDATE steril_batch SET \"_status\"='d' WHERE id=:batchId", p);
        return r;
    }

    // =================================================================
    // 유틸
    // =================================================================
    private Map<String, Object> getBatchRaw(Integer batchId) {
        return this.sqlRunner.getRow("""
            SELECT id, "State", "ItemType", "Actor_id", "Equipment_id", "StartTime"
              FROM steril_batch WHERE id=:batchId AND COALESCE("_status",'a')='a'
            """, new MapSqlParameterSource().addValue("batchId", batchId));
    }

    /** 편집 가능(로트 담기/빼기/삭제) 상태 = 대기 또는 작업중 */
    private boolean isEditable(Map<String, Object> batch) {
        String st = (batch == null) ? "" : String.valueOf(batch.get("State"));
        return "wait".equals(st) || "working".equals(st);
    }

    private float toF(Object o) { return (o == null) ? 0f : Float.parseFloat(String.valueOf(o)); }

    private String fmt(float f) { return (f == Math.floor(f)) ? String.valueOf((long) f) : String.valueOf(f); }
}