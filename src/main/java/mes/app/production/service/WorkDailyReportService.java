package mes.app.production.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import mes.domain.services.SqlRunner;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 작업일보 — 날짜별 · 공정별 작업량/작업자 (조회 · 인쇄).
 *
 * 데이터 소스 2개를 UNION (뷰 없이 원본 테이블 직접 조회):
 *   1) mat_produce                      : 생산공정(조립/블리스터/융착/멸균/포장 …) 실적
 *   2) wash_work + wash_work_item : 세척 실적 (작지 없음)
 *
 * ★ 생산실적현황(ProdResultListService)은 손대지 않는다.
 *   세척은 job_res/mat_produce 를 만들지 않으므로 그쪽에서 자동 제외되며,
 *   세척이 생산량으로 이중계상되지 않는 것이 정상 동작이다.
 */
@Service
public class WorkDailyReportService {

    @Autowired
    SqlRunner sqlRunner;

    /**
     * 공통 base CTE — 생산실적 + 세척실적을 같은 모양으로 정규화.
     * 세척은 State='done', 생산은 State='finished' 인 행만 집계 대상.
     */
    private static final String BASE_CTE = """
        WITH base AS (
            -- (1) 생산공정 실적
            SELECT mp."ProductionDate"::date        AS work_date
                 , wc."Process_id"                  AS process_id
                 , p."Name"                         AS process_name
                 , p."Code"                         AS process_code
                 , mp."Actor_id"                    AS actor_id
                 , mp."Equipment_id"                AS equipment_id
                 , mp."Material_id"                 AS mat_id
                 , jr."WorkOrderNumber"             AS order_num
                 , mp."LotNumber"                   AS lot_no
                 , COALESCE(mp."GoodQty", 0)        AS good_qty
                 , COALESCE(mp."DefectQty", 0)      AS defect_qty
                 , mp."StartTime"                   AS start_time
                 , mp."EndTime"                     AS end_time
                 , mp.spjangcd                      AS spjangcd
                 , 'produce'                        AS src
              FROM mat_produce mp
              JOIN job_res jr        ON jr.id = mp."JobResponse_id"
              LEFT JOIN work_center wc ON wc.id = mp."WorkCenter_id"
              LEFT JOIN process p      ON p.id  = wc."Process_id"
             WHERE COALESCE(mp."_status", 'a') = 'a'
               AND mp."State" = 'finished'

            UNION ALL

            -- (2) 세척 실적 (작지 없음 — wash_work_item 직접 조회)
            SELECT ww."WashDate"                    AS work_date
                 , p.id                             AS process_id
                 , p."Name"                         AS process_name
                 , p."Code"                         AS process_code
                 , ww."Actor_id"                    AS actor_id
                 , ww."Equipment_id"                AS equipment_id
                 , wwi."Material_id"                AS mat_id
                 , NULL::varchar                    AS order_num
                 , NULL::varchar                    AS lot_no
                 , COALESCE(wwi."Qty", 0)           AS good_qty
                 , COALESCE(wwi."DefectQty", 0)     AS defect_qty
                 , wwi."StartTime"                  AS start_time
                 , wwi."EndTime"                    AS end_time
                 , ww.spjangcd                      AS spjangcd
                 , 'wash'                           AS src
              FROM wash_work_item wwi
              JOIN wash_work ww ON ww.id = wwi."WashWork_id"
              LEFT JOIN process p  ON p."Code" = 'bsc01'
             WHERE wwi."_status" = 'a'
               AND ww."_status"  = 'a'
               AND wwi."State"   = 'done'
        )
        """;

    /**
     * 요약 — 날짜 × 공정 집계 (작업일보 상단 그리드)
     */
    public List<Map<String, Object>> getSummary(String dateFrom, String dateTo,
                                                Integer processPk, String spjangcd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("dateFrom", LocalDate.parse(dateFrom));
        p.addValue("dateTo",   LocalDate.parse(dateTo));
        p.addValue("processPk", processPk);
        p.addValue("spjangcd", spjangcd);

        String sql = BASE_CTE + """
            SELECT to_char(b.work_date, 'yyyy-mm-dd')      AS work_date
                 , b.process_id
                 , COALESCE(b.process_name, '-')           AS process_name
                 , b.process_code
                 , COUNT(*)                                AS row_cnt
                 , COUNT(DISTINCT b.actor_id)              AS worker_cnt
                 , COUNT(DISTINCT b.mat_id)                AS mat_cnt
                 , SUM(b.good_qty)                         AS good_qty
                 , SUM(b.defect_qty)                       AS defect_qty
                 , ROUND((CASE WHEN SUM(b.good_qty) + SUM(b.defect_qty) = 0 THEN 0
                               ELSE SUM(b.defect_qty) / (SUM(b.good_qty) + SUM(b.defect_qty)) * 100
                          END)::numeric, 2)                AS defect_percent
                 , to_char(MIN(b.start_time), 'hh24:mi')   AS first_start
                 , to_char(MAX(b.end_time),   'hh24:mi')   AS last_end
              FROM base b
             WHERE b.work_date BETWEEN :dateFrom AND :dateTo
               AND b.spjangcd = :spjangcd
            """;
        if (processPk != null) sql += " AND b.process_id = :processPk ";
        sql += """
             GROUP BY b.work_date, b.process_id, b.process_name, b.process_code
             ORDER BY b.work_date DESC, b.process_code
            """;
        return this.sqlRunner.getRows(sql, p);
    }

