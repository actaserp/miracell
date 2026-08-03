package mes.app.production.service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import mes.domain.services.SqlRunner;

/**
 * 작업실적현황 (1공장 키트 / 2공장 M-CELL) — 조회 전용.
 *
 * ★ 왜 ProdResultListService 에 붙이지 않았나
 *   저쪽은 "작지 × 차수" 축이고 jr.State='finished' 를 요구한다.
 *   이 화면은 "작지 × 공정" 축이고 진행 중인 것을 보는 게 목적이라 축이 정반대다.
 *   같은 서비스에 넣으면 한쪽을 고칠 때 다른 쪽이 조용히 흔들린다.
 *
 * ★ 세척(bsc01)·멸균(bsc04)은 A뷰에 없다
 *   wash_work / steril_batch 에 JobResponse_id 가 없어서 작지에 귀속시킬 수 없다.
 *   (클린룸 5번 창고를 여러 작지가 공유하므로 로트로도 못 가른다)
 *   → 작지 무관한 별도 섹션으로 뽑는다. getWashList / getSterilList.
 *
 * ★ 포장(bsc05)은 자식 작지가 2개다
 *   CK 생산(반제품) + 키트 결합(완제품)이 같은 공정코드를 쓴다.
 *   산출품목으로 갈라 화면에서 2칸으로 보여준다. resolveProcKey 참조.
 */
@Service
public class WorkStatusService {

    @Autowired
    SqlRunner sqlRunner;

    /** SqlRunner.getRows 는 오류 시 null 을 반환한다 (빈 리스트 아님) */
    private static List<Map<String, Object>> nz(List<Map<String, Object>> rows) {
        return (rows == null) ? new ArrayList<>() : rows;
    }

