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
 * M-CELL 조립 서비스 (2공장, 공정 mc01 / 워크센터 52).
 *
 * 1공장(ProductionWorkService)과 축이 다르다.
 *   1공장 : 날짜 → 작업조(날짜×교대×작업자 파생) → 용기 차수
 *   2공장 : 작업지시 → 유닛(1대=1로트) → BOM 계층 스텝
 *
 * 핵심 규칙
 *   - 작지 OrderQty = N  →  mcell_unit N행 (1대 = 1로트)
 *   - 유닛마다 BOM 트리를 재귀 전개해 mcell_unit_step 을 미리 깔아둔다
 *       · 자식 BOM(manufacturing)을 가진 품목 = 어셈블리 = 스텝
 *       · 자식 BOM이 없는 품목               = 원자재 = 그 스텝의 투입자재
 *   - 스텝 완료 1건 = ProductionCreateService.startProduction + finishProduction (goodQty=1)
 *       → mat_produce 1건 + mat_lot 1건(모듈 로트) + BOM FIFO 차감
 *   - 하위 스텝이 전부 done 이어야 상위 스텝 활성 (계층 잠금)
 *   - Source='stock' : 조립하지 않고 창고 재고 로트를 그대로 투입 → 하위 스텝 전체 스킵
 *   - 최상위(Depth=0) 스텝 완료 → 유닛 = 검사대기
 *
 * 실적 생성은 전부 ProductionCreateService 에 위임한다(1공장과 동일 알맹이).
 */
@Service
public class McellAssemblyService {

    @Autowired SqlRunner sqlRunner;
    @Autowired ProductionCreateService productionCreateService;

    /** 2공장 기본 창고 */
    public static final int STORE_PROD    = 17;   // 생산창고 — 조립 산출/투입
    public static final int STORE_INSPECT = 19;   // 검사완료창고
    public static final int WC_ASSEMBLY   = 52;   // 조립(2공장) 워크센터

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    // =====================================================================
    // 조회
    // =====================================================================

