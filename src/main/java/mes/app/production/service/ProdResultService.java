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
 * 생산실적 — 화면 4개 공용.
 *
 *   기간별생산실적   /period    행 = 실적 1건
 *   제품별생산실적   /material  품목으로 접음
 *   생산실적집계표   /summary   품목 × 12개월
 *   공정별작업현황   /process   공정으로 접음
 *
 * ─────────────────────────────────────────────────────────────────────
 * ★ 왜 새로 만들었나 (ProdResultListService 를 고쳐 쓰지 않은 이유)
 *
 *   ① 기존 /read 는 job_res."Parent_id" IS NULL 을 걸었다. 라우팅을 타는
 *      공정 작지는 전부 자식이라(블리스터 2434 → 부모 2432, 실데이터 확인)
 *      조립·블리스터·융착·포장 실적이 통째로 빠진다.
 *   ② 기간을 jr."ProductionDate" 로 걸었다. 그 값은 작지 지시일이라
 *      7/28 작지로 8/4 에 작업하면 8/4 조회에서 사라진다.
 *   ③ 부적합을 job_res_defect 에서 읽었다. 부적합은 defect_regist 로
 *      옮겼고 그 테이블은 쓰지 않는다 — 항상 0건이었다.
 *   ④ mat_produce 만 봐서 세척·멸균이 없고, 2공장은 반대로 한 대가
 *      BOM 스텝 수만큼(대여섯 건) 부풀었다.
 *
 * ─────────────────────────────────────────────────────────────────────
 * ★ 구조
 *
 *   공정마다 실적이 남는 테이블이 다르다. 그래서 소스를 6개로 나눠
 *   같은 컬럼으로 맞춘 뒤 UNION ALL 하고, 그 위에 축 4개를 얹는다.
 *   한 곳을 고치면 화면 넷이 같이 맞는다.
 *
 *     produce   1공장 조립·블리스터·융착·포장     mat_produce
 *     wash      1공장 세척                        wash_work_item
 *     steril    1공장 멸균                        steril_batch_item
 *     mcunit    2공장 조립·수리                   mcell_unit      (1대=1건)
 *     mcinsp    2공장 검사                        insp_result     (1대=1건)
 *     mcpack    2공장 포장                        mat_produce     (완제품 차수)
 *     defect    전 공정 부적합                    defect_regist
 *
 *   쿼리 본문은 WorkStatusService / McellWorkStatusService 에서 가져왔다.
 *   그쪽은 카드 보드용이라 반환이 중첩 구조고 이쪽은 그리드용 평면이라
 *   서비스를 호출하지 않고 SQL 만 공유한다. 호출해서 형태를 변환하면
 *   그 변환 코드가 세 번째 진실이 된다.
 *
 * ★ 기준일은 「끝난 날」이다
 *   COALESCE(EndTime, StartTime, _created). 이틀에 걸친 작업이 시작일로
 *   묶이면 27일에 시작해 29일에 끝난 건이 27일 실적이 된다.
 *   세척(WashDate)·멸균(SterilDate)은 실제 작업일이라 그대로 쓴다.
 */
@Service
public class ProdResultService {

    @Autowired
    SqlRunner sqlRunner;

    /** SqlRunner.getRows 는 오류 시 null 을 반환한다 (빈 리스트 아님) */
    private static List<Map<String, Object>> nz(List<Map<String, Object>> rows) {
        return (rows == null) ? new ArrayList<>() : rows;
    }

    private MapSqlParameterSource param(String dateFrom, String dateTo, String spjangcd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("date_from", Timestamp.valueOf(dateFrom + " 00:00:00"));
        p.addValue("date_to",   Timestamp.valueOf(dateTo   + " 23:59:59"));
        p.addValue("spjangcd", (spjangcd == null || spjangcd.isBlank()) ? "ZZ" : spjangcd);
        return p;
    }

