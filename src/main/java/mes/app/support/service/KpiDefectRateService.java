package mes.app.support.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import mes.domain.services.SqlRunner;

/**
 * KPI 공정불량률.
 *
 * 공정불량률(%) = 불량수량 / 검사수량 * 100,  검사수량 = 생산수량.
 * (사업계획서 1.5 / 제품불량현황과 동일한 분모)
 *
 * ★ 데이터 소스 (제품불량현황 ProcessDefectService 와 동일)
 *   - 불량   : defect_regist (발생일 DefectDate, 공정 Process_id, 제품 Material_id, State='confirmed')
 *   - 생산량 : mat_produce(생산형, WorkCenter→Process) + wash_work_item(세척 bsc01) + steril_batch_item(멸균 bsc04)
 *             세 소스 모두 Material_id 를 가지므로 제품별 집계가 가능하다.
 *
 * 화면(kpi_defect_rate.html)은 MockData.fetch 가 주던
 *   { lines: [...], details: [...], months: [...] } 형식을 그대로 기대한다.
 *   - lines   : 월 × 공정 × 제품 (검사수량/불량수량/불량률)
 *   - details : 불량 상세 (드릴다운 팝업용)
 */
@Service
public class KpiDefectRateService {

	@Autowired
	SqlRunner sqlRunner;

	private static List<Map<String, Object>> nz(List<Map<String, Object>> rows) {
		return (rows == null) ? new ArrayList<>() : rows;
	}

	/** 공정 콤보 (실제 등록된 공정 동적) */
	public List<Map<String, Object>> getProcessCombo() {
		return nz(this.sqlRunner.getRows("""
            SELECT p."Code" AS code, p."Name" AS name,
                   COALESCE(p."Factory_id", 1) AS factory_id
              FROM process p
             WHERE COALESCE(p._status, 'a') = 'a'
               AND p."Code" ~ '^(bsc|mc)[0-9]+$'
             ORDER BY COALESCE(p."Factory_id", 1), p."Code"
            """, new MapSqlParameterSource()));
	}

	/**
	 * 월 × 공정 × 제품 불량률 라인.
	 *
	 * @param monthFrom "yyyy-MM" (포함)
	 * @param monthTo   "yyyy-MM" (포함)
	 * @param procCode  공정코드. null/blank = 전체
	 * @param materialId 제품 id. null = 전체
	 */
	public List<Map<String, Object>> getLines(String monthFrom, String monthTo,
											  String procCode, Integer materialId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		// 월 경계: monthFrom 1일 ~ monthTo 말일+1 (반개구간)
		p.addValue("date_from", monthFrom + "-01");
		p.addValue("date_to", monthTo + "-01");
		p.addValue("proc_code", (procCode == null || procCode.isBlank()) ? null : procCode);
		p.addValue("material_id", materialId);

		String sql = """
            WITH
            -- 월 경계 (date_to 는 해당 월 1일 -> 그 달 포함 위해 +1개월)
            bound AS (
                SELECT CAST(:date_from AS date) AS d_from,
                       (CAST(:date_to AS date) + INTERVAL '1 month')::date AS d_to
            ),
            -- ① 생산량(=검사수량) : 월 × 공정 × 제품
            out_src AS (
                -- 생산형
                SELECT pr."Code" AS proc_code,
                       to_char(COALESCE(mp."EndTime", mp."StartTime", mp."_created"), 'YYYY-MM') AS ym,
                       mp."Material_id" AS material_id,
                       COALESCE(mp."GoodQty", 0)::decimal AS qty
                  FROM mat_produce mp
                  LEFT JOIN work_center wc ON wc.id = mp."WorkCenter_id"
                  LEFT JOIN process     pr ON pr.id = wc."Process_id"
                  CROSS JOIN bound bd
                 WHERE COALESCE(mp."EndTime", mp."StartTime", mp."_created") >= bd.d_from
                   AND COALESCE(mp."EndTime", mp."StartTime", mp."_created") <  bd.d_to
                   AND COALESCE(mp._status, 'a') = 'a'

                UNION ALL
                -- 세척(bsc01)
                SELECT 'bsc01',
                       to_char(w."WashDate", 'YYYY-MM'),
                       wi."Material_id",
                       COALESCE(wi."Qty", 0)::decimal
                  FROM wash_work_item wi
                  JOIN wash_work w ON w.id = wi."WashWork_id"
                  CROSS JOIN bound bd
                 WHERE w."WashDate" >= bd.d_from AND w."WashDate" < bd.d_to
                   AND COALESCE(w._status,  'a') = 'a'
                   AND COALESCE(wi._status, 'a') = 'a'

                UNION ALL
                -- 멸균(bsc04) : BI fail 이면 산출 0
                SELECT 'bsc04',
                       to_char(b."SterilDate", 'YYYY-MM'),
                       si."Material_id",
                       CASE WHEN COALESCE(b."BiResult",'') = 'fail' THEN 0
                            ELSE COALESCE(si."Qty", 0) END::decimal
                  FROM steril_batch_item si
                  JOIN steril_batch b ON b.id = si."SterilBatch_id"
                  CROSS JOIN bound bd
                 WHERE b."SterilDate" >= bd.d_from AND b."SterilDate" < bd.d_to
                   AND COALESCE(b._status,  'a') = 'a'
                   AND COALESCE(si._status, 'a') = 'a'
            ),
            outp AS (
                SELECT proc_code, ym, material_id, SUM(qty)::decimal AS output_qty
                  FROM out_src
                 WHERE (CAST(:material_id AS integer) IS NULL OR material_id = CAST(:material_id AS integer))
                 GROUP BY proc_code, ym, material_id
            ),
            -- ② 불량 : 월 × 공정 × 제품
            def AS (
                SELECT p."Code" AS proc_code,
                       to_char(d."DefectDate", 'YYYY-MM') AS ym,
                       d."Material_id" AS material_id,
                       SUM(COALESCE(d."DefectQty", 0))::decimal AS defect_qty,
                       COUNT(*) AS defect_cnt
                  FROM defect_regist d
                  LEFT JOIN process p ON p.id = d."Process_id"
                  CROSS JOIN bound bd
                 WHERE d."DefectDate" >= bd.d_from AND d."DefectDate" < bd.d_to
                   AND COALESCE(d."State",'') = 'confirmed'
                   AND COALESCE(d._status, 'a') = 'a'
                   AND (CAST(:material_id AS integer) IS NULL OR d."Material_id" = CAST(:material_id AS integer))
                 GROUP BY p."Code", to_char(d."DefectDate", 'YYYY-MM'), d."Material_id"
            ),
            -- ③ 공정×월×제품 키 통합 (생산 or 불량 어느 쪽이든 있으면 행 생성)
            keys AS (
                SELECT proc_code, ym, material_id FROM outp
                UNION
                SELECT proc_code, ym, material_id FROM def
            )
            SELECT k.ym                                   AS "Month",
                   k.proc_code                            AS "ProcessCode",
                   pr."Name"                              AS "ProcessName",
                   COALESCE(pr."Factory_id", 1)           AS factory_id,
                   k.material_id                          AS material_id,
                   m."Name"                               AS "ProductName",
                   m."Code"                               AS product_code,
                   COALESCE(o.output_qty, 0)              AS "InspectQty",
                   COALESCE(df.defect_qty, 0)             AS "DefectQty",
                   COALESCE(df.defect_cnt, 0)             AS defect_cnt
              FROM keys k
              LEFT JOIN outp o  ON o.proc_code = k.proc_code AND o.ym = k.ym
                               AND o.material_id IS NOT DISTINCT FROM k.material_id
              LEFT JOIN def  df ON df.proc_code = k.proc_code AND df.ym = k.ym
                               AND df.material_id IS NOT DISTINCT FROM k.material_id
              LEFT JOIN process  pr ON pr."Code" = k.proc_code
              LEFT JOIN material m  ON m.id = k.material_id
             WHERE (CAST(:proc_code AS varchar) IS NULL OR k.proc_code = CAST(:proc_code AS varchar))
             ORDER BY k.ym, COALESCE(pr."Factory_id",1), k.proc_code, m."Name"
            """;

		return nz(this.sqlRunner.getRows(sql, p));
	}