    /** 공정 컨텍스트 — process_code(mc01) → 워크센터/산출창고 */
    public Map<String, Object> getContext(String processCode, Integer factoryId) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("processCode", processCode)
                .addValue("factoryId", factoryId);
        return this.sqlRunner.getRow("""
                SELECT p.id AS process_id, p."Code" AS process_code, p."Name" AS process_name,
                       wc.id AS workcenter_id, wc."Name" AS workcenter_name,
                       wc."ProcessStoreHouse_id" AS out_store_id, wc."Factory_id" AS factory_id
                  FROM process p
                  LEFT JOIN work_center wc ON wc."Process_id" = p.id
                        AND (CAST(:factoryId AS INTEGER) IS NULL OR wc."Factory_id" = CAST(:factoryId AS INTEGER))
                 WHERE p."Code" = :processCode
                 ORDER BY wc.id
                 LIMIT 1
                """, p);
    }

    /**
     * A화면 — 작업지시(생산계획) 큐.
     * 유닛이 아직 생성 전이면 unit_cnt=0, 계획수량은 job_res.OrderQty.
     */
    public List<Map<String, Object>> getWoQueue(Integer processId, String spjangcd,
                                                String dateFrom, String dateTo) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("processId", processId)
                .addValue("spjangcd", spjangcd)
                .addValue("dateFrom", (dateFrom == null || dateFrom.isBlank()) ? null : LocalDate.parse(dateFrom))
                .addValue("dateTo",   (dateTo   == null || dateTo.isBlank())   ? null : LocalDate.parse(dateTo));
        return this.sqlRunner.getRows("""
                SELECT jr.id                                      AS job_res_id
                     , jr."WorkOrderNumber"                       AS order_num
                     , jr."Material_id"                           AS mat_id
                     , m."Code"                                   AS mat_code
                     , m."Name"                                   AS mat_name
                     , to_char(jr."ProductionDate", 'yyyy-mm-dd') AS plan_date
                     , COALESCE(jr."OrderQty", 0)                 AS plan_qty
                     , COALESCE(u.unit_cnt,    0)                 AS unit_cnt
                     , COALESCE(u.done_cnt,    0)                 AS done_cnt
                     , COALESCE(u.working_cnt, 0)                 AS working_cnt
                     , COALESCE(u.reject_cnt,  0)                 AS reject_cnt
                     , CASE WHEN COALESCE(u.done_cnt,0) >= COALESCE(jr."OrderQty",0)
                                 AND COALESCE(jr."OrderQty",0) > 0            THEN 'done'
                            WHEN COALESCE(u.working_cnt,0) + COALESCE(u.done_cnt,0) > 0 THEN 'working'
                            ELSE 'wait' END                       AS state
                  FROM job_res jr
                  JOIN work_center wc ON wc.id = jr."WorkCenter_id"
                  LEFT JOIN material m ON m.id = jr."Material_id"
                  LEFT JOIN LATERAL (
                        SELECT COUNT(*)                                                          AS unit_cnt
                             , COUNT(*) FILTER (WHERE mu."State" IN ('inspect_wait','pass','packed')) AS done_cnt
                             , COUNT(*) FILTER (WHERE mu."State" = 'assembling')                 AS working_cnt
                             , COUNT(*) FILTER (WHERE mu."State" = 'reject')                     AS reject_cnt
                          FROM mcell_unit mu
                         WHERE mu."JobResponse_id" = jr.id AND COALESCE(mu."_status",'a') = 'a'
                  ) u ON true
                 WHERE jr.spjangcd = :spjangcd
                   AND wc."Process_id" = :processId
                   AND jr."State" IN ('ordered','working','finished')
                   -- ★ 유닛 작지(검사 대상 = 시리얼 부여 품목) + 단독 지시(자체재고 모듈)만 노출.
                   --    완제품 전개로 딸려 생긴 하위 모듈 작지는 카드로 띄우지 않는다.
                   --    (그 모듈들은 유닛 안의 '스텝'으로 표시되고, 실적은 이 작지에 붙는다)
                   AND (COALESCE(m."InspectYN",'N') = 'Y' OR jr."Parent_id" IS NULL)
                   AND (CAST(:dateFrom AS date) IS NULL OR jr."ProductionDate"::date >= CAST(:dateFrom AS date))
                   AND (CAST(:dateTo   AS date) IS NULL OR jr."ProductionDate"::date <= CAST(:dateTo   AS date))
                 ORDER BY jr."ProductionDate" DESC, jr."WorkOrderNumber" DESC
                 LIMIT 200
                """, p);
    }

    /** B화면 — 유닛 목록 */
    public List<Map<String, Object>> getUnitList(Integer jobResId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("jrId", jobResId);
        return this.sqlRunner.getRows("""
                SELECT mu.id                                        AS unit_id
                     , mu."UnitNo"                                  AS unit_no
                     , mu."LotNumber"                               AS lot_number
                     , mu."State"                                   AS state
                     , to_char(mu."StartTime", 'yyyy-mm-dd hh24:mi') AS start_time
                     , to_char(mu."EndTime",   'yyyy-mm-dd hh24:mi') AS end_time
                     , mu."RejectReason"                            AS reject_reason
                     , mu."RejectInspNo"                            AS reject_insp_no
                     , to_char(mu."RejectAt", 'yyyy-mm-dd hh24:mi') AS reject_at
                     , COALESCE(s.total, 0)                         AS step_total
                     , COALESCE(s.done,  0)                         AS step_done
                  FROM mcell_unit mu
                  LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE st."State"='done') AS done
                          FROM mcell_unit_step st
                         WHERE st."McellUnit_id" = mu.id
                           AND COALESCE(st."_status",'a') = 'a'
                  ) s ON true
                 WHERE mu."JobResponse_id" = :jrId AND COALESCE(mu."_status",'a') = 'a'
                 ORDER BY mu."UnitNo"
                """, p);
    }

    /**
     * C화면 — 유닛의 스텝 트리.
     * 화면 순서 = 깊은 레벨(하위 모듈)부터 → 최상위. 목업 buildSteps() 의 post-order 와 동일.
     * locked / skipped 는 여기서 계산해 내려준다.
     */
    public List<Map<String, Object>> getStepList(Integer unitId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("unitId", unitId);
        List<Map<String, Object>> rows = this.sqlRunner.getRows("""
                SELECT st.id                                        AS step_id
                     , st."Material_id"                             AS mat_id
                     , m."Code"                                     AS mat_code
                     , m."Name"                                     AS mat_name
                     , st."ParentMaterial_id"                       AS parent_mat_id
                     , st."Depth"                                   AS depth
                     , st."SeqNo"                                   AS seq_no
                     , st."Source"                                  AS source
                     , st."StockMatLot_id"                          AS stock_lot_id
                     , ml."LotNumber"                               AS stock_lot_no
                     , st."MatProduce_id"                           AS mp_id
                     , st."LotNumber"                               AS lot_number
                     , st."State"                                   AS state
                     , st."ReworkYN"                                AS rework_yn
                     , st."Actor_id"                                AS actor_id
                     , pr."Name"                                    AS actor_name
                     , st."Equipment_id"                            AS equipment_id
                     , eq."Name"                                    AS equipment_name
                     , to_char(st."StartTime", 'yyyy-mm-dd hh24:mi') AS start_time
                     , to_char(st."EndTime",   'yyyy-mm-dd hh24:mi') AS end_time
                     , COALESCE(bm.mat_cnt, 0)                      AS bom_cnt
                  FROM mcell_unit_step st
                  JOIN material m ON m.id = st."Material_id"
                  LEFT JOIN mat_lot  ml ON ml.id = st."StockMatLot_id"
                  LEFT JOIN person   pr ON pr.id = st."Actor_id"
                  LEFT JOIN equ eq ON eq.id = st."Equipment_id"
                  LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS mat_cnt
                          FROM bom b JOIN bom_comp bc ON bc."BOM_id" = b.id
                         WHERE b."Material_id" = st."Material_id" AND b."BOMType"='manufacturing'
                           AND NOT EXISTS (SELECT 1 FROM bom b2
                                            WHERE b2."Material_id" = bc."Material_id"
                                              AND b2."BOMType"='manufacturing')
                  ) bm ON true
                 WHERE st."McellUnit_id" = :unitId AND COALESCE(st."_status",'a') = 'a'
                 ORDER BY st."Depth" DESC, st."SeqNo", st.id
                """, p);

        // ── 계층 관계 계산 (자식 목록 / 잠금 / 재고투입 조상에 의한 스킵) ──
        Map<Integer, Map<String, Object>> byMat = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) byMat.put(asInt(r.get("mat_id")), r);

        Map<Integer, List<Integer>> kids = new HashMap<>();
        for (Map<String, Object> r : rows) {
            Integer pm = asInt(r.get("parent_mat_id"));
            if (pm != null) kids.computeIfAbsent(pm, k -> new ArrayList<>()).add(asInt(r.get("mat_id")));
        }

        for (Map<String, Object> r : rows) {
            Integer mid = asInt(r.get("mat_id"));
            List<Integer> ks = kids.getOrDefault(mid, List.of());
            r.put("kid_mat_ids", ks);
            r.put("kid_cnt", ks.size());

            // 재고 투입한 조상이 있으면 이 스텝은 스킵(화면에서 '대체됨' 표시)
            boolean skipped = false;
            Integer pm = asInt(r.get("parent_mat_id"));
            while (pm != null) {
                Map<String, Object> ps = byMat.get(pm);
                if (ps == null) break;
                if ("stock".equals(ps.get("source"))) { skipped = true; break; }
                pm = asInt(ps.get("parent_mat_id"));
            }
            r.put("skipped", skipped);

            // 잠금 : 재고 투입 스텝은 하위 불필요 → 해제.
            //        그 외엔 자식이 전부 done (또는 재고 로트 지정 완료) 이어야 활성.
            boolean locked = false;
            if (!"stock".equals(r.get("source"))) {
                for (Integer k : ks) {
                    Map<String, Object> cs = byMat.get(k);
                    if (cs == null) continue;
                    boolean satisfied = "done".equals(cs.get("state"))
                            || ("stock".equals(cs.get("source")) && cs.get("stock_lot_id") != null);
                    if (!satisfied) { locked = true; break; }
                }
            }
            r.put("locked", locked);
        }
        return rows;
    }

    /**
     * 스텝의 투입자재 기본값 — 그 어셈블리 BOM 중 '자식 BOM이 없는 것(원자재)' 만.
     * 자식 BOM을 가진 것은 하위 스텝이 만들어 오므로 별도 처리(아래 sub 항목).
     * 2공장 소스창고는 InspectYN 으로 갈린다(Y=검사완료 19 / N=생산 17).
     */
    public List<Map<String, Object>> getStepMaterials(Integer stepId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("stepId", stepId);
        return this.sqlRunner.getRows("""
                WITH s AS (SELECT "Material_id" AS mat_id, "McellUnit_id" AS unit_id
                             FROM mcell_unit_step WHERE id = :stepId)
                SELECT bc."Material_id"                       AS mat_id
                     , m."Code"                               AS mat_code
                     , m."Name"                               AS mat_name
                     , un."Name"                              AS unit
                     , bc."Amount" / NULLIF(b."OutputAmount",0) AS per
                     , CEIL(bc."Amount" / NULLIF(b."OutputAmount",0)) AS default_qty
                     , (CASE WHEN COALESCE(m."InspectYN",'N')='Y' THEN 19 ELSE 17 END) AS src_store
                     , sh."Name"                              AS src_store_name
                     , COALESCE(stk.stock, 0)                 AS stock
                     , (CASE WHEN EXISTS (SELECT 1 FROM bom b2
                                           WHERE b2."Material_id" = bc."Material_id"
                                             AND b2."BOMType"='manufacturing')
                             THEN 'Y' ELSE 'N' END)           AS is_assembly
                  FROM s
                  JOIN bom b       ON b."Material_id" = s.mat_id AND b."BOMType" = 'manufacturing'
                  JOIN bom_comp bc ON bc."BOM_id" = b.id
                  JOIN material m  ON m.id = bc."Material_id"
                  LEFT JOIN unit un ON un.id = m."Unit_id"
                  LEFT JOIN store_house sh
                         ON sh.id = (CASE WHEN COALESCE(m."InspectYN",'N')='Y' THEN 19 ELSE 17 END)
                  LEFT JOIN LATERAL (
                        SELECT SUM(ml."CurrentStock") AS stock FROM mat_lot ml
                         WHERE ml."Material_id" = bc."Material_id"
                           AND ml."StoreHouse_id" = (CASE WHEN COALESCE(m."InspectYN",'N')='Y' THEN 19 ELSE 17 END)
                  ) stk ON true
                 ORDER BY is_assembly DESC, bc.id
                """, p);
    }

    /** 재고 재공품 로트 (재고 투입 토글용). 오래된 것부터 = FIFO 권장 순. */
    public List<Map<String, Object>> getWipLots(Integer materialId, Integer storeId) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("matId", materialId)
                .addValue("store", storeId == null ? STORE_PROD : storeId);
        return this.sqlRunner.getRows("""
                SELECT ml.id AS mat_lot_id, ml."LotNumber" AS lot_number,
                       ml."CurrentStock" AS stock,
                       to_char(ml."InputDateTime", 'yyyy-mm-dd hh24:mi') AS input_time
                  FROM mat_lot ml
                 WHERE ml."Material_id" = :matId AND ml."StoreHouse_id" = :store
                   AND COALESCE(ml."CurrentStock",0) > 0
                 ORDER BY ml."InputDateTime" ASC, ml.id ASC
                """, p);
    }

    /** 자재 추가 시트 — 생산창고 재고 목록 */
    public List<Map<String, Object>> getStockList(Integer storeId, String keyword) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("store", storeId == null ? STORE_PROD : storeId)
                .addValue("kw", (keyword == null || keyword.isBlank()) ? null : "%" + keyword.trim() + "%");
        return this.sqlRunner.getRows("""
                SELECT m.id AS mat_id, m."Code" AS mat_code, m."Name" AS mat_name,
                       un."Name" AS unit, COALESCE(SUM(ml."CurrentStock"),0) AS stock
                  FROM material m
                  LEFT JOIN unit un ON un.id = m."Unit_id"
                  LEFT JOIN mat_lot ml ON ml."Material_id" = m.id AND ml."StoreHouse_id" = :store
                 WHERE COALESCE(m."Factory_id", 0) = 2
                   AND (CAST(:kw AS varchar) IS NULL OR m."Code" ILIKE :kw OR m."Name" ILIKE :kw)
                 GROUP BY m.id, m."Code", m."Name", un."Name"
                 ORDER BY m."Code"
                 LIMIT 300
                """, p);
    }

    /** 작업자 목록 (작업 시작 시트) */
    public List<Map<String, Object>> getWorkers() {
        return this.sqlRunner.getRows("""
                SELECT p.id AS actor_id, p."Name" AS actor_name
                  FROM person p
                 WHERE COALESCE(p."_status",'a') = 'a'
                 ORDER BY p."Name"
                """, new MapSqlParameterSource());
    }

    /**
     * 조립 설비 목록 — 워크센터로 필터.
     * workCenterId 가 null 이면 전체(디버그용).
     */
    public List<Map<String, Object>> getEquipments(Integer workCenterId) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("wcId", workCenterId);
        return this.sqlRunner.getRows("""
                SELECT e.id AS equipment_id, e."Code" AS equipment_code, e."Name" AS equipment_name
                  FROM equ e
                 WHERE COALESCE(e."_status",'a') = 'a'
                   AND (CAST(:wcId AS INTEGER) IS NULL
                        OR e."WorkCenter_id" = CAST(:wcId AS INTEGER))
                 ORDER BY e."Code"
                """, p);
    }

    // =====================================================================
    // 유닛/스텝 생성 — 작지 진입 시 1회 (멱등)
    // =====================================================================

    /**
     * 작지의 유닛을 OrderQty 만큼 만들고, 유닛마다 BOM 트리를 전개해 스텝을 깐다.
     * 이미 만들어져 있으면 부족분만 채운다(멱등).
     */
    @Transactional
    public AjaxResult initUnits(Integer jobResId, String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        MapSqlParameterSource p = new MapSqlParameterSource().addValue("jrId", jobResId);
        Map<String, Object> jr = this.sqlRunner.getRow("""
                SELECT jr."Material_id" AS mat_id, COALESCE(jr."OrderQty",0) AS order_qty,
                       to_char(jr."ProductionDate",'yyyy-mm-dd') AS prod_date
                  FROM job_res jr WHERE jr.id = :jrId
                """, p);
        if (jr == null) { r.success = false; r.message = "작업지시를 찾을 수 없습니다."; return r; }

        Integer rootMatId = asInt(jr.get("mat_id"));
        int orderQty = (int) Math.floor(toD(jr.get("order_qty")));
        if (orderQty <= 0) { r.success = false; r.message = "작업지시 수량이 0입니다."; return r; }

        // BOM 트리(어셈블리 = 스텝) 1회 전개 → 모든 유닛이 같은 트리를 공유
        List<Map<String, Object>> tree = buildStepTree(rootMatId, str(jr.get("prod_date")));
        if (tree.isEmpty()) {
            r.success = false;
            r.message = "이 품목의 제조 BOM(manufacturing)이 없습니다. BOM을 먼저 등록하세요.";
            return r;
        }

        Map<String, Object> ex = this.sqlRunner.getRow(
                "SELECT COALESCE(MAX(\"UnitNo\"),0) AS mx, COUNT(*) AS c FROM mcell_unit "
                        + "WHERE \"JobResponse_id\" = :jrId AND COALESCE(\"_status\",'a')='a'", p);
        int maxNo = asInt(ex.get("mx")) == null ? 0 : asInt(ex.get("mx"));
        int have = asInt(ex.get("c")) == null ? 0 : asInt(ex.get("c"));

        int created = 0;
        for (int no = maxNo + 1; have + created < orderQty; no++) {
            MapSqlParameterSource up = new MapSqlParameterSource()
                    .addValue("jrId", jobResId)
                    .addValue("matId", rootMatId)
                    .addValue("no", no)
                    .addValue("userId", user.getId())
                    .addValue("spjangcd", spjangcd);
            Map<String, Object> ins = this.sqlRunner.getRow("""
                    INSERT INTO mcell_unit ("JobResponse_id","Material_id","UnitNo","State",
                                            "_status","_created","_creater_id",spjangcd)
                    VALUES (:jrId,:matId,:no,'wait','a',now(),:userId,:spjangcd)
                    RETURNING id
                    """, up);
            Integer unitId = asInt(ins.get("id"));

            for (Map<String, Object> n : tree) {
                MapSqlParameterSource sp = new MapSqlParameterSource()
                        .addValue("unitId", unitId)
                        .addValue("matId", asInt(n.get("mat_id")))
                        .addValue("parentId", asInt(n.get("parent_mat_id")))
                        .addValue("depth", asInt(n.get("depth")))
                        .addValue("seq", asInt(n.get("seq_no")))
                        .addValue("userId", user.getId())
                        .addValue("spjangcd", spjangcd);
                this.sqlRunner.execute("""
                        INSERT INTO mcell_unit_step
                            ("McellUnit_id","Material_id","ParentMaterial_id","Depth","SeqNo",
                             "Source","State","_status","_created","_creater_id",spjangcd)
                        VALUES (:unitId,:matId,:parentId,:depth,:seq,'build','wait','a',now(),:userId,:spjangcd)
                        ON CONFLICT DO NOTHING
                        """, sp);
            }
            created++;
        }

        r.data = Map.of("created", created, "total", have + created, "step_cnt", tree.size());
        return r;
    }

    /**
     * BOM 트리 재귀 전개 — '자식 BOM(manufacturing)을 가진 품목' 만 스텝이 된다.
     * 사이클 방지(path), BOM 유효기간 반영.
     */
    public List<Map<String, Object>> buildStepTree(Integer rootMatId, String prodDate) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("rootId", rootMatId)
                .addValue("prodDate", (prodDate == null || prodDate.isBlank())
                        ? LocalDate.now().toString() : prodDate);
        return this.sqlRunner.getRows("""
                WITH RECURSIVE tree AS (
                    SELECT :rootId::int          AS mat_id
                         , NULL::int             AS parent_mat_id
                         , 0                     AS depth
                         , 0                     AS seq_no
                         , ARRAY[:rootId::int]   AS path
                    UNION ALL
                    SELECT bc."Material_id"
                         , t.mat_id
                         , t.depth + 1
                         , ROW_NUMBER() OVER (PARTITION BY t.mat_id ORDER BY bc.id)::int
                         , t.path || bc."Material_id"
                      FROM tree t
                      JOIN bom b       ON b."Material_id" = t.mat_id
                                      AND b."BOMType" = 'manufacturing'
                                      AND CAST(:prodDate AS date)
                                          BETWEEN COALESCE(b."StartDate", DATE '0001-01-01')
                                              AND COALESCE(b."EndDate",   DATE '9999-12-31')
                      JOIN bom_comp bc ON bc."BOM_id" = b.id
                     WHERE NOT (bc."Material_id" = ANY(t.path))
                       AND EXISTS (SELECT 1 FROM bom b2
                                    WHERE b2."Material_id" = bc."Material_id"
                                      AND b2."BOMType" = 'manufacturing')
                )
                SELECT t.mat_id, t.parent_mat_id, t.depth, t.seq_no,
                       m."Code" AS mat_code, m."Name" AS mat_name
                  FROM tree t JOIN material m ON m.id = t.mat_id
                 ORDER BY t.depth DESC, t.seq_no, t.mat_id
                """, p);
    }

    // =====================================================================
    // 스텝 상태 전이
    // =====================================================================

    /** 재고 투입 / 조립 전환. stock 이면 하위 스텝은 화면에서 스킵되고 상위 완료 시 이 로트를 소비한다. */
    @Transactional
    public AjaxResult setStepSource(Integer stepId, String source, Integer matLotId, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        Map<String, Object> st = getStep(stepId);
        if (st == null) { r.success = false; r.message = "스텝을 찾을 수 없습니다."; return r; }
        if ("done".equals(st.get("state"))) {
            r.success = false; r.message = "완료된 스텝은 변경할 수 없습니다. 완료취소 후 변경하세요."; return r;
        }
        if ("stock".equals(source) && matLotId == null) {
            r.success = false; r.message = "투입할 재고 로트를 선택하세요."; return r;
        }
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("stepId", stepId)
                .addValue("src", "stock".equals(source) ? "stock" : "build")
                .addValue("lotId", "stock".equals(source) ? matLotId : null)
                .addValue("userId", user.getId());
        this.sqlRunner.execute("""
                UPDATE mcell_unit_step
                   SET "Source"=:src, "StockMatLot_id"=:lotId,
                       "_modified"=now(), "_modifier_id"=:userId
                 WHERE id=:stepId
                """, p);
        return r;
    }

    /** 작업 시작 — 스텝을 working 으로. 유닛 로트가 없으면 여기서 발번. */
    @Transactional
    public AjaxResult startStep(Integer stepId, Integer actorId, Integer equipmentId,
                                String startTime, String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        Map<String, Object> st = getStep(stepId);
        if (st == null) { r.success = false; r.message = "스텝을 찾을 수 없습니다."; return r; }
        if ("done".equals(st.get("state"))) { r.success = false; r.message = "이미 완료된 스텝입니다."; return r; }

        Integer unitId = asInt(st.get("unit_id"));
        AjaxResult lock = checkKidsDone(unitId, asInt(st.get("mat_id")), str(st.get("source")));
        if (!lock.success) return lock;

        // 유닛 로트 발번 (첫 스텝 시작 시)
        Map<String, Object> unit = getUnit(unitId);
        if (unit.get("lot_number") == null) {
            String lot = makeLot(asInt(unit.get("mat_id")));
            MapSqlParameterSource up = new MapSqlParameterSource()
                    .addValue("unitId", unitId).addValue("lot", lot).addValue("userId", user.getId());
            this.sqlRunner.execute("""
                    UPDATE mcell_unit
                       SET "LotNumber"=:lot, "State"='assembling',
                           "StartTime"=COALESCE("StartTime", LOCALTIMESTAMP),
                           "_modified"=now(), "_modifier_id"=:userId
                     WHERE id=:unitId
                    """, up);
        }

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("stepId", stepId)
                .addValue("actorId", actorId)
                .addValue("equipId", equipmentId)
                .addValue("startTime", (startTime == null || startTime.isBlank()) ? null : startTime)
                .addValue("userId", user.getId());
        this.sqlRunner.execute("""
                UPDATE mcell_unit_step
                   SET "State"='working', "Actor_id"=:actorId, "Equipment_id"=:equipId,
                       "StartTime"=COALESCE(CAST(:startTime AS timestamp), "StartTime", LOCALTIMESTAMP),
                       "_modified"=now(), "_modifier_id"=:userId
                 WHERE id=:stepId
                """, p);
        return r;
    }

    /**
     * 스텝 완료 — 실적 생성의 알맹이는 ProductionCreateService.
     *   1) 계층 잠금 확인
     *   2) 투입자재 결정 : 화면 편집분(bomList) + 하위 어셈블리(자식 스텝 산출 로트 or 재고 투입 로트)
     *   3) 작지 자동생성(모듈) / 유닛 작지 사용(최상위) → mat_produce + BOM FIFO 차감 + 모듈 로트 입고
     *   4) 최상위(Depth=0) 완료면 유닛 = 검사대기
     */
    @Transactional
    public AjaxResult finishStep(Integer stepId, List<ProductionCreateService.BomInput> bomList,
                                 Integer actorId, Integer equipmentId,
                                 String startTime, String endTime,
                                 String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        Map<String, Object> st = getStep(stepId);
        if (st == null) { r.success = false; r.message = "스텝을 찾을 수 없습니다."; return r; }
        if ("done".equals(st.get("state"))) { r.success = false; r.message = "이미 완료된 스텝입니다."; return r; }
        if ("stock".equals(st.get("source"))) {
            r.success = false; r.message = "재고 투입 스텝은 조립 완료 대상이 아닙니다."; return r;
        }

        Integer unitId  = asInt(st.get("unit_id"));
        Integer matId   = asInt(st.get("mat_id"));
        Integer depth   = asInt(st.get("depth"));
        Map<String, Object> unit = getUnit(unitId);

        AjaxResult lock = checkKidsDone(unitId, matId, "build");
        if (!lock.success) return lock;

        // ── 하위 어셈블리 투입분을 bomList 에 합류 ──
        //    자식 스텝이 조립된 것이면 그 스텝이 만든 로트, 재고 투입이면 선택한 로트를 소비한다.
        List<ProductionCreateService.BomInput> inputs = new ArrayList<>();
        if (bomList != null) inputs.addAll(bomList);
        for (Map<String, Object> kid : getKidSteps(unitId, matId)) {
            Integer kidMat = asInt(kid.get("mat_id"));
            if (inputs.stream().anyMatch(b -> kidMat.equals(b.matId))) continue;   // 화면에서 이미 보냈으면 중복 방지
            inputs.add(new ProductionCreateService.BomInput(kidMat, 1f));
        }

        // ── 로트번호 : 최상위는 유닛 로트, 하위 모듈은 모듈 로트 ──
        //   최상위 = 유닛 로트. 하위 모듈 = 스텝에 이미 붙어 있던 로트(재조립) 또는 신규 발번.
        String lotNumber = (depth != null && depth == 0)
                ? str(unit.get("lot_number"))
                : str(st.get("lot_number"));
        if (lotNumber == null || lotNumber.isBlank()) lotNumber = makeLot(matId);

        ProductionCreateService.CreateReq req = new ProductionCreateService.CreateReq();
        // 최상위 = 유닛 작지. 하위 모듈 = 완제품 전개로 이미 생성된 형제 작지에 붙인다.
        //   (없으면 jobResId=null → ProductionCreateService 가 심플 작지 자동생성)
        Integer moduleJrId = (depth != null && depth == 0)
                ? asInt(unit.get("job_res_id"))
                : findSiblingJobRes(asInt(unit.get("job_res_id")), matId);
        req.jobResId     = moduleJrId;
        req.materialId   = matId;                       // 작지 자동생성 대비
        req.workCenterId = WC_ASSEMBLY;
        req.equipmentId  = equipmentId;
        req.actorId      = actorId;
        req.memberIds    = null;                        // 2공장은 1인 작업 — 조원 명단 없음
        req.shiftCode    = null;
        req.goodQty      = 1f;                          // 1대 = 1로트
        req.defectQty    = 0f;
        req.productionDate = str(getUnitProdDate(unitId));
        req.startTime    = (startTime != null && !startTime.isBlank()) ? startTime : str(st.get("start_time"));
        req.endTime      = endTime;
        req.bomList      = inputs;
        req.cleanStore   = STORE_PROD;                  // 2공장 투입 소스 = 생산창고
        req.lotNumber    = lotNumber;                   // ★ 패치된 필드
        req.spjangcd     = spjangcd;

        AjaxResult made = this.productionCreateService.createProduction(req, user);
        if (!made.success) return made;

        Integer mpId = asInt(((Map<?, ?>) made.data).get("mat_produce_id"));

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("stepId", stepId).addValue("mpId", mpId)
                .addValue("lot", lotNumber).addValue("actorId", actorId)
                .addValue("equipId", equipmentId).addValue("userId", user.getId());
        this.sqlRunner.execute("""
                UPDATE mcell_unit_step
                   SET "State"='done', "MatProduce_id"=:mpId, "LotNumber"=:lot,
                       "Actor_id"=COALESCE(:actorId,"Actor_id"),
                       "Equipment_id"=COALESCE(:equipId,"Equipment_id"),
                       "StartTime"=COALESCE("StartTime", LOCALTIMESTAMP),
                       "EndTime"=LOCALTIMESTAMP,
                       "_modified"=now(), "_modifier_id"=:userId
                 WHERE id=:stepId
                """, p);

        // ── 최상위 완료 → 유닛 검사대기 ──
        if (depth != null && depth == 0) {
            MapSqlParameterSource up = new MapSqlParameterSource()
                    .addValue("unitId", unitId).addValue("userId", user.getId());
            this.sqlRunner.execute("""
                    UPDATE mcell_unit
                       SET "State"='inspect_wait', "EndTime"=LOCALTIMESTAMP,
                           "RejectReason"=NULL, "RejectInspNo"=NULL, "RejectAt"=NULL,
                           "_modified"=now(), "_modifier_id"=:userId
                     WHERE id=:unitId
                    """, up);
        }

        r.data = Map.of("mat_produce_id", mpId, "lot_number", lotNumber);
        return r;
    }

    /**
     * 완료취소 (캐스케이드).
     * 이 스텝의 산출 로트는 상위 스텝이 이미 소비했을 수 있으므로,
     * 최상위 → 이 스텝 순서로 위에서부터 되돌린다.
     */
    @Transactional
    public AjaxResult cancelStep(Integer stepId, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        Map<String, Object> st = getStep(stepId);
        if (st == null) { r.success = false; r.message = "스텝을 찾을 수 없습니다."; return r; }
        Integer unitId = asInt(st.get("unit_id"));

        // 조상 체인 수집 (자신 포함), 최상위가 앞에 오도록 정렬
        List<Map<String, Object>> chain = new ArrayList<>();
        chain.add(st);
        Integer pm = asInt(st.get("parent_mat_id"));
        while (pm != null) {
            Map<String, Object> ps = getStepByMat(unitId, pm);
            if (ps == null) break;
            chain.add(ps);
            pm = asInt(ps.get("parent_mat_id"));
        }
        Collections.reverse(chain);   // 최상위부터

        int rolled = 0;
        for (Map<String, Object> s : chain) {
            if (!"done".equals(s.get("state"))) continue;
            AjaxResult rb = rollbackProduce(asInt(s.get("mp_id")), str(s.get("lot_number")), user);
            if (!rb.success) return rb;
            // 분해 = 같은 물건을 다시 조립하는 것. 로트번호와 담당자는 유지해 이력을 잇는다.
            MapSqlParameterSource p = new MapSqlParameterSource()
                    .addValue("stepId", asInt(s.get("step_id"))).addValue("userId", user.getId());
            this.sqlRunner.execute("""
                    UPDATE mcell_unit_step
                       SET "State"='working', "MatProduce_id"=NULL,
                           "EndTime"=NULL, "ReworkYN"='Y',
                           "_modified"=now(), "_modifier_id"=:userId
                     WHERE id=:stepId
                    """, p);
            rolled++;
        }

        // 분해했으면 그 유닛의 검사 결과는 더 이상 유효하지 않다.
        //   진행중 회차는 삭제, 확정된 회차는 이력으로 남기되 유닛은 조립중으로 되돌린다.
        MapSqlParameterSource up = new MapSqlParameterSource()
                .addValue("unitId", unitId).addValue("userId", user.getId());
        this.sqlRunner.execute("""
                DELETE FROM insp_result_item
                 WHERE "InspResult_id" IN (SELECT id FROM insp_result
                                            WHERE "McellUnit_id"=:unitId AND "Verdict" IS NULL)
                """, up);
        this.sqlRunner.execute("""
                DELETE FROM insp_result WHERE "McellUnit_id"=:unitId AND "Verdict" IS NULL
                """, up);
        this.sqlRunner.execute("""
                UPDATE mcell_unit
                   SET "State"='assembling', "EndTime"=NULL,
                       "_modified"=now(), "_modifier_id"=:userId
                 WHERE id=:unitId AND "State" <> 'packed'
                """, up);

        r.message = rolled > 1
                ? ("상위 어셈블리 " + (rolled - 1) + "개도 함께 분해되었습니다. 자재 교체 후 다시 완료하세요.")
                : "분해 완료 · 자재를 교체하고 다시 완료하세요.";
        r.data = Map.of("rolled", rolled);
        return r;
    }

    /** 작업 삭제 — working 스텝을 wait 로 되돌린다(재고 미이동 상태). */
    @Transactional
    public AjaxResult deleteStepWork(Integer stepId, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        Map<String, Object> st = getStep(stepId);
        if (st == null) { r.success = false; r.message = "스텝을 찾을 수 없습니다."; return r; }
        if ("done".equals(st.get("state"))) {
            r.success = false; r.message = "완료된 스텝입니다. 완료취소를 사용하세요."; return r;
        }
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("stepId", stepId).addValue("userId", user.getId());
        this.sqlRunner.execute("""
                UPDATE mcell_unit_step
                   SET "State"='wait', "Actor_id"=NULL, "Equipment_id"=NULL,
                       "StartTime"=NULL, "EndTime"=NULL,
                       "_modified"=now(), "_modifier_id"=:userId
                 WHERE id=:stepId
                """, p);
        return r;
    }

    /** 시작/완료 시각 수정 */
    @Transactional
    public AjaxResult setStepTime(Integer stepId, String which, String value, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        String col = "end".equals(which) ? "EndTime" : "StartTime";
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("stepId", stepId).addValue("val", value).addValue("userId", user.getId());
        this.sqlRunner.execute("UPDATE mcell_unit_step SET \"" + col + "\"=CAST(:val AS timestamp), "
                + "\"_modified\"=now(), \"_modifier_id\"=:userId WHERE id=:stepId", p);
        // 완료된 스텝이면 mat_produce 시각도 함께 맞춘다
        Map<String, Object> st = getStep(stepId);
        if (st != null && st.get("mp_id") != null) {
            MapSqlParameterSource mp = new MapSqlParameterSource()
                    .addValue("mpId", asInt(st.get("mp_id"))).addValue("val", value);
            this.sqlRunner.execute("UPDATE mat_produce SET \"" + col + "\"=CAST(:val AS timestamp) WHERE id=:mpId", mp);
        }
        return r;
    }

    // =====================================================================
    // 내부 유틸
    // =====================================================================

    /**
     * 차수 롤백 — ProductionWorkController.itemCancel 과 동일 절차.
     * 다만 M-CELL 은 스텝을 wait 로 되돌리므로 차수 자체를 소프트삭제한다.
     */
    private AjaxResult rollbackProduce(Integer mpId, User user) {
        return rollbackProduce(mpId, null, user);
    }

    private AjaxResult rollbackProduce(Integer mpId, String lotNumber, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        if (mpId == null) return r;

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("mpId", mpId).addValue("userId", user.getId());

        // 산출 로트가 이 체인 밖에서 이미 소진됐으면 차단
        Map<String, Object> used = this.sqlRunner.getRow("""
                SELECT COUNT(*) AS c FROM mat_lot ml
                 WHERE ml."SourceTableName"='mat_produce' AND ml."SourceDataPk"=:mpId
                   AND COALESCE(ml."CurrentStock",0) < COALESCE(ml."InputQty",0)
                """, p);
        if (used != null && asInt(used.get("c")) != null && asInt(used.get("c")) > 0) {
            r.success = false;
            r.message = "이 모듈 로트가 다른 곳에서 사용되어 취소할 수 없습니다.";
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
        this.sqlRunner.execute("DELETE FROM mat_lot_cons WHERE \"SourceTableName\"='mat_produce' AND \"SourceDataPk\"=:mpId", p);
        this.sqlRunner.execute("""
                DELETE FROM mat_consu
                 WHERE "JobResponse_id"=(SELECT "JobResponse_id" FROM mat_produce WHERE id=:mpId)
                   AND "LotIndex"=(SELECT "LotIndex" FROM mat_produce WHERE id=:mpId)
                """, p);
        this.sqlRunner.execute("""
                UPDATE mat_produce SET "_status"='d', "State"='wait',
                       "_modified"=now(), "_modifier_id"=:userId
                 WHERE id=:mpId
                """, p);

        // ★ 검사 공정이 남긴 창고이동 이력(mcell_unit)도 함께 정리.
        //    이걸 놔두면 로트는 사라졌는데 이동 이력만 남아 재고가 부풀어 오른다.
        if (lotNumber != null && !lotNumber.isBlank()) {
            MapSqlParameterSource lp = new MapSqlParameterSource().addValue("lot", lotNumber);
            this.sqlRunner.execute("""
                    DELETE FROM mat_inout
                     WHERE "SourceTableName"='mcell_unit' AND "LotNumber"=:lot
                    """, lp);
        }
        // ★ 롤백 후 작지 롤업 재실행 —
        //   안 하면 분해했는데도 작지가 finished 로 남아 실적에 계속 뜬다.
        Map<String, Object> jrRow = this.sqlRunner.getRow(
                "SELECT \"JobResponse_id\" AS jr_id FROM mat_produce WHERE id = :mpId", p);
        if (jrRow != null && jrRow.get("jr_id") != null) {
            this.productionCreateService.recalcJobRes(
                    ((Number) jrRow.get("jr_id")).intValue(), user);
        }
        return r;
    }

    /** 계층 잠금 확인 — 자식 스텝이 전부 done 이어야 한다. 재고 투입 스텝은 면제. */
    private AjaxResult checkKidsDone(Integer unitId, Integer matId, String source) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        if ("stock".equals(source)) return r;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("unitId", unitId).addValue("matId", matId);
        Map<String, Object> row = this.sqlRunner.getRow("""
                SELECT COUNT(*) FILTER (
                           WHERE st."State" <> 'done'
                             AND NOT (st."Source" = 'stock' AND st."StockMatLot_id" IS NOT NULL)
                       ) AS pending, COUNT(*) AS total
                  FROM mcell_unit_step st
                 WHERE st."McellUnit_id"=:unitId AND st."ParentMaterial_id"=:matId
                   AND COALESCE(st."_status",'a')='a'
                """, p);
        int pending = (row == null || asInt(row.get("pending")) == null) ? 0 : asInt(row.get("pending"));
        if (pending > 0) {
            r.success = false;
            r.message = "하위 어셈블리 " + pending + "개가 아직 완료되지 않았습니다.";
        }
        return r;
    }

    /** 자식 스텝 — 상위 완료 시 투입할 대상(조립분 or 재고 투입분) */
    private List<Map<String, Object>> getKidSteps(Integer unitId, Integer matId) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("unitId", unitId).addValue("matId", matId);
        return this.sqlRunner.getRows("""
                SELECT st.id AS step_id, st."Material_id" AS mat_id, st."Source" AS source,
                       st."LotNumber" AS lot_number, st."StockMatLot_id" AS stock_lot_id
                  FROM mcell_unit_step st
                 WHERE st."McellUnit_id"=:unitId AND st."ParentMaterial_id"=:matId
                   AND COALESCE(st."_status",'a')='a'
                 ORDER BY st."SeqNo", st.id
                """, p);
    }

    /**
     * 하위 모듈 스텝이 붙을 작지 찾기.
     * 완제품 작지 전개(explodeProcessRows)로 이미 모듈별 자식 작지가 만들어져 있으므로,
     * 유닛 작지와 같은 부모 아래에서 같은 품목의 형제 작지를 찾아 재사용한다.
     * 못 찾으면 null → ProductionCreateService 가 심플 작지를 자동생성한다.
     */
    private Integer findSiblingJobRes(Integer unitJobResId, Integer matId) {
        if (unitJobResId == null || matId == null) return null;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("jrId", unitJobResId).addValue("matId", matId);
        Map<String, Object> row = this.sqlRunner.getRow("""
                SELECT jr2.id AS id
                  FROM job_res jr1
                  JOIN job_res jr2
                    ON jr2."Parent_id" = COALESCE(jr1."Parent_id", jr1.id)
                 WHERE jr1.id = :jrId
                   AND jr2."Material_id" = :matId
                   AND jr2."State" IN ('ordered','working')
                 ORDER BY jr2.id
                 LIMIT 1
                """, p);
        return (row == null) ? null : asInt(row.get("id"));
    }

    private Map<String, Object> getStep(Integer stepId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("stepId", stepId);
        return this.sqlRunner.getRow("""
                SELECT st.id AS step_id, st."McellUnit_id" AS unit_id, st."Material_id" AS mat_id,
                       st."ParentMaterial_id" AS parent_mat_id, st."Depth" AS depth,
                       st."Source" AS source, st."StockMatLot_id" AS stock_lot_id,
                       st."MatProduce_id" AS mp_id, st."State" AS state, st."LotNumber" AS lot_number,
                       to_char(st."StartTime",'yyyy-mm-dd hh24:mi') AS start_time
                  FROM mcell_unit_step st WHERE st.id=:stepId
                """, p);
    }

    private Map<String, Object> getStepByMat(Integer unitId, Integer matId) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("unitId", unitId).addValue("matId", matId);
        return this.sqlRunner.getRow("""
                SELECT st.id AS step_id, st."McellUnit_id" AS unit_id, st."Material_id" AS mat_id,
                       st."ParentMaterial_id" AS parent_mat_id, st."Depth" AS depth,
                       st."Source" AS source, st."MatProduce_id" AS mp_id, st."State" AS state,
                       st."LotNumber" AS lot_number
                  FROM mcell_unit_step st
                 WHERE st."McellUnit_id"=:unitId AND st."Material_id"=:matId
                """, p);
    }

    private Map<String, Object> getUnit(Integer unitId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("unitId", unitId);
        return this.sqlRunner.getRow("""
                SELECT mu.id AS unit_id, mu."JobResponse_id" AS job_res_id, mu."Material_id" AS mat_id,
                       mu."UnitNo" AS unit_no, mu."LotNumber" AS lot_number, mu."State" AS state
                  FROM mcell_unit mu WHERE mu.id=:unitId
                """, p);
    }

    private String getUnitProdDate(Integer unitId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("unitId", unitId);
        Map<String, Object> row = this.sqlRunner.getRow("""
                SELECT to_char(jr."ProductionDate",'yyyy-mm-dd') AS d
                  FROM mcell_unit mu JOIN job_res jr ON jr.id = mu."JobResponse_id"
                 WHERE mu.id=:unitId
                """, p);
        return (row == null) ? LocalDate.now().toString() : str(row.get("d"));
    }

    /**
     * 로트 채번 — {품목코드접두}-{yyMMdd}-{일련}
     *   WIP-MC20022N → MC20022N-260728-001   (유닛 = 검사·포장이 물고 가는 번호)
     *   WIP-MA2007   → MA2007-260728-001     (모듈 = 재고 투입 대상)
     * 외부 라벨(M2FJ109393)과는 무관한 사내 번호. 포장에서 외부 로트와 매칭한다.
     */
    private String makeLot(Integer matId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("matId", matId);
        Map<String, Object> m = this.sqlRunner.getRow("SELECT \"Code\" AS code FROM material WHERE id=:matId", p);
        String code = (m == null || m.get("code") == null) ? ("M" + matId) : String.valueOf(m.get("code"));
        String prefix = code.replaceAll("^WIP-", "").replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (prefix.length() > 16) prefix = prefix.substring(0, 16);
        String head = prefix + "-" + LocalDate.now().format(YYMMDD) + "-";

        MapSqlParameterSource lp = new MapSqlParameterSource().addValue("head", head + "%");
        Map<String, Object> row = this.sqlRunner.getRow("""
                SELECT COALESCE(MAX(CAST(RIGHT("LotNumber",3) AS integer)),0) AS mx
                  FROM mat_lot WHERE "LotNumber" LIKE :head AND RIGHT("LotNumber",3) ~ '^[0-9]{3}$'
                """, lp);
        int next = (row == null || asInt(row.get("mx")) == null) ? 1 : asInt(row.get("mx")) + 1;
        return head + String.format("%03d", next);
    }

    private static Integer asInt(Object o) {
        return (o == null) ? null : ((Number) o).intValue();
    }

    private static double toD(Object o) {
        return (o == null) ? 0d : Double.parseDouble(String.valueOf(o));
    }

    private static String str(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }
}