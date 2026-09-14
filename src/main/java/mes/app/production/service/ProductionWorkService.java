package mes.app.production.service;

import mes.domain.model.AjaxResult;
import mes.domain.entity.User;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ①생산형 공용 조회 서비스 (조립·블리스터·융착·포장). 화면은 process_id 만 바꿔 공용.
 * <p>
 * 화면 골격: 날짜(A) → 작업조(B) → 용기(C)
 * - 작업조 = 별도 테이블 없음. (ProductionDate, ShiftCode, Actor_id) 로 mat_produce 를 group by 하여 파생.
 * - 용기   = mat_produce 차수 1건.
 * - 실적 생성/완료 = ProductionCreateService (ProductionWorkController 에서 호출).
 * <p>
 * process_id 만 바꿔 조립/블리스터/융착/포장이 동일 엔드포인트를 쓴다.
 */
@Service
public class ProductionWorkService {

    @Autowired
    SqlRunner sqlRunner;

    /* 삭제 시 작지 롤업(recalcJobRes)을 다시 굴리기 위한 주입.
       ★ 중간저장(itemSave)·작업시작(itemStart)에는 붙이지 않는다.
         작업 중 mat_produce."GoodQty" 는 아직 입력 중인 예정값이고,
         확정은 finishProduction 이 한다("수량 확정").
         여기서 롤업하면 작업만 시작해도 작지 양품이 올라가
         생산실적현황·작업일보가 만들지도 않은 수량을 실적으로 보고한다.
       (ProductionCreateService 는 이 클래스를 참조하지 않아 순환 없음) */
    @Autowired
    ProductionCreateService productionCreateService;

    /**
     * 공정 컨텍스트 — process_id 로 워크센터/산출창고 조회
     */
    public Map<String, Object> getContext(String processCode, Integer factoryId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("processCode", processCode);
        p.addValue("factoryId", factoryId);
        String sql = """
                SELECT p.id AS process_id, p."Code" AS process_code, p."Name" AS process_name,
                       wc.id AS workcenter_id, wc."Name" AS workcenter_name,
                       wc."ProcessStoreHouse_id" AS out_store_id, wc."Factory_id" AS factory_id
                  FROM process p
                  LEFT JOIN work_center wc ON wc."Process_id" = p.id
                        AND (CAST(:factoryId AS INTEGER) IS NULL OR wc."Factory_id" = CAST(:factoryId AS INTEGER))
                 WHERE p."Code" = :processCode
                 ORDER BY wc.id
                 LIMIT 1
                """;
        return this.sqlRunner.getRow(sql, p);
    }

    /**
     * 날짜 카드 목록 — 해당 공정의 mat_produce 를 날짜로 집계
     */
    public List<Map<String, Object>> getDayList(Integer processId, String dateFrom, String dateTo, String spjangcd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("processId", processId);
        p.addValue("dateFrom", LocalDate.parse(dateFrom));
        p.addValue("dateTo", LocalDate.parse(dateTo));
        p.addValue("spjangcd", spjangcd);
        String sql = """
                SELECT mp."ProductionDate"::date                              AS work_date
                     , to_char(mp."ProductionDate", 'yyyy-mm-dd')             AS work_date_str
                     , COUNT(DISTINCT (mp."ShiftCode", mp."Actor_id"))        AS crew_cnt
                     , COUNT(*)                                               AS item_cnt
                     , COUNT(*) FILTER (WHERE mp."State" = 'finished')        AS item_done_cnt
                     , COUNT(*) FILTER (WHERE mp."State" = 'working')         AS item_working_cnt
                     , COALESCE(SUM(mp."GoodQty") FILTER (WHERE mp."State" = 'finished'), 0) AS good_qty
                     , CASE WHEN COUNT(*) = 0 THEN 'wait'
                            WHEN COUNT(*) = COUNT(*) FILTER (WHERE mp."State" = 'finished') THEN 'done'
                            ELSE 'working' END                                AS state
                  FROM mat_produce mp
                  JOIN job_res jr ON jr.id = mp."JobResponse_id"
                  LEFT JOIN work_center wc ON wc.id = mp."WorkCenter_id"
                 WHERE COALESCE(mp."_status",'a') = 'a'
                   AND wc."Process_id" = :processId
                   AND mp.spjangcd = :spjangcd
                   AND mp."ProductionDate"::date BETWEEN :dateFrom AND :dateTo
                 GROUP BY mp."ProductionDate"::date
                 ORDER BY mp."ProductionDate"::date DESC
                """;
        return this.sqlRunner.getRows(sql, p);
    }

    /**
     * 작업조 목록 — (날짜, ShiftCode, Actor_id) 파생
     */
    public List<Map<String, Object>> getCrewList(Integer processId, String date, String spjangcd) {
        return getCrewList(processId, date, spjangcd, null);
    }

