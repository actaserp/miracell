package mes.app.dashboard.service;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;

@Slf4j
@Service
public class DashboardProjectService {

	@Autowired
	SqlRunner sqlRunner;

	/**
	 * 프로젝트 목록 + 진척률 2종
	 * - 공정 진척: 전체 자식 공정 row(BOM 전개) 중 finished 비율
	 * - 생산량 진척: 완제품(라우팅 마지막 공정) 양품 / 수주량
	 */
	public List<Map<String, Object>> getProjectList(String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);

		String sql = """
            SELECT
                d.projno,
                d.projnm,
                d.balcltnm                                AS company,
                d.stdate, d.eddate, d.endflag,
                COUNT(DISTINCT s.id)                      AS part_cnt,
                COALESCE(SUM(s."SujuQty"), 0)             AS suju_qty,
                COALESCE(SUM(agg.total_proc), 0)          AS total_proc,
                COALESCE(SUM(agg.done_proc), 0)           AS done_proc,
                COALESCE(SUM(agg.working_proc), 0)        AS working_proc,
                CASE WHEN SUM(agg.total_proc) > 0
                     THEN ROUND(SUM(agg.done_proc)::numeric / SUM(agg.total_proc)::numeric * 100, 1)
                     ELSE 0 END                           AS proc_progress,
                COALESCE(SUM(agg.final_good), 0)          AS good_qty,
                CASE WHEN SUM(s."SujuQty") > 0
                     THEN ROUND(SUM(agg.final_good)::numeric / SUM(s."SujuQty")::numeric * 100, 1)
                     ELSE 0 END                           AS qty_progress
            FROM tb_da003 d
            LEFT JOIN suju s ON s.project_id = d.projno
            LEFT JOIN LATERAL (
                SELECT
                    (SELECT COUNT(*) FROM job_res c
                      WHERE c."Parent_id" = h.id) AS total_proc,
                    (SELECT COUNT(*) FROM job_res c
                      WHERE c."Parent_id" = h.id AND c."State" = 'finished') AS done_proc,
                    (SELECT COUNT(*) FROM job_res c
                      WHERE c."Parent_id" = h.id AND c."State" = 'working') AS working_proc,
                    (SELECT COALESCE(SUM(c."GoodQty"),0)
                       FROM job_res c
                       JOIN work_center wc ON wc.id = c."WorkCenter_id"
                       JOIN routing_proc rp ON rp."Routing_id" = h."Routing_id"
                                           AND rp."Process_id" = wc."Process_id"
                      WHERE c."Parent_id" = h.id
                        AND rp."ProcessOrder" = (
                            SELECT MAX(rp2."ProcessOrder") FROM routing_proc rp2
                            WHERE rp2."Routing_id" = h."Routing_id")
                    ) AS final_good
                FROM job_res h
                WHERE h."SourceTableName" = 'suju'
                  AND h."SourceDataPk" = s.id
                  AND h."Parent_id" IS NULL
            ) agg ON true
            WHERE (:spjangcd IS NULL OR d.spjangcd = :spjangcd)
            GROUP BY d.projno, d.projnm, d.balcltnm, d.stdate, d.eddate, d.endflag
            ORDER BY d.projno DESC
            """;

		return this.sqlRunner.getRows(sql, p);
	}

	/**
	 * 프로젝트 상세 - 부품(suju) → 반제품(Material) → 공정 전개
	 * 화면에서 part_name > proc_mat_name 으로 묶고, proc_order 순으로 공정 바를 그림
	 */
	public List<Map<String, Object>> getProjectDetail(String projno) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("projno", projno);

		String sql = """
            SELECT
                s.id                AS suju_id,
                s."Material_Name"   AS part_name,
                s."Standard"        AS standard,
                s."SujuQty"         AS suju_qty,
                h.id                AS header_id,
                h."Routing_id"      AS routing_id,
                c.id                AS proc_jr_id,
                c."State"           AS proc_state,
                c."OrderQty"        AS proc_order_qty,
                c."GoodQty"         AS proc_good_qty,
                c."DefectQty"       AS proc_defect_qty,
                c."Material_id"     AS proc_mat_id,
                cm."Name"           AS proc_mat_name,
                p."Code"            AS process_code,
                p."Name"            AS process_name,
                p."ProcessType"     AS process_type,
                rp."ProcessOrder"   AS proc_order
            FROM suju s
            JOIN job_res h  ON h."SourceTableName"='suju' AND h."SourceDataPk"=s.id AND h."Parent_id" IS NULL
            LEFT JOIN job_res c ON c."Parent_id" = h.id
            LEFT JOIN work_center wc ON wc.id = c."WorkCenter_id"
            LEFT JOIN process p ON p.id = wc."Process_id"
            LEFT JOIN routing_proc rp ON rp."Routing_id" = c."Routing_id" AND rp."Process_id" = wc."Process_id"
            LEFT JOIN material cm ON cm.id = c."Material_id"
            WHERE s.project_id = :projno
            ORDER BY s.id, c."Material_id", rp."ProcessOrder"
            """;

		return this.sqlRunner.getRows(sql, p);
	}

	/**
	 * 현재 작업중(working) 현황 - 작업자/설비 (작업자 여러 명이면 여러 행)
	 */
	public List<Map<String, Object>> getWorkingNow(String projno) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("projno", projno);

		String sql = """
            SELECT
                c.id                AS jr_id,
                cm."Name"           AS part_name,
                p."Code"            AS process_code,
                p."Name"            AS process_name,
                c."OrderQty"        AS order_qty,
                c."GoodQty"         AS good_qty,
                c."StartTime"       AS start_time,
                eq."Name"           AS equipment_name,
                per."Name"          AS worker_name,
                er."RunState"       AS run_state
            FROM job_res h
            JOIN job_res c ON c."Parent_id" = h.id AND c."State"='working'
            LEFT JOIN work_center wc ON wc.id = c."WorkCenter_id"
            LEFT JOIN process p ON p.id = wc."Process_id"
            LEFT JOIN material cm ON cm.id = c."Material_id"
            LEFT JOIN equ eq ON eq.id = c."Equipment_id"
            LEFT JOIN mat_produce mp ON mp."JobResponse_id" = c.id AND mp."State"='working'
            LEFT JOIN person per ON per.id = mp."Actor_id"
            LEFT JOIN equ_run er ON er."JobResponse_id" = c.id AND er."RunState"='run'
            WHERE h."SourceTableName"='suju'
              AND h."SourceDataPk" IN (SELECT id FROM suju WHERE project_id = :projno)
            ORDER BY c."StartTime"
            """;

		return this.sqlRunner.getRows(sql, p);
	}
}