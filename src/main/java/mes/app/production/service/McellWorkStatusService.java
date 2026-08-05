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
 * 2공장 M-CELL 작업실적현황 — 조회 전용.
 *
 * ★ 왜 1공장(WorkStatusService)과 분리했나
 *   1공장은 "작업조가 용기 여러 개를 만든다" → 실적 단위 = mat_produce 차수.
 *   2공장은 "1대 = 1로트 = 1시리얼" → 실적 단위 = mcell_unit 유닛.
 *   유닛 한 대를 조립하면 BOM 계층 스텝마다 createProduction 이 돌아
 *   mat_produce 가 대여섯 건씩 생긴다. 그걸 그대로 세면 5대 만든 날이 30건으로 보인다.
 *   → 2공장은 mcell_unit 을 세고, mat_produce 는 스텝 상세에서만 쓴다.
 *
 * ★ 공정마다 실적이 남는 곳이 다르다
 *   mc01 조립  mcell_unit + mcell_unit_step + mat_produce
 *   mc02 검사  insp_result (mat_produce 없음 — 검사는 생산이 아니다)
 *   mc04 수리  mcell_unit(McellRepair_id) + mat_produce
 *   mc03 포장  mat_produce 중 산출품목이 완제품(MRCN-*)인 차수 + mat_lot(박스 라벨)
 */
@Service
public class McellWorkStatusService {

    @Autowired
    SqlRunner sqlRunner;

    /** SqlRunner.getRows 는 오류 시 null 을 반환한다 (빈 리스트 아님) */
    private static List<Map<String, Object>> nz(List<Map<String, Object>> rows) {
        return (rows == null) ? new ArrayList<>() : rows;
    }

    private static MapSqlParameterSource period(String dateFrom, String dateTo,
                                                Integer actorPk, String spjangcd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("date_from", Timestamp.valueOf(dateFrom + " 00:00:00"));
        p.addValue("date_to", Timestamp.valueOf(dateTo + " 23:59:59"));
        p.addValue("actor_pk", actorPk);
        p.addValue("spjangcd", (spjangcd == null || spjangcd.isBlank()) ? "ZZ" : spjangcd);
        return p;
    }

    /**
     * 유닛의 작업 시각 — 스텝 실적(mat_produce)의 처음~끝.
     *
     * mcell_unit."StartTime" 은 작업자 배정 시각이라 실제 조립 구간과 다를 수 있어
     * 스텝 실적을 우선하고, 없으면 유닛 시각으로 떨어진다.
     */
    private static final String UNIT_TIME = """
                LEFT JOIN LATERAL (
                      SELECT MIN(mp."StartTime")                AS st
                           , MAX(mp."EndTime")                  AS et
                           , COUNT(*)                           AS step_cnt
                           , COUNT(*) FILTER (WHERE mp."EndTime" IS NULL) AS open_cnt
                           -- 스텝마다 작업자가 다를 수 있다. 여러 명이면 명단으로 보여준다
                           , string_agg(DISTINCT spe."Name", ', ')  AS step_workers
                           , string_agg(DISTINCT se."Name",  ', ')  AS step_equips
                        FROM mcell_unit_step us
                        JOIN mat_produce mp ON mp.id = us."MatProduce_id"
                        LEFT JOIN person spe ON spe.id = us."Actor_id"
                        LEFT JOIN equ    se  ON se.id  = us."Equipment_id"
                       WHERE us."McellUnit_id" = mu.id
                         AND COALESCE(mp._status,'a') = 'a'
                ) tm ON true
            """;

    // =================================================================
    // mc01 조립 — 카드 1장 = 유닛 1대
    // =================================================================