    // =====================================================================
    // 공통 소스 — 아래 6+1 조각이 같은 컬럼을 낸다
    //
    //   src_type    produce | wash | steril | mcunit | mcinsp | mcpack | defect
    //   ref_pk      상세 모달이 되짚을 PK (조각마다 테이블이 다르다)
    //   proc_code   공정 코드
    //   work_date   ★ 끝난 날
    //   is_product  완제품 여부 — 합계에서 반제품을 빼기 위한 플래그
    // =====================================================================
    private static final String SRC = """
        WITH src AS (

        -- ① 1공장 생산형 (조립·블리스터·융착·포장) ─────────────────────
        --    2공장 품목은 여기서 뺀다. 그쪽은 유닛으로 세야 하고,
        --    M-CELL 포장만 ⑥ 에서 따로 잡는다.
        SELECT 'produce'                       AS src_type
             , mp.id                           AS ref_pk
             , jr.id                           AS jr_pk
             , jr."WorkOrderNumber"            AS wo
             , p."Code"                        AS proc_code
             , p."Name"                        AS proc_name
             , wc."Name"                       AS workcenter
             , COALESCE(mp."EndTime", mp."StartTime", mp."_created")::date AS work_date
             , COALESCE(om."Factory_id", m."Factory_id", 1)                AS factory_id
             -- ★ 산출품목은 차수 자기 것을 먼저 본다. 작지 품목을 쓰면
             --   포장처럼 한 작지에 CK 생산과 완제품 결합이 섞인 경우 전부 CK 로 보인다.
             , COALESCE(om.id,     m.id)       AS mat_id
             , COALESCE(om."Code", m."Code")   AS mat_code
             , COALESCE(om."Name", m."Name")   AS mat_name
             , COALESCE(ou."Name", u."Name")   AS unit
             , COALESCE(omg.id,    mg.id)      AS mat_grp_id
             , COALESCE(omg."Name",mg."Name")  AS mat_grp_name
             , COALESCE(omg."MaterialType", mg."MaterialType") AS mat_type
             , COALESCE(om."UnitPrice", m."UnitPrice", 0)      AS unit_price
             -- ★ 포장은 CK(반제품)와 키트 결합(완제품)을 둘 다 만든다.
             --   합치면 완제품 수량이 CK 만큼 부풀어 오른다.
             , CASE WHEN COALESCE(omg."MaterialType", mg."MaterialType", '') = 'product'
                      OR COALESCE(om."Code", m."Code") LIKE '%FG%'
                    THEN 'Y' ELSE 'N' END      AS is_product
             , COALESCE(jr."OrderQty", 0)      AS order_qty
             , COALESCE(mp."GoodQty",  0)      AS good_qty
             , 0                               AS defect_qty
             , 1                               AS row_cnt
             , mp."LotNumber"                  AS lot_number
             , pe."Name"                       AS worker
             , e."Name"                        AS equipment
             , mp."ShiftCode"                  AS shift_code
             , mp."State"                      AS state
             , to_char(mp."StartTime", 'yyyy-mm-dd hh24:mi') AS start_time
             , to_char(mp."EndTime",   'yyyy-mm-dd hh24:mi') AS end_time
          FROM mat_produce mp
          JOIN job_res jr ON jr.id = mp."JobResponse_id"
          LEFT JOIN material    m   ON m.id   = jr."Material_id"
          LEFT JOIN unit        u   ON u.id   = m."Unit_id"
          LEFT JOIN mat_grp     mg  ON mg.id  = m."MaterialGroup_id"
          LEFT JOIN material    om  ON om.id  = mp."Material_id"
          LEFT JOIN unit        ou  ON ou.id  = om."Unit_id"
          LEFT JOIN mat_grp     omg ON omg.id = om."MaterialGroup_id"
          LEFT JOIN work_center wc  ON wc.id  = mp."WorkCenter_id"
          LEFT JOIN process     p   ON p.id   = wc."Process_id"
          LEFT JOIN person      pe  ON pe.id  = mp."Actor_id"
          LEFT JOIN equ         e   ON e.id   = mp."Equipment_id"
         WHERE COALESCE(mp."EndTime", mp."StartTime", mp."_created") BETWEEN :date_from AND :date_to
           AND COALESCE(mp._status, 'a') = 'a'
           AND COALESCE(om."Factory_id", m."Factory_id", 1) <> 2

        UNION ALL

        -- ② 세척 — 작지에 귀속되지 않는다 (wash_work 에 JobResponse_id 없음)
        SELECT 'wash', wi.id, NULL::integer, NULL
             , 'bsc01', '세척', NULL
             , w."WashDate"
             , 1
             , m.id, m."Code", m."Name", u."Name"
             , mg.id, mg."Name", mg."MaterialType", COALESCE(m."UnitPrice", 0)
             , 'N'
             , 0
             , COALESCE(wi."Qty", 0)
             , COALESCE(wi."DefectQty", 0)
             , 1
             , NULL
             , pe."Name", e."Name", NULL
             , wi."State"
             , to_char(wi."StartTime", 'yyyy-mm-dd hh24:mi')
             , to_char(wi."EndTime",   'yyyy-mm-dd hh24:mi')
          FROM wash_work_item wi
          JOIN wash_work w  ON w.id = wi."WashWork_id"
          LEFT JOIN material m  ON m.id  = wi."Material_id"
          LEFT JOIN unit     u  ON u.id  = m."Unit_id"
          LEFT JOIN mat_grp  mg ON mg.id = m."MaterialGroup_id"
          LEFT JOIN person   pe ON pe.id = w."Actor_id"
          LEFT JOIN equ      e  ON e.id  = w."Equipment_id"
         WHERE w."WashDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
           AND COALESCE(w._status,  'a') = 'a'
           AND COALESCE(wi._status, 'a') = 'a'

        UNION ALL

        -- ③ 멸균 — 배치에 담긴 로트 1건. 산출물이 없고 상태·창고만 바뀐다.
        --    FAIL 배치는 파기라 양품으로 세지 않는다.
        SELECT 'steril', si.id, NULL::integer, b."BatchNo"
             , 'bsc04', '멸균', NULL
             , b."SterilDate"
             , 1
             , m.id, m."Code", m."Name", u."Name"
             , mg.id, mg."Name", mg."MaterialType", COALESCE(m."UnitPrice", 0)
             , 'N'
             , 0
             , CASE WHEN COALESCE(b."BiResult",'') = 'fail' THEN 0
                    ELSE COALESCE(si."Qty", 0) END
             , CASE WHEN COALESCE(b."BiResult",'') = 'fail' THEN COALESCE(si."Qty", 0)
                    ELSE 0 END
             , 1
             , NULL
             , COALESCE(pe."Name", ce."Name"), e."Name", NULL
             , b."State"
             , to_char(b."StartTime", 'yyyy-mm-dd hh24:mi')
             , to_char(b."EndTime",   'yyyy-mm-dd hh24:mi')
          FROM steril_batch_item si
          JOIN steril_batch b ON b.id = si."SterilBatch_id"
          LEFT JOIN material m  ON m.id  = si."Material_id"
          LEFT JOIN unit     u  ON u.id  = m."Unit_id"
          LEFT JOIN mat_grp  mg ON mg.id = m."MaterialGroup_id"
          -- ★ Actor_id 가 person 에 없는 배치가 있어 등록자로 떨어뜨린다
          LEFT JOIN person   pe ON pe.id = b."Actor_id"
          LEFT JOIN person   ce ON ce.id = b._creater_id
          LEFT JOIN equ      e  ON e.id  = b."Equipment_id"
         WHERE b."SterilDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
           AND b.spjangcd = :spjangcd
           AND COALESCE(b._status,  'a') = 'a'
           AND COALESCE(si._status, 'a') = 'a'

        UNION ALL

        -- ④ 2공장 조립·수리 — ★ 실적 단위가 유닛(1대)이다.
        --    mat_produce 를 세면 BOM 계층 스텝마다 생겨 5대가 30건이 된다.
        SELECT CASE WHEN mu."McellRepair_id" IS NOT NULL THEN 'mcrepair' ELSE 'mcunit' END
             , mu.id, jr.id, jr."WorkOrderNumber"
             , CASE WHEN mu."McellRepair_id" IS NOT NULL THEN 'mc04' ELSE 'mc01' END
             , CASE WHEN mu."McellRepair_id" IS NOT NULL THEN '수리'  ELSE '조립' END
             , NULL
             , COALESCE(tm.et, tm.st, mu."StartTime", mu."_created")::date
             , 2
             , m.id, m."Code", m."Name", u."Name"
             , mg.id, mg."Name", mg."MaterialType", COALESCE(m."UnitPrice", 0)
             , 'N'
             , COALESCE(jr."OrderQty", 0)
             -- 완성된 대만 양품 1. 진행 중이면 0 (건수 row_cnt 로는 잡힌다)
             , CASE WHEN mu."State" IN ('wait','working','repairing') THEN 0 ELSE 1 END
             , 0
             , 1
             , mu."LotNumber"
             , COALESCE(tm.step_workers, pe."Name"), COALESCE(tm.step_equips, e."Name"), NULL
             , mu."State"
             , to_char(COALESCE(tm.st, mu."StartTime"), 'yyyy-mm-dd hh24:mi')
             , to_char(tm.et, 'yyyy-mm-dd hh24:mi')
          FROM mcell_unit mu
          JOIN job_res jr ON jr.id = mu."JobResponse_id"
          LEFT JOIN material m  ON m.id  = jr."Material_id"
          LEFT JOIN unit     u  ON u.id  = m."Unit_id"
          LEFT JOIN mat_grp  mg ON mg.id = m."MaterialGroup_id"
          LEFT JOIN person   pe ON pe.id = mu."Actor_id"
          LEFT JOIN equ      e  ON e.id  = mu."Equipment_id"
          -- 유닛 시각 = 스텝 실적의 처음~끝. mcell_unit."StartTime" 은 배정 시각이라 다르다
          LEFT JOIN LATERAL (
                SELECT MIN(mp."StartTime") AS st, MAX(mp."EndTime") AS et
                     , string_agg(DISTINCT spe."Name", ', ') AS step_workers
                     , string_agg(DISTINCT se."Name",  ', ') AS step_equips
                  FROM mcell_unit_step us
                  JOIN mat_produce mp ON mp.id = us."MatProduce_id"
                  LEFT JOIN person spe ON spe.id = us."Actor_id"
                  LEFT JOIN equ    se  ON se.id  = us."Equipment_id"
                 WHERE us."McellUnit_id" = mu.id
                   AND COALESCE(mp._status,'a') = 'a'
          ) tm ON true
         WHERE COALESCE(tm.et, tm.st, mu."StartTime", mu."_created") BETWEEN :date_from AND :date_to
           AND COALESCE(mu._status, 'a') = 'a'

        UNION ALL

        -- ⑤ 2공장 검사 — 1대 1건. ★ 판정은 「양식별 마지막 회차」다.
        --    불합격 회차가 하나라도 있으면 불합격으로 치면
        --    재검사로 통과한 대가 영원히 불합격으로 남는다.
        SELECT 'mcinsp', mu.id, jr.id, jr."WorkOrderNumber"
             , 'mc02', '검사', NULL
             , ir.last_at::date
             , 2
             , m.id, m."Code", m."Name", u."Name"
             , mg.id, mg."Name", mg."MaterialType", COALESCE(m."UnitPrice", 0)
             , 'N'
             , 0
             , CASE WHEN ir.last_fail = 0 AND ir.last_open = 0 THEN 1 ELSE 0 END
             , CASE WHEN ir.last_fail > 0 THEN 1 ELSE 0 END
             , 1
             , mu."LotNumber"
             , ir.workers, NULL, NULL
             , mu."State"
             , to_char(ir.first_at, 'yyyy-mm-dd hh24:mi')
             , to_char(ir.last_at,  'yyyy-mm-dd hh24:mi')
          FROM mcell_unit mu
          JOIN LATERAL (
                SELECT MIN(t.first_at) AS first_at, MAX(t.last_at) AS last_at
                     , string_agg(DISTINCT t.workers, ', ') AS workers
                     , COUNT(*) FILTER (WHERE t.last_verdict = 'fail') AS last_fail
                     , COUNT(*) FILTER (WHERE t.last_verdict IS NULL)  AS last_open
                     , COUNT(*) AS form_cnt
                  FROM (
                        SELECT r."InspForm_id"
                             , MIN(r."_created") AS first_at
                             , MAX(r."_created") AS last_at
                             , string_agg(DISTINCT pe."Name", ', ') AS workers
                             , (ARRAY_AGG(r."Verdict" ORDER BY r."TryNo" DESC, r.id DESC))[1] AS last_verdict
                          FROM insp_result r
                          LEFT JOIN person pe ON pe.id = COALESCE(r."Actor_id", r."_creater_id")
                         WHERE r."McellUnit_id" = mu.id
                           AND r."_created" BETWEEN :date_from AND :date_to
                         GROUP BY r."InspForm_id"
                  ) t
          ) ir ON ir.form_cnt > 0
          LEFT JOIN job_res  jr ON jr.id = mu."JobResponse_id"
          LEFT JOIN material m  ON m.id  = jr."Material_id"
          LEFT JOIN unit     u  ON u.id  = m."Unit_id"
          LEFT JOIN mat_grp  mg ON mg.id = m."MaterialGroup_id"
         WHERE COALESCE(mu._status, 'a') = 'a'

        UNION ALL

        -- ⑥ 2공장 포장 — ★ 공정 코드도 창고도 못 쓴다.
        --    packUnit 이 워크센터 56 을 넘겨도 startProduction 이 작지의 워크센터를
        --    쓰는 탓에 52(조립)로 저장되고 산출 로트도 창고 17 로 간다.
        --    실데이터: mat_produce 2090 = MRCN-21022, wc 52, 창고 17.
        --    조립·수리는 WIP-* 재공품, 포장만 MRCN-* 완제품 → 품목이 유일한 축이다.
        SELECT 'mcpack', mp.id, jr.id, jr."WorkOrderNumber"
             , 'mc03', '포장', NULL
             , COALESCE(mp."EndTime", mp."StartTime", mp."_created")::date
             , 2
             , m.id, m."Code", m."Name", u."Name"
             , mg.id, mg."Name", mg."MaterialType", COALESCE(m."UnitPrice", 0)
             , 'Y'
             , COALESCE(jr."OrderQty", 0)
             , COALESCE(mp."GoodQty", 0)
             , 0
             , 1
             , mp."LotNumber"
             , pe."Name", e."Name", mp."ShiftCode"
             , mp."State"
             , to_char(mp."StartTime", 'yyyy-mm-dd hh24:mi')
             , to_char(mp."EndTime",   'yyyy-mm-dd hh24:mi')
          FROM mat_produce mp
          JOIN job_res jr ON jr.id = mp."JobResponse_id"
          LEFT JOIN material m  ON m.id  = COALESCE(mp."Material_id", jr."Material_id")
          LEFT JOIN unit     u  ON u.id  = m."Unit_id"
          LEFT JOIN mat_grp  mg ON mg.id = m."MaterialGroup_id"
          LEFT JOIN person   pe ON pe.id = mp."Actor_id"
          LEFT JOIN equ      e  ON e.id  = mp."Equipment_id"
         WHERE COALESCE(mg."MaterialType",'') = 'product'
           -- ★ 품목군만 보면 1공장 키트 완제품(BSC*-FG*)도 같이 잡힌다
           AND COALESCE(m."Factory_id", 1) = 2
           -- ★ M-CELL 포장은 반드시 유닛 1대를 소비한다 (2차 방어)
           AND EXISTS (SELECT 1 FROM mcell_unit x
                        WHERE x."LotNumber" = mp."LotNumber"
                          AND COALESCE(x._status,'a') = 'a')
           AND COALESCE(mp._status, 'a') = 'a'
           AND COALESCE(mp."EndTime", mp."StartTime", mp."_created") BETWEEN :date_from AND :date_to

        UNION ALL

        -- ⑦ 부적합 — ★ 이중 계상이 아니다.
        --    부적합 등록 화면이 유일한 등록처가 되면서 mat_produce."DefectQty" 는
        --    더 이상 채우지 않는다(①⑥ 의 defect_qty 를 0 으로 둔 이유).
        --    작지에 안 붙는 건도 있어 jr_pk 가 null 일 수 있다 —
        --    버리지 말고 「작지 미지정」으로 보여줄 것.
        SELECT 'defect', d.id, d."JobResponse_id", jr."WorkOrderNumber"
             , p."Code", p."Name", NULL
             , d."DefectDate"
             , COALESCE(m."Factory_id", 1)
             , m.id, m."Code", m."Name", u."Name"
             , mg.id, mg."Name", mg."MaterialType", COALESCE(m."UnitPrice", 0)
             , 'N'
             , 0
             , 0
             , COALESCE(d."DefectQty", 0)
             , 0                                    -- 건수에는 넣지 않는다
             , NULL
             , pe."Name", NULL, NULL
             , d."State"
             , NULL, NULL
          FROM defect_regist d
          LEFT JOIN process  p  ON p.id  = d."Process_id"
          LEFT JOIN material m  ON m.id  = d."Material_id"
          LEFT JOIN unit     u  ON u.id  = m."Unit_id"
          LEFT JOIN mat_grp  mg ON mg.id = m."MaterialGroup_id"
          LEFT JOIN job_res  jr ON jr.id = d."JobResponse_id"
          LEFT JOIN person   pe ON pe.id = d."Actor_id"
         WHERE d."DefectDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
           AND COALESCE(d."State",'') = 'confirmed'
           AND COALESCE(d._status, 'a') = 'a'
        )
        """;