    /**
     * 작업조 목록. jobResId 가 주어지면(WO-우선 공정: 블리스터 등) 그 작업지시에 귀속된
     * 차수만 대상으로 조를 파생한다(작지는 날짜를 넘길 수 있으므로 날짜 필터 제거).
     * jobResId 가 null 이면 기존 날짜 기반(조립 등).
     */
    public List<Map<String, Object>> getCrewList(Integer processId, String date, String spjangcd, Integer jobResId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("processId", processId);
        p.addValue("date", date == null ? null : LocalDate.parse(date));
        p.addValue("spjangcd", spjangcd);
        p.addValue("jobResId", jobResId);
        String sql = """
                SELECT mp."ShiftCode"                                   AS shift_code
                     , sh."Name"                                        AS shift_name
                     , mp."Actor_id"                                    AS actor_id
                     , pr."Name"                                        AS leader_name
                     , mp."Equipment_id"                                AS equipment_id
                     , e."Name"                                         AS equipment_name
                     , COUNT(*)                                         AS item_cnt
                     , COUNT(*) FILTER (WHERE mp."State" = 'finished')  AS item_done_cnt
                     , COALESCE(SUM(mp."GoodQty") FILTER (WHERE mp."State"='finished'), 0) AS good_qty
                     , to_char(MIN(mp."StartTime"), 'yyyy-mm-dd hh24:mi') AS start_time
                     , CASE WHEN COUNT(*) > 0 AND COUNT(*) = COUNT(*) FILTER (WHERE mp."State"='finished')
                            THEN to_char(MAX(mp."EndTime"), 'yyyy-mm-dd hh24:mi') ELSE NULL END AS end_time
                     , CASE WHEN COUNT(*) = COUNT(*) FILTER (WHERE mp."State"='finished') THEN 'done'
                            WHEN COUNT(*) FILTER (WHERE mp."State"<>'finished') > 0
                                 AND COUNT(*) FILTER (WHERE mp."State"='finished') > 0 THEN 'working'
                            WHEN COUNT(*) FILTER (WHERE mp."State"='working') > 0 THEN 'working'
                            ELSE 'working' END                          AS state
                     /* ★ 조원은 스칼라 서브쿼리로 붙인다. LATERAL 로 조인하면 조원 수만큼
                          원본 행이 복제되어 위의 COUNT/SUM 이 배로 부풀어 오른다.
                          MIN(mp.id) = 이 작업조의 가장 이른 차수. 조원은 조 단위로 고정이므로
                          대표 차수 하나를 기준 삼으면 된다. */
                     , (SELECT string_agg(p2."Name", ', '
                                          ORDER BY (m2."IsLeader"='Y') DESC, p2."Name")
                          FROM mat_produce_member m2
                          JOIN person p2 ON p2.id = m2."Person_id"
                         WHERE m2."SourceTableName" = 'mat_produce'
                           AND m2."SourceDataPk"    = MIN(mp.id)
                           AND COALESCE(m2."_status",'a') = 'a')       AS members
                  FROM mat_produce mp
                  JOIN job_res jr ON jr.id = mp."JobResponse_id"
                  LEFT JOIN work_center wc ON wc.id = mp."WorkCenter_id"
                  LEFT JOIN shift sh ON sh."Code" = mp."ShiftCode"
                  LEFT JOIN person pr ON pr.id = mp."Actor_id"
                  LEFT JOIN equ e ON e.id = mp."Equipment_id"
                 WHERE COALESCE(mp."_status",'a') = 'a'
                   AND wc."Process_id" = :processId
                   AND mp.spjangcd = :spjangcd
                   AND ( (CAST(:jobResId AS INTEGER) IS NOT NULL
                          AND mp."JobResponse_id" = CAST(:jobResId AS INTEGER))
                      OR (CAST(:jobResId AS INTEGER) IS NULL
                          AND mp."ProductionDate"::date = :date) )
                 GROUP BY mp."ShiftCode", sh."Name", mp."Actor_id", pr."Name",
                          mp."Equipment_id", e."Name"
                 ORDER BY MIN(mp."StartTime") NULLS LAST
                """;
        return this.sqlRunner.getRows(sql, p);
    }

    /**
     * 용기(차수) 목록 — 한 작업조가 만든 mat_produce 들
     */
    public List<Map<String, Object>> getItemList(Integer processId, String date, String shiftCode,
                                                 Integer actorId, String spjangcd) {
        return getItemList(processId, date, shiftCode, actorId, spjangcd, null);
    }

    /**
     * 한 작업조가 만든 차수 목록. jobResId 가 주어지면 그 작업지시로 한정(WO-우선 공정).
     */
    public List<Map<String, Object>> getItemList(Integer processId, String date, String shiftCode,
                                                 Integer actorId, String spjangcd, Integer jobResId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("processId", processId);
        p.addValue("date", date == null ? null : LocalDate.parse(date));
        p.addValue("shiftCode", shiftCode);
        p.addValue("actorId", actorId);
        p.addValue("spjangcd", spjangcd);
        p.addValue("jobResId", jobResId);
        String sql = """
                SELECT mp.id                          AS mp_id
                     , mp."JobResponse_id"            AS job_res_id
                     , jr."WorkOrderNumber"           AS order_num
                     , mp."Material_id"               AS mat_id
                     , m."Code"                       AS mat_code
                     , m."Name"                       AS mat_name
                     , u."Name"                       AS unit
                     , mp."LotNumber"                 AS lot_no
                     , mp."LotIndex"                  AS chasu
                     , COALESCE(mp."GoodQty",0)       AS good_qty
                     , COALESCE(mp."DefectQty",0)     AS defect_qty
                     , mp."State"                     AS state
                     , to_char(mp."StartTime",'yyyy-mm-dd hh24:mi') AS start_time
                     , to_char(mp."EndTime",'yyyy-mm-dd hh24:mi')   AS end_time
                  FROM mat_produce mp
                  JOIN job_res jr ON jr.id = mp."JobResponse_id"
                  LEFT JOIN work_center wc ON wc.id = mp."WorkCenter_id"
                  LEFT JOIN material m ON m.id = mp."Material_id"
                  LEFT JOIN unit u ON u.id = m."Unit_id"
                 WHERE COALESCE(mp."_status",'a') = 'a'
                   AND wc."Process_id" = :processId
                   AND mp.spjangcd = :spjangcd
                   AND mp."ShiftCode" = :shiftCode
                   AND mp."Actor_id" = :actorId
                   AND ( (CAST(:jobResId AS INTEGER) IS NOT NULL
                          AND mp."JobResponse_id" = CAST(:jobResId AS INTEGER))
                      OR (CAST(:jobResId AS INTEGER) IS NULL
                          AND mp."ProductionDate"::date = :date) )
                 ORDER BY mp.id
                """;
        return this.sqlRunner.getRows(sql, p);
    }

    /**
     * 대상 산출품목 후보 — 이 공정(process)에서 만들 수 있는 반제품.
     * 작지 있는 것(WO) + 작지 없이 직접 선택할 SKU(semi 반제품) 둘 다.
     */
    public List<Map<String, Object>> getTargetMaterials(Integer processId, String matTypeLike,
                                                        String keyword, String spjangcd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("processId", processId);
        p.addValue("keyword", (keyword == null || keyword.isBlank()) ? null : "%" + keyword + "%");
        // 이 공정에서 생산되는 반제품 = 품목의 WorkCenter 공정이 이 공정인 것.
        //  (반제품은 자기 Routing_id 가 없고, material.WorkCenter_id 로 공정이 정해짐 — 작지 유무와 무관)
        //  raw/sub_mat 은 MaterialType 으로 방어(워크센터가 나중에 생겨도 안 뜸).
        String sql = """
                SELECT m.id AS mat_id, m."Code" AS mat_code, m."Name" AS mat_name,
                       u."Name" AS unit, mg."MaterialType" AS mat_type
                  FROM material m
                  JOIN work_center wc ON wc.id = m."WorkCenter_id"
                  LEFT JOIN unit u ON u.id = m."Unit_id"
                  LEFT JOIN mat_grp mg ON mg.id = m."MaterialGroup_id"
                 WHERE COALESCE(m."_status",'a') = 'a'
                   AND mg."MaterialType" IN ('semi','product')
                   AND wc."Process_id" = :processId
                   AND (CAST(:keyword AS VARCHAR) IS NULL
                        OR m."Name" LIKE CAST(:keyword AS VARCHAR)
                        OR m."Code" LIKE CAST(:keyword AS VARCHAR))
                 ORDER BY m."Code"
                 LIMIT 100
                """;
        return this.sqlRunner.getRows(sql, p);
    }