	/**
	 * 불량 상세 (드릴다운). 월 × 공정 × 제품 필터 안의 개별 부적합 건.
	 */
	public List<Map<String, Object>> getDetails(String monthFrom, String monthTo,
												String procCode, Integer materialId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("date_from", monthFrom + "-01");
		p.addValue("date_to", monthTo + "-01");
		p.addValue("proc_code", (procCode == null || procCode.isBlank()) ? null : procCode);
		p.addValue("material_id", materialId);

		String sql = """
            WITH bound AS (
                SELECT CAST(:date_from AS date) AS d_from,
                       (CAST(:date_to AS date) + INTERVAL '1 month')::date AS d_to
            )
            SELECT to_char(d."DefectDate", 'YYYY-MM')          AS "Month",
                   to_char(d."DefectDate", 'yyyy-mm-dd')       AS defect_date,
                   p."Code"                                    AS "ProcessCode",
                   p."Name"                                    AS "ProcessName",
                   d."Material_id"                             AS material_id,
                   m."Name"                                    AS "ProductName",
                   COALESCE(dt."Name", d."DefectTypeEtc")      AS "DefectType",
                   d."DefectQty"                               AS "DefectQty",
                   pe."Name"                                   AS "Worker",
                   d."Description"                             AS "Description",
                   COALESCE(l.lot_numbers, '')                 AS "LotNo"
              FROM defect_regist d
              CROSS JOIN bound bd
              LEFT JOIN process     p  ON p.id  = d."Process_id"
              LEFT JOIN material    m  ON m.id  = d."Material_id"
              LEFT JOIN defect_type dt ON dt.id = d."DefectType_id"
              LEFT JOIN person      pe ON pe.id = d."Actor_id"
              LEFT JOIN LATERAL (
                    SELECT string_agg(rl."LotNumber", ', ' ORDER BY rl.id) AS lot_numbers
                      FROM defect_regist_lot rl
                     WHERE rl."DefectRegist_id" = d.id
              ) l ON true
             WHERE d."DefectDate" >= bd.d_from AND d."DefectDate" < bd.d_to
               AND COALESCE(d."State",'') = 'confirmed'
               AND COALESCE(d._status, 'a') = 'a'
               AND (CAST(:proc_code AS varchar) IS NULL OR p."Code" = CAST(:proc_code AS varchar))
               AND (CAST(:material_id AS integer) IS NULL OR d."Material_id" = CAST(:material_id AS integer))
             ORDER BY d."DefectDate", p."Code", d.id
            """;

		return nz(this.sqlRunner.getRows(sql, p));
	}
}