    /** 공통 필터 — 축 네 개가 같이 쓴다 */
    private static final String WHERE_COMMON = """
         WHERE (CAST(:factory_id AS integer) IS NULL OR factory_id = CAST(:factory_id AS integer))
           AND (CAST(:proc_code AS varchar) IS NULL OR proc_code = CAST(:proc_code AS varchar))
           AND (CAST(:mat_grp_id AS integer) IS NULL OR mat_grp_id = CAST(:mat_grp_id AS integer))
           AND (CAST(:mat_type AS varchar) IS NULL OR mat_type = CAST(:mat_type AS varchar))
        """;

    private MapSqlParameterSource withFilters(MapSqlParameterSource p, Integer factoryId,
                                              String procCode, Integer matGrpId, String matType) {
        p.addValue("factory_id", factoryId);
        p.addValue("proc_code", (procCode == null || procCode.isBlank()) ? null : procCode);
        p.addValue("mat_grp_id", matGrpId);
        p.addValue("mat_type", (matType == null || matType.isBlank()) ? null : matType);
        return p;
    }

    // =====================================================================
    // ① 기간별생산실적 — 행 = 실적 1건
    // =====================================================================
    public List<Map<String, Object>> getPeriodList(String dateFrom, String dateTo,
                                                   Integer factoryId, String procCode,
                                                   Integer matGrpId, String matType,
                                                   String spjangcd) {
        MapSqlParameterSource p = withFilters(param(dateFrom, dateTo, spjangcd),
                factoryId, procCode, matGrpId, matType);
        String sql = SRC + """
            SELECT src_type, ref_pk, jr_pk, wo
                 , proc_code, proc_name, workcenter
                 , to_char(work_date, 'yyyy-mm-dd') AS prod_date
                 , factory_id
                 , mat_id, mat_code, mat_name, unit
                 , mat_grp_name, is_product
                 , order_qty, good_qty, defect_qty
                 , lot_number, worker, equipment, shift_code, state
                 , start_time, end_time
              FROM src
            """ + WHERE_COMMON + """
             ORDER BY work_date DESC, proc_code, ref_pk DESC
            """;
        return nz(this.sqlRunner.getRows(sql, p));
    }