    /**
     * 상세 — 날짜 × 공정 × 작업자 × 품목 (작업일보 하단 / 인쇄용 본문)
     */
    public List<Map<String, Object>> getDetail(String dateFrom, String dateTo,
                                               Integer processPk, Integer actorPk, String spjangcd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("dateFrom", LocalDate.parse(dateFrom));
        p.addValue("dateTo",   LocalDate.parse(dateTo));
        p.addValue("processPk", processPk);
        p.addValue("actorPk", actorPk);
        p.addValue("spjangcd", spjangcd);

        String sql = BASE_CTE + """
            SELECT to_char(b.work_date, 'yyyy-mm-dd')      AS work_date
                 , b.process_id
                 , COALESCE(b.process_name, '-')           AS process_name
                 , b.process_code
                 , b.actor_id
                 , COALESCE(pr."Name", '-')                AS worker_name
                 , COALESCE(e."Name", '-')                 AS equipment_name
                 , b.mat_id
                 , m."Code"                                AS mat_code
                 , m."Name"                                AS mat_name
                 , u."Name"                                AS unit
                 , b.order_num
                 , b.lot_no
                 , b.good_qty
                 , b.defect_qty
                 , to_char(b.start_time, 'hh24:mi')        AS start_time
                 , to_char(b.end_time,   'hh24:mi')        AS end_time
                 , ROUND((EXTRACT(EPOCH FROM (b.end_time - b.start_time)) / 60)::numeric, 0) AS work_min
                 , b.src
              FROM base b
              LEFT JOIN person pr  ON pr.id = b.actor_id
              LEFT JOIN equ e      ON e.id  = b.equipment_id
              LEFT JOIN material m ON m.id  = b.mat_id
              LEFT JOIN unit u     ON u.id  = m."Unit_id"
             WHERE b.work_date BETWEEN :dateFrom AND :dateTo
               AND b.spjangcd = :spjangcd
            """;
        if (processPk != null) sql += " AND b.process_id = :processPk ";
        if (actorPk != null)   sql += " AND b.actor_id = :actorPk ";
        sql += " ORDER BY b.work_date DESC, b.process_code, worker_name, b.start_time ";
        return this.sqlRunner.getRows(sql, p);
    }

    /**
     * 작업자별 집계 — 날짜 × 작업자 (작업일보 "작업자별 실적" 탭)
     */
    public List<Map<String, Object>> getByWorker(String dateFrom, String dateTo, String spjangcd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("dateFrom", LocalDate.parse(dateFrom));
        p.addValue("dateTo",   LocalDate.parse(dateTo));
        p.addValue("spjangcd", spjangcd);

        String sql = BASE_CTE + """
            SELECT to_char(b.work_date, 'yyyy-mm-dd')  AS work_date
                 , b.actor_id
                 , COALESCE(pr."Name", '-')            AS worker_name
                 , COUNT(DISTINCT b.process_id)        AS process_cnt
                 , string_agg(DISTINCT COALESCE(b.process_name, '-'), ', ') AS processes
                 , SUM(b.good_qty)                     AS good_qty
                 , SUM(b.defect_qty)                   AS defect_qty
                 , ROUND((SUM(EXTRACT(EPOCH FROM (b.end_time - b.start_time))) / 60)::numeric, 0) AS work_min
              FROM base b
              LEFT JOIN person pr ON pr.id = b.actor_id
             WHERE b.work_date BETWEEN :dateFrom AND :dateTo
               AND b.spjangcd = :spjangcd
             GROUP BY b.work_date, b.actor_id, pr."Name"
             ORDER BY b.work_date DESC, worker_name
            """;
        return this.sqlRunner.getRows(sql, p);
    }
}