    private static MapSqlParameterSource period(String dateFrom, String dateTo, String spjangcd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("date_from", blank(dateFrom) ? null : Timestamp.valueOf(dateFrom + " 00:00:00"));
        p.addValue("date_to",   blank(dateTo)   ? null : Timestamp.valueOf(dateTo   + " 23:59:59"));
        p.addValue("spjangcd", (spjangcd == null || spjangcd.isBlank()) ? "ZZ" : spjangcd);
        return p;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    /**
     * A뷰(작지 기준)의 부모 작지 선정 조건.
     *
     * ★ 날짜는 「어떤 작지를 볼지」만 정한다. 그 작지의 실적은 기간과 무관하게 전부 더한다.
     *   (기간 안 실적만 세면 지난달에 끝낸 조립이 이번달 조회에서 「대기」로 보인다 —
     *    실제로 작지 2439 가 7/27 에 조립 100 을 채웠는데 8월 조회에서 대기로 나왔다)
     *
     * ★ 날짜는 선택값이다. 비우면 전체 기간 — 기본 사용 흐름이 "지금 뭐가 남았나"라서
     *   상태 필터(open)가 주 필터이고 날짜는 과거를 들출 때만 쓴다.
     */
    private static String parentWhere(String a) {
        return """
                   AND (CAST(:date_from AS timestamp) IS NULL
                        OR {a}."ProductionDate" >= CAST(:date_from AS timestamp))
                   AND (CAST(:date_to   AS timestamp) IS NULL
                        OR {a}."ProductionDate" <= CAST(:date_to   AS timestamp))
                   AND {a}.spjangcd = :spjangcd
                   AND {a}."Parent_id" IS NULL
                   AND COALESCE({a}._status, 'a') = 'a'
                   AND (CAST(:state AS varchar) IS NULL
                        OR (CAST(:state AS varchar) = 'open' AND {a}."State" <> 'finished')
                        OR (CAST(:state AS varchar) = 'done' AND {a}."State"  = 'finished'))
            """.replace("{a}", a);
    }

    /**
     * 공정 → 화면 칸(proc_key) 매핑 SQL 조각.
     *
     * bsc05 가 CK 생산과 키트 결합 둘 다라서 산출품목으로 가른다.
     * 완제품(product)이면 결합, 아니면 CK 생산.
     */
    private static final String PROC_KEY_CASE = """
            CASE p."Code"
                 WHEN 'bsc02' THEN 'assy'
                 WHEN 'bsc03' THEN 'blister'
                 WHEN 'bsc06' THEN 'welding'
                 WHEN 'bsc05' THEN 'pack'
                 WHEN 'bsc01' THEN 'wash'
                 WHEN 'bsc04' THEN 'steril'
                 ELSE p."Code"
            END
            """;

    /**
     * 포장(bsc05)은 자식 작지가 둘이다 — CK 생산(반제품)과 키트 결합(완제품).
     * 목업이 「포장」 한 칸이라 같은 key 로 접되, 어느 쪽인지는 sub_key 로 구분해
     * 모달에서 나눠 보여준다. 두 자식이 다 끝나야 그 칸이 완료다.
     *
     * ★ MaterialType 은 material 이 아니라 mat_grp 에 있다.
     *   (resolveSourceStore 도 mat_grp mg ON mg.id = m."MaterialGroup_id" 로 조인한다)
     */
    private static final String SUB_KEY_CASE = """
            CASE WHEN p."Code" <> 'bsc05' THEN NULL
                 WHEN COALESCE(cmg."MaterialType",'') = 'product'
                   OR cm."Code" LIKE '%FG%'                THEN 'pack'
                 ELSE 'ckprod' END
            """;

    // =================================================================
    // 1공장 — A뷰 / B뷰 공용 (부모 작지 + 공정별 자식)
    // =================================================================

    /**
     * 키트 작업지시 목록. 한 행 = 부모 작지(Parent_id IS NULL, 공정 없음).
     *
     * 셀 상태는 화면에서 조립하도록 자식 행을 그대로 내린다.
     * (한 행에 공정을 피벗해 넣으면 공정이 늘 때마다 SQL 을 고쳐야 한다)
     */
    public List<Map<String, Object>> getF1Orders(String dateFrom, String dateTo,
                                                 String line, String state, String spjangcd) {
        MapSqlParameterSource p = period(dateFrom, dateTo, spjangcd);
        p.addValue("line", blank(line) ? null : line.toUpperCase());
        p.addValue("state", (blank(state) || "all".equals(state)) ? null : state);

        String sql = """
                SELECT jr.id                       AS pk
                     , jr."WorkOrderNumber"        AS wo
                     , jr."State"                  AS state
                     , fn_code_name('job_state', jr."State") AS state_name
                     , COALESCE(jr."OrderQty", 0)  AS order_qty
                     , COALESCE(jr."GoodQty", 0)   AS good_qty
                     , COALESCE(jr."DefectQty", 0) AS defect_qty
                     , to_char(jr."ProductionDate", 'yyyy-mm-dd') AS prod_date
                     , m.id                        AS mat_pk
                     , m."Code"                    AS mat_code
                     , m."Name"                    AS mat_name
                     , CASE WHEN UPPER(COALESCE(m."Code",'')) LIKE 'BMSC%'
                            THEN 'BMSC' ELSE 'BSC' END AS line
                  FROM job_res jr
                  LEFT JOIN material    m  ON m.id  = jr."Material_id"
                  LEFT JOIN work_center wc ON wc.id = jr."WorkCenter_id"
                  LEFT JOIN process     p  ON p.id  = wc."Process_id"
                 WHERE p.id IS NULL
                   -- ★ 공장은 품목이 들고 있다. 안 거르면 M-CELL(2공장)이 섞인다
                   AND COALESCE(m."Factory_id", 1) = 1
                   AND (CAST(:line AS varchar) IS NULL
                        OR CASE WHEN UPPER(COALESCE(m."Code",'')) LIKE 'BMSC%'
                                THEN 'BMSC' ELSE 'BSC' END = CAST(:line AS varchar))
                """ + parentWhere("jr")
                + " ORDER BY jr.\"ProductionDate\" DESC, jr.\"WorkOrderNumber\" ASC ";

        return nz(this.sqlRunner.getRows(sql, p));
    }

    /**
     * 공정별 자식 작지. 부모 pk 목록에 대해 한 번에 긁는다.
     *
     * has_produce — ordered 인데 차수가 있는 경우를 구분하기 위한 값.
     *   patchJobResState 가 못 올린 작지가 실제로 있어서 State 만으로는 대기/작업중이 안 갈린다.
     */
    public List<Map<String, Object>> getF1Steps(String dateFrom, String dateTo,
                                                String state, String spjangcd) {
        MapSqlParameterSource p = period(dateFrom, dateTo, spjangcd);
        p.addValue("state", (blank(state) || "all".equals(state)) ? null : state);

        String sql = """
                SELECT c.id                        AS pk
                     , c."Parent_id"               AS parent_pk
                     , c."WorkIndex"               AS work_idx
                     , p."Code"                    AS proc_code
                     , p."Name"                    AS proc_name
                     , {PROC_KEY}                   AS proc_key
                     , {SUB_KEY}                    AS sub_key
                     , c."State"                   AS state
                     , COALESCE(c."OrderQty", 0)   AS order_qty
                     , COALESCE(c."GoodQty", 0)    AS good_qty
                     , COALESCE(c."DefectQty", 0)  AS defect_qty
                     , cm."Code"                   AS mat_code
                     , cm."Name"                   AS mat_name
                     , mps.produce_cnt
                     , COALESCE(mps.mp_good, 0)    AS mp_good
                     , COALESCE(mps.mp_defect, 0)  AS mp_defect
                     , COALESCE(mps.open_cnt, 0)   AS open_cnt
                  FROM job_res c
                  JOIN job_res pr ON pr.id = c."Parent_id"
                  LEFT JOIN material    cm  ON cm.id  = c."Material_id"
                  LEFT JOIN mat_grp     cmg ON cmg.id = cm."MaterialGroup_id"
                  LEFT JOIN work_center wc  ON wc.id  = c."WorkCenter_id"
                  LEFT JOIN process     p   ON p.id   = wc."Process_id"
                  LEFT JOIN LATERAL (
                        SELECT COUNT(*)                                     AS produce_cnt
                             , SUM(COALESCE(mp."GoodQty",0))                AS mp_good
                             , SUM(COALESCE(mp."DefectQty",0))              AS mp_defect
                             , COUNT(*) FILTER (WHERE mp."State" <> 'finished') AS open_cnt
                          FROM mat_produce mp
                         WHERE mp."JobResponse_id" = c.id
                           AND COALESCE(mp._status,'a') = 'a'
                  ) mps ON true
                 WHERE COALESCE(c._status, 'a') = 'a'
                """.replace("{PROC_KEY}", PROC_KEY_CASE).replace("{SUB_KEY}", SUB_KEY_CASE)
                + parentWhere("pr")
                + " ORDER BY c.\"Parent_id\", c.\"WorkIndex\", c.id ";

        return nz(this.sqlRunner.getRows(sql, p));
    }

    /**
     * 미전개 작지 — Parent_id 가 없는데 공정이 붙어 있는 것.
     *
     * 부모 없이 홀로 뜬 공정 작지다. A뷰에 넣으면 "제품 = 용기"인 행이 되어
     * 키트 목록이 오염되므로 따로 뺀다. 숨기지 않는 이유는 부적합 「미지정」과 같다 —
     * 안 보이면 작업자는 등록이 안 된 줄 안다.
     */
    public List<Map<String, Object>> getF1Orphans(String dateFrom, String dateTo, String spjangcd) {
        MapSqlParameterSource p = period(dateFrom, dateTo, spjangcd);
        p.addValue("state", null);

        String sql = """
                SELECT c.id                        AS pk
                     , c."WorkOrderNumber"         AS wo
                     , to_char(c."ProductionDate", 'yyyy-mm-dd') AS prod_date
                     , p."Code"                    AS proc_code
                     , p."Name"                    AS proc_name
                     , {PROC_KEY}                   AS proc_key
                     , {SUB_KEY}                    AS sub_key
                     , c."State"                   AS state
                     , fn_code_name('job_state', c."State") AS state_name
                     , COALESCE(c."OrderQty", 0)   AS order_qty
                     , COALESCE(c."GoodQty", 0)    AS good_qty
                     , cm."Code"                   AS mat_code
                     , cm."Name"                   AS mat_name
                  FROM job_res c
                  LEFT JOIN material    cm  ON cm.id  = c."Material_id"
                  LEFT JOIN mat_grp     cmg ON cmg.id = cm."MaterialGroup_id"
                  LEFT JOIN work_center wc  ON wc.id  = c."WorkCenter_id"
                  LEFT JOIN process     p   ON p.id   = wc."Process_id"
                 WHERE (CAST(:date_from AS timestamp) IS NULL
                          OR c."ProductionDate" >= CAST(:date_from AS timestamp))
                   AND (CAST(:date_to AS timestamp) IS NULL
                          OR c."ProductionDate" <= CAST(:date_to AS timestamp))
                   AND c.spjangcd = :spjangcd
                   AND c."Parent_id" IS NULL
                   AND p.id IS NOT NULL
                   AND COALESCE(p."Code",'') LIKE 'bsc%'
                   AND COALESCE(cm."Factory_id", 1) = 1
                   AND COALESCE(c._status, 'a') = 'a'
                 ORDER BY c."ProductionDate" DESC, c.id DESC
                """.replace("{PROC_KEY}", PROC_KEY_CASE).replace("{SUB_KEY}", SUB_KEY_CASE);

        return nz(this.sqlRunner.getRows(sql, p));
    }

    // =================================================================
    // B뷰 — 공정별 실적 (기간 × 공정 × 차수)
    // =================================================================

    /**
     * 그 기간에 **실제로 발생한** 실적만 나열한다.
     *
     * ★ A뷰와 날짜의 의미가 다르다.
     *   A뷰  job_res."ProductionDate"    = 어떤 작지를 볼지 (실적은 누적)
     *   B뷰  mat_produce."ProductionDate" = 실적 필터 (그 기간 것만)
     *   이 둘을 하나로 걸었던 것이 "지난달 끝낸 조립이 대기로 보이던" 원인이다.
     *
     * ★ 없는 공정은 「대기」가 아니라 행이 없다. 구간 집계라 오독의 여지가 없다.
     *
     * 생산형(조립·블리스터·융착·포장)만 여기서 나온다.
     * 세척·멸균은 mat_produce 를 만들지 않으므로 getWashList / getSterilList 가 따로 낸다
     * ("생산이 아닌 것은 생산실적에 넣지 않는다" — v3 §1).
     */
    public List<Map<String, Object>> getProcessResults(String dateFrom, String dateTo,
                                                       Integer processPk, Integer actorPk,
                                                       Integer factoryId, String spjangcd) {
        MapSqlParameterSource p = period(dateFrom, dateTo, spjangcd);
        p.addValue("process_pk", processPk);
        p.addValue("actor_pk", actorPk);
        p.addValue("factory_id", factoryId);

        String sql = """
                SELECT mp.id                        AS pk
                     -- ★ 실적의 기준일은 「끝난 날」이다. 여러 날에 걸친 작업이 있어서
                     --    시작일로 묶으면 27일에 시작해 29일에 끝난 건이 27일 실적이 된다.
                     --    아직 안 끝난 건은 기댈 값이 없으므로 ProductionDate 로 떨어진다.
                     , to_char(COALESCE(mp."EndTime", mp."ProductionDate"), 'yyyy-mm-dd') AS prod_date
                     , p.id                         AS proc_pk
                     , p."Code"                     AS proc_code
                     , p."Name"                     AS proc_name
                     , wc."Name"                    AS workcenter
                     , jr.id                        AS jr_pk
                     , jr."WorkOrderNumber"         AS wo
                     , COALESCE(jr."Parent_id", jr.id) AS parent_pk
                     -- ★ 산출품목은 mat_produce 자기 것을 먼저 본다.
                     --   작지(job_res)의 품목을 쓰면 포장처럼 한 작지에 CK 생산과
                     --   완제품 결합이 섞인 경우 전부 CK 로 보인다.
                     , COALESCE(om."Code", m."Code")  AS mat_code
                     , COALESCE(om."Name", m."Name")  AS mat_name
                     , COALESCE(ou."Name", u."Name")  AS unit
                     , COALESCE(jr."OrderQty", 0)     AS order_qty
                     , mp."LotIndex"                AS chasu
                     , mp."LotNumber"               AS lot_number
                     , mp."State"                   AS state
                     , COALESCE(mp."GoodQty", 0)    AS good_qty
                     , COALESCE(mp."DefectQty", 0)  AS defect_qty
                     , to_char(mp."StartTime", 'yyyy-mm-dd hh24:mi') AS start_time
                     , to_char(mp."EndTime",   'yyyy-mm-dd hh24:mi') AS end_time
                     , to_char(mp."StartTime", 'mm-dd hh24:mi')      AS start_short
                     , to_char(mp."EndTime",   'mm-dd hh24:mi')      AS end_short
                     -- 여러 날 걸친 작업 판별용
                     , CASE WHEN mp."EndTime" IS NOT NULL
                             AND mp."StartTime" IS NOT NULL
                             AND mp."EndTime"::date <> mp."StartTime"::date
                            THEN 'Y' ELSE 'N' END     AS multi_day
                     , pe."Name"                    AS worker
                     , e."Name"                     AS equipment
                     , sh."Name"                    AS shift_name
                     , (SELECT string_agg(mem."Name", ', ' ORDER BY mem."Name")
                          FROM mat_produce_member mm
                          JOIN person mem ON mem.id = mm."Person_id"
                         WHERE mm."MatProduce_id" = mp.id
                           AND COALESCE(mm._status,'a') = 'a')  AS members
                  FROM mat_produce mp
                  JOIN job_res jr ON jr.id = mp."JobResponse_id"
                  LEFT JOIN material    m  ON m.id  = jr."Material_id"
                  LEFT JOIN unit        u  ON u.id  = m."Unit_id"
                  LEFT JOIN material    om ON om.id = mp."Material_id"
                  LEFT JOIN unit        ou ON ou.id = om."Unit_id"
                  LEFT JOIN work_center wc ON wc.id = mp."WorkCenter_id"
                  LEFT JOIN process     p  ON p.id  = wc."Process_id"
                  LEFT JOIN person      pe ON pe.id = mp."Actor_id"
                  LEFT JOIN equ         e  ON e.id  = mp."Equipment_id"
                  LEFT JOIN shift       sh ON sh."Code" = mp."ShiftCode"
                 WHERE COALESCE(mp."EndTime", mp."ProductionDate") BETWEEN :date_from AND :date_to
                   AND COALESCE(mp._status, 'a') = 'a'
                   AND (CAST(:process_pk AS integer) IS NULL
                        OR p.id = CAST(:process_pk AS integer))
                   AND (CAST(:actor_pk AS integer) IS NULL
                        OR mp."Actor_id" = CAST(:actor_pk AS integer))
                   AND (CAST(:factory_id AS integer) IS NULL
                        OR COALESCE(om."Factory_id", m."Factory_id", 1) = CAST(:factory_id AS integer))
                 ORDER BY COALESCE(mp."EndTime", mp."ProductionDate") DESC, p."Code", mp.id
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    /** 공정 콤보 — 그 공장 공정 전체. 실적이 없어도 목록에는 있어야 고를 수 있다 */
    public List<Map<String, Object>> getProcessCombo(Integer factoryId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("factory_id", factoryId);
        return nz(this.sqlRunner.getRows("""
                SELECT p.id AS pk, p."Code" AS code, p."Name" AS name
                  FROM process p
                 WHERE (CAST(:factory_id AS integer) IS NULL
                        OR COALESCE(p."Factory_id", 1) = CAST(:factory_id AS integer))
                   AND COALESCE(p._status, 'a') = 'a'
                 ORDER BY p."Code"
                """, p));
    }

    /** 작업자 콤보 — 그 기간에 실제 실적을 남긴 사람만 */
    public List<Map<String, Object>> getActorCombo(String dateFrom, String dateTo,
                                                   Integer factoryId, String spjangcd) {
        MapSqlParameterSource p = period(dateFrom, dateTo, spjangcd);
        p.addValue("factory_id", factoryId);
        return nz(this.sqlRunner.getRows("""
                SELECT DISTINCT pe.id AS pk, pe."Name" AS name
                  FROM mat_produce mp
                  JOIN person   pe ON pe.id = mp."Actor_id"
                  JOIN job_res  jr ON jr.id = mp."JobResponse_id"
                  LEFT JOIN material m ON m.id = jr."Material_id"
                 WHERE COALESCE(mp."EndTime", mp."ProductionDate") BETWEEN :date_from AND :date_to
                   AND COALESCE(mp._status, 'a') = 'a'
                   AND (CAST(:factory_id AS integer) IS NULL
                        OR COALESCE(m."Factory_id", 1) = CAST(:factory_id AS integer))
                 UNION
                SELECT DISTINCT pe.id AS pk, pe."Name" AS name
                  FROM wash_work w
                  JOIN person pe ON pe.id = w."Actor_id"
                 WHERE w."WashDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
                   AND COALESCE(w._status, 'a') = 'a'
                   AND COALESCE(CAST(:factory_id AS integer), 1) = 1
                 ORDER BY name
                """, p));
    }

    // =================================================================
    // 1공장 — 세션 상세 (셀 클릭)
    // =================================================================

    /**
     * 한 공정 작지의 차수(세션) 목록.
     * 조원은 mat_produce_member 를 string_agg 로 한 칸에 넣는다.
     */
    public List<Map<String, Object>> getF1Sessions(Integer jrPk) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("jr_pk", jrPk);

        String sql = """
                SELECT mp.id                        AS pk
                     , mp."LotIndex"                AS chasu
                     , mp."LotNumber"               AS lot_number
                     , mp."State"                   AS state
                     , COALESCE(mp."GoodQty", 0)    AS good_qty
                     , COALESCE(mp."DefectQty", 0)  AS defect_qty
                     , to_char(mp."StartTime", 'hh24:mi') AS start_time
                     , to_char(mp."EndTime",   'hh24:mi') AS end_time
                     , to_char(mp."ProductionDate", 'yyyy-mm-dd') AS prod_date
                     , pe."Name"                    AS worker
                     , e."Name"                     AS equipment
                     , sh."Name"                    AS shift_name
                     , (SELECT string_agg(mem."Name", ', ' ORDER BY mem."Name")
                          FROM mat_produce_member mm
                          JOIN person mem ON mem.id = mm."Person_id"
                         WHERE mm."MatProduce_id" = mp.id
                           AND COALESCE(mm._status,'a') = 'a')  AS members
                  FROM mat_produce mp
                  LEFT JOIN person pe ON pe.id = mp."Actor_id"
                  LEFT JOIN equ    e  ON e.id  = mp."Equipment_id"
                  LEFT JOIN shift  sh ON sh."Code" = mp."ShiftCode"
                 WHERE mp."JobResponse_id" = :jr_pk
                   AND COALESCE(mp._status, 'a') = 'a'
                 ORDER BY mp."LotIndex", mp.id
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    /**
     * 그 작지에 연결된 부적합.
     *
     * defect_regist."JobResponse_id" 는 선택값이라 없는 건이 많다.
     * 여기서는 연결된 것만 본다. 미연결분은 getDefectByProcess 가 공정·일자로 모은다.
     */
    public List<Map<String, Object>> getF1SessionDefects(Integer jrPk) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("jr_pk", jrPk);

        String sql = """
                SELECT d.id                         AS pk
                     , to_char(d."DefectDate", 'yyyy-mm-dd') AS defect_date
                     , COALESCE(dt."Name", d."DefectTypeEtc") AS defect_type
                     , COALESCE(d."DefectQty", 0)   AS defect_qty
                     , m."Name"                     AS mat_name
                     , pe."Name"                    AS worker
                     , d."Description"              AS description
                  FROM defect_regist d
                  LEFT JOIN defect_type dt ON dt.id = d."DefectType_id"
                  LEFT JOIN material    m  ON m.id  = d."Material_id"
                  LEFT JOIN person      pe ON pe.id = d."Actor_id"
                 WHERE d."JobResponse_id" = :jr_pk
                   AND d."State" = 'confirmed'
                 ORDER BY d."DefectDate" DESC, d.id DESC
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    // =================================================================
    // 1공장 — 세척 (작지 무관 · 품목 나열)
    // =================================================================

    public List<Map<String, Object>> getWashList(String dateFrom, String dateTo,
                                                 Integer actorPk, String spjangcd) {
        MapSqlParameterSource p = period(dateFrom, dateTo, spjangcd);
        p.addValue("actor_pk", actorPk);

        String sql = """
                SELECT wi.id                        AS pk
                     , to_char(w."WashDate", 'yyyy-mm-dd') AS wash_date
                     , m."Code"                     AS mat_code
                     , m."Name"                     AS mat_name
                     , u."Name"                     AS unit
                     , COALESCE(wi."Qty", 0)        AS qty
                     , COALESCE(wi."DefectQty", 0)  AS defect_qty
                     , wi."State"                   AS state
                     , to_char(wi."StartTime", 'yyyy-mm-dd hh24:mi') AS start_time
                     , to_char(wi."EndTime",   'yyyy-mm-dd hh24:mi') AS end_time
                     , to_char(wi."StartTime", 'mm-dd hh24:mi')      AS start_short
                     , to_char(wi."EndTime",   'mm-dd hh24:mi')      AS end_short
                     , pe."Name"                    AS worker
                     , e."Name"                     AS equipment
                     , wi."Description"             AS description
                  FROM wash_work_item wi
                  JOIN wash_work w  ON w.id = wi."WashWork_id"
                  LEFT JOIN material m  ON m.id = wi."Material_id"
                  LEFT JOIN unit     u  ON u.id = m."Unit_id"
                  LEFT JOIN person   pe ON pe.id = w."Actor_id"
                  LEFT JOIN equ      e  ON e.id  = w."Equipment_id"
                 WHERE w."WashDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
                   AND COALESCE(w._status,  'a') = 'a'
                   AND COALESCE(wi._status, 'a') = 'a'
                   AND (CAST(:actor_pk AS integer) IS NULL
                        OR w."Actor_id" = CAST(:actor_pk AS integer))
                 ORDER BY w."WashDate" DESC, wi.id
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    // =================================================================
    // 1공장 — 멸균 (배치 나열)
    // =================================================================

    /**
     * 멸균 배치 헤더.
     *
     * BiResult(null|pass|fail) 와 State(working|done|scrapped) 는 별개 축이다.
     * 화면에서 칩 두 개로 띄운다. 한쪽으로 합치면 "판정은 났는데 배치는 안 닫힘"이 사라진다.
     */
    public List<Map<String, Object>> getSterilList(String dateFrom, String dateTo, String spjangcd) {
        MapSqlParameterSource p = period(dateFrom, dateTo, spjangcd);

        String sql = """
                SELECT b.id                         AS pk
                     , b."BatchNo"                  AS batch_no
                     , to_char(b."SterilDate", 'yyyy-mm-dd') AS steril_date
                     , b."ItemType"                 AS item_type
                     , b."BiResult"                 AS bi_result
                     , b."BiLotNo"                  AS bi_lot_no
                     , b."State"                    AS state
                     , b."MakerLotNo"               AS maker_lot_no
                     , to_char(b."StartTime", 'yyyy-mm-dd hh24:mi') AS start_time
                     , to_char(b."EndTime",   'yyyy-mm-dd hh24:mi') AS end_time
                     , to_char(b."StartTime", 'mm-dd hh24:mi')      AS start_short
                     , to_char(b."EndTime",   'mm-dd hh24:mi')      AS end_short
                     -- ★ Actor_id 가 person 에 없는 배치가 있어 작업자가 빈다.
                     --   그 경우 등록자(_creater_id)로 떨어뜨린다.
                     , COALESCE(pe."Name", ce."Name") AS worker
                     , e."Name"                     AS equipment
                     , b."Description"              AS description
                     , (SELECT COUNT(*) FROM steril_batch_item si
                         WHERE si."SterilBatch_id" = b.id
                           AND COALESCE(si._status,'a') = 'a')      AS lot_cnt
                     , (SELECT COALESCE(SUM(si."Qty"), 0) FROM steril_batch_item si
                         WHERE si."SterilBatch_id" = b.id
                           AND COALESCE(si._status,'a') = 'a')      AS total_qty
                  FROM steril_batch b
                  LEFT JOIN person pe ON pe.id = b."Actor_id"
                  LEFT JOIN person ce ON ce.id = b._creater_id
                  LEFT JOIN equ    e  ON e.id  = b."Equipment_id"
                 WHERE b."SterilDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
                   AND b.spjangcd = :spjangcd
                   AND COALESCE(b._status, 'a') = 'a'
                 ORDER BY b."SterilDate" DESC, b.id DESC
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    /**
     * 멸균 — 품목 단위 집계. 화면의 「멸균」 칸/카드가 이걸 쓴다.
     *
     * 배치는 로트 묶음이라 작지에 안 붙는다. 그래서 세척과 같이 품목 축으로 접는다.
     * 상태는 배치 State 를 품목으로 접은 것 — working 이 하나라도 있으면 진행중.
     */
    public List<Map<String, Object>> getSterilByMaterial(String dateFrom, String dateTo, String spjangcd) {
        MapSqlParameterSource p = period(dateFrom, dateTo, spjangcd);

        String sql = """
                SELECT m.id                         AS mat_pk
                     , m."Code"                     AS mat_code
                     , m."Name"                     AS mat_name
                     , COUNT(DISTINCT b.id)         AS batch_cnt
                     , COUNT(*)                     AS lot_cnt
                     , COALESCE(SUM(si."Qty"), 0)   AS qty
                     , COUNT(*) FILTER (WHERE b."State" = 'working')   AS run_cnt
                     , COUNT(*) FILTER (WHERE b."State" = 'done')      AS done_cnt
                     , COUNT(*) FILTER (WHERE b."State" = 'scrapped')  AS scrap_cnt
                     , COUNT(*) FILTER (WHERE b."BiResult" = 'fail')   AS fail_cnt
                  FROM steril_batch_item si
                  JOIN steril_batch b ON b.id = si."SterilBatch_id"
                  LEFT JOIN material  m ON m.id = si."Material_id"
                 WHERE b."SterilDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
                   AND b.spjangcd = :spjangcd
                   AND COALESCE(b._status,  'a') = 'a'
                   AND COALESCE(si._status, 'a') = 'a'
                 GROUP BY m.id, m."Code", m."Name"
                 ORDER BY m."Code"
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    /**
     * 세척 — 품목 단위 집계. 「세척」 칸/카드가 이걸 쓴다.
     * 상세(작업자·설비·시각)는 getWashList 가 따로 내린다.
     */
    public List<Map<String, Object>> getWashByMaterial(String dateFrom, String dateTo, String spjangcd) {
        MapSqlParameterSource p = period(dateFrom, dateTo, spjangcd);

        String sql = """
                SELECT m.id                         AS mat_pk
                     , m."Code"                     AS mat_code
                     , m."Name"                     AS mat_name
                     , COUNT(*)                     AS work_cnt
                     , COALESCE(SUM(wi."Qty"), 0)       AS qty
                     , COALESCE(SUM(wi."DefectQty"), 0) AS defect_qty
                     , COUNT(*) FILTER (WHERE wi."State" = 'working') AS run_cnt
                     , COUNT(*) FILTER (WHERE wi."State" = 'done')    AS done_cnt
                     , COUNT(*) FILTER (WHERE wi."State" = 'wait')    AS wait_cnt
                  FROM wash_work_item wi
                  JOIN wash_work w ON w.id = wi."WashWork_id"
                  LEFT JOIN material m ON m.id = wi."Material_id"
                 WHERE w."WashDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
                   AND COALESCE(w._status,  'a') = 'a'
                   AND COALESCE(wi._status, 'a') = 'a'
                 GROUP BY m.id, m."Code", m."Name"
                 ORDER BY m."Code"
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    /**
     * 멸균 배치를 작업지시에 매칭한다.
     *
     * ★ 어떻게 붙이나 — steril_batch_item 에 JobResponse_id 는 없지만 MatLot_id 가 있다.
     *   그 로트를 만든 생산차수(mat_produce.LotNumber)를 거쳐 작지로, 다시 부모 키트 작지로 간다.
     *
     *     steril_batch_item.MatLot_id → mat_lot.LotNumber
     *       → mat_produce.LotNumber → mat_produce.JobResponse_id
     *       → job_res.Parent_id (= 키트 작지)
     *
     *   PK·필터팩은 블리스터/융착 자식 작지가 만든 것이라 그 부모가 곧 키트 작지다.
     *   계보를 못 타는 배치(외부 반입·구형)는 parent_pk 가 null 로 내려온다 —
     *   화면에서 「작지 미매칭」으로 따로 보여준다. 버리면 배치가 사라진다.
     */
    public List<Map<String, Object>> getSterilSteps(String dateFrom, String dateTo, String spjangcd) {
        MapSqlParameterSource p = period(dateFrom, dateTo, spjangcd);

        String sql = """
                SELECT DISTINCT
                       COALESCE(jr."Parent_id", jr.id)  AS parent_pk
                     , b.id                             AS batch_pk
                     , b."BatchNo"                      AS batch_no
                     , to_char(b."SterilDate", 'yyyy-mm-dd') AS steril_date
                     , b."State"                        AS state
                     , b."BiResult"                     AS bi_result
                     , b."ItemType"                     AS item_type
                     , si."LotNumber"                   AS lot_number
                     , COALESCE(si."Qty", 0)            AS qty
                     , m."Code"                         AS mat_code
                     , m."Name"                         AS mat_name
                     , pe."Name"                        AS worker
                     , e."Name"                         AS equipment
                  FROM steril_batch_item si
                  JOIN steril_batch b  ON b.id = si."SterilBatch_id"
                  LEFT JOIN mat_lot   ml ON ml.id = si."MatLot_id"
                  LEFT JOIN mat_produce mp
                         ON mp."LotNumber" = COALESCE(si."LotNumber", ml."LotNumber")
                        AND COALESCE(mp._status,'a') = 'a'
                  LEFT JOIN job_res   jr ON jr.id = mp."JobResponse_id"
                  LEFT JOIN material  m  ON m.id  = si."Material_id"
                  LEFT JOIN person    pe ON pe.id = b."Actor_id"
                  LEFT JOIN equ       e  ON e.id  = b."Equipment_id"
                 WHERE b.spjangcd = :spjangcd
                   AND COALESCE(b._status,  'a') = 'a'
                   AND COALESCE(si._status, 'a') = 'a'
                 ORDER BY parent_pk, b.id, si."LotNumber"
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    /**
     * 부적합 — 품목 단위 집계 (상단 카드 · 모달용).
     * 작지 연결 여부와 무관하게 전부 센다. 연결분만 보면 미지정 건이 사라진다.
     */
    public List<Map<String, Object>> getDefectByMaterial(String dateFrom, String dateTo,
                                                         Integer factoryId, String spjangcd) {
        MapSqlParameterSource p = period(dateFrom, dateTo, spjangcd);
        p.addValue("factory_id", factoryId);

        String sql = """
                SELECT m.id                          AS mat_pk
                     , m."Code"                      AS mat_code
                     , m."Name"                      AS mat_name
                     , p."Name"                      AS proc_name
                     , p."Code"                      AS proc_code
                     , COALESCE(dt."Name", d."DefectTypeEtc") AS defect_type
                     , SUM(COALESCE(d."DefectQty", 0))        AS defect_qty
                     , COUNT(*)                      AS cnt
                     , COUNT(*) FILTER (WHERE d."JobResponse_id" IS NULL) AS unlinked_cnt
                     , MAX(to_char(d."DefectDate", 'yyyy-mm-dd'))         AS last_date
                  FROM defect_regist d
                  LEFT JOIN process     p  ON p.id  = d."Process_id"
                  LEFT JOIN defect_type dt ON dt.id = d."DefectType_id"
                  LEFT JOIN material    m  ON m.id  = d."Material_id"
                 WHERE d."DefectDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
                   AND d."State" = 'confirmed'
                   AND (CAST(:factory_id AS integer) IS NULL
                        OR d."Factory_id" = CAST(:factory_id AS integer))
                 GROUP BY m.id, m."Code", m."Name", p."Name", p."Code",
                          COALESCE(dt."Name", d."DefectTypeEtc")
                 ORDER BY SUM(COALESCE(d."DefectQty", 0)) DESC
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    /**
     * 배치에 담긴 로트 (펼치기).
     *
     * ★ 외부라벨(MakerLotNo)은 두 곳에 있다.
     *   mat_lot."MakerLotNo"     로트별 외부 라벨 (구매·반입품)
     *   steril_batch."MakerLotNo" 배치 단위로 적어둔 라벨 (실데이터에 BLD.../BMJ... 가 여기 있다)
     *   로트 쪽이 비어 있는 경우가 많아 배치 값으로 떨어뜨린다. 안 하면 전부 '-' 로 보인다.
     */
    public List<Map<String, Object>> getSterilItems(Integer batchPk) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("batch_pk", batchPk);

        String sql = """
                SELECT si.id                        AS pk
                     , si."LotNumber"               AS lot_number
                     , COALESCE(si."Qty", 0)        AS qty
                     , si."State"                   AS state
                     , m."Code"                     AS mat_code
                     , m."Name"                     AS mat_name
                     , COALESCE(ml."MakerLotNo", b."MakerLotNo") AS maker_lot_no
                     , ml."MakerLotNo"              AS lot_maker_no
                     , b."MakerLotNo"               AS batch_maker_no
                     , b."BiLotNo"                  AS bi_lot_no
                  FROM steril_batch_item si
                  JOIN steril_batch b   ON b.id  = si."SterilBatch_id"
                  LEFT JOIN material m  ON m.id  = si."Material_id"
                  LEFT JOIN mat_lot  ml ON ml.id = si."MatLot_id"
                 WHERE si."SterilBatch_id" = :batch_pk
                   AND COALESCE(si._status, 'a') = 'a'
                 ORDER BY si.id
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    // =================================================================
    // 부적합 — 작지 미연결분 (공정 · 일자 축)
    // =================================================================

    /**
     * 작지에 연결되지 않은 부적합을 공정·일자로 모은다.
     *
     * ★ 미지정 건이 사라지면 안 된다. 어느 집계에도 안 나오면
     *   작업자는 등록이 안 된 줄 안다.
     */
    public List<Map<String, Object>> getDefectUnlinked(String dateFrom, String dateTo,
                                                       Integer factoryId, String spjangcd) {
        MapSqlParameterSource p = period(dateFrom, dateTo, spjangcd);
        p.addValue("factory_id", factoryId);

        String sql = """
                SELECT to_char(d."DefectDate", 'yyyy-mm-dd') AS defect_date
                     , p."Code"                     AS proc_code
                     , p."Name"                     AS proc_name
                     , COALESCE(dt."Name", d."DefectTypeEtc") AS defect_type
                     , m."Name"                     AS mat_name
                     , SUM(COALESCE(d."DefectQty", 0)) AS defect_qty
                     , COUNT(*)                     AS cnt
                  FROM defect_regist d
                  LEFT JOIN process     p  ON p.id  = d."Process_id"
                  LEFT JOIN defect_type dt ON dt.id = d."DefectType_id"
                  LEFT JOIN material    m  ON m.id  = d."Material_id"
                 WHERE d."DefectDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
                   AND d."State" = 'confirmed'
                   AND d."JobResponse_id" IS NULL
                   AND (CAST(:factory_id AS integer) IS NULL
                        OR d."Factory_id" = CAST(:factory_id AS integer))
                 GROUP BY d."DefectDate", p."Code", p."Name",
                          COALESCE(dt."Name", d."DefectTypeEtc"), m."Name"
                 ORDER BY d."DefectDate" DESC, p."Code"
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    // =================================================================
    // 2공장 — M-CELL
    // =================================================================

    /**
     * 수주(작업지시) 목록. 유닛 작지만 — 하위 모듈 작지는 제외.
     *
     * ★ Parent_id IS NULL 필터가 필요하다.
     *   전개로 생긴 하위 모듈 작지가 유닛 없이 차수만 갖고 있어서
     *   그대로 두면 조립 실적으로 중복 노출된다(실적현황 문서 §1-①).
     */
    public List<Map<String, Object>> getF2Orders(String dateFrom, String dateTo,
                                                 String model, String spjangcd) {
        MapSqlParameterSource p = period(dateFrom, dateTo, spjangcd);
        p.addValue("model", (model == null || model.isBlank()) ? null : model);

        String sql = """
                SELECT jr.id                        AS pk
                     , jr."WorkOrderNumber"         AS wo
                     , jr."State"                   AS state
                     , fn_code_name('job_state', jr."State") AS state_name
                     , COALESCE(jr."OrderQty", 0)   AS order_qty
                     , to_char(jr."ProductionDate", 'yyyy-mm-dd') AS prod_date
                     , m.id                         AS mat_pk
                     , m."Code"                     AS mat_code
                     , m."Name"                     AS mat_name
                     , CASE WHEN COALESCE(m."Name",'') LIKE '%해외%'
                             OR COALESCE(m."Name",'') LIKE '%수출%'
                            THEN '해외' ELSE '국내' END AS model
                     , (SELECT COUNT(*) FROM mcell_unit mu
                         WHERE mu."JobResponse_id" = jr.id)        AS unit_cnt
                  FROM job_res jr
                  LEFT JOIN material m ON m.id = jr."Material_id"
                 WHERE jr."ProductionDate" BETWEEN :date_from AND :date_to
                   AND jr.spjangcd = :spjangcd
                   AND jr."Parent_id" IS NULL
                   AND COALESCE(jr._status, 'a') = 'a'
                   AND COALESCE(m."Factory_id", 1) = 2
                   AND (CAST(:model AS varchar) IS NULL
                        OR CASE WHEN COALESCE(m."Name",'') LIKE '%해외%'
                                     OR COALESCE(m."Name",'') LIKE '%수출%'
                                THEN '해외' ELSE '국내' END = CAST(:model AS varchar))
                 ORDER BY jr."ProductionDate" DESC, jr."WorkOrderNumber" ASC
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    /**
     * 유닛 목록. 유닛 1대가 지금 어느 공정 칸에 걸려 있는지까지 계산해 내린다.
     *
     * ★ 한 유닛은 한 칸에만 나온다.
     *   유닛은 조립→검사→수리→검사를 돌 수 있어서 이력대로 뿌리면
     *   칸 합계가 수주 대수보다 커진다. 현재 State 기준으로 한 칸에만 세고,
     *   수리를 거친 유닛은 is_repair 배지로 표시한다.
     *
     * proc_key / cell_state 매핑
     *   wait|assembling                    → assy   (수리건이면 repair)
     *   repairing                          → repair
     *   inspect_wait|reject                → insp
     *   pass                               → pack   (검사 끝 = 포장 대기)
     *   packed                             → pack   done
     */
    public List<Map<String, Object>> getF2Units(String dateFrom, String dateTo,
                                                String model, String spjangcd) {
        MapSqlParameterSource p = period(dateFrom, dateTo, spjangcd);
        p.addValue("model", (model == null || model.isBlank()) ? null : model);

        String sql = """
                SELECT mu.id                        AS pk
                     , mu."JobResponse_id"          AS jr_pk
                     , mu."UnitNo"                  AS unit_no
                     , mu."LotNumber"               AS lot_number
                     , mu."State"                   AS unit_state
                     , mu."RejectReason"            AS reject_reason
                     , mu."SrcLotNumber"            AS src_lot
                     , CASE WHEN mu."McellRepair_id" IS NOT NULL THEN 'Y' ELSE 'N' END AS is_repair
                     , rp."Cat"                     AS repair_cat
                     , rp."RepairNo"                AS repair_no
                     , pe."Name"                    AS worker
                     , e."Name"                     AS equipment
                     , to_char(mu."StartTime", 'yyyy-mm-dd hh24:mi') AS start_time
                     , CASE
                           WHEN mu."State" = 'packed'                       THEN 'pack'
                           WHEN mu."State" = 'pass'                         THEN 'pack'
                           WHEN mu."State" IN ('inspect_wait','reject')     THEN 'insp'
                           WHEN mu."State" = 'repairing'                    THEN 'repair'
                           WHEN mu."McellRepair_id" IS NOT NULL             THEN 'repair'
                           ELSE 'assy'
                       END                          AS proc_key
                     , CASE
                           WHEN mu."State" = 'packed'                       THEN 'done'
                           WHEN mu."State" IN ('assembling','repairing')    THEN 'run'
                           WHEN mu."State" IN ('inspect_wait','pass',
                                               'reject','wait')             THEN 'wait'
                           ELSE 'wait'
                       END                          AS cell_state
                  FROM mcell_unit mu
                  JOIN job_res jr ON jr.id = mu."JobResponse_id"
                  LEFT JOIN material     m  ON m.id  = jr."Material_id"
                  LEFT JOIN mcell_repair rp ON rp.id = mu."McellRepair_id"
                  LEFT JOIN person       pe ON pe.id = mu."Actor_id"
                  LEFT JOIN equ          e  ON e.id  = mu."Equipment_id"
                 WHERE jr."ProductionDate" BETWEEN :date_from AND :date_to
                   AND jr.spjangcd = :spjangcd
                   AND jr."Parent_id" IS NULL
                   AND COALESCE(m."Factory_id", 1) = 2
                   AND (CAST(:model AS varchar) IS NULL
                        OR CASE WHEN COALESCE(m."Name",'') LIKE '%해외%'
                                     OR COALESCE(m."Name",'') LIKE '%수출%'
                                THEN '해외' ELSE '국내' END = CAST(:model AS varchar))
                 ORDER BY mu."JobResponse_id", mu."UnitNo", mu.id
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    /** 유닛 1대의 검사 회차 (모달 하단) */
    public List<Map<String, Object>> getF2Inspects(Integer unitPk) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("unit_pk", unitPk);

        String sql = """
                SELECT r.id                         AS pk
                     , r."TryNo"                    AS try_no
                     , r."Verdict"                  AS verdict
                     , r."FailReason"               AS fail_reason
                     , f."Name"                     AS form_name
                     , to_char(r."_created", 'yyyy-mm-dd hh24:mi') AS created
                  FROM insp_result r
                  LEFT JOIN insp_form f ON f.id = r."InspForm_id"
                 WHERE r."McellUnit_id" = :unit_pk
                 ORDER BY r."TryNo" DESC, r.id DESC
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }
}