    /**
     * 작지 있는 대상(WO) — 해당 공정 워크센터의 미완료 작지
     */
    public List<Map<String, Object>> getWorkOrders(Integer processId, String date, String spjangcd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("processId", processId);
        p.addValue("date", LocalDate.parse(date));
        p.addValue("spjangcd", spjangcd);
        String sql = """
                SELECT (array_agg(jr.id ORDER BY jr."WorkOrderNumber" DESC))[1] AS job_res_id
                     , MAX(jr."WorkOrderNumber")           AS order_num
                     , jr."Material_id"                    AS mat_id
                     , m."Code"                            AS mat_code
                     , m."Name"                            AS mat_name
                     , SUM(COALESCE(jr."OrderQty",0))      AS order_qty
                     , COUNT(*)                            AS wo_cnt
                  FROM job_res jr
                  LEFT JOIN material m ON m.id = jr."Material_id"
                  LEFT JOIN work_center wc ON wc.id = jr."WorkCenter_id"
                 WHERE jr.spjangcd = :spjangcd
                   AND wc."Process_id" = :processId
                   AND jr."State" IN ('ordered','working')
                   -- ★ 뒤 공정이 끝난 작지는 제외 (getWorkOrderQueue 의 같은 조건 참고).
                   --   두 목록이 같은 규칙을 써야 화면마다 다른 개수가 보이지 않는다.
                   AND NOT EXISTS (
                         SELECT 1 FROM job_res nx
                          WHERE jr."Parent_id" IS NOT NULL
                            AND nx."Parent_id" = jr."Parent_id"
                            AND nx."WorkIndex" > jr."WorkIndex"
                            AND nx."State" = 'finished'
                   )
                   AND jr."ProductionDate"::date <= :date
                 GROUP BY jr."Material_id", m."Code", m."Name"
                 ORDER BY m."Code"
                 LIMIT 100
                """;
        return this.sqlRunner.getRows(sql, p);
    }

    /**
     * 투입자재 후보 — 클린룸(5) 재고 있는 자재 (화면 BOM 추가용)
     */
    public List<Map<String, Object>> getCleanStock(Integer storeId, String keyword, String filterMode) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("storeId", storeId);
        p.addValue("keyword", (keyword == null || keyword.isBlank()) ? null : "%" + keyword + "%");