    // =====================================================================
    // ② 공정별작업현황 — 공정으로 접음
    //
    //   ★ 세척·멸균·2공장이 여기 들어 있다. 기존 read_process 는
    //     mat_produce 만 봐서 「공정별」이라는 이름과 달리 절반이 비었다.
    // =====================================================================
    public List<Map<String, Object>> getProcessList(String dateFrom, String dateTo,
                                                    Integer factoryId, String procCode,
                                                    Integer matGrpId, String matType,
                                                    String spjangcd) {
        MapSqlParameterSource p = withFilters(param(dateFrom, dateTo, spjangcd),
                factoryId, procCode, matGrpId, matType);
        String sql = SRC + """
            SELECT proc_code, proc_name, factory_id
                 , COUNT(*) FILTER (WHERE row_cnt = 1)          AS work_cnt
                 , COUNT(DISTINCT mat_id)                       AS mat_cnt
                 , COUNT(DISTINCT work_date)                    AS day_cnt
                 , SUM(good_qty)                                AS good_qty
                 , SUM(defect_qty)                              AS defect_qty
                 -- 완제품만 따로. 공정마다 만드는 게 달라 전부 더하면
                 -- 같은 물건을 여러 번 세게 된다
                 , SUM(good_qty) FILTER (WHERE is_product = 'Y') AS product_qty
                 -- ★ SUM(double precision) 은 numeric 이 아니라 round(x,2) 를 못 받는다.
                 --   나눗셈 결과 전체를 numeric 으로 캐스팅해야 한다.
                 , ROUND((CASE WHEN SUM(good_qty) + SUM(defect_qty) = 0 THEN 0
                          ELSE SUM(defect_qty)
                               / (SUM(good_qty) + SUM(defect_qty)) * 100 END)::numeric, 2) AS defect_percent
                 , to_char(MIN(work_date), 'yyyy-mm-dd')        AS first_date
                 , to_char(MAX(work_date), 'yyyy-mm-dd')        AS last_date
              FROM src
            """ + WHERE_COMMON + """
               AND proc_code IS NOT NULL
             GROUP BY proc_code, proc_name, factory_id
             ORDER BY factory_id, proc_code
            """;
        return nz(this.sqlRunner.getRows(sql, p));
    }

    // =====================================================================
    // ③ 제품별생산실적 — 품목으로 접음
    // =====================================================================
    public List<Map<String, Object>> getMaterialList(String dateFrom, String dateTo,
                                                     Integer factoryId, String procCode,
                                                     Integer matGrpId, String matType,
                                                     String spjangcd) {
        MapSqlParameterSource p = withFilters(param(dateFrom, dateTo, spjangcd),
                factoryId, procCode, matGrpId, matType);
        String sql = SRC + """
            SELECT mat_id, mat_code, mat_name, unit
                 , mat_grp_name
                 , fn_code_name('mat_type', mat_type) AS mat_type_name
                 , factory_id
                 , MAX(is_product)                              AS is_product
                 , COUNT(*) FILTER (WHERE row_cnt = 1)          AS work_cnt
                 , COUNT(DISTINCT proc_code)                    AS proc_cnt
                 , string_agg(DISTINCT proc_name, ', ')         AS proc_names
                 , SUM(good_qty)                                AS good_qty
                 , SUM(defect_qty)                              AS defect_qty
                 , SUM(good_qty * unit_price)                   AS good_amt
                 -- ★ SUM(double precision) 은 numeric 이 아니라 round(x,2) 를 못 받는다.
                 --   나눗셈 결과 전체를 numeric 으로 캐스팅해야 한다.
                 , ROUND((CASE WHEN SUM(good_qty) + SUM(defect_qty) = 0 THEN 0
                          ELSE SUM(defect_qty)
                               / (SUM(good_qty) + SUM(defect_qty)) * 100 END)::numeric, 2) AS defect_percent
                 , to_char(MAX(work_date), 'yyyy-mm-dd')        AS last_date
              FROM src
            """ + WHERE_COMMON + """
               AND mat_id IS NOT NULL
             GROUP BY mat_id, mat_code, mat_name, unit, mat_grp_name, mat_type, factory_id
             ORDER BY mat_type, mat_code
            """;
        return nz(this.sqlRunner.getRows(sql, p));
    }

    // =====================================================================
    // ④ 생산실적집계표 — 품목 × 12개월
    //
    //   dataDiv : qty(수량) | money(금액)
    // =====================================================================
    public List<Map<String, Object>> getMonthSummary(String year, Integer factoryId,
                                                     Integer matGrpId, String matType,
                                                     String dataDiv, String spjangcd) {
        MapSqlParameterSource p = withFilters(param(year + "-01-01", year + "-12-31", spjangcd),
                factoryId, null, matGrpId, matType);

        String val = "money".equals(dataDiv) ? "good_qty * unit_price" : "good_qty";

        StringBuilder mon = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            mon.append(" , SUM(CASE WHEN EXTRACT(month FROM work_date) = ").append(i)
                    .append(" THEN ").append(val).append(" END) AS mon_").append(i).append("\n");
        }

        String sql = SRC + """
            SELECT mat_id, mat_code, mat_name, unit
                 , mat_grp_name
                 , fn_code_name('mat_type', mat_type) AS mat_type_name
                 , SUM(good_qty)                AS year_qty_sum
                 , SUM(good_qty * unit_price)   AS year_money_sum
            """ + mon + """
              FROM src
            """ + WHERE_COMMON + """
               AND mat_id IS NOT NULL
             GROUP BY mat_id, mat_code, mat_name, unit, mat_grp_name, mat_type
             ORDER BY mat_type, mat_grp_name, mat_code
            """;
        return nz(this.sqlRunner.getRows(sql, p));
    }

    // =====================================================================
    // 상세 — 행 클릭 시. src_type 마다 되짚을 곳이 다르다.
    // =====================================================================

    /**
     * 투입 자재 (produce / mcpack 행만 해당).
     *
     * ★ mat_lot_cons 를 쓴다 — 투입 차감의 진실은 여기다.
     *   mat_consu 는 "InputQty" 공란이 144 건 있어 이력이 신뢰되지 않고,
     *   무엇보다 어떤 로트를 썼는지가 없다. 로트 추적은 UDI·실사에서
     *   요구되는 항목이라 로트가 남는 쪽을 본다.
     *
     * 한 차수가 같은 품목을 여러 로트에서 끌어 쓰면(FIFO 차감) 행이 여러 개다.
     * 로트별로 보여주는 것이 맞다 — 합치면 어느 로트가 들어갔는지 사라진다.
     */
    public List<Map<String, Object>> getConsumedList(int mpPk) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("mp_pk", mpPk);
        String sql = """
            SELECT m."Code"                        AS mat_code
                 , m."Name"                        AS mat_name
                 , u."Name"                        AS unit
                 , COALESCE(mlc."OutputQty", 0)    AS consumed
                 , ml."LotNumber"                  AS lot_number
                 , ml."MakerLotNo"                 AS maker_lot_no
                 , sh."Name"                       AS store_house
                 , to_char(COALESCE(mlc."OutputDateTime", mlc."_created"), 'yyyy-mm-dd hh24:mi')
                                                   AS consumed_at
              FROM mat_lot_cons mlc
              JOIN mat_lot   ml ON ml.id = mlc."MaterialLot_id"
              LEFT JOIN material    m  ON m.id  = ml."Material_id"
              LEFT JOIN unit        u  ON u.id  = m."Unit_id"
              LEFT JOIN store_house sh ON sh.id = ml."StoreHouse_id"
             WHERE mlc."SourceTableName" = 'mat_produce'
               AND mlc."SourceDataPk"    = :mp_pk
               AND COALESCE(mlc._status, 'a') = 'a'
             ORDER BY m."Code", ml."LotNumber"
            """;
        return nz(this.sqlRunner.getRows(sql, p));
    }

    /**
     * 부적합 내역.
     *
     * ★ job_res_defect 는 쓰지 않는다 — 부적합 등록 화면이 유일한 등록처가 되면서
     *   그 테이블은 비었다. 기존 getProdResultDefectList 가 항상 0건이던 이유.
     */
    public List<Map<String, Object>> getDefectList(String dateFrom, String dateTo,
                                                   String procCode, Integer matId,
                                                   String spjangcd) {
        MapSqlParameterSource p = param(dateFrom, dateTo, spjangcd);
        p.addValue("proc_code", (procCode == null || procCode.isBlank()) ? null : procCode);
        p.addValue("mat_id", matId);

        String sql = """
            SELECT d.id                         AS pk
                 , to_char(d."DefectDate", 'yyyy-mm-dd') AS defect_date
                 , p."Code"                     AS proc_code
                 , p."Name"                     AS proc_name
                 , m."Code"                     AS mat_code
                 , m."Name"                     AS mat_name
                 , u."Name"                     AS unit
                 , COALESCE(dt."Name", d."DefectTypeEtc") AS defect_type
                 , COALESCE(d."DefectQty", 0)   AS defect_qty
                 , pe."Name"                    AS worker
                 , jr."WorkOrderNumber"         AS wo
                 , d."Description"              AS description
              FROM defect_regist d
              LEFT JOIN process     p  ON p.id  = d."Process_id"
              LEFT JOIN material    m  ON m.id  = d."Material_id"
              LEFT JOIN unit        u  ON u.id  = m."Unit_id"
              LEFT JOIN defect_type dt ON dt.id = d."DefectType_id"
              LEFT JOIN person      pe ON pe.id = d."Actor_id"
              LEFT JOIN job_res     jr ON jr.id = d."JobResponse_id"
             WHERE d."DefectDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
               AND COALESCE(d."State",'') = 'confirmed'
               AND COALESCE(d._status, 'a') = 'a'
               AND (CAST(:proc_code AS varchar) IS NULL OR p."Code" = CAST(:proc_code AS varchar))
               AND (CAST(:mat_id AS integer) IS NULL OR d."Material_id" = CAST(:mat_id AS integer))
             ORDER BY d."DefectDate" DESC, d.id DESC
            """;
        return nz(this.sqlRunner.getRows(sql, p));
    }

    /** 공정 콤보 — 실적이 없어도 목록에는 있어야 고를 수 있다 */
    public List<Map<String, Object>> getProcessCombo(Integer factoryId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("factory_id", factoryId);
        return nz(this.sqlRunner.getRows("""
            SELECT p."Code" AS code, p."Name" AS name, COALESCE(p."Factory_id", 1) AS factory_id
              FROM process p
             WHERE COALESCE(p._status, 'a') = 'a'
               AND (CAST(:factory_id AS integer) IS NULL
                    OR COALESCE(p."Factory_id", 1) = CAST(:factory_id AS integer))
             ORDER BY COALESCE(p."Factory_id", 1), p."Code"
            """, p));
    }
}