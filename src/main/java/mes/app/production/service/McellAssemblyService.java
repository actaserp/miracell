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
    @Autowired WorkMemberService workMemberService;

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
                     , mu."SerialNo"                                AS serial_no   -- ★ 실제 제품 S/N (없으면 SN-001 임시번호)
                     , mu."LotNumber"                               AS lot_number
                     , mu."State"                                   AS state
                     , to_char(mu."StartTime", 'yyyy-mm-dd hh24:mi') AS start_time
                     , to_char(mu."EndTime",   'yyyy-mm-dd hh24:mi') AS end_time
                     , mu."RejectReason"                            AS reject_reason
                     , mu."RejectInspNo"                            AS reject_insp_no
                     , to_char(mu."RejectAt", 'yyyy-mm-dd hh24:mi') AS reject_at
                     , COALESCE(s.total, 0)                         AS step_total
                     , COALESCE(s.done,  0)                         AS step_done
                     /* ★ 이번 조립에 대해 판정이 난 양식 수 / 전체 양식 수.
                          분해 경고를 유닛 전체 합격(pass)일 때만 띄우면, 양식 2개 중 1개만
                          합격한 유닛은 아무 말 없이 뜯겼다 — 그 합격도 무효가 되는데.
                          «이번 조립» = 판정 시각이 조립 완료(mu.EndTime) 이후인 것.
                          검사 화면이 이전 판정을 「(이전)」 으로 거르는 것과 같은 기준이다. */
                     , COALESCE(ic.judged, 0)                       AS insp_judged
                     , COALESCE(ic.total,  0)                       AS insp_total
                  FROM mcell_unit mu
                  LEFT JOIN LATERAL (
                        SELECT (SELECT COUNT(*) FROM insp_form_mat fm
                                  JOIN insp_form f ON f.id = fm."InspForm_id"
                                 WHERE fm."Material_id" = mu."Material_id"
                                   AND COALESCE(f."InspType",'final') = 'final'   -- ★ 공정검사·포장 체크 양식 제외
                                   AND COALESCE(f."UseYN",'Y') = 'Y'
                                   AND COALESCE(f."_status",'a') = 'a')      AS total
                             , (SELECT COUNT(DISTINCT ir."InspForm_id") FROM insp_result ir
                                 WHERE ir."McellUnit_id" = mu.id
                                   AND ir."Verdict" IS NOT NULL
                                   AND COALESCE(ir._status,'a') = 'a'
                                   AND (mu."EndTime" IS NULL OR ir."EndTime" >= mu."EndTime")) AS judged
                  ) ic ON true
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
        List<Map<String, Object>> rows = this.sqlRunner.getRows(("""
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
                     , ${MEMBER_IDS}                                AS member_ids
                     , ${MEMBER_NAMES}                              AS member_names
                     , st."Equipment_id"                            AS equipment_id
                     , eq."Name"                                    AS equipment_name
                     , to_char(st."StartTime", 'yyyy-mm-dd hh24:mi') AS start_time
                     , to_char(st."EndTime",   'yyyy-mm-dd hh24:mi') AS end_time
                     , COALESCE(bm.mat_cnt, 0)                      AS bom_cnt
                     , COALESCE(sq.need, 0)                         AS sn_need   -- ★ S/N 입력해야 할 개수
                     , COALESCE(sq.done, 0)                         AS sn_done   -- ★ 입력된 개수
                     , sq.mids                                      AS sn_mat_ids
                     , pf.form_id                                   AS pi_form_id     -- ★ 공정검사 양식
                     , pf.form_name                                 AS pi_form_name
                     , pi.id                                        AS pi_result_id
                     , pi."Verdict"                                 AS pi_verdict
                     , pi."State"                                   AS pi_state
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
                  LEFT JOIN LATERAL (
                        SELECT SUM(x.q)::int AS need, string_agg(x.mid::text, ',') AS mids
                             , SUM(LEAST(x.q, (SELECT COUNT(*) FROM mcell_unit_sn us
                                                WHERE us."McellUnit_id" = st."McellUnit_id"
                                                  AND us."Material_id" = x.mid
                                                  AND COALESCE(us."_status",'a') = 'a')))::int AS done
                          FROM (SELECT bc."Material_id" AS mid,
                                       GREATEST(1, ROUND(bc."Amount" / NULLIF(b."OutputAmount",0)))::int AS q
                                  FROM bom b JOIN bom_comp bc ON bc."BOM_id" = b.id
                                  JOIN material sm ON sm.id = bc."Material_id"
                                 WHERE b."Material_id" = st."Material_id" AND b."BOMType"='manufacturing'
                                   AND COALESCE(sm."SnYN",'N') = 'Y') x
                  ) sq ON true
                  LEFT JOIN LATERAL (
                        SELECT f.id AS form_id, f."Name" AS form_name
                          FROM insp_form f JOIN insp_form_mat fm ON fm."InspForm_id" = f.id
                         WHERE fm."Material_id" = st."Material_id" AND f."InspType" = 'process'
                           AND COALESCE(f."UseYN",'Y') = 'Y' AND COALESCE(f."_status",'a') = 'a'
                         ORDER BY f."SeqNo", f.id LIMIT 1
                  ) pf ON true
                  LEFT JOIN LATERAL (
                        SELECT x.id, x."Verdict", x."State" FROM mcell_proc_insp x
                         WHERE x."McellUnitStep_id" = st.id AND x."InspForm_id" = pf.form_id
                           AND x."State" <> 'void' AND COALESCE(x."_status",'a') = 'a'
                         ORDER BY x."TryNo" DESC LIMIT 1
                  ) pi ON true
                 WHERE st."McellUnit_id" = :unitId AND COALESCE(st."_status",'a') = 'a'
                 ORDER BY st."Depth" DESC, st."SeqNo", st.id
                """
                /* ★ 조원은 스칼라 서브쿼리. LATERAL 로 붙이면 행이 복제된다. */
                .replace("${MEMBER_IDS}",   WorkMemberService.idsSql("mcell_unit_step", "st.id"))
                .replace("${MEMBER_NAMES}", WorkMemberService.namesSql("mcell_unit_step", "st.id"))), p);

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
    /**
     * 스텝 투입자재 편집분 저장.
     * 수량 가감 · 삭제 · 품목 추가가 일어날 때마다 화면이 즉시 호출한다.
     * 완료 버튼을 눌러야 반영되던 예전 방식은, 중간에 화면을 벗어나면 편집이 통째로 사라졌다.
     */
    @Transactional
    public AjaxResult saveStepMaterials(Integer stepId, String bomJson, User user) {
        AjaxResult r = new AjaxResult();

        Map<String, Object> st = getStep(stepId);
        if (st == null) { r.success = false; r.message = "스텝을 찾을 수 없습니다."; return r; }
        if ("done".equals(st.get("state"))) {
            r.success = false;
            r.message = "완료된 스텝은 투입자재를 바꿀 수 없습니다. 먼저 완료취소(분해)하세요.";
            return r;
        }

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("stepId", stepId)
                .addValue("json", (bomJson == null || bomJson.isBlank()) ? null : bomJson)
                .addValue("userId", user.getId());
        this.sqlRunner.execute("""
                UPDATE mcell_unit_step
                   SET "DraftBomJson" = :json, "_modified" = now(), "_modifier_id" = :userId
                 WHERE id = :stepId
                """, p);

        r.success = true;
        return r;
    }

    /**
     * 스텝 투입자재.
     *
     * 미완료 스텝 → BOM 기본값.
     * 완료 스텝   → 실제 소비량(mat_lot_cons). BOM 을 다시 계산해 보여주면
     *               작업자가 고친 수량이나 추가한 품목이 사라진 것처럼 보인다.
     *               완료된 스텝의 진실은 BOM 이 아니라 실제로 빠져나간 자재다.
     *
     * ※ mat_lot_cons 에는 Material_id 가 없다. mat_lot 을 거쳐 품목을 찾는다.
     *   실적과의 연결은 SourceTableName='mat_produce' + SourceDataPk=MatProduce_id.
     */
    public List<Map<String, Object>> getStepMaterials(Integer stepId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("stepId", stepId);
        return this.sqlRunner.getRows("""
                WITH s AS (SELECT "Material_id" AS mat_id, "McellUnit_id" AS unit_id,
                                  "MatProduce_id" AS mp_id, "DraftBomJson" AS draft_json
                             FROM mcell_unit_step WHERE id = :stepId)
                -- 작업자가 편집해 둔 구성 (완료 전에도 즉시 저장된다)
                , draft AS (
                    SELECT (j->>'matId')::int     AS mat_id
                         , (j->>'qty')::numeric   AS qty
                         , NULLIF(j->>'lotId','')::int AS lot_id   -- ★ 작업자가 고른 투입 로트(없으면 FIFO)
                      FROM s, LATERAL jsonb_array_elements(
                            CASE WHEN COALESCE(s.draft_json,'') = '' THEN '[]'::jsonb
                                 ELSE s.draft_json::jsonb END) j
                )
                -- BOM 기본 구성
                , bomc AS (
                    SELECT bc."Material_id" AS mat_id
                         , bc.id            AS ord
                         , bc."Amount" / NULLIF(b."OutputAmount",0) AS per
                      FROM s
                      JOIN bom b       ON b."Material_id" = s.mat_id AND b."BOMType" = 'manufacturing'
                      JOIN bom_comp bc ON bc."BOM_id" = b.id
                )
                -- 실제 소비분 (완료된 스텝에만 존재)
                , used AS (
                    SELECT ml."Material_id" AS mat_id
                         , SUM(mlc."OutputQty") AS qty
                         , string_agg(DISTINCT ml."LotNumber", ', ') AS used_lots   -- ★ 실제로 빠진 로트
                      FROM s
                      JOIN mat_lot_cons mlc
                        ON mlc."SourceDataPk" = s.mp_id
                       AND COALESCE(mlc."SourceTableName",'mat_produce') = 'mat_produce'
                       AND COALESCE(mlc."_status",'a') = 'a'
                      JOIN mat_lot ml ON ml.id = mlc."MaterialLot_id"
                     WHERE s.mp_id IS NOT NULL
                     GROUP BY ml."Material_id"
                )
                -- BOM 에 없던 품목을 추가 투입했을 수 있으므로 합집합으로 뽑는다
                , keys AS (
                    SELECT mat_id FROM bomc
                    UNION SELECT mat_id FROM used
                    UNION SELECT mat_id FROM draft
                )
                SELECT k.mat_id
                     , m."Code"  AS mat_code
                     , m."Name"  AS mat_name
                     , un."Name" AS unit
                     , bomc.per  AS per
                     -- 우선순위 : 실제 소비(완료) > 편집분(초안) > BOM 기본
                     , COALESCE(used.qty, draft.qty, CEIL(bomc.per), 0) AS default_qty
                     , (CASE WHEN used.qty IS NOT NULL OR draft.qty IS NOT NULL
                             THEN 'Y' ELSE 'N' END) AS is_actual
                     , (CASE WHEN bomc.mat_id IS NULL   THEN 'Y' ELSE 'N' END) AS is_added
                     -- ★ 투입 로트 · S/N
                     , draft.lot_id                         AS lot_id
                     , dl."LotNumber"                       AS lot_number
                     , used.used_lots                       AS used_lots
                     , COALESCE(m."SnYN",'N')                AS sn_yn
                     , (CASE WHEN COALESCE(m."InspectYN",'N')='Y' THEN 19 ELSE 17 END) AS src_store
                     , sh."Name" AS src_store_name
                     , COALESCE(stk.stock, 0) AS stock
                     , (CASE WHEN EXISTS (SELECT 1 FROM bom b2
                                           WHERE b2."Material_id" = k.mat_id
                                             AND b2."BOMType"='manufacturing')
                             THEN 'Y' ELSE 'N' END) AS is_assembly
                  FROM keys k
                  JOIN material m   ON m.id = k.mat_id
                  LEFT JOIN bomc    ON bomc.mat_id = k.mat_id
                  LEFT JOIN used    ON used.mat_id = k.mat_id
                  LEFT JOIN draft   ON draft.mat_id = k.mat_id
                  LEFT JOIN mat_lot dl ON dl.id = draft.lot_id
                  LEFT JOIN unit un ON un.id = m."Unit_id"
                  LEFT JOIN store_house sh
                         ON sh.id = (CASE WHEN COALESCE(m."InspectYN",'N')='Y' THEN 19 ELSE 17 END)
                  LEFT JOIN LATERAL (
                        SELECT SUM(ml."CurrentStock") AS stock FROM mat_lot ml
                         WHERE ml."Material_id" = k.mat_id
                           AND ml."StoreHouse_id" = (CASE WHEN COALESCE(m."InspectYN",'N')='Y' THEN 19 ELSE 17 END)
                  ) stk ON true
                 -- 편집분이 있으면 그것이 곧 구성이다. 지운 자재가 BOM 기본으로 되살아나면 안 된다.
                 -- 단 하위 어셈블리 행은 스텝 트리 표시용이라 항상 남긴다.
                 WHERE NOT EXISTS (SELECT 1 FROM draft)
                    OR draft.mat_id IS NOT NULL
                    OR EXISTS (SELECT 1 FROM bom b3
                                WHERE b3."Material_id" = k.mat_id AND b3."BOMType" = 'manufacturing')
                 ORDER BY is_assembly DESC, bomc.ord NULLS LAST, m."Code"
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
    /**
     * 작지 수량 축소에 맞춰 남는 유닛을 걷어낸다.
     *
     * initUnits 는 부족한 만큼만 채우는 멱등 동작이라 「늘리기」는 이미 되지만
     * 「줄이기」는 아무 처리가 없어, 5대 → 3대로 바꿔도 유닛 5대가 그대로 남았다.
     *
     * 규칙
     *  · 착수하지 않은 유닛만 지운다 — State='wait' + 스텝 전부 'wait' + 실적(MatProduce_id) 없음
     *  · UnitNo 큰 것부터. 뒤에서 걷어내야 남는 번호가 연속한다
     *  · 지울 수 있는 대수가 모자라면 아무것도 안 하고 거부. 부분 반영은 화면과 DB 가 어긋난다
     *
     * @param targetQty 줄인 뒤의 목표 대수
     */

    @Transactional
    public AjaxResult shrinkUnits(Integer jobResId, int targetQty, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;

        MapSqlParameterSource p = new MapSqlParameterSource().addValue("jrId", jobResId);

        // 현재 살아 있는 유닛
        Integer have = this.sqlRunner.queryForCount("""
                SELECT COUNT(*) FROM mcell_unit
                 WHERE "JobResponse_id" = :jrId AND COALESCE("_status",'a') = 'a'
                """, p);
        if (have == null) have = 0;

        int excess = have - targetQty;
        if (excess <= 0) {           // 줄일 것이 없다 (같거나 오히려 늘리는 경우)
            r.data = Map.of("deleted", 0, "total", have);
            return r;
        }

        // 지울 수 있는 유닛 = 손대지 않은 것. UnitNo 큰 것부터.
        List<Map<String, Object>> free = this.sqlRunner.getRows("""
                SELECT mu.id, mu."UnitNo"
                  FROM mcell_unit mu
                 WHERE mu."JobResponse_id" = :jrId
                   AND COALESCE(mu."_status",'a') = 'a'
                   AND mu."State" = 'wait'
                   AND NOT EXISTS (
                         SELECT 1 FROM mcell_unit_step st
                          WHERE st."McellUnit_id" = mu.id
                            AND (st."State" <> 'wait' OR st."MatProduce_id" IS NOT NULL)
                       )
                 ORDER BY mu."UnitNo" DESC
                """, p);

        int freeCnt = (free == null) ? 0 : free.size();
        if (freeCnt < excess) {
            r.success = false;
            r.message = "이미 착수한 유닛이 있어 수량을 줄일 수 없습니다.\n"
                    + "현재 " + have + "대 중 미착수 " + freeCnt + "대. "
                    + "최소 " + (have - freeCnt) + "대까지만 줄일 수 있습니다.";
            return r;
        }

        int deleted = 0;
        for (Map<String, Object> u : free) {
            if (deleted >= excess) break;
            Integer unitId = asInt(u.get("id"));

            MapSqlParameterSource up = new MapSqlParameterSource().addValue("unitId", unitId);
            // 스텝 먼저 (FK 순서)
            this.sqlRunner.execute(
                    "DELETE FROM mcell_unit_step WHERE \"McellUnit_id\" = :unitId", up);
            this.sqlRunner.execute(
                    "DELETE FROM mcell_unit WHERE id = :unitId", up);
            deleted++;
        }

        r.data = Map.of("deleted", deleted, "total", have - deleted);
        return r;
    }

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
    public AjaxResult startStep(Integer stepId, Integer actorId, String memberIds, Integer equipmentId,
                                String startTime, String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        Map<String, Object> st = getStep(stepId);
        if (st == null) { r.success = false; r.message = "스텝을 찾을 수 없습니다."; return r; }
        if ("done".equals(st.get("state"))) { r.success = false; r.message = "이미 완료된 스텝입니다."; return r; }

        Integer unitId = asInt(st.get("unit_id"));
        // ★ 모든 조립의 첫 작업은 프로그램 다운로드다
        AjaxResult dlOk = checkDownloadDone(unitId);
        if (!dlOk.success) return dlOk;
        AjaxResult lock = checkKidsDone(unitId, asInt(st.get("mat_id")), str(st.get("source")));
        if (!lock.success) return lock;

        // 유닛 로트 발번 (첫 스텝 시작 시)
        Map<String, Object> unit = getUnit(unitId);
        if (unit.get("lot_number") == null) {
            // 두 사람이 동시에 시작하면 같은 번호를 받을 수 있다.
            // 이미 쓰인 번호면 다음 번호로 밀어 최대 20회까지 비켜 간다.
            String lot = makeLot(asInt(unit.get("mat_id")));
            for (int i = 0; i < 20 && lotTaken(lot); i++) {
                lot = makeLot(asInt(unit.get("mat_id")));
            }
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

        // 조원 명단. 대표(Actor_id)는 위에서 스텝에 저장했고, 여기엔 대표도 1행 들어간다.
        this.workMemberService.save(WorkMemberService.SRC_MCELL_STEP,
                stepId, actorId, memberIds, user, spjangcd);
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

        AjaxResult pin = checkProcInspPassed(stepId, matId);   // ★ 공정검사 합격해야 완료
        if (!pin.success) return pin;

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

        /* ★ 형제 작지가 없어 자동생성된 경우 — 유닛 작지 밑으로 붙인다.
             부모가 없으면 조립 큐에 «단독 모듈 작지» 카드로 뜨고(WO-xxx),
             다음 유닛의 같은 모듈도 이 작지를 못 찾아 또 새로 만든다. */
        if (moduleJrId == null && unit.get("job_res_id") != null) {
            this.sqlRunner.execute("""
                    UPDATE job_res SET "Parent_id" = (SELECT COALESCE(u."Parent_id", u.id) FROM job_res u WHERE u.id = :ujr)
                     WHERE id = (SELECT "JobResponse_id" FROM mat_produce WHERE id = :mp) AND "Parent_id" IS NULL
                       AND id <> :ujr
                    """, new MapSqlParameterSource().addValue("ujr", asInt(unit.get("job_res_id"))).addValue("mp", mpId));
        }

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

        // ★ S/N 의 로트를 «실제로 빠진 로트»로 맞춘다.
        //   그 자재가 로트 하나에서만 빠졌으면 그 로트로 확정, 여러 로트에 걸쳤으면 손대지 않는다
        //   (작업자가 고른 로트가 모자라 FIFO 가 이어 채운 경우 — 어느 S/N 이 어느 로트인지 모른다).
        syncSnLots(stepId, mpId);

        // ★ 조원을 mat_produce 로 승계한다.
        //   스텝이 완료돼야 비로소 mat_produce 가 생긴다. 작업일보·실적현황은 mat_produce 축으로
        //   조원을 읽으므로 여기서 한 번 더 심어야 2공장 조립에도 조원이 보인다.
        String stepMembers = this.workMemberService.idsOf(WorkMemberService.SRC_MCELL_STEP, stepId);
        Integer leaderId = (actorId != null) ? actorId : asInt(st.get("actor_id"));
        this.workMemberService.save(WorkMemberService.SRC_MAT_PRODUCE,
                mpId, leaderId, stepMembers, user, spjangcd);

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

        /* ★ 검사가 «진행 중» 이면 분해하지 않는다.
             아래에서 판정 없는 회차(Verdict IS NULL)를 지우는데, 그게 바로
             검사원이 지금 입력하고 있는 기록이다. 조립에서 분해를 누르는 순간
             검사 화면의 입력이 말없이 사라졌다.
             분해를 막으면 작지 삭제도 자연히 막힌다(유닛이 wait 로 내려가지 않으므로).
           ※ 판정이 끝난 회차(pass·fail)는 이력이라 지우지 않으므로 여기서 막을 이유가 없다. */
        int inspecting = this.sqlRunner.queryForCount("""
                SELECT COUNT(*) FROM insp_result
                 WHERE "McellUnit_id" = :unitId
                   AND "Verdict" IS NULL
                   AND COALESCE(_status,'a') = 'a'
                """, new MapSqlParameterSource().addValue("unitId", unitId));
        if (inspecting > 0) {
            r.success = false;
            r.message = "이 유닛은 검사가 진행 중입니다.\n"
                    + "검사 화면에서 판정을 마치거나 «검사 취소» 한 뒤 분해하세요.";
            return r;
        }

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

        /* 재작업(ReworkYN) 판정 — «검사가 돌려보낸» 것만이다.
           재작업은 「여기가 잘못됐으니 다시 하세요」라는 지시가 있어야 성립한다.
           그 지시를 내리는 곳은 검사뿐이므로 기준은 reject 하나다.

           ★ pass·packed 는 뺀다 — 합격품을 조립에서 스스로 뜯는 것은
             재작업이 아니라 그냥 분해다. 사유도 조립 쪽 판단이다.
           ★ inspect_wait 도 아니다 — 검사를 «기다리는» 상태이지 받은 것이 아니다.
             최종 조립을 마치면 자동으로 이 상태가 되므로, 검사 전에 분해한 것까지
             재작업으로 잡혀 버렸다.
           ※ 수리(mc04)는 출하 후 반품 건이라 이 경로와 무관하다. */
        Map<String, Object> unitNow = getUnit(unitId);
        String unitState = (unitNow == null) ? "" : str(unitNow.get("state"));
        String reworkYn = "reject".equals(unitState) ? "Y" : "N";

        int rolled = 0;
        for (Map<String, Object> s : chain) {
            if (!"done".equals(s.get("state"))) continue;
            /* 분해하면 투입자재 편집분을 지워 BOM 기본값으로 되돌린다.
               분해는 자재를 바꾸려고 하는 경우가 많아, 직전 편집분이 남아 있으면
               작업자가 기본 구성을 다시 확인할 수 없다. (2공장 요청)
               ※ 실제 소비 내역은 mat_lot_cons 롤백으로 함께 정리되므로
                 여기서 초안만 비우면 다음 조회가 BOM 기준으로 계산된다. */
            this.sqlRunner.execute(
                    "UPDATE mcell_unit_step SET \"DraftBomJson\" = NULL WHERE id = :stepId",
                    new MapSqlParameterSource().addValue("stepId", asInt(s.get("step_id"))));

            AjaxResult rb = rollbackProduce(asInt(s.get("mp_id")), str(s.get("lot_number")), user);
            if (!rb.success) return rb;
            // 분해 = 같은 물건을 다시 조립하는 것. 로트번호와 담당자는 유지해 이력을 잇는다.
            MapSqlParameterSource p = new MapSqlParameterSource()
                    .addValue("stepId", asInt(s.get("step_id"))).addValue("userId", user.getId())
                    .addValue("reworkYn", reworkYn);
            /* ★ 분해한 스텝은 «대기» 로 되돌린다.
                 예전에는 'working' 으로 두어 「작업 시작은 눌러 둔」 상태가 남았다.
                 그러면 전부 분해해도 유닛이 계속 조립중으로 잡혀
                 작지 삭제가 「이미 착수한 유닛이 있다」며 막혔다.
                 분해는 그 단계를 없던 일로 하는 것이므로, 다시 할 때는
                 작업자·설비부터 새로 지정하는 것이 맞다.
               ※ StartTime·Actor_id 도 비운다 — 남겨 두면 시작하지 않은 스텝에
                 예전 작업자가 붙어 있어 작업실적현황이 어긋난다. */
            this.sqlRunner.execute("""
                    UPDATE mcell_unit_step
                       SET "State"='wait', "MatProduce_id"=NULL,
                           "StartTime"=NULL, "EndTime"=NULL, "Actor_id"=NULL, "Equipment_id"=NULL,
                           -- 이미 재작업이던 스텝은 그대로 둔다(한 번 재작업이면 계속 재작업)
                           "ReworkYN"=CASE WHEN :reworkYn='Y' THEN 'Y'
                                           ELSE COALESCE("ReworkYN",'N') END,
                           "_modified"=now(), "_modifier_id"=:userId
                     WHERE id=:stepId
                    """, p);
            rolled++;
        }

        // ★ 이 스텝의 공정검사는 분해 전 물건 기준이므로 무효 처리(이력은 남김) — 다시 조립하면 다시 검사
        voidProcInsp(stepId, user);

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
        /* ★ 남은 작업이 하나도 없으면 «대기» 로 되돌린다.
             예전에는 무조건 'assembling' 으로 찍어, 모든 스텝을 분해해 빈 유닛이
             되어도 「조립 중인 물건」으로 남았다. 그 탓에 작지 삭제가
             「이미 착수한 유닛이 있다」며 막혔고, 대시보드에도 조립중으로 잡혔다.

             기준은 스텝이다 — 하나라도 손댄 흔적(완료·진행·실적)이 있으면 조립중,
             전부 wait 이고 실적도 없으면 아무 일도 없던 상태와 같다. */
        this.sqlRunner.execute("""
                UPDATE mcell_unit mu
                   SET "State" = CASE WHEN EXISTS (
                                        SELECT 1 FROM mcell_unit_step st
                                         WHERE st."McellUnit_id" = mu.id
                                           AND (st."State" <> 'wait'
                                                OR st."MatProduce_id" IS NOT NULL))
                                      THEN 'assembling' ELSE 'wait' END
                     , "EndTime"=NULL,
                       "_modified"=now(), "_modifier_id"=:userId
                 WHERE mu.id=:unitId AND mu."State" <> 'packed'
                """, up);

        /* ★ 스텝·유닛을 모두 정리한 «뒤에» 작지 롤업을 한 번 더 돌린다.
             rollbackProduce 안에서도 부르지만, 그때는 아직 이 스텝이 done/working 이라
             「손댄 흔적이 있다」고 판단해 작지가 ordered 로 내려오지 못한다.
             (분해를 전부 마쳐도 job_res 만 working 으로 남아 삭제가 막히던 원인)
             유닛이 매달린 작지와 그 부모를 함께 재계산한다 —
             2공장은 유닛이 조립 자식에 붙고 그 위에 헤더 작지가 있다. */
        Map<String, Object> jrRow = this.sqlRunner.getRow("""
                SELECT mu."JobResponse_id" AS jr_id
                     , jr."Parent_id"      AS parent_id
                  FROM mcell_unit mu
                  LEFT JOIN job_res jr ON jr.id = mu."JobResponse_id"
                 WHERE mu.id = :unitId
                """, new MapSqlParameterSource().addValue("unitId", unitId));
        if (jrRow != null && jrRow.get("jr_id") != null) {
            this.productionCreateService.recalcJobRes(asInt(jrRow.get("jr_id")), user);
            if (jrRow.get("parent_id") != null) {
                this.productionCreateService.recalcJobRes(asInt(jrRow.get("parent_id")), user);
            }
        }

        r.message = rolled > 1
                ? ("상위 어셈블리 " + (rolled - 1) + "개도 함께 분해되었습니다. 작업을 다시 시작하세요.")
                : "분해 완료 · 작업을 다시 시작하세요.";
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
        voidProcInsp(stepId, user);
        this.sqlRunner.execute("""
                UPDATE mcell_unit_step
                   SET "State"='wait', "Actor_id"=NULL, "Equipment_id"=NULL,
                       "StartTime"=NULL, "EndTime"=NULL,
                       "_modified"=now(), "_modifier_id"=:userId
                 WHERE id=:stepId
                """, p);

        this.workMemberService.clear(WorkMemberService.SRC_MCELL_STEP, stepId);

        // 이 유닛에서 진행 중인 것이 하나도 안 남았으면 발번한 로트를 되돌린다.
        // 안 하면 그 번호가 영구히 비고 다음 유닛이 -002 부터 시작한다.
        releaseUnitLotIfEmpty(asInt(st.get("unit_id")), user);
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
                   -- ★ finished 도 포함 — 모듈 수량을 다 채운 뒤 분해·재조립하면
                   --   형제 작지가 finished 라 못 찾고, 부모 없는 새 작지(WO-xxx)가 생겨 조립 큐에 떴다.
                   AND jr2."State" IN ('ordered','working','finished')
                 ORDER BY (CASE WHEN jr2."State" IN ('ordered','working') THEN 0 ELSE 1 END), jr2.id
                 LIMIT 1
                """, p);
        return (row == null) ? null : asInt(row.get("id"));
    }

    // =====================================================================
    // ★ 주요자재 S/N — «유닛 × 자재 × 순번» 하나가 하나의 기록이다
    //   프로그램 다운로드에서 입력한 보드 S/N 과, 나중에 그 보드를 투입하는 어셈블리 스텝의 S/N 은
    //   같은 행이다. 어느 화면에서 고쳐도 한 행이 바뀌므로 둘이 어긋날 수 없다.
    //   McellUnitStep_id 는 «어느 스텝에서 투입됐는가» — 다운로드 단계에서는 비어 있다.
    //   재고와 무관한 기록이라 스텝 완료 후에도 고칠 수 있다.
    // =====================================================================

    /** 유닛의 S/N 전부 (화면이 자재별로 나눠 쓴다). stepId 만 와도 유닛을 찾아 준다. */
    public List<Map<String, Object>> getSnList(Integer unitId, Integer stepId) {
        Integer uid = resolveUnit(unitId, stepId);
        if (uid == null) return new ArrayList<>();
        return this.sqlRunner.getRows("""
                SELECT sn.id AS sn_id, sn."Material_id" AS mat_id, sn."SeqNo" AS seq_no,
                       sn."SerialNo" AS serial_no, sn."MatLot_id" AS mat_lot_id, sn."LotNumber" AS lot_number,
                       sn."McellUnitStep_id" AS step_id
                  FROM mcell_unit_sn sn
                 WHERE sn."McellUnit_id" = :uid AND COALESCE(sn."_status",'a')='a'
                 ORDER BY sn."Material_id", sn."SeqNo"
                """, new MapSqlParameterSource().addValue("uid", uid));
    }

    /**
     * S/N 1칸 저장. serial 이 비면 그 칸을 지운다.
     * @param stepId 어셈블리 스텝에서 입력하면 그 스텝(투입 위치)이 함께 기록된다. 다운로드에서는 null.
     */
    @Transactional
    public AjaxResult saveSn(Integer unitId, Integer stepId, Integer matId, Integer seqNo, String serial,
                             Integer matLotId, String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        Integer uid = resolveUnit(unitId, stepId);
        if (uid == null) { r.success = false; r.message = "유닛을 찾을 수 없습니다."; return r; }
        String sn = (serial == null) ? "" : serial.trim();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("uid", uid).addValue("stepId", stepId)
                .addValue("matId", matId).addValue("seq", seqNo == null ? 1 : seqNo)
                .addValue("sn", sn).addValue("lotId", matLotId)
                .addValue("userId", user.getId()).addValue("spjangcd", spjangcd);

        Map<String, Object> ex = this.sqlRunner.getRow("""
                SELECT id, "SerialNo" AS serial_no FROM mcell_unit_sn
                 WHERE "McellUnit_id"=:uid AND "Material_id"=:matId AND "SeqNo"=:seq
                   AND COALESCE("_status",'a')='a'
                """, p);
        String before = ex == null ? "" : String.valueOf(ex.get("serial_no") == null ? "" : ex.get("serial_no"));

        if (sn.isEmpty()) {
            this.sqlRunner.execute("""
                    DELETE FROM mcell_unit_sn
                     WHERE "McellUnit_id"=:uid AND "Material_id"=:matId AND "SeqNo"=:seq
                    """, p);
        } else if (ex == null) {
            this.sqlRunner.execute("""
                    INSERT INTO mcell_unit_sn ("McellUnit_id","McellUnitStep_id","Material_id","SeqNo","SerialNo",
                                               "MatLot_id","LotNumber","_status","_created","_creater_id",spjangcd)
                    VALUES (:uid,CAST(:stepId AS integer),:matId,:seq,:sn,CAST(:lotId AS integer),
                            (SELECT "LotNumber" FROM mat_lot WHERE id = CAST(:lotId AS integer)),
                            'a',now(),:userId,:spjangcd)
                    """, p);
        } else {
            this.sqlRunner.execute("""
                    UPDATE mcell_unit_sn
                       SET "SerialNo"=:sn,
                           "McellUnitStep_id"=COALESCE(CAST(:stepId AS integer),"McellUnitStep_id"),
                           "MatLot_id"=COALESCE(CAST(:lotId AS integer),"MatLot_id"),
                           "LotNumber"=COALESCE((SELECT "LotNumber" FROM mat_lot WHERE id = CAST(:lotId AS integer)),"LotNumber"),
                           "_modified"=now(),"_modifier_id"=:userId
                     WHERE id = :id
                    """, p.addValue("id", asInt(ex.get("id"))));
        }

        /* ★ 다운로드 «완료» 때 적어 둔 S/N(DoneSns) 과 비교한다.
             처음 입력은 변경이 아니고, 잘못 고쳤다가 원래대로 되돌리면 경고도 사라진다. */
        if (stepId != null) {
            Map<String, Object> dl = getDl(uid);
            String was = dl == null ? null : doneSnMap(dl).get(matId);
            if (was != null && !was.equals(sn == null ? "" : sn)) {
                r.message = "프로그램 다운로드 때 S/N(" + was + ")과 다릅니다. 보드를 바꿨다면 새 보드에 프로그램을 받으세요.";
            }
        }

        if (!sn.isEmpty()) {
            Map<String, Object> dup = this.sqlRunner.getRow("""
                    SELECT u."LotNumber" AS unit_lot, u."UnitNo" AS unit_no FROM mcell_unit_sn s
                      JOIN mcell_unit u ON u.id = s."McellUnit_id"
                     WHERE s."Material_id"=:matId AND s."SerialNo"=:sn AND COALESCE(s."_status",'a')='a'
                       AND NOT (s."McellUnit_id"=:uid AND s."SeqNo"=:seq)
                     LIMIT 1
                    """, p);
            if (dup != null) {
                r.message = "같은 S/N 이 다른 유닛(" + (dup.get("unit_lot") != null ? str(dup.get("unit_lot"))
                        : "#" + str(dup.get("unit_no"))) + ")에도 입력돼 있습니다. 확인하세요.";
            }
        }
        return r;
    }

    /** 완료 직후 — 자재가 로트 1개에서만 빠졌으면 그 자재 S/N 의 로트를 실제 로트로 맞추고 투입 스텝을 기록 */
    private void syncSnLots(Integer stepId, Integer mpId) {
        if (mpId == null) return;
        Map<String, Object> st = getStep(stepId);
        if (st == null) return;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("stepId", stepId).addValue("mpId", mpId).addValue("uid", asInt(st.get("unit_id")));
        // 이 스텝에서 실제로 소비한 자재의 S/N 은 이 스텝이 투입 위치다
        this.sqlRunner.execute("""
                UPDATE mcell_unit_sn sn SET "McellUnitStep_id" = :stepId
                 WHERE sn."McellUnit_id" = :uid AND sn."McellUnitStep_id" IS NULL
                   AND sn."Material_id" IN (SELECT ml."Material_id" FROM mat_lot_cons mlc
                                             JOIN mat_lot ml ON ml.id = mlc."MaterialLot_id"
                                            WHERE mlc."SourceDataPk" = :mpId
                                              AND COALESCE(mlc."SourceTableName",'mat_produce') = 'mat_produce')
                """, p);
        this.sqlRunner.execute("""
                UPDATE mcell_unit_sn sn
                   SET "MatLot_id" = x.lot_id, "LotNumber" = x.lot_no
                  FROM (SELECT ml."Material_id" AS mat_id, MIN(ml.id) AS lot_id, MIN(ml."LotNumber") AS lot_no
                          FROM mat_lot_cons mlc JOIN mat_lot ml ON ml.id = mlc."MaterialLot_id"
                         WHERE mlc."SourceDataPk" = :mpId
                           AND COALESCE(mlc."SourceTableName",'mat_produce') = 'mat_produce'
                           AND COALESCE(mlc."_status",'a') = 'a'
                         GROUP BY ml."Material_id"
                        HAVING COUNT(DISTINCT ml.id) = 1) x
                 WHERE sn."McellUnit_id" = :uid AND sn."McellUnitStep_id" = :stepId AND sn."Material_id" = x.mat_id
                """, p);
    }

    private Integer resolveUnit(Integer unitId, Integer stepId) {
        if (unitId != null) return unitId;
        if (stepId == null) return null;
        Map<String, Object> st = getStep(stepId);
        return st == null ? null : asInt(st.get("unit_id"));
    }

    // =====================================================================
    // ★ 프로그램 다운로드 — 모든 조립의 첫 작업
    //   보드(메인보드 · RT4 · LCD 등)에 프로그램을 올리고 S/N 을 남긴다. 재고는 움직이지 않는다
    //   (보드는 아직 선반에 있고, 실제 차감은 그 보드가 들어가는 어셈블리 스텝에서 일어난다).
    //   다운로드를 마쳐야 다른 조립 스텝을 시작할 수 있다.
    //
    //   카드에 올릴 보드 : 유닛 품목 BOM 전체에서 S/N 관리 대상(SnYN='Y')이면서
    //                      이름에 «보드/BOARD» 가 들어간 자재를 기본으로 깐다.
    //                      작업자가 «＋ 보드» 로 더하거나 빼면 그 목록(MatIds)을 따른다.
    // =====================================================================

    /** 다운로드 완료 시점의 보드 S/N — "matId=SN;matId=SN" */
    private Map<Integer, String> doneSnMap(Map<String, Object> dl) {
        Map<Integer, String> m = new HashMap<>();
        if (dl == null || !"done".equals(str(dl.get("state")))) return m;
        String raw = str(dl.get("done_sns"));
        if (raw == null) return m;
        for (String kv : raw.split(";")) {
            int k = kv.indexOf('=');
            if (k > 0) try { m.put(Integer.valueOf(kv.substring(0, k).trim()), kv.substring(k + 1)); } catch (NumberFormatException ignore) {}
        }
        return m;
    }

    private Map<String, Object> getDl(Integer unitId) {
        return this.sqlRunner.getRow("""
                SELECT d.id, d."State" AS state, d."MatIds" AS mat_ids, d."ChangedYN" AS changed_yn, d."DoneSns" AS done_sns,
                       d."Actor_id" AS actor_id, pe."Name" AS actor_name, d."Equipment_id" AS equipment_id,
                       to_char(d."StartTime",'yyyy-mm-dd hh24:mi') AS start_time,
                       to_char(d."EndTime",'yyyy-mm-dd hh24:mi')   AS end_time
                  FROM mcell_unit_dl d LEFT JOIN person pe ON pe.id = d."Actor_id"
                 WHERE d."McellUnit_id" = :uid
                """, new MapSqlParameterSource().addValue("uid", unitId));
    }

    /** 유닛 품목 BOM 전체(모든 레벨)의 자재 — 보드 칸 «변경» 후보 */
    public List<Map<String, Object>> getDlSlotCands(Integer unitId) {
        Map<String, Object> u = getUnit(unitId);
        if (u == null) return new ArrayList<>();
        return this.sqlRunner.getRows("""
                WITH RECURSIVE t AS (
                    SELECT :root::int AS mat_id, ARRAY[:root::int] AS path, 0 AS d
                    UNION ALL
                    SELECT bc."Material_id", t.path || bc."Material_id", t.d + 1
                      FROM t
                      JOIN bom b ON b."Material_id" = t.mat_id AND b."BOMType" = 'manufacturing'
                                AND CURRENT_DATE BETWEEN COALESCE(b."StartDate", DATE '0001-01-01')
                                                     AND COALESCE(b."EndDate",   DATE '9999-12-31')
                      JOIN bom_comp bc ON bc."BOM_id" = b.id
                     WHERE NOT (bc."Material_id" = ANY(t.path)) AND t.d < 8
                )
                SELECT DISTINCT m.id AS mat_id, m."Code" AS mat_code, m."Name" AS mat_name,
                       COALESCE(m."SnYN",'N') AS sn_yn,
                       (m."Name" ~* '(보드|board|b''d|pcb|기판|lcd)') AS boardish
                  FROM t JOIN material m ON m.id = t.mat_id
                 WHERE t.d > 0
                 ORDER BY 5 DESC, 4 DESC, 3
                """, new MapSqlParameterSource().addValue("root", asInt(u.get("mat_id"))));
    }

    /** ★ 보드 칸 지정 저장 — 모델(유닛 품목) 단위. mat_id 비우면 자동(품명)으로 되돌린다 */
    @Transactional
    public AjaxResult saveDlSlot(Integer unitId, Integer slot, Integer matId, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        if (slot == null || slot < 1 || slot > 3) { r.success = false; r.message = "칸 구분 오류"; return r; }
        Map<String, Object> u = getUnit(unitId);
        if (u == null) { r.success = false; r.message = "유닛을 찾을 수 없습니다."; return r; }
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("root", asInt(u.get("mat_id")))
                .addValue("slot", slot).addValue("mat", matId).addValue("userId", user.getId());
        this.sqlRunner.execute("DELETE FROM mcell_dl_slot WHERE \"RootMat_id\"=:root AND \"Slot\"=:slot", p);
        if (matId != null) {
            this.sqlRunner.execute("""
                    INSERT INTO mcell_dl_slot ("RootMat_id","Slot","Material_id","_creater_id")
                    VALUES (:root,:slot,:mat,:userId)
                    """, p);
        }
        r.message = matId == null ? DL_SLOTS[slot - 1] + " 을 자동(품명)으로 되돌렸습니다."
                : DL_SLOTS[slot - 1] + " 자재를 바꿨습니다. 같은 모델 전체에 적용됩니다.";
        return r;
    }

    /** 모델별 보드 칸 지정 (테이블 없으면 빈 맵) */
    private Map<Integer, Integer> dlSlotMap(Integer rootMatId) {
        Map<Integer, Integer> m = new HashMap<>();
        try {
            for (Map<String, Object> row : this.sqlRunner.getRows("""
                    SELECT "Slot" AS slot, "Material_id" AS mat_id FROM mcell_dl_slot WHERE "RootMat_id" = :root
                    """, new MapSqlParameterSource().addValue("root", rootMatId))) {
                m.put(asInt(row.get("slot")), asInt(row.get("mat_id")));
            }
        } catch (Exception ignore) {}
        return m;
    }

    /** 유닛 품목 BOM 전체(모든 레벨)에서 S/N 관리 대상 자재 */
    private List<Map<String, Object>> snMatsOfUnit(Integer unitId) {
        List<Map<String, Object>> auto = snMatsOfUnitAuto(unitId);
        Map<String, Object> u = getUnit(unitId);
        Map<Integer, Integer> fix = (u == null) ? Map.of() : dlSlotMap(asInt(u.get("mat_id")));
        if (fix.isEmpty()) return auto;
        // ★ 지정된 칸은 지정 자재로 — 품명 자동 결과를 덮는다
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : auto) {
            Integer sl = asInt(m.get("slot"));
            if (sl != null && fix.containsKey(sl)) continue;
            out.add(m);
        }
        if (!fix.isEmpty()) {
            for (Map.Entry<Integer, Integer> e : fix.entrySet()) {
                Map<String, Object> mm = this.sqlRunner.getRow("""
                        SELECT id AS mat_id, "Code" AS mat_code, "Name" AS mat_name FROM material WHERE id = :id
                        """, new MapSqlParameterSource().addValue("id", e.getValue()));
                if (mm == null) continue;
                Map<String, Object> row = new LinkedHashMap<>(mm);
                row.put("slot", e.getKey());
                row.put("pri", 0);
                row.put("fixed", "Y");
                out.add(0, row);
            }
        }
        return out;
    }

    private List<Map<String, Object>> snMatsOfUnitAuto(Integer unitId) {
        Map<String, Object> u = getUnit(unitId);
        if (u == null) return new ArrayList<>();
        List<Map<String, Object>> rows = this.sqlRunner.getRows("""
                WITH RECURSIVE t AS (
                    SELECT :root::int AS mat_id, ARRAY[:root::int] AS path, 0 AS d
                    UNION ALL
                    SELECT bc."Material_id", t.path || bc."Material_id", t.d + 1
                      FROM t
                      JOIN bom b ON b."Material_id" = t.mat_id AND b."BOMType" = 'manufacturing'
                                AND CURRENT_DATE BETWEEN COALESCE(b."StartDate", DATE '0001-01-01')
                                                     AND COALESCE(b."EndDate",   DATE '9999-12-31')
                      JOIN bom_comp bc ON bc."BOM_id" = b.id
                     WHERE NOT (bc."Material_id" = ANY(t.path)) AND t.d < 8
                )
                /* ★ 보드 3종 — 모델마다 이름이 다르다 (M2 = RT 2보드, M4 = RT4 보드 …).
                     메인보드도 «메인 PCB · MAIN B'D · 메인기판» 처럼 모델마다 달라 넓게 잡는다(LED 기판 · Ass'y 제외).
                     RT 뒤 숫자·공백은 무시하고, «보드/board» 가 붙은 것을 우선한다. */
                SELECT x.* FROM (
                  SELECT DISTINCT m.id AS mat_id, m."Code" AS mat_code, m."Name" AS mat_name,
                         CASE WHEN m."Name" ~* '(메인|main)\\s*_?\\s*(보드|board|b''?d|pcb|기판)' AND m."Name" !~* '(led|ass)' THEN 1
                              WHEN m."Name" ~* 'RT\\s*[0-9]*\\s*(보드|board|B''D)' THEN 2
                              WHEN m."Name" ~* 'LCD' AND m."Name" !~* 'ass' THEN 3 END AS slot,
                         CASE WHEN m."Name" ~* '(보드|board)' THEN 0 ELSE 1 END AS pri
                    FROM t JOIN material m ON m.id = t.mat_id
                ) x
                 WHERE x.slot IS NOT NULL
                 ORDER BY x.slot, x.pri, x.mat_name
                """, new MapSqlParameterSource().addValue("root", asInt(u.get("mat_id"))));
        return rows == null ? new ArrayList<>() : rows;
    }

    /** 다운로드 보드 = 고정 3종(메인보드 / RT / LCD) — 유닛 BOM 에서 이름으로 찾는다 */
    static final String[] DL_SLOTS = {"메인보드", "RT 보드", "LCD 보드"};

    private List<Integer> dlMatIds(Integer unitId, Map<String, Object> dl) {
        List<Integer> ids = new ArrayList<>();
        for (int k = 1; k <= 3; k++) {
            for (Map<String, Object> m : snMatsOfUnit(unitId)) {
                if (asInt(m.get("slot")) != null && asInt(m.get("slot")) == k) { ids.add(asInt(m.get("mat_id"))); break; }
            }
        }
        return ids;
    }

    /** 다운로드 카드 — 상태 + 보드 목록(S/N 포함) + 더할 수 있는 S/N 대상 자재 */
    public Map<String, Object> getDownload(Integer unitId) {
        Map<String, Object> dl = getDl(unitId);
        List<Map<String, Object>> all = snMatsOfUnit(unitId);
        Map<Integer, String> snBy = new HashMap<>();
        for (Map<String, Object> s : getSnList(unitId, null)) {
            if (asInt(s.get("seq_no")) == 1) snBy.put(asInt(s.get("mat_id")), str(s.get("serial_no")));
        }
        List<Map<String, Object>> boards = new ArrayList<>();
        Map<Integer, String> doneMap = doneSnMap(dl);
        List<String> changed = new ArrayList<>();
        for (int k = 1; k <= 3; k++) {
            final int kk = k;
            Map<String, Object> m = all.stream().filter(x -> asInt(x.get("slot")) != null && asInt(x.get("slot")) == kk)
                    .findFirst().orElse(null);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("slot_name", DL_SLOTS[k - 1]);
            if (m != null) {
                b.putAll(m);
                Integer mid = asInt(m.get("mat_id"));
                b.put("serial_no", snBy.get(mid));
                String was = doneMap.get(mid), now = snBy.get(mid) == null ? "" : snBy.get(mid);
                if (was != null && !was.equals(now)) { b.put("done_sn", was); changed.add(DL_SLOTS[k - 1] + " " + was + " → " + (now.isEmpty() ? "(없음)" : now)); }
            }
            boards.add(b);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", dl == null ? "wait" : str(dl.get("state")));
        out.put("changed_yn", changed.isEmpty() ? "N" : "Y");
        out.put("changed_list", changed);
        if (dl != null) {
            out.put("actor_id", dl.get("actor_id"));
            out.put("actor_name", dl.get("actor_name"));
            out.put("equipment_id", dl.get("equipment_id"));
            out.put("start_time", dl.get("start_time"));
            out.put("end_time", dl.get("end_time"));
        }
        out.put("boards", boards);
        return out;
    }

    @Transactional
    public AjaxResult startDownload(Integer unitId, Integer actorId, String memberIds, Integer equipmentId,
                                    String startTime, String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("uid", unitId).addValue("actorId", actorId).addValue("equipId", equipmentId)
                .addValue("st", (startTime == null || startTime.isBlank()) ? null : startTime)
                .addValue("userId", user.getId()).addValue("spjangcd", spjangcd);
        Map<String, Object> dl = getDl(unitId);
        if (dl == null) {
            this.sqlRunner.execute("""
                    INSERT INTO mcell_unit_dl ("McellUnit_id","State","Actor_id","Equipment_id","StartTime",
                                               "_created","_creater_id",spjangcd)
                    VALUES (:uid,'working',:actorId,:equipId,COALESCE(CAST(:st AS timestamp),LOCALTIMESTAMP),
                            now(),:userId,:spjangcd)
                    """, p);
        } else {
            this.sqlRunner.execute("""
                    UPDATE mcell_unit_dl
                       SET "State"=CASE WHEN "State"='done' THEN 'done' ELSE 'working' END,
                           "Actor_id"=:actorId, "Equipment_id"=:equipId,
                           "StartTime"=COALESCE(CAST(:st AS timestamp),"StartTime",LOCALTIMESTAMP),
                           "_modified"=now(), "_modifier_id"=:userId
                     WHERE "McellUnit_id"=:uid
                    """, p);
        }
        // ※ 다운로드는 1인 작업 — 대표 작업자만 남긴다(조원 명단 없음)
        return r;
    }

    /** 다운로드 완료 — 카드의 보드 S/N 이 다 들어와야 한다 */
    @Transactional
    public AjaxResult finishDownload(Integer unitId, String endTime, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        Map<String, Object> dl = getDl(unitId);
        if (dl == null || "wait".equals(str(dl.get("state")))) {
            r.success = false; r.message = "프로그램 다운로드를 먼저 시작하세요."; return r;
        }
        Map<String, Object> view = getDownload(unitId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> boards = (List<Map<String, Object>>) view.get("boards");
        List<String> miss = new ArrayList<>();
        for (Map<String, Object> b : boards) {
            if (b.get("mat_id") == null) {
                r.success = false; r.message = b.get("slot_name") + " 가 이 제품 BOM 에 없습니다. BOM 을 확인하세요."; return r;
            }
            String sn = str(b.get("serial_no"));
            if (sn == null || sn.isBlank()) miss.add(str(b.get("slot_name")));
        }
        if (!miss.isEmpty()) {
            r.success = false; r.message = "S/N 미입력 : " + String.join(", ", miss); return r;
        }
        this.sqlRunner.execute("""
                UPDATE mcell_unit_dl
                   SET "State"='done', "ChangedYN"='N',
                       "EndTime"=COALESCE(CAST(:et AS timestamp), LOCALTIMESTAMP),
                       "MatIds"=COALESCE("MatIds", :ids),
                       "DoneSns"=:doneSns,
                       "_modified"=now(), "_modifier_id"=:userId
                 WHERE "McellUnit_id"=:uid
                """, new MapSqlParameterSource().addValue("uid", unitId)
                .addValue("et", (endTime == null || endTime.isBlank()) ? null : endTime)
                .addValue("ids", joinIds(dlMatIds(unitId, dl)))
                .addValue("doneSns", boards.stream().filter(b -> b.get("mat_id") != null)
                        .map(b -> asInt(b.get("mat_id")) + "=" + str(b.get("serial_no")))
                        .reduce((x, y) -> x + ";" + y).orElse(null))
                .addValue("userId", user.getId()));
        r.message = "프로그램 다운로드 완료 · 조립을 시작할 수 있습니다";
        return r;
    }

    /** 완료 취소 — 다운로드는 재고를 안 움직이므로 언제든 되돌릴 수 있다(S/N 은 남긴다) */
    @Transactional
    public AjaxResult cancelDownload(Integer unitId, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        this.sqlRunner.execute("""
                UPDATE mcell_unit_dl SET "State"='working', "EndTime"=NULL, "_modified"=now(), "_modifier_id"=:userId
                 WHERE "McellUnit_id"=:uid
                """, new MapSqlParameterSource().addValue("uid", unitId).addValue("userId", user.getId()));
        return r;
    }

    /** 카드의 보드 목록 저장 (＋ 보드 / 빼기) */
    @Transactional
    public AjaxResult saveDownloadBoards(Integer unitId, String matIds, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        Map<String, Object> dl = getDl(unitId);
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("uid", unitId).addValue("ids", matIds == null ? "" : matIds).addValue("userId", user.getId());
        if (dl == null) {
            this.sqlRunner.execute("""
                    INSERT INTO mcell_unit_dl ("McellUnit_id","State","MatIds","_created","_creater_id")
                    VALUES (:uid,'wait',:ids,now(),:userId)
                    """, p);
        } else {
            this.sqlRunner.execute("""
                    UPDATE mcell_unit_dl SET "MatIds"=:ids, "_modified"=now(), "_modifier_id"=:userId
                     WHERE "McellUnit_id"=:uid
                    """, p);
        }
        return r;
    }

    /** 다운로드 시각 수정 */
    @Transactional
    public AjaxResult setDownloadTime(Integer unitId, String which, String value, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        Map<String, Object> dl = getDl(unitId);
        if (dl == null) { r.success = false; r.message = "다운로드 기록이 없습니다."; return r; }
        String st = "start".equals(which) ? value : str(dl.get("start_time"));
        String et = "end".equals(which) ? value : str(dl.get("end_time"));
        if (st != null && et != null && st.compareTo(et) > 0) {
            r.success = false; r.message = "시작이 완료보다 늦을 수 없습니다."; return r;
        }
        this.sqlRunner.execute("start".equals(which)
                        ? "UPDATE mcell_unit_dl SET \"StartTime\"=CAST(:v AS timestamp), \"_modified\"=now(), \"_modifier_id\"=:userId WHERE \"McellUnit_id\"=:uid"
                        : "UPDATE mcell_unit_dl SET \"EndTime\"=CAST(:v AS timestamp), \"_modified\"=now(), \"_modifier_id\"=:userId WHERE \"McellUnit_id\"=:uid",
                new MapSqlParameterSource().addValue("uid", unitId).addValue("v", value).addValue("userId", user.getId()));
        return r;
    }

    /** 조립 스텝 시작 전 확인 — 다운로드가 끝나야 한다 */
    private AjaxResult checkDownloadDone(Integer unitId) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        Map<String, Object> dl = getDl(unitId);
        if (dl == null || !"done".equals(str(dl.get("state")))) {
            r.success = false;
            r.message = "프로그램 다운로드를 먼저 완료하세요. (모든 조립의 첫 작업입니다)";
        }
        return r;
    }

    private static String joinIds(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (Integer i : ids) { if (sb.length() > 0) sb.append(','); sb.append(i); }
        return sb.toString();
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
     *
     * ★ 일련번호는 mat_lot 만 봐서는 안 된다.
     *   유닛 로트는 "발번 ≠ 재고 생성" 이라 첫 스텝 시작 시 mcell_unit 에만 쓰이고
     *   mat_lot 행은 스텝을 완료해야 생긴다. mat_lot 만 세면 같은 날 두 대를 시작할 때
     *   둘 다 -001 을 받는다. 실제로 그 사고가 났다.
     *   → 번호를 들고 있을 수 있는 세 곳(mat_lot / mcell_unit / mcell_unit_step)을
     *     모두 훑어 최댓값을 구한다.
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
                SELECT COALESCE(MAX(seq), 0) AS mx
                  FROM (
                        SELECT CAST(RIGHT("LotNumber",3) AS integer) AS seq
                          FROM mat_lot
                         WHERE "LotNumber" LIKE :head AND RIGHT("LotNumber",3) ~ '^[0-9]{3}$'
                        UNION ALL
                        SELECT CAST(RIGHT("LotNumber",3) AS integer)
                          FROM mcell_unit
                         WHERE "LotNumber" LIKE :head AND RIGHT("LotNumber",3) ~ '^[0-9]{3}$'
                        UNION ALL
                        SELECT CAST(RIGHT("LotNumber",3) AS integer)
                          FROM mcell_unit_step
                         WHERE "LotNumber" LIKE :head AND RIGHT("LotNumber",3) ~ '^[0-9]{3}$'
                  ) t
                """, lp);
        int next = (row == null || asInt(row.get("mx")) == null) ? 1 : asInt(row.get("mx")) + 1;
        return head + String.format("%03d", next);
    }

    /**
     * 유닛 로트 회수 — 아무 실적도 없는 상태로 되돌아갔을 때만.
     *
     * ★ 왜 조건을 다는가
     *   분해(재조립)는 「로트번호 유지」가 원칙이다. 같은 물건을 다시 조립하는 것이라
     *   번호가 바뀌면 검사·포장 이력이 끊긴다. 그래서 롤백 일반에는 손대지 않는다.
     *
     *   다만 「작업 삭제」로 스텝이 전부 wait 으로 돌아가고 실적(mat_produce)도 없는
     *   경우는 얘기가 다르다. 그건 시작 자체를 무른 것이고, 번호만 남으면
     *   그 번호가 영구히 비어 버린다(다음 유닛은 -002 를 받는다).
     *   → 실적이 하나도 없고 모든 스텝이 wait 일 때만 비운다.
     */
    /** 이미 누군가 쓰고 있는 로트번호인지 (동시 시작 대비) */
    private boolean lotTaken(String lot) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("lot", lot);
        Map<String, Object> row = this.sqlRunner.getRow("""
                SELECT 1 AS x FROM mat_lot        WHERE "LotNumber" = :lot
                 UNION ALL
                SELECT 1     FROM mcell_unit      WHERE "LotNumber" = :lot
                 UNION ALL
                SELECT 1     FROM mcell_unit_step WHERE "LotNumber" = :lot
                 LIMIT 1
                """, p);
        return row != null;
    }

    private void releaseUnitLotIfEmpty(Integer unitId, User user) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("unitId", unitId);
        Map<String, Object> row = this.sqlRunner.getRow("""
                SELECT COUNT(*) FILTER (WHERE st."State" <> 'wait')        AS busy
                     , COUNT(*) FILTER (WHERE st."MatProduce_id" IS NOT NULL) AS produced
                  FROM mcell_unit_step st
                 WHERE st."McellUnit_id" = :unitId
                """, p);
        if (row == null) return;
        Integer busy = asInt(row.get("busy"));
        Integer produced = asInt(row.get("produced"));
        if ((busy != null && busy > 0) || (produced != null && produced > 0)) return;

        MapSqlParameterSource up = new MapSqlParameterSource()
                .addValue("unitId", unitId).addValue("userId", user.getId());
        this.sqlRunner.execute("""
                UPDATE mcell_unit
                   SET "LotNumber" = NULL, "State" = 'wait', "StartTime" = NULL,
                       "_modified" = now(), "_modifier_id" = :userId
                 WHERE id = :unitId
                """, up);
    }

    private static Integer asInt(Object o) {
        return (o == null) ? null : ((Number) o).intValue();
    }

    private static double toD(Object o) {
        return (o == null) ? 0d : Double.parseDouble(String.valueOf(o));
    }

    // =====================================================================
    // ★ 유닛 제품 번호 — 작지에서 자동으로 붙는 SN-001 은 임시 표시다. 실제 S/N 으로 바꿔 쓸 수 있다.
    //   로트번호(재고·실적 추적 키)는 바꾸지 않는다 — 표시·문서용 번호만 바뀐다.
    // =====================================================================
    @Transactional
    public AjaxResult setUnitSerial(Integer unitId, String serial, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        String sn = serial == null ? "" : serial.trim();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("uid", unitId)
                .addValue("sn", sn.isEmpty() ? null : sn).addValue("userId", user.getId());
        if (!sn.isEmpty()) {
            Map<String, Object> dup = this.sqlRunner.getRow("""
                    SELECT mu."LotNumber" AS lot FROM mcell_unit mu
                     WHERE mu."SerialNo" = :sn AND mu.id <> :uid AND COALESCE(mu."_status",'a')='a' LIMIT 1
                    """, p);
            if (dup != null) {
                r.success = false; r.message = "이미 다른 유닛(" + dup.get("lot") + ")에 쓰인 번호입니다."; return r;
            }
        }
        this.sqlRunner.execute("""
                UPDATE mcell_unit SET "SerialNo"=:sn, "_modified"=now(), "_modifier_id"=:userId WHERE id=:uid
                """, p);
        r.message = sn.isEmpty() ? "임시번호로 되돌렸습니다." : "제품 번호를 " + sn + " 으로 바꿨습니다.";
        return r;
    }

    // =====================================================================
    // ★ 공정검사 (조립 중간 검사, F803-6)
    //   · 양식 = insp_form(InspType='process') ↔ insp_form_mat 로 어셈블리 품목에 연결
    //   · 스텝 «작업중» 에 검사 → 합격해야 «모듈 완료 / 최종 완료» 가능
    //   · 불합격 → 고친 뒤 재검사(새 회차). 분해하면 그 스텝의 검사는 무효(void)
    // =====================================================================

    private Map<String, Object> procForm(Integer matId) {
        return this.sqlRunner.getRow("""
                SELECT f.id AS form_id, f."Code" AS form_code, f."Name" AS form_name
                  FROM insp_form f JOIN insp_form_mat fm ON fm."InspForm_id" = f.id
                 WHERE fm."Material_id" = :matId AND f."InspType" = 'process'
                   AND COALESCE(f."UseYN",'Y') = 'Y' AND COALESCE(f."_status",'a') = 'a'
                 ORDER BY f."SeqNo", f.id LIMIT 1
                """, new MapSqlParameterSource().addValue("matId", matId));
    }

    private Map<String, Object> procLatest(Integer stepId, Integer formId) {
        return this.sqlRunner.getRow("""
                SELECT x.id AS result_id, x."TryNo" AS try_no, x."State" AS state, x."Verdict" AS verdict,
                       x."FailReason" AS fail_reason, x."Actor_id" AS actor_id, pr."Name" AS actor_name,
                       to_char(x."StartTime",'yyyy-mm-dd hh24:mi') AS start_time,
                       to_char(x."EndTime",'yyyy-mm-dd hh24:mi')   AS end_time
                  FROM mcell_proc_insp x LEFT JOIN person pr ON pr.id = x."Actor_id"
                 WHERE x."McellUnitStep_id" = :stepId AND x."InspForm_id" = :formId
                   AND x."State" <> 'void' AND COALESCE(x."_status",'a') = 'a'
                 ORDER BY x."TryNo" DESC LIMIT 1
                """, new MapSqlParameterSource().addValue("stepId", stepId).addValue("formId", formId));
    }

    private AjaxResult checkProcInspPassed(Integer stepId, Integer matId) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        Map<String, Object> f = procForm(matId);
        if (f == null) return r;
        Map<String, Object> cur = procLatest(stepId, asInt(f.get("form_id")));
        if (cur == null || !"pass".equals(str(cur.get("verdict")))) {
            r.success = false;
            r.message = "공정검사(" + str(f.get("form_name")) + ") 합격 후 완료할 수 있습니다."
                    + (cur != null && "fail".equals(str(cur.get("verdict"))) ? "\n불합격 — 조치 후 재검사하세요." : "");
        }
        return r;
    }

    private void voidProcInsp(Integer stepId, User user) {
        this.sqlRunner.execute("""
                UPDATE mcell_proc_insp SET "State"='void', "_modified"=now(), "_modifier_id"=:userId
                 WHERE "McellUnitStep_id"=:stepId AND "State"<>'void'
                """, new MapSqlParameterSource().addValue("stepId", stepId).addValue("userId", user.getId()));
    }

    /** 공정검사 상세 — 양식 항목 + 현재 회차 입력값 + 이력 */
    public Map<String, Object> getProcInsp(Integer stepId) {
        Map<String, Object> st = getStep(stepId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (st == null) return out;
        Map<String, Object> f = procForm(asInt(st.get("mat_id")));
        if (f == null) return out;
        Integer formId = asInt(f.get("form_id"));
        out.put("form", f);
        out.put("items", this.sqlRunner.getRows("""
                SELECT i.id AS item_id, i."SeqNo" AS seq_no, i."ItemName" AS item_name,
                       i."Method" AS method, i."Criteria" AS criteria
                  FROM insp_form_item i
                 WHERE i."InspForm_id" = :formId AND COALESCE(i."_status",'a') = 'a'
                 ORDER BY i."SeqNo", i.id
                """, new MapSqlParameterSource().addValue("formId", formId)));
        Map<String, Object> cur = procLatest(stepId, formId);
        out.put("result", cur);
        out.put("values", cur == null ? List.of() : this.sqlRunner.getRows("""
                SELECT "InspFormItem_id" AS item_id, "Result" AS result, "Value" AS value
                  FROM mcell_proc_insp_item WHERE "ProcInsp_id" = :rid
                """, new MapSqlParameterSource().addValue("rid", asInt(cur.get("result_id")))));
        out.put("history", this.sqlRunner.getRows("""
                SELECT x."TryNo" AS try_no, x."Verdict" AS verdict, x."FailReason" AS fail_reason,
                       pr."Name" AS actor_name, to_char(x."EndTime",'yyyy-mm-dd hh24:mi') AS end_time
                  FROM mcell_proc_insp x LEFT JOIN person pr ON pr.id = x."Actor_id"
                 WHERE x."McellUnitStep_id" = :stepId AND x."InspForm_id" = :formId
                   AND x."State" <> 'void' AND x."Verdict" IS NOT NULL
                 ORDER BY x."TryNo" DESC
                """, new MapSqlParameterSource().addValue("stepId", stepId).addValue("formId", formId)));
        return out;
    }

    /** 공정검사 시작 / 재검사 — 열린 회차가 있으면 그걸 쓰고, 없으면(또는 불합격 뒤) 새 회차 */
    @Transactional
    public AjaxResult startProcInsp(Integer stepId, Integer actorId, String spjangcd, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        Map<String, Object> st = getStep(stepId);
        if (st == null) { r.success = false; r.message = "스텝을 찾을 수 없습니다."; return r; }
        if (!"working".equals(str(st.get("state")))) {
            r.success = false; r.message = "조립 «작업중» 일 때 공정검사를 할 수 있습니다."; return r;
        }
        Map<String, Object> f = procForm(asInt(st.get("mat_id")));
        if (f == null) { r.success = false; r.message = "이 어셈블리에 연결된 공정검사 양식이 없습니다."; return r; }
        Integer formId = asInt(f.get("form_id"));
        Map<String, Object> cur = procLatest(stepId, formId);
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("unitId", asInt(st.get("unit_id"))).addValue("stepId", stepId).addValue("formId", formId)
                .addValue("actorId", actorId).addValue("userId", user.getId()).addValue("spjangcd", spjangcd);
        if (cur != null && cur.get("verdict") == null) {
            this.sqlRunner.execute("""
                    UPDATE mcell_proc_insp SET "Actor_id"=COALESCE(:actorId,"Actor_id"), "_modified"=now(), "_modifier_id"=:userId
                     WHERE id = """ + asInt(cur.get("result_id")), p);
            r.data = Map.of("result_id", asInt(cur.get("result_id")));
            return r;
        }
        if (cur != null && "pass".equals(str(cur.get("verdict")))) {
            r.success = false; r.message = "이미 합격했습니다."; return r;
        }
        Map<String, Object> ins = this.sqlRunner.getRow("""
                INSERT INTO mcell_proc_insp ("McellUnit_id","McellUnitStep_id","InspForm_id","TryNo","State",
                                             "Actor_id","StartTime","_status","_created","_creater_id",spjangcd)
                SELECT :unitId,:stepId,:formId,
                       COALESCE((SELECT MAX("TryNo") FROM mcell_proc_insp
                                  WHERE "McellUnitStep_id"=:stepId AND "InspForm_id"=:formId),0)+1,
                       'working',:actorId,LOCALTIMESTAMP,'a',now(),:userId,:spjangcd
                RETURNING id
                """, p);
        r.data = Map.of("result_id", asInt(ins.get("id")));
        return r;
    }

    /** 항목 합/불 · 측정값 저장 (result 비우면 삭제) */
    @Transactional
    public AjaxResult saveProcInspItem(Integer resultId, Integer itemId, String result, String value, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        Map<String, Object> cur = this.sqlRunner.getRow(
                "SELECT \"Verdict\" AS verdict, \"State\" AS state FROM mcell_proc_insp WHERE id=:id",
                new MapSqlParameterSource().addValue("id", resultId));
        if (cur == null || "void".equals(str(cur.get("state")))) { r.success = false; r.message = "검사 회차를 찾을 수 없습니다."; return r; }
        if (cur.get("verdict") != null) { r.success = false; r.message = "판정이 확정된 회차입니다. «판정 수정» 후 고치세요."; return r; }
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("rid", resultId).addValue("iid", itemId)
                .addValue("res", (result == null || result.isBlank()) ? null : result)
                .addValue("val", (value == null || value.isBlank()) ? null : value.trim())
                .addValue("userId", user.getId());
        if (p.getValue("res") == null && p.getValue("val") == null) {
            this.sqlRunner.execute("DELETE FROM mcell_proc_insp_item WHERE \"ProcInsp_id\"=:rid AND \"InspFormItem_id\"=:iid", p);
            return r;
        }
        this.sqlRunner.execute("""
                INSERT INTO mcell_proc_insp_item ("ProcInsp_id","InspFormItem_id","Result","Value","_created","_creater_id")
                VALUES (:rid,:iid,:res,:val,now(),:userId)
                ON CONFLICT ("ProcInsp_id","InspFormItem_id")
                DO UPDATE SET "Result"=EXCLUDED."Result", "Value"=EXCLUDED."Value"
                """, p);
        return r;
    }

    /** 판정 확정 — 모든 항목 합/불 입력 필요. 하나라도 불합격이면 fail */
    @Transactional
    public AjaxResult judgeProcInsp(Integer resultId, String failReason, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("rid", resultId)
                .addValue("reason", failReason).addValue("userId", user.getId());
        Map<String, Object> chk = this.sqlRunner.getRow("""
                SELECT x."Verdict" AS verdict, x."State" AS state,
                       (SELECT COUNT(*) FROM insp_form_item i
                         WHERE i."InspForm_id" = x."InspForm_id" AND COALESCE(i."_status",'a')='a') AS need,
                       (SELECT COUNT(*) FROM mcell_proc_insp_item v
                         WHERE v."ProcInsp_id" = x.id AND v."Result" IS NOT NULL) AS got,
                       (SELECT COUNT(*) FROM mcell_proc_insp_item v
                         WHERE v."ProcInsp_id" = x.id AND v."Result" = 'fail') AS bad
                  FROM mcell_proc_insp x WHERE x.id = :rid
                """, p);
        if (chk == null || "void".equals(str(chk.get("state")))) { r.success = false; r.message = "검사 회차를 찾을 수 없습니다."; return r; }
        if (chk.get("verdict") != null) { r.success = false; r.message = "이미 확정된 회차입니다."; return r; }
        int need = asInt(chk.get("need")), got = asInt(chk.get("got")), bad = asInt(chk.get("bad"));
        if (got < need) { r.success = false; r.message = "미입력 항목이 " + (need - got) + "건 남았습니다."; return r; }
        String v = bad > 0 ? "fail" : "pass";
        p.addValue("v", v);
        this.sqlRunner.execute("""
                UPDATE mcell_proc_insp SET "Verdict"=:v, "State"='done', "EndTime"=LOCALTIMESTAMP,
                       "FailReason"=CASE WHEN :v='fail' THEN :reason END,
                       "_modified"=now(), "_modifier_id"=:userId
                 WHERE id=:rid
                """, p);
        r.data = Map.of("verdict", v);
        r.message = "pass".equals(v) ? "공정검사 합격" : "공정검사 불합격 — 조치 후 재검사하세요";
        return r;
    }

    /** 판정 수정 — 스텝이 아직 «작업중» 일 때만 최신 회차를 다시 연다(완료된 스텝은 분해 후) */
    @Transactional
    public AjaxResult reviseProcInsp(Integer resultId, User user) {
        AjaxResult r = new AjaxResult();
        r.success = true;
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("rid", resultId).addValue("userId", user.getId());
        Map<String, Object> x = this.sqlRunner.getRow("""
                SELECT x."State" AS state, st."State" AS step_state,
                       x."TryNo" AS try_no,
                       (SELECT MAX(y."TryNo") FROM mcell_proc_insp y
                         WHERE y."McellUnitStep_id" = x."McellUnitStep_id" AND y."InspForm_id" = x."InspForm_id"
                           AND y."State" <> 'void') AS max_try
                  FROM mcell_proc_insp x JOIN mcell_unit_step st ON st.id = x."McellUnitStep_id"
                 WHERE x.id = :rid
                """, p);
        if (x == null || "void".equals(str(x.get("state")))) { r.success = false; r.message = "검사 회차를 찾을 수 없습니다."; return r; }
        if (!"working".equals(str(x.get("step_state")))) {
            r.success = false; r.message = "완료된 어셈블리입니다. 분해 후 다시 검사하세요."; return r;
        }
        if (!asInt(x.get("try_no")).equals(asInt(x.get("max_try")))) {
            r.success = false; r.message = "최신 회차만 수정할 수 있습니다."; return r;
        }
        this.sqlRunner.execute("""
                UPDATE mcell_proc_insp SET "Verdict"=NULL, "State"='working', "EndTime"=NULL,
                       "_modified"=now(), "_modifier_id"=:userId WHERE id=:rid
                """, p);
        return r;
    }

    private static String str(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }
}