        // 투입자재 후보 필터 모드
        //  'wash'  = 세척부품(WashYN='Y') — 조립 공정용. 재고 0도 노출(세척으로 곧 채워짐).
        //  'stock' = 소스창고 실재고 있는 자재(용기 반제품 + 부자재) — 블리스터/융착/포장 등
        //            반제품을 투입으로 쓰는 공정용. WashYN 무관, 물리적으로 창고에 있는 것만.
        //  기본값 'wash' (조립 기존 동작 보존).
        boolean stockMode = "stock".equalsIgnoreCase(filterMode);
        String matPred = stockMode
                ? "EXISTS (SELECT 1 FROM mat_lot ml2 WHERE ml2.\"Material_id\" = m.id "
                + "AND ml2.\"StoreHouse_id\" = :storeId AND ml2.\"CurrentStock\" > 0)"
                : "COALESCE(m.\"WashYN\",'N') = 'Y'";
        String sql = """
                SELECT m.id AS mat_id, m."Code" AS mat_code, m."Name" AS mat_name,
                       u."Name" AS unit, COALESCE(SUM(ml."CurrentStock"),0) AS stock
                  FROM material m
                  LEFT JOIN mat_lot ml ON ml."Material_id" = m.id
                                      AND ml."StoreHouse_id" = :storeId
                                      AND ml."CurrentStock" > 0
                  LEFT JOIN unit u ON u.id = m."Unit_id"
                 WHERE COALESCE(m."_status",'a') = 'a'
                   AND %s
                   AND (CAST(:keyword AS VARCHAR) IS NULL
                        OR m."Name" LIKE CAST(:keyword AS VARCHAR)
                        OR m."Code" LIKE CAST(:keyword AS VARCHAR))
                 GROUP BY m.id, m."Code", m."Name", u."Name"
                 ORDER BY m."Code"
                 LIMIT 100
                """.formatted(matPred);
        /**
         * 조립 BOM 기본값 — 산출품목(용기)의 manufacturing BOM 을 수량만큼 전개.
         * 클린룸(5) 현재고를 함께 붙여 화면에서 부족 여부 표시.
         * 작업자는 이 기본값에서 추가/해지/수량조절 후 완료 시 편집분을 bom_json 으로 전송.
         */
        return this.sqlRunner.getRows(sql, p);
    }

    /** 구버전 호출부 호환 — 초안 없이 BOM 기본값만. */
    public List<Map<String, Object>> getBomDefault(Integer materialId, Float qty,
                                                   Integer cleanStore, String prodDate) {
        return getBomDefault(materialId, qty, cleanStore, prodDate, null);
    }

    /**
     * BOM 기본값 + 투입 초안(mat_produce."DraftBomJson") 덮어쓰기.
     *
     * SQL 은 건드리지 않고 결과에 초안을 얹는다 —
     * 거대한 BOM 쿼리에 CTE 를 끼워 넣는 것보다 되돌리기 쉽고, 초안이 없으면
     * 기존 동작과 완전히 같다.
     *
     *   draft_qty : 초안에 적힌 절대 수량 (화면이 per 로 재계산하지 않게 하는 값)
     *   is_draft  : 'Y' 면 초안에서 온 값
     *   is_added  : 'Y' 면 BOM 에 없는 추가 투입분
     */
    public List<Map<String, Object>> getBomDefault(Integer materialId, Float qty,
                                                   Integer cleanStore, String prodDate,
                                                   Integer mpId) {
        List<Map<String, Object>> rows = getBomDefaultRaw(materialId, qty, cleanStore, prodDate);

        Map<Integer, DraftRow> draft = loadDraftBom(mpId);
        if (draft.isEmpty()) return rows;

        List<Map<String, Object>> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            Integer mid = r.get("mat_id") == null ? null : ((Number) r.get("mat_id")).intValue();
            seen.add(mid);
            DraftRow d = draft.get(mid);
            m.put("is_added", "N");
            if (d != null) {
                m.put("draft_qty", d.qty);
                m.put("default_qty", d.qty);
                m.put("is_draft", "Y");
                /* 작업자가 고친 줄만 'Y'. 화면은 이 값만 잠그고,
                   나머지는 per×수량 자동계산으로 되돌린다. */
                m.put("is_manual", d.manual ? "Y" : "N");
            } else {
                /* 초안이 있는데 이 자재가 빠져 있다 = 작업자가 뺀 것.
                   BOM 기본으로 되살리면 지운 자재가 다시 나타난다. */
                m.put("draft_qty", 0d);
                m.put("default_qty", 0d);
                m.put("is_draft", "Y");
                m.put("is_manual", "N");
            }
            out.add(m);
        }

        // BOM 에 없던 추가 투입분을 행으로 만들어 붙인다
        List<Integer> extra = new ArrayList<>();
        for (Integer mid : draft.keySet()) if (!seen.contains(mid)) extra.add(mid);
        if (!extra.isEmpty()) {
            MapSqlParameterSource ep = new MapSqlParameterSource().addValue("ids", extra);
            List<Map<String, Object>> infos = this.sqlRunner.getRows("""
                    SELECT m.id                       AS mat_id
                         , m."Code"                   AS mat_code
                         , m."Name"                   AS mat_name
                         , u."Name"                   AS unit
                         , mg."MaterialType"          AS mat_type
                         , COALESCE(m."LotUseYN",'N') AS lot_use_yn
                         , (CASE WHEN COALESCE(m."SterilizationYN",'N')='Y'  THEN 18
                                 WHEN mg."MaterialType" IN ('semi','product') THEN 5
                                 WHEN COALESCE(m."WashYN",'N')='Y'           THEN 5
                                 ELSE 17 END)         AS src_store
                      FROM material m
                      LEFT JOIN unit u ON u.id = m."Unit_id"
                      LEFT JOIN mat_grp mg ON mg.id = m."MaterialGroup_id"
                     WHERE m.id IN (:ids)
                     ORDER BY m."Code"
                    """, ep);
            for (Map<String, Object> i : infos) {
                Map<String, Object> m = new LinkedHashMap<>(i);
                Integer mid = ((Number) i.get("mat_id")).intValue();
                DraftRow d = draft.get(mid);
                m.put("per", 0d);
                m.put("draft_qty", d.qty);
                m.put("default_qty", d.qty);
                m.put("is_draft", "Y");
                /* BOM 에 없는 추가 투입분은 per 가 없어 자동계산 자체가 불가능하다.
                   항상 수동으로 둔다. */
                m.put("is_manual", "Y");
                m.put("is_added", "Y");
                m.put("stock", 0);
                out.add(m);
            }
        }
        return out;
    }

    /** 초안 한 줄. qty = 그때 투입한 수량, manual = 작업자가 직접 고친 줄인지. */
    private static class DraftRow {
        double qty;
        boolean manual;
        DraftRow(double q, boolean m) { this.qty = q; this.manual = m; }
    }

    /** DraftBomJson → {matId: DraftRow}. 없거나 깨졌으면 빈 맵. */
    private Map<Integer, DraftRow> loadDraftBom(Integer mpId) {
        Map<Integer, DraftRow> map = new LinkedHashMap<>();
        if (mpId == null) return map;
        Map<String, Object> row = this.sqlRunner.getRow(
                "SELECT \"DraftBomJson\" AS j FROM mat_produce WHERE id = :mpId",
                new MapSqlParameterSource().addValue("mpId", mpId));
        if (row == null || row.get("j") == null) return map;
        String json = String.valueOf(row.get("j"));
        if (json.isBlank()) return map;
        try {
            List<Map<String, Object>> arr =
                    new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
            for (Map<String, Object> e : arr) {
                Object mid = e.get("matId");
                Object q = e.get("qty");
                if (mid == null) continue;
                /* manual 이 없는 초안 = 이 기능 이전에 저장된 것.
                   구분할 근거가 없으니 수동으로 본다(값을 잃는 쪽보다 안전하다). */
                Object mn = e.get("manual");
                boolean manual = (mn == null) || Boolean.parseBoolean(String.valueOf(mn));
                map.put(((Number) mid).intValue(),
                        new DraftRow(q == null ? 0d : Double.parseDouble(String.valueOf(q)), manual));
            }
        } catch (Exception ignore) {
            /* 초안이 깨졌으면 없는 셈 친다 — 조회가 실패하는 것보다 낫다 */
        }
        return map;
    }

    /**
     * 완료취소 직전 스냅샷.
     *
     * 초안은 이미 시작·완료 때 저장돼 있지만 그건 「화면이 보낸 값」이다.
     * 서버가 FIFO 로 실제 차감한 결과와 다를 수 있어(로트 부족 등), 수량은
     * mat_lot_cons 를 진실로 삼는다. 다만 manual 플래그는 실제 차감에는 없는
     * 정보이므로 기존 초안에서 그대로 물려받는다 —
     * 안 그러면 취소할 때마다 「무엇을 손댔는지」가 사라진다.
     */
    public void snapshotDraftOnCancel(Integer mpId, User user) {
        if (mpId == null) return;

        List<Map<String, Object>> used = this.sqlRunner.getRows("""
                SELECT ml."Material_id" AS mat_id, SUM(mlc."OutputQty") AS qty
                  FROM mat_lot_cons mlc
                  JOIN mat_lot ml ON ml.id = mlc."MaterialLot_id"
                 WHERE mlc."SourceDataPk" = :mpId
                   AND COALESCE(mlc."SourceTableName",'mat_produce') = 'mat_produce'
                   AND COALESCE(mlc."_status",'a') = 'a'
                 GROUP BY ml."Material_id"
                """, new MapSqlParameterSource().addValue("mpId", mpId));

        // 실제 차감이 없으면(예약만 하고 끝난 차수) 기존 초안을 그대로 둔다
        if (used == null || used.isEmpty()) return;

        Map<Integer, DraftRow> prev = loadDraftBom(mpId);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> u : used) {
            Integer mid = ((Number) u.get("mat_id")).intValue();
            double q = u.get("qty") == null ? 0d : ((Number) u.get("qty")).doubleValue();
            DraftRow p0 = prev.get(mid);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("matId", mid);
            r.put("qty", q);
            r.put("manual", p0 != null && p0.manual);
            rows.add(r);
        }

        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(rows);
            this.sqlRunner.execute("""
                    UPDATE mat_produce
                       SET "DraftBomJson" = :json,
                           "_modified" = now(), "_modifier_id" = :userId
                     WHERE id = :mpId
                    """, new MapSqlParameterSource()
                    .addValue("mpId", mpId)
                    .addValue("json", json)
                    .addValue("userId", user == null ? null : user.getId()));
        } catch (Exception ignore) {
            /* 스냅샷 실패로 완료취소 자체를 막지는 않는다. 기존 초안이 남는다. */
        }
    }

    /**
     * 차수 시작/종료 시각 수정.
     * 준 값만 바꾼다(널이면 기존 유지). 앞뒤가 뒤집히면 거부한다.
     */
    @Transactional
    public AjaxResult updateItemTime(Integer mpId, String startTime, String endTime, User user) {
        AjaxResult r = new AjaxResult();
        if (mpId == null) {
            r.success = false;
            r.message = "차수를 찾을 수 없습니다.";
            return r;
        }

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("mpId", mpId)
                .addValue("st", (startTime == null || startTime.isBlank()) ? null : startTime)
                .addValue("et", (endTime == null || endTime.isBlank()) ? null : endTime);

        // 바뀐 뒤의 값으로 앞뒤를 검사한다 — 한쪽만 보내와도 맞물려야 한다
        Map<String, Object> chk = this.sqlRunner.getRow("""
                SELECT COALESCE(CAST(:st AS timestamp), "StartTime") AS s
                     , COALESCE(CAST(:et AS timestamp), "EndTime")   AS e
                  FROM mat_produce WHERE id = :mpId
                """, p);
        if (chk == null) {
            r.success = false;
            r.message = "차수를 찾을 수 없습니다.";
            return r;
        }
        Object s0 = chk.get("s"), e0 = chk.get("e");
        if (s0 != null && e0 != null && s0 instanceof java.sql.Timestamp
                && e0 instanceof java.sql.Timestamp
                && ((java.sql.Timestamp) s0).after((java.sql.Timestamp) e0)) {
            r.success = false;
            r.message = "시작이 완료보다 늦을 수 없습니다.";
            return r;
        }

        p.addValue("userId", user == null ? null : user.getId());
        this.sqlRunner.execute("""
                UPDATE mat_produce
                   SET "StartTime" = COALESCE(CAST(:st AS timestamp), "StartTime")
                     , "EndTime"   = COALESCE(CAST(:et AS timestamp), "EndTime")
                     , "_modified" = now(), "_modifier_id" = :userId
                 WHERE id = :mpId
                """, p);

        r.success = true;
        return r;
    }

    /** 투입 초안 저장. 화면이 bom_json 을 보내는 지점(작업시작·완료)에서 부른다. */
    public void saveDraftBom(Integer mpId, String bomJson, User user) {
        if (mpId == null) return;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("mpId", mpId)
                .addValue("json", (bomJson == null || bomJson.isBlank()) ? null : bomJson)
                .addValue("userId", user == null ? null : user.getId());
        /* 빈 값이면 기존 초안을 지우지 않는다.
           화면이 자재 편집 없이 수량만 고쳐 보낼 수 있기 때문이다. */
        this.sqlRunner.execute("""
                UPDATE mat_produce
                   SET "DraftBomJson" = COALESCE(:json, "DraftBomJson"),
                       "_modified" = now(), "_modifier_id" = :userId
                 WHERE id = :mpId
                """, p);
    }

    private List<Map<String, Object>> getBomDefaultRaw(Integer materialId, Float qty,
                                                       Integer cleanStore, String prodDate) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("materialId", materialId);
        p.addValue("qty", qty == null ? 0f : qty);
        p.addValue("cleanStore", cleanStore == null ? 5 : cleanStore);
        p.addValue("prodDate", (prodDate == null || prodDate.isBlank())
                ? LocalDate.now().toString() : prodDate);
        String sql = """
                SELECT bc."Material_id"                        AS mat_id
                     , m."Code"                                AS mat_code
                     , m."Name"                                AS mat_name
                     , u."Name"                                AS unit
                     , bc."Amount" / b."OutputAmount"          AS per
                     , CEIL(bc."Amount" / b."OutputAmount" * :qty) AS default_qty
                     , COALESCE(src.stock, 0)                  AS stock
                     , COALESCE(m."LotUseYN",'N')              AS lot_use_yn
                     , mg."MaterialType"                       AS mat_type
                     , (CASE WHEN COALESCE(m."SterilizationYN",'N')='Y'  THEN 18
                             WHEN mg."MaterialType" IN ('semi','product') THEN 5
                             WHEN COALESCE(m."WashYN",'N')='Y'           THEN 5
                             ELSE 17 END)                      AS src_store
                     , sh."Name"                               AS src_store_name
                     -- 다음에 소진될 로트(FIFO 앞줄 3건) + 세척일자.
                     -- ★ 실제 배분이 아니라 예고다. getBomDefault 는 qty=1 로 불려
                     --   소요량을 모르므로 "얼마를 어디서" 는 알 수 없다.
                     --   차수를 완료해야 mat_lot_cons 에 실제 차감이 남는다(getConsumedInputs).
                     , nx.next_lots                            AS next_lots
                  FROM bom b
                  JOIN bom_comp bc ON bc."BOM_id" = b.id
                  JOIN material m  ON m.id = bc."Material_id"
                  LEFT JOIN unit u ON u.id = m."Unit_id"
                  LEFT JOIN mat_grp mg ON mg.id = m."MaterialGroup_id"
                  LEFT JOIN LATERAL (
                        SELECT SUM(ml."CurrentStock") AS stock FROM mat_lot ml
                         WHERE ml."Material_id" = bc."Material_id"
                           AND ml."StoreHouse_id" = (CASE WHEN COALESCE(m."SterilizationYN",'N')='Y'  THEN 18
                                                          WHEN mg."MaterialType" IN ('semi','product') THEN 5
                                                          WHEN COALESCE(m."WashYN",'N')='Y'           THEN 5
                                                          ELSE 17 END)
                  ) src ON true
                  -- FIFO 앞줄 로트 미리보기. 정렬은 실제 소비(consumeBomForChasu)와 같아야
                  -- 예고와 결과가 어긋나지 않는다.
                  LEFT JOIN LATERAL (
                        SELECT string_agg(t.label, ', ' ORDER BY t.rn) AS next_lots
                          FROM (
                            SELECT ROW_NUMBER() OVER (ORDER BY ml."InputDateTime" ASC, ml.id ASC) AS rn
                                 -- ★ 잔량을 반드시 붙인다.
                                 --   로트 하나를 여러 세션에 나눠 세척하면 같은 LotNumber 로
                                 --   mat_lot 행이 여러 개 생긴다. 세션마다 세척시각도 유효기한도
                                 --   다르니 이건 정상이고, 합쳐서도 안 된다.
                                 --   번호만 찍으면 화면에 같은 줄이 두 번 나온 것처럼 보여
                                 --   작업자가 중복 등록으로 오해한다. 잔량이 붙으면
                                 --   「같은 게 둘」이 아니라 「50개짜리가 둘」로 읽힌다.
                                 --   같은 날 두 세션이면 날짜까지 같아서 잔량만이 유일한 구분자다.
                                 , ml."LotNumber"
                                   || COALESCE(' (' || to_char(ww."WashDate", 'MM/DD') || ' 세척)', '')
                                   || ' ' || COALESCE(ml."CurrentStock",0)::int || '개'
                                   AS label
                              FROM mat_lot ml
                              -- ★ "SourceTableName" 조건 필수. "SourceDataPk" 는 테이블마다
                              --   재사용되는 값이라, 빼면 불출 로트(mat_move_scan)가 엉뚱한
                              --   세척 건에 붙는다.
                              LEFT JOIN wash_work_item wi ON ml."SourceTableName" = 'wash_work_item'
                                                         AND wi.id = ml."SourceDataPk"
                              LEFT JOIN wash_work ww ON ww.id = wi."WashWork_id"
                             WHERE ml."Material_id" = bc."Material_id"
                               AND ml."StoreHouse_id" = (CASE WHEN COALESCE(m."SterilizationYN",'N')='Y'  THEN 18
                                                              WHEN mg."MaterialType" IN ('semi','product') THEN 5
                                                              WHEN COALESCE(m."WashYN",'N')='Y'           THEN 5
                                                              ELSE 17 END)
                               AND COALESCE(ml."CurrentStock",0) > 0
                               AND COALESCE(ml._status,'a') = 'a'
                             ORDER BY ml."InputDateTime" ASC, ml.id ASC
                             LIMIT 3
                          ) t
                  ) nx ON true
                  LEFT JOIN store_house sh ON sh.id = (CASE WHEN COALESCE(m."SterilizationYN",'N')='Y'  THEN 18
                                                            WHEN mg."MaterialType" IN ('semi','product') THEN 5
                                                            WHEN COALESCE(m."WashYN",'N')='Y'           THEN 5
                                                            ELSE 17 END)
                 WHERE b."Material_id" = :materialId
                   AND b."BOMType" = 'manufacturing'
                   AND CAST(:prodDate AS date)
                       BETWEEN COALESCE(b."StartDate", DATE '0001-01-01')
                           AND COALESCE(b."EndDate",   DATE '9999-12-31')
                 ORDER BY bc.id
                """;
        return this.sqlRunner.getRows(sql, p);
    }

    /**
     * 완료된 차수(mp)가 실제 소비한 투입자재 — mat_lot_cons(로트별 차감) 를 자재별로 집계.
     * 완료 후 화면에서 '차감된 투입자재'를 보여주기 위한 조회(읽기전용).
     */
    public List<Map<String, Object>> getConsumedInputs(Integer mpId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("mpId", mpId);
        String sql = """
                SELECT m.id                                   AS mat_id
                     , m."Code"                                AS mat_code
                     , m."Name"                                AS mat_name
                     , u."Name"                                AS unit
                     , mg."MaterialType"                       AS mat_type
                     , SUM(COALESCE(mlc."OutputQty",0))        AS consumed_qty
                     , string_agg(DISTINCT ml."LotNumber", ', ') AS lots
                     -- 로트 + 세척일자. 세척을 안 거친 자재(구매품)는 로트만 나온다.
                     , string_agg(DISTINCT
                           ml."LotNumber"
                           || COALESCE(' (' || to_char(ww."WashDate", 'MM/DD') || ' 세척)', '')
                       , ', ')                                  AS lots_wash
                     , (array_agg(sh."Name"))[1]               AS store
                  FROM mat_lot_cons mlc
                  JOIN mat_lot ml ON ml.id = mlc."MaterialLot_id"
                  JOIN material m ON m.id = ml."Material_id"
                  LEFT JOIN unit u ON u.id = m."Unit_id"
                  LEFT JOIN mat_grp mg ON mg.id = m."MaterialGroup_id"
                  LEFT JOIN store_house sh ON sh.id = ml."StoreHouse_id"
                  -- ★ "SourceTableName" 조건을 반드시 함께 건다.
                  --   "SourceDataPk" 는 테이블마다 재사용되는 값이라, 조건 없이 조인하면
                  --   불출로 생긴 로트(mat_move_scan)가 엉뚱한 세척 건에 붙는다.
                  LEFT JOIN wash_work_item wi ON ml."SourceTableName" = 'wash_work_item'
                                             AND wi.id = ml."SourceDataPk"
                  LEFT JOIN wash_work ww ON ww.id = wi."WashWork_id"
                  /* ★ 정렬을 BOM 등록순(bc.id)에 맞춘다.
                     작업중은 getBomDefault 가 ORDER BY bc.id 로 내려주는데
                     여기만 품목코드순이면, 완료를 누르는 순간 자재 줄 순서가
                     뒤바뀌어 작업자가 다른 화면을 본 것처럼 느낀다.
                     BOM 에 없는 추가 투입분은 ord 가 NULL → 맨 뒤로 보낸다. */
                  LEFT JOIN LATERAL (
                        SELECT MIN(bc.id) AS ord
                          FROM mat_produce mp
                          JOIN bom b       ON b."Material_id" = mp."Material_id"
                                          AND b."BOMType" = 'manufacturing'
                          JOIN bom_comp bc ON bc."BOM_id" = b.id
                                          AND bc."Material_id" = m.id
                         WHERE mp.id = :mpId
                  ) bo ON true
                 WHERE mlc."SourceTableName" = 'mat_produce'
                   AND mlc."SourceDataPk" = :mpId
                 GROUP BY m.id, m."Code", m."Name", u."Name", mg."MaterialType", bo.ord
                 ORDER BY bo.ord NULLS LAST, m."Code"
                """;
        return this.sqlRunner.getRows(sql, p);
    }

    /**
     * 특정 자재의 창고(클린룸) 재고 로트 — FIFO 순서(InputDateTime ASC, id ASC).
     * 용기 반제품 로트 선택/표시용. 소비 순서(reserveInput·consumeBomForChasu)와 동일 정렬.
     */
    public List<Map<String, Object>> getMaterialLots(Integer materialId, Integer storeId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("materialId", materialId);
        p.addValue("storeId", storeId == null ? 5 : storeId);
        String sql = """
                SELECT ml.id                                   AS lot_id
                     , ml."LotNumber"                          AS lot_no
                     , m."Name"                                AS mat_name
                     , m."Code"                                AS mat_code
                     , COALESCE(ml."CurrentStock",0)           AS avail
                     , sh."Name"                               AS warehouse
                     , to_char(ml."InputDateTime", 'yyyy-mm-dd') AS in_date
                     -- 이 로트가 어느 세척 건에서 나왔는지. 같은 로트번호가 여러 줄일 때
                     -- 무엇이 잡혔는지 작업자가 확인할 수 있어야 한다.
                     , to_char(ww."WashDate", 'yyyy-mm-dd')     AS wash_date
                     , wp."Name"                                AS washer
                     , wi.id                                    AS wash_item_id
                  FROM mat_lot ml
                  JOIN material m ON m.id = ml."Material_id"
                  LEFT JOIN store_house sh ON sh.id = ml."StoreHouse_id"
                  -- ★ "SourceTableName" 조건 필수 (위 getConsumedInputs 주석 참고)
                  LEFT JOIN wash_work_item wi ON ml."SourceTableName" = 'wash_work_item'
                                             AND wi.id = ml."SourceDataPk"
                  LEFT JOIN wash_work ww ON ww.id = wi."WashWork_id"
                  LEFT JOIN person wp ON wp.id = ww."Actor_id"
                 WHERE ml."Material_id" = :materialId
                   AND ml."StoreHouse_id" = :storeId
                   AND COALESCE(ml."CurrentStock",0) > 0
                 ORDER BY ml."InputDateTime" ASC, ml.id ASC
                 LIMIT 100
                """;
        return this.sqlRunner.getRows(sql, p);
    }

    /**
     * 작업지시 큐(WO-우선 공정 진입 화면용) — 작지 1건 = PK 1개.
     * 완제품 작지 전개로 생성된 PK 자식 작지들을 진행률과 함께 개별 카드로 내려준다.
     * getWorkOrders 는 품목별 집계였지만, 이건 job_res 단위(개별 WO) 로 편다.
     */
    public List<Map<String, Object>> getWorkOrderQueue(Integer processId, String spjangcd,
                                                       String dateFrom, String dateTo) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("processId", processId);
        p.addValue("spjangcd", spjangcd);
        p.addValue("dateFrom", (dateFrom == null || dateFrom.isBlank()) ? null : LocalDate.parse(dateFrom));
        p.addValue("dateTo",   (dateTo   == null || dateTo.isBlank())   ? null : LocalDate.parse(dateTo));
        String sql = """
                SELECT jr.id                                   AS job_res_id
                     , jr."WorkOrderNumber"                    AS order_num
                     , jr."Material_id"                        AS mat_id
                     , m."Code"                                AS mat_code
                     , m."Name"                                AS mat_name
                     , u."Name"                                AS unit
                     , to_char(jr."ProductionDate", 'yyyy-mm-dd') AS plan_date
                     , COALESCE(jr."OrderQty",0)               AS plan_qty
                     , COALESCE(prod.good_qty, 0)              AS good_qty
                     , COALESCE(prod.chasu_cnt, 0)             AS chasu_cnt
                     , COALESCE(prod.crew_cnt, 0)              AS crew_cnt
                     , COALESCE(prod.done_cnt, 0)              AS done_cnt
                     , COALESCE(prod.working_cnt, 0)           AS working_cnt
                     , CASE WHEN COALESCE(prod.chasu_cnt,0) = 0 THEN 'wait'
                            WHEN COALESCE(prod.good_qty,0) >= COALESCE(jr."OrderQty",0)
                                 AND COALESCE(jr."OrderQty",0) > 0 THEN 'done'
                            ELSE 'working' END                 AS state
                  FROM job_res jr
                  JOIN work_center wc ON wc.id = jr."WorkCenter_id"
                  LEFT JOIN material m ON m.id = jr."Material_id"
                  LEFT JOIN unit u ON u.id = m."Unit_id"
                  LEFT JOIN LATERAL (
                        SELECT SUM(mp."GoodQty") FILTER (WHERE mp."State"='finished') AS good_qty
                             , COUNT(*)                                               AS chasu_cnt
                             , COUNT(*) FILTER (WHERE mp."State"='finished')          AS done_cnt
                             , COUNT(*) FILTER (WHERE mp."State"='working')           AS working_cnt
                             , COUNT(DISTINCT (mp."ShiftCode", mp."Actor_id"))        AS crew_cnt
                          FROM mat_produce mp
                         WHERE mp."JobResponse_id" = jr.id
                           AND COALESCE(mp."_status",'a') = 'a'
                  ) prod ON true
                 WHERE jr.spjangcd = :spjangcd
                   AND wc."Process_id" = :processId
                   AND jr."State" IN ('ordered','working','finished')
                   /* ★ 뒤 공정이 이미 끝났으면 이 작지는 큐에서 감춘다.
                        수주 품목은 라우팅대로 조립·블리스터·융착·포장 작지가 한꺼번에 깔리는데,
                        앞 공정은 기존 재고로 대체하고 포장부터 하는 경우가 흔하다.
                        그러면 조립 작지가 영영 미완으로 남아 큐를 채운다.

                        삭제하지 않는 이유는 consumePrevWipForChasu 가
                        (Parent_id, WorkIndex-1) 로 전 공정을 찾기 때문이다 —
                        중간 작지를 지우면 다음 공정이 전 공정을 못 찾는다.
                        양품 0 으로 완료 처리하지 않는 이유는, 실적 화면이
                        「지시 20 · 생산 0 · 완료」를 그대로 보고해
                        불량으로 버린 것인지 재고로 대체한 것인지 구분이 사라지기 때문이다.
                        데이터는 사실 그대로 두고 보이는 것만 줄인다.

                      ★ 형제 작지(같은 Parent_id)이면서 WorkIndex 가 더 큰 것만 본다.
                        Parent_id 가 없는 자체 재고 생산지시는 라우팅이 없어
                        이 조건에 걸리지 않는다 — 그건 그 품목 하나만 만드는 지시다. */
                   AND NOT EXISTS (
                         SELECT 1 FROM job_res nx
                          WHERE jr."Parent_id" IS NOT NULL
                            AND nx."Parent_id" = jr."Parent_id"
                            AND nx."WorkIndex" > jr."WorkIndex"
                            AND nx."State" = 'finished'
                   )
                   AND (CAST(:dateFrom AS date) IS NULL OR jr."ProductionDate"::date >= CAST(:dateFrom AS date))
                   AND (CAST(:dateTo   AS date) IS NULL OR jr."ProductionDate"::date <= CAST(:dateTo   AS date))
                 ORDER BY jr."ProductionDate" DESC, jr."WorkOrderNumber" DESC
                 LIMIT 200
                """;
        return this.sqlRunner.getRows(sql, p);
    }

    // =====================================================================
    // 쓰기 — 용기(mat_produce) 상태 전이. (item_add·item_finish 는 ProductionCreateService)
    //   세척과 동일 저장 시점: 담기(wait) → 시작(working) → 완료(finished).
    // =====================================================================

    /** 수량 중간저장(완료 전). 세척 itemSave 대응. */
    @Transactional
    public AjaxResult itemSave(Integer mpId, Float qty, Float defectQty, User user) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("mpId", mpId);
        p.addValue("qty", qty == null ? 0f : qty);
        p.addValue("defectQty", defectQty == null ? 0f : defectQty);
        p.addValue("userId", user.getId());
        this.sqlRunner.execute("""
            UPDATE mat_produce
               SET "GoodQty"=:qty, "DefectQty"=:defectQty,
                   "State"=CASE WHEN "State"='wait' AND :qty > 0 THEN 'working' ELSE "State" END,
                   "StartTime"=COALESCE("StartTime", CASE WHEN :qty > 0 THEN LOCALTIMESTAMP ELSE NULL END),
                   "_modified"=now(), "_modifier_id"=:userId
             WHERE id=:mpId AND "State"<>'finished'
            """, p);
        AjaxResult r = new AjaxResult(); r.success = true; return r;
    }

    /** 작업시작 = wait→working (수량 함께 저장). 재고 이동은 완료에서. 세척 itemStart 대응. */
    @Transactional
    public AjaxResult itemStart(Integer mpId, Float qty, Float defectQty, User user) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("mpId", mpId);
        p.addValue("qty", qty);
        p.addValue("defectQty", defectQty);
        p.addValue("userId", user.getId());
        this.sqlRunner.execute("""
            UPDATE mat_produce
               SET "State"='working',
                   "StartTime"=COALESCE("StartTime", LOCALTIMESTAMP),
                   "GoodQty"=COALESCE(CAST(:qty AS DOUBLE PRECISION), "GoodQty"),
                   "DefectQty"=COALESCE(CAST(:defectQty AS DOUBLE PRECISION), "DefectQty"),
                   "_modified"=now(), "_modifier_id"=:userId
             WHERE id=:mpId AND "State"<>'finished'
            """, p);
        AjaxResult r = new AjaxResult(); r.success = true; return r;
    }

    /** 용기 삭제(완료 전만). member 도 함께 소프트삭제. 세척 itemDelete 대응. */
    @Transactional
    public AjaxResult itemDelete(Integer mpId, User user) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("mpId", mpId);
        p.addValue("userId", user.getId());
        Map<String, Object> it = this.sqlRunner.getRow(
                "SELECT mp.\"State\" AS state, mp.\"JobResponse_id\" AS jr_id, mp.\"Material_id\" AS mat_id "
                        + "FROM mat_produce mp WHERE mp.id=:mpId", p);
        AjaxResult r = new AjaxResult(); r.success = true;
        if (it == null) { r.success = false; r.message = "용기를 찾을 수 없습니다."; return r; }
        // 완료(재고 실제 차감됨)만 차단 — wait/working 은 삭제 허용
        if ("finished".equals(it.get("state"))) {
            r.success = false; r.message = "완료된 용기는 삭제할 수 없습니다. 완료취소 후 삭제하세요."; return r;
        }

        // working 이면 작업시작 때 잡아둔 예약(mat_proc_input RequestQty)이 있을 수 있으므로 함께 정리.
        //   재고는 아직 안 움직였으니(완료 전) 예약 행만 지우면 됨.
        Integer jrId = (it.get("jr_id") != null) ? ((Number) it.get("jr_id")).intValue() : null;
        Integer matId = (it.get("mat_id") != null) ? ((Number) it.get("mat_id")).intValue() : null;
        if (jrId != null) {
            MapSqlParameterSource dp = new MapSqlParameterSource();
            dp.addValue("jrId", jrId);
            // 이 작지(차수)의 예약분 삭제. 한 작지에 한 차수만 있는 조립 특성상 작지 단위로 정리.
            this.sqlRunner.execute("""
                DELETE FROM mat_proc_input
                 WHERE "MaterialProcessInputRequest_id" = (
                        SELECT "MaterialProcessInputRequest_id" FROM job_res WHERE id=:jrId)
                """, dp);
        }

        this.sqlRunner.execute("DELETE FROM mat_produce_member WHERE \"MatProduce_id\"=:mpId", p);
        this.sqlRunner.execute("""
            UPDATE mat_produce SET "_status"='d', "_modified"=now(), "_modifier_id"=:userId WHERE id=:mpId
            """, p);
        // 지운 차수의 수량이 작지 합계에 남지 않도록 롤업
        if (jrId != null) this.productionCreateService.recalcJobRes(jrId, user);
        return r;
    }
}