    /**
     * 조립 유닛.
     *
     * ★ 수리에서 온 유닛(McellRepair_id IS NOT NULL)은 제외한다.
     *   수리는 별도 공정(mc04)이고, 여기 넣으면 한 대가 두 칼럼에 잡힌다.
     *
     * 기준일은 마지막 스텝을 끝낸 날. 아직 조립 중이면 유닛 시작일로 떨어진다.
     */
    public List<Map<String, Object>> getAssemblyUnits(String dateFrom, String dateTo,
                                                      Integer actorPk, String spjangcd) {
        MapSqlParameterSource p = period(dateFrom, dateTo, actorPk, spjangcd);

        String sql = """
                SELECT mu.id                          AS pk
                     , mu."UnitNo"                    AS unit_no
                     , mu."LotNumber"                 AS lot_number
                     , mu."State"                     AS unit_state
                     , mu."RejectReason"              AS reject_reason
                     , jr.id                          AS jr_pk
                     , jr."WorkOrderNumber"           AS wo
                     , m."Code"                       AS mat_code
                     , m."Name"                       AS mat_name
                     , COALESCE(jr."OrderQty", 0)     AS order_qty
                     -- 스텝 작업자가 있으면 그쪽이 진실. 없으면 유닛 담당자
                     , COALESCE(tm.step_workers, pe."Name") AS worker
                     , COALESCE(tm.step_equips,  e."Name")  AS equipment
                     , to_char(COALESCE(tm.et, tm.st, mu."StartTime", mu."_created"), 'yyyy-mm-dd') AS prod_date
                     , to_char(COALESCE(tm.st, mu."StartTime"), 'yyyy-mm-dd hh24:mi') AS start_time
                     , to_char(tm.et, 'yyyy-mm-dd hh24:mi')  AS end_time
                     , to_char(COALESCE(tm.st, mu."StartTime"), 'mm-dd hh24:mi') AS start_short
                     , to_char(tm.et, 'mm-dd hh24:mi')       AS end_short
                     , COALESCE(tm.step_cnt, 0)       AS step_cnt
                     , COALESCE(tm.open_cnt, 0)       AS open_cnt
                     , (SELECT COUNT(*) FROM mcell_unit_step us
                         WHERE us."McellUnit_id" = mu.id)                        AS step_total
                     , (SELECT COUNT(*) FROM mcell_unit_step us
                         WHERE us."McellUnit_id" = mu.id AND us."State" = 'done') AS step_done
                     , (SELECT COUNT(*) FROM mcell_unit_step us
                         WHERE us."McellUnit_id" = mu.id AND us."ReworkYN" = 'Y') AS rework_cnt
                  FROM mcell_unit mu
                  JOIN job_res jr ON jr.id = mu."JobResponse_id"
                  LEFT JOIN material m  ON m.id  = jr."Material_id"
                  LEFT JOIN person   pe ON pe.id = mu."Actor_id"
                  LEFT JOIN equ      e  ON e.id  = mu."Equipment_id"
                %s
                 WHERE mu."McellRepair_id" IS NULL
                   AND COALESCE(tm.et, tm.st, mu."StartTime", mu."_created") BETWEEN :date_from AND :date_to
                   -- 유닛 담당자뿐 아니라 스텝을 실제로 한 사람으로도 걸린다
                   AND (CAST(:actor_pk AS integer) IS NULL
                        OR mu."Actor_id" = CAST(:actor_pk AS integer)
                        OR EXISTS (SELECT 1 FROM mcell_unit_step us2
                                    WHERE us2."McellUnit_id" = mu.id
                                      AND us2."Actor_id" = CAST(:actor_pk AS integer)))
                 ORDER BY COALESCE(tm.et, tm.st, mu."StartTime", mu."_created") DESC, mu."UnitNo"
                """.formatted(UNIT_TIME);

        return nz(this.sqlRunner.getRows(sql, p));
    }

    /**
     * 조립 스텝 — 카드/행 1건 = 모듈 1개.
     *
     * ★ 일보는 유닛이 아니라 스텝으로 본다.
     *   한 대가 BOM 계층 스텝 13개로 이루어지고 며칠에 걸쳐 조립된다.
     *   유닛 단위로만 적으면 그날 무슨 모듈을 만들었는지가 통째로 사라지고,
     *   완성되지 않은 날은 아무 실적도 없는 것처럼 보인다.
     *   → "그날 끝낸 모듈"을 한 줄씩 적는다. 최상위(Depth=0)가 끝난 날이 곧 완성일.
     *
     * 기준일은 스텝 실적(mat_produce)의 종료일.
     *
     * ★ ProductionDate 는 쓰지 않는다 — 작지의 지시일을 그대로 물고 들어온다.
     *   7/28 작지로 8/4 에 조립해도 값은 7/28 이라 오늘 조회에서 빠진다.
     */
    public List<Map<String, Object>> getAssemblySteps(String dateFrom, String dateTo,
                                                      Integer actorPk, String spjangcd) {
        MapSqlParameterSource p = period(dateFrom, dateTo, actorPk, spjangcd);

        String sql = """
                SELECT us.id                          AS pk
                     , us."Depth"                     AS depth
                     , us."State"                     AS state
                     , us."Source"                    AS source
                     , COALESCE(us."ReworkYN",'N')    AS rework_yn
                     , us."LotNumber"                 AS step_lot
                     , sm."Code"                      AS mat_code
                     , sm."Name"                      AS mat_name
                     , pm."Name"                      AS parent_name
                     , mu.id                          AS unit_pk
                     , mu."UnitNo"                    AS unit_no
                     , mu."LotNumber"                 AS lot_number
                     , mu."State"                     AS unit_state
                     , CASE WHEN mu."McellRepair_id" IS NOT NULL THEN 'Y' ELSE 'N' END AS is_repair
                     , jr."WorkOrderNumber"           AS wo
                     , m."Name"                       AS unit_mat_name
                     , pe."Name"                      AS worker
                     , e."Name"                       AS equipment
                     , COALESCE(mp."GoodQty", 0)      AS good_qty
                     , to_char(COALESCE(mp."EndTime", mp."StartTime", mp."_created"), 'yyyy-mm-dd') AS prod_date
                     , to_char(mp."StartTime", 'yyyy-mm-dd hh24:mi') AS start_time
                     , to_char(mp."EndTime",   'yyyy-mm-dd hh24:mi') AS end_time
                     , to_char(mp."StartTime", 'mm-dd hh24:mi')      AS start_short
                     , to_char(mp."EndTime",   'mm-dd hh24:mi')      AS end_short
                  FROM mcell_unit_step us
                  JOIN mat_produce mp ON mp.id = us."MatProduce_id"
                  JOIN mcell_unit  mu ON mu.id = us."McellUnit_id"
                  LEFT JOIN material sm ON sm.id = us."Material_id"
                  LEFT JOIN material pm ON pm.id = us."ParentMaterial_id"
                  LEFT JOIN job_res  jr ON jr.id = mu."JobResponse_id"
                  LEFT JOIN material m  ON m.id  = jr."Material_id"
                  LEFT JOIN person   pe ON pe.id = COALESCE(us."Actor_id", mu."Actor_id")
                  LEFT JOIN equ      e  ON e.id  = COALESCE(us."Equipment_id", mu."Equipment_id")
                 WHERE COALESCE(mp."EndTime", mp."StartTime", mp."_created") BETWEEN :date_from AND :date_to
                   AND COALESCE(mp._status,'a') = 'a'
                   AND (CAST(:actor_pk AS integer) IS NULL
                        OR COALESCE(us."Actor_id", mu."Actor_id") = CAST(:actor_pk AS integer))
                 ORDER BY COALESCE(mp."EndTime", mp."StartTime", mp."_created"), mu."UnitNo", us."Depth" DESC
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    // =================================================================
    // mc02 검사 — 카드 1장 = 검사 회차 1건
    // =================================================================

    /**
     * 검사 — 카드 1장 = 유닛 1대(품목).
     *
     * ★ 회차를 카드로 내지 않는다.
     *   한 대가 ROTOR·BASKET 등 양식 여러 개를 받고, 불합격하면 회차가 또 늘어난다.
     *   회차마다 카드를 내면 3회차까지 간 한 대가 카드 3장으로 흩어져 대수가 안 보인다.
     *   → 카드는 유닛, 양식별 회차는 카드를 눌러서 본다.
     *
     * ★ 판정은 「양식별 마지막 회차」로 한다.
     *   1·2회차 불합격 → 3회차 합격이면 최종은 합격이다.
     *   불합격 회차가 하나라도 있으면 불합격으로 치면 재검사로 통과한 대가
     *   영원히 불합격으로 남는다. 이전 회차는 이력일 뿐 현재 상태가 아니다.
     *   (전 양식의 마지막 회차가 모두 합격이어야 합격 — mc02 recalcUnit 과 같은 규칙)
     */
    public List<Map<String, Object>> getInspectUnits(String dateFrom, String dateTo,
                                                     Integer actorPk, String spjangcd) {
        MapSqlParameterSource p = period(dateFrom, dateTo, actorPk, spjangcd);

        String sql = """
                SELECT mu.id                          AS pk
                     , mu."UnitNo"                    AS unit_no
                     , mu."LotNumber"                 AS lot_number
                     , mu."State"                     AS unit_state
                     , mu."RejectReason"              AS reject_reason
                     , CASE WHEN mu."McellRepair_id" IS NOT NULL THEN 'Y' ELSE 'N' END AS is_repair
                     , mu."SrcLotNumber"              AS src_lot
                     , jr."WorkOrderNumber"           AS wo
                     , m."Code"                       AS mat_code
                     , m."Name"                       AS mat_name
                     , ir.form_cnt                    AS form_cnt
                     , ir.try_cnt                     AS try_cnt
                     , ir.forms                       AS form_names
                     , ir.workers                     AS worker
                     -- 마지막 회차 기준 집계 (재검사로 통과한 대가 불합격으로 남지 않게)
                     , ir.last_pass                   AS pass_cnt
                     , ir.last_fail                   AS fail_cnt
                     , ir.last_open                   AS open_cnt
                     , to_char(ir.last_at, 'yyyy-mm-dd')          AS prod_date
                     , to_char(ir.first_at, 'yyyy-mm-dd hh24:mi') AS start_time
                     , to_char(ir.last_at,  'yyyy-mm-dd hh24:mi') AS end_time
                     , to_char(ir.first_at, 'mm-dd hh24:mi')      AS start_short
                     , to_char(ir.last_at,  'mm-dd hh24:mi')      AS end_short
                  FROM mcell_unit mu
                  JOIN LATERAL (
                        SELECT COUNT(*)                                  AS form_cnt
                             , SUM(t.try_cnt)                            AS try_cnt
                             , MIN(t.first_at)                           AS first_at
                             , MAX(t.last_at)                            AS last_at
                             , string_agg(DISTINCT t.form_name, ', ')    AS forms
                             , string_agg(DISTINCT t.workers,   ', ')    AS workers
                             , COUNT(*) FILTER (WHERE t.last_verdict = 'pass') AS last_pass
                             , COUNT(*) FILTER (WHERE t.last_verdict = 'fail') AS last_fail
                             , COUNT(*) FILTER (WHERE t.last_verdict IS NULL)  AS last_open
                          FROM (
                                -- 양식 하나당 한 줄. 마지막 회차의 판정만 남긴다
                                SELECT r."InspForm_id"
                                     , MAX(f."Name")                     AS form_name
                                     , COUNT(*)                          AS try_cnt
                                     , MIN(r."_created")                 AS first_at
                                     , MAX(r."_created")                 AS last_at
                                     , string_agg(DISTINCT pe."Name", ', ') AS workers
                                     , (ARRAY_AGG(r."Verdict" ORDER BY r."TryNo" DESC, r.id DESC))[1]
                                                                         AS last_verdict
                                  FROM insp_result r
                                  LEFT JOIN insp_form f  ON f.id  = r."InspForm_id"
                                  LEFT JOIN person    pe ON pe.id = COALESCE(r."Actor_id", r."_creater_id")
                                 WHERE r."McellUnit_id" = mu.id
                                   AND r."_created" BETWEEN :date_from AND :date_to
                                   AND (CAST(:actor_pk AS integer) IS NULL
                                        OR COALESCE(r."Actor_id", r."_creater_id") = CAST(:actor_pk AS integer))
                                 GROUP BY r."InspForm_id"
                          ) t
                  ) ir ON ir.form_cnt > 0
                  LEFT JOIN job_res  jr ON jr.id = mu."JobResponse_id"
                  LEFT JOIN material m  ON m.id  = jr."Material_id"
                 ORDER BY ir.last_at DESC, mu."UnitNo"
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    /**
     * 유닛의 검사 양식별 묶음 (검사 카드 클릭 1단계).
     *
     * ROTOR / BASKET 같은 양식이 각각 한 줄이 되고, 그 안에 회차가 들어간다.
     */
    public List<Map<String, Object>> getUnitInspectForms(Integer unitPk) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("unit_pk", unitPk);

        String sql = """
                SELECT f.id                           AS form_pk
                     , f."Name"                       AS form_name
                     , COUNT(*)                       AS try_cnt
                     , MAX(r."TryNo")                 AS last_try
                     , COUNT(*) FILTER (WHERE r."Verdict" = 'pass') AS pass_cnt
                     , COUNT(*) FILTER (WHERE r."Verdict" = 'fail') AS fail_cnt
                     , COUNT(*) FILTER (WHERE r."Verdict" IS NULL)  AS open_cnt
                     -- ★ 최종 판정 = 마지막 회차. 이전 불합격은 이력일 뿐이다
                     , (ARRAY_AGG(r."Verdict" ORDER BY r."TryNo" DESC, r.id DESC))[1] AS last_verdict
                     , to_char(MAX(r."_created"), 'yyyy-mm-dd hh24:mi') AS last_at
                     , (SELECT COUNT(*) FROM insp_form_item fi
                         WHERE fi."InspForm_id" = f.id)             AS item_cnt
                  FROM insp_result r
                  LEFT JOIN insp_form f ON f.id = r."InspForm_id"
                 WHERE r."McellUnit_id" = :unit_pk
                 GROUP BY f.id, f."Name"
                 ORDER BY f.id
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    // =================================================================
    // mc04 수리 — 카드 1장 = 수리 유닛 1대
    // =================================================================

    /**
     * 수리 유닛.
     *
     * 접수 유형(반품/사양변경)과 원 로트를 함께 내린다.
     * 재작업(검사 불합격 후 이어서 고친 것)은 1차 실적을 이어서 쓰므로
     * 별도 행이 아니라 rework 표시로만 구분한다.
     */
    public List<Map<String, Object>> getRepairUnits(String dateFrom, String dateTo,
                                                    Integer actorPk, String spjangcd) {
        MapSqlParameterSource p = period(dateFrom, dateTo, actorPk, spjangcd);

        String sql = """
                SELECT mu.id                          AS pk
                     , mu."UnitNo"                    AS unit_no
                     , mu."LotNumber"                 AS lot_number
                     , mu."State"                     AS unit_state
                     , mu."SrcLotNumber"              AS src_lot
                     , mu."RejectReason"              AS reject_reason
                     , rp.id                          AS repair_pk
                     , rp."RepairNo"                  AS repair_no
                     , rp."Cat"                       AS repair_cat
                     , rp."Reason"                    AS repair_reason
                     , jr."WorkOrderNumber"           AS wo
                     , m."Code"                       AS mat_code
                     , m."Name"                       AS mat_name
                     , pe."Name"                      AS worker
                     , e."Name"                       AS equipment
                     , to_char(COALESCE(tm.et, tm.st, mu."StartTime", mu."_created"), 'yyyy-mm-dd') AS prod_date
                     , to_char(COALESCE(tm.st, mu."StartTime"), 'yyyy-mm-dd hh24:mi') AS start_time
                     , to_char(tm.et, 'yyyy-mm-dd hh24:mi')  AS end_time
                     , to_char(COALESCE(tm.st, mu."StartTime"), 'mm-dd hh24:mi') AS start_short
                     , to_char(tm.et, 'mm-dd hh24:mi')       AS end_short
                     , (SELECT COUNT(*) FROM mcell_repair_mat rm
                         WHERE rm."McellUnit_id" = mu.id AND rm."Dir" = '+')  AS mat_in_cnt
                     , (SELECT COUNT(*) FROM mcell_repair_mat rm
                         WHERE rm."McellUnit_id" = mu.id AND rm."Dir" = '-')  AS mat_out_cnt
                  FROM mcell_unit mu
                  JOIN mcell_repair rp ON rp.id = mu."McellRepair_id"
                  LEFT JOIN job_res  jr ON jr.id = mu."JobResponse_id"
                  LEFT JOIN material m  ON m.id  = jr."Material_id"
                  LEFT JOIN person   pe ON pe.id = mu."Actor_id"
                  LEFT JOIN equ      e  ON e.id  = mu."Equipment_id"
                  LEFT JOIN LATERAL (
                        SELECT mp."StartTime" AS st, mp."EndTime" AS et
                          FROM mat_produce mp
                         WHERE mp.id = mu."MatProduce_id"
                  ) tm ON true
                 WHERE COALESCE(tm.et, tm.st, mu."StartTime", mu."_created") BETWEEN :date_from AND :date_to
                   AND (CAST(:actor_pk AS integer) IS NULL
                        OR mu."Actor_id" = CAST(:actor_pk AS integer))
                 ORDER BY COALESCE(tm.et, tm.st, mu."StartTime", mu."_created") DESC, mu."UnitNo"
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    // =================================================================
    // mc03 포장 — 카드 1장 = 박스 1개
    // =================================================================

    /**
     * 포장 완료 — 카드 1장 = 박스 1개(= 유닛 1대).
     *
     * ★ mcell_unit."State"='packed' 가 아니라 mat_produce 를 센다.
     *   포장은 박스 하나가 mat_produce 1건(GoodQty=1)으로 남고,
     *   작업자·설비·시각·완제품 로트가 전부 거기 있다.
     *   유닛 상태만 보면 "언제 누가 포장했는지"를 알 수 없고,
     *   포장 취소로 packed → pass 로 되돌아간 건도 구분이 안 된다.
     *
     * ★ 박스 라벨(MakerLotNo)은 완제품 로트(mat_lot)에 붙는다.
     *   SourceTableName='mat_produce' + SourceDataPk 로 그 차수의 산출 로트를 찾는다.
     *
     * ★ 유닛은 로트번호로 되짚는다.
     *   완제품 로트가 유닛 로트번호를 승계하므로 같은 번호로 찾을 수 있다.
     *   수리가 keep 모드면 같은 번호의 유닛이 둘일 수 있어 packed 인 쪽을 먼저 고른다.
     *   (McellPackService.getPackedList 와 같은 규칙)
     */
    public List<Map<String, Object>> getPackUnits(String dateFrom, String dateTo,
                                                  Integer actorPk, String spjangcd) {
        MapSqlParameterSource p = period(dateFrom, dateTo, actorPk, spjangcd);

        String sql = """
                SELECT mp.id                          AS pk
                     , mp."LotIndex"                  AS chasu
                     , mp."LotNumber"                 AS lot_number
                     , COALESCE(mp."GoodQty", 0)      AS good_qty
                     , ml."MakerLotNo"                AS maker_lot_no
                     , jr.id                          AS jr_pk
                     , jr."WorkOrderNumber"           AS wo
                     , m."Code"                       AS mat_code
                     , m."Name"                       AS mat_name
                     , mu.id                          AS unit_pk
                     , mu."UnitNo"                    AS unit_no
                     , mu."State"                     AS unit_state
                     , mu.is_repair                   AS is_repair
                     , mu."SrcLotNumber"              AS src_lot
                     , pe."Name"                      AS worker
                     , e."Name"                       AS equipment
                     , to_char(COALESCE(mp."EndTime", mp."StartTime", mp."_created"), 'yyyy-mm-dd') AS prod_date
                     , to_char(mp."StartTime", 'yyyy-mm-dd hh24:mi') AS start_time
                     , to_char(mp."EndTime",   'yyyy-mm-dd hh24:mi') AS end_time
                     , to_char(mp."StartTime", 'mm-dd hh24:mi')      AS start_short
                     , to_char(mp."EndTime",   'mm-dd hh24:mi')      AS end_short
                  FROM mat_produce mp
                  JOIN job_res jr ON jr.id = mp."JobResponse_id"
                  LEFT JOIN material    m   ON m.id   = COALESCE(mp."Material_id", jr."Material_id")
                  LEFT JOIN mat_grp     pmg ON pmg.id = m."MaterialGroup_id"
                  LEFT JOIN person      pe  ON pe.id  = mp."Actor_id"
                  LEFT JOIN equ         e   ON e.id   = mp."Equipment_id"
                  -- 박스 라벨은 그 차수가 만든 완제품 로트에 붙는다
                  LEFT JOIN mat_lot ml ON ml."SourceTableName" = 'mat_produce'
                                      AND ml."SourceDataPk"    = mp.id
                  LEFT JOIN LATERAL (
                        SELECT x.id, x."UnitNo", x."State", x."SrcLotNumber"
                             , CASE WHEN x."McellRepair_id" IS NOT NULL THEN 'Y' ELSE 'N' END AS is_repair
                          FROM mcell_unit x
                         WHERE x."LotNumber" = mp."LotNumber"
                           AND COALESCE(x."_status",'a') = 'a'
                         ORDER BY (CASE WHEN x."State" = 'packed' THEN 0 ELSE 1 END), x.id DESC
                         LIMIT 1
                  ) mu ON true
                 -- ★ 포장은 「산출 품목이 완제품인 차수」로 가린다.
                 --   공정 코드도 창고도 못 쓴다. packUnit 이 req.workCenterId=56 을 넘기지만
                 --   startProduction 이 작지의 워크센터를 쓰는 탓에 실제 저장값은 52(조립)이고,
                 --   산출 로트도 그 워크센터의 산출창고(17)로 들어간다.
                 --   실데이터 확인 : mat_produce 2090 = 품목 MRCN-21022, wc 52, 창고 17.
                 --   조립·수리는 WIP-* 재공품을 만들고 포장만 MRCN-* 완제품을 만든다 —
                 --   품목이 유일하게 믿을 수 있는 축이다.
                 WHERE COALESCE(pmg."MaterialType",'') = 'product'
                   -- ★ 2공장 것만. 품목군만 보면 1공장 키트 완제품(BSC*-FG*)도 같이 잡힌다.
                   AND COALESCE(m."Factory_id", 1) = 2
                   -- ★ M-CELL 포장은 반드시 유닛 1대를 소비한다.
                   --   유닛이 안 붙는 차수는 이 공정의 실적이 아니다(2차 방어).
                   AND mu.id IS NOT NULL
                   AND COALESCE(mp._status, 'a') = 'a'
                   AND COALESCE(mp."EndTime", mp."StartTime", mp."_created")
                       BETWEEN :date_from AND :date_to
                   AND (CAST(:actor_pk AS integer) IS NULL
                        OR mp."Actor_id" = CAST(:actor_pk AS integer))
                 ORDER BY COALESCE(mp."EndTime", mp."StartTime", mp."_created") DESC, mp."LotIndex" DESC
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    /** 포장 차수의 투입 자재 (포장자재 + 유닛 로트) */
    public List<Map<String, Object>> getPackConsumed(Integer mpPk) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("mp_pk", mpPk);

        String sql = """
                SELECT m."Code"                       AS mat_code
                     , m."Name"                       AS mat_name
                     , u."Name"                       AS unit
                     , ml."LotNumber"                 AS lot_number
                     , ml."MakerLotNo"                AS maker_lot_no
                     , SUM(COALESCE(lc."OutputQty", 0)) AS qty
                     , MAX(COALESCE(mtg."MaterialType",'')) AS mat_type
                  FROM mat_lot_cons lc
                  LEFT JOIN mat_lot  ml  ON ml.id  = lc."MaterialLot_id"
                  LEFT JOIN material m   ON m.id   = ml."Material_id"
                  LEFT JOIN mat_grp  mtg ON mtg.id = m."MaterialGroup_id"
                  LEFT JOIN unit     u   ON u.id   = m."Unit_id"
                 WHERE lc."SourceTableName" = 'mat_produce'
                   AND lc."SourceDataPk"    = :mp_pk
                   AND COALESCE(lc._status,'a') = 'a'
                 GROUP BY m."Code", m."Name", u."Name", ml."LotNumber", ml."MakerLotNo"
                 ORDER BY m."Code"
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    // =================================================================
    // 상세
    // =================================================================

    /** 유닛의 BOM 계층 스텝 (조립 카드 클릭) */
    public List<Map<String, Object>> getUnitSteps(Integer unitPk) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("unit_pk", unitPk);

        String sql = """
                SELECT us.id                          AS pk
                     , us."Depth"                     AS depth
                     , us."State"                     AS state
                     , us."Source"                    AS source
                     , us."LotNumber"                 AS lot_number
                     , COALESCE(us."ReworkYN",'N')    AS rework_yn
                     , m."Code"                       AS mat_code
                     , m."Name"                       AS mat_name
                     , to_char(mp."StartTime", 'mm-dd hh24:mi') AS start_short
                     , to_char(mp."EndTime",   'mm-dd hh24:mi') AS end_short
                     , pe."Name"                      AS worker
                     , e."Name"                       AS equipment
                     , mp.id                          AS mp_pk
                  FROM mcell_unit_step us
                  LEFT JOIN material    m  ON m.id  = us."Material_id"
                  LEFT JOIN mat_produce mp ON mp.id = us."MatProduce_id"
                  LEFT JOIN person      pe ON pe.id = us."Actor_id"
                  LEFT JOIN equ         e  ON e.id  = us."Equipment_id"
                 WHERE us."McellUnit_id" = :unit_pk
                 ORDER BY us."Depth" DESC, us.id
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    /**
     * 유닛에 투입된 자재 — 로트 단위.
     *
     * ★ mat_lot_cons 가 "어느 로트를 몇 개 썼는지"의 진실이다.
     *   수량 컬럼은 ConsumedQty 가 아니라 **"OutputQty"** — 소비는 출고이기 때문.
     *   (mat_consu 는 품목 단위라 같은 부품이 두 로트에서 나눠 들어간 경우가 안 보인다)
     *
     * 조인은 스텝의 생산차수(mat_produce.id)를 SourceDataPk 로 건다.
     */
    public List<Map<String, Object>> getUnitConsumed(Integer unitPk) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("unit_pk", unitPk);

        String sql = """
                SELECT m."Code"                       AS mat_code
                     , m."Name"                       AS mat_name
                     , u."Name"                       AS unit
                     , ml."LotNumber"                 AS lot_number
                     , ml."MakerLotNo"                AS maker_lot_no
                     , SUM(COALESCE(lc."OutputQty", 0)) AS qty
                  FROM mcell_unit_step us
                  JOIN mat_lot_cons lc ON lc."SourceDataPk" = us."MatProduce_id"
                                      AND lc."SourceTableName" = 'mat_produce'
                                      AND COALESCE(lc._status,'a') = 'a'
                  LEFT JOIN mat_lot  ml ON ml.id = lc."MaterialLot_id"
                  LEFT JOIN material m  ON m.id  = ml."Material_id"
                  LEFT JOIN unit     u  ON u.id  = m."Unit_id"
                 WHERE us."McellUnit_id" = :unit_pk
                 GROUP BY m."Code", m."Name", u."Name", ml."LotNumber", ml."MakerLotNo"
                 ORDER BY m."Code", ml."LotNumber"
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    /** 유닛의 검사 회차 전체 */
    public List<Map<String, Object>> getUnitInspects(Integer unitPk) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("unit_pk", unitPk);

        String sql = """
                SELECT r.id                           AS pk
                     , r."TryNo"                      AS try_no
                     , r."Verdict"                    AS verdict
                     , r."FailReason"                 AS fail_reason
                     , f.id                           AS form_pk
                     , f."Name"                       AS form_name
                     , pe."Name"                      AS worker
                     , to_char(r."_created", 'yyyy-mm-dd hh24:mi') AS created
                  FROM insp_result r
                  LEFT JOIN insp_form f  ON f.id  = r."InspForm_id"
                  LEFT JOIN person    pe ON pe.id = COALESCE(r."Actor_id", r."_creater_id")
                 WHERE r."McellUnit_id" = :unit_pk
                 ORDER BY f.id, r."TryNo" DESC, r.id DESC
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    /** 검사 회차의 항목별 결과 (검사 카드 클릭) */
    public List<Map<String, Object>> getInspectItems(Integer resultPk) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("result_pk", resultPk);

        String sql = """
                SELECT ri.id                          AS pk
                     , ri."RepeatNo"                  AS repeat_no
                     , ri."Result"                    AS result
                     , ri."Value"                     AS value
                     , ri."SpecLabel"                 AS spec_label
                     , ri."SpecTool"                  AS spec_tool
                     , fi."ItemName"                  AS item_name
                     , fi."Criteria"                  AS criteria
                     , fi."Method"                    AS method
                     , fi."Unit"                      AS item_unit
                     , fi."LowerLimit"                AS lower_limit
                     , fi."UpperLimit"                AS upper_limit
                     , fi."JudgeType"                 AS judge_type
                     , fi."RepeatCount"               AS repeat_count
                     , pe."Name"                      AS worker
                  FROM insp_result_item ri
                  LEFT JOIN insp_form_item fi ON fi.id = ri."InspFormItem_id"
                  LEFT JOIN person         pe ON pe.id = ri."Actor_id"
                 WHERE ri."InspResult_id" = :result_pk
                 ORDER BY fi."SeqNo", fi.id, ri."RepeatNo"
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    /** 수리 유닛의 자재 가감 (수리 카드 클릭) */
    public List<Map<String, Object>> getRepairMats(Integer unitPk) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("unit_pk", unitPk);

        String sql = """
                SELECT rm.id                          AS pk
                     , rm."Dir"                       AS dir
                     , COALESCE(rm."Qty", 0)          AS qty
                     , rm."State"                     AS state
                     , m."Code"                       AS mat_code
                     , m."Name"                       AS mat_name
                     , u."Name"                       AS unit
                     , sl."LotNumber"                 AS src_lot
                     , ml."LotNumber"                 AS lot_number
                  FROM mcell_repair_mat rm
                  LEFT JOIN material m  ON m.id  = rm."Material_id"
                  LEFT JOIN unit     u  ON u.id  = m."Unit_id"
                  LEFT JOIN mat_lot  sl ON sl.id = rm."SrcMatLot_id"
                  LEFT JOIN mat_lot  ml ON ml.id = rm."MatLot_id"
                 WHERE rm."McellUnit_id" = :unit_pk
                 ORDER BY rm."Dir" DESC, rm.id
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    // =================================================================
    // 부적합 · 콤보
    // =================================================================

    /**
     * 부적합 — 품목 단위.
     *
     * ★ 유닛 본체 불량은 여기 없다.
     *   부적합 등록 화면이 InspectYN='Y'(유닛 본체)를 자재 후보에서 빼기 때문.
     *   유닛 불량의 진실은 insp_result."FailReason" 이고 검사 칼럼에서 본다.
     *   양쪽에서 받으면 한 대가 두 번 계상된다.
     */
    public List<Map<String, Object>> getDefectByMaterial(String dateFrom, String dateTo, String spjangcd) {
        MapSqlParameterSource p = period(dateFrom, dateTo, null, spjangcd);

        String sql = """
                SELECT m."Code"                       AS mat_code
                     , m."Name"                       AS mat_name
                     , p."Code"                       AS proc_code
                     , p."Name"                       AS proc_name
                     , COALESCE(dt."Name", d."DefectTypeEtc") AS defect_type
                     , SUM(COALESCE(d."DefectQty", 0))        AS defect_qty
                     , COUNT(*)                       AS cnt
                     , COUNT(*) FILTER (WHERE d."JobResponse_id" IS NULL) AS unlinked_cnt
                     , MAX(to_char(d."DefectDate", 'yyyy-mm-dd'))         AS last_date
                  FROM defect_regist d
                  LEFT JOIN process     p  ON p.id  = d."Process_id"
                  LEFT JOIN defect_type dt ON dt.id = d."DefectType_id"
                  LEFT JOIN material    m  ON m.id  = d."Material_id"
                 WHERE d."DefectDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
                   AND d."State" = 'confirmed'
                   AND d."Factory_id" = 2
                 GROUP BY m."Code", m."Name", p."Code", p."Name",
                          COALESCE(dt."Name", d."DefectTypeEtc")
                 ORDER BY SUM(COALESCE(d."DefectQty", 0)) DESC
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }

    /** 작업자 콤보 — 그 기간에 실제로 무언가를 한 사람만 */
    public List<Map<String, Object>> getActorCombo(String dateFrom, String dateTo, String spjangcd) {
        MapSqlParameterSource p = period(dateFrom, dateTo, null, spjangcd);

        String sql = """
                SELECT DISTINCT pe.id AS pk, pe."Name" AS name
                  FROM mcell_unit mu
                  JOIN person pe ON pe.id = mu."Actor_id"
                 WHERE COALESCE(mu."_modified", mu."_created", mu."StartTime")
                       BETWEEN :date_from AND :date_to
                 UNION
                SELECT DISTINCT pe.id AS pk, pe."Name" AS name
                  FROM mcell_unit_step us
                  JOIN person pe ON pe.id = us."Actor_id"
                  JOIN mat_produce mp ON mp.id = us."MatProduce_id"
                 WHERE COALESCE(mp."EndTime", mp."StartTime", mp."_created") BETWEEN :date_from AND :date_to
                 UNION
                SELECT DISTINCT pe.id AS pk, pe."Name" AS name
                  FROM insp_result r
                  JOIN person pe ON pe.id = COALESCE(r."Actor_id", r."_creater_id")
                 WHERE r."_created" BETWEEN :date_from AND :date_to
                 ORDER BY name
                """;

        return nz(this.sqlRunner.getRows(sql, p));
    }
}