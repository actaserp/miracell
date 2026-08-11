package mes.app.quality.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import mes.domain.services.SqlRunner;

/**
 * 제품불량현황 — 공정별 불량률.
 *
 * 기간 내 공정별로 (생산수량, 불량수량, 불량률)을 한 행으로 집계한다.
 *
 * ★ 데이터 소스는 summary.ProductionDefectService 와 동일하다.
 *   - 불량   : defect_regist (발생일 DefectDate, 공정 Process_id, State='confirmed')
 *   - 생산량 : mat_produce(생산형) + wash_work(세척) + steril_batch(멸균)
 *   전수 육안검사라 검사수량 = 생산수량. 불량률 = 불량수량 / 생산수량 × 100.
 *   (KPI 화면과 분모를 맞춘다 — 사업계획서 1.5)
 *
 * 그 화면은 "부적합유형 × 일자" 매트릭스였고, 이 화면은 "공정 × 기간합산" 한 줄이다.
 */
@Service
public class ProcessDefectService {

	@Autowired
	SqlRunner sqlRunner;

	private static List<Map<String, Object>> nz(List<Map<String, Object>> rows) {
		return (rows == null) ? new ArrayList<>() : rows;
	}

	/**
	 * 공정별 불량률 집계.
	 *
	 * 공정 목록을 기준(왼쪽)으로 두고, 불량 합계와 생산량 합계를 각각 붙인다.
	 * 실적이 없는 공정도 목록에는 나오되 생산량 0 → 불량률은 계산 불가(null)로 둔다.
	 *
	 * @param procCode 공정 코드. null/blank 면 전체
	 * @param materialId 제품 id. null 이면 전체. 지정 시 불량수량만 그 제품으로 거른다
	 *                   (생산량/불량률은 공정 전체 기준 — 세척·멸균이 제품 단위가 아니라
	 *                    제품별 분모를 낼 수 없다. 불량수량 위주로 본다.)
	 */
	public List<Map<String, Object>> getProcessDefect(String dateFrom, String dateTo,
													  String procCode, Integer materialId) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("date_from", dateFrom);
		p.addValue("date_to", dateTo);
		p.addValue("proc_code", (procCode == null || procCode.isBlank()) ? null : procCode);
		p.addValue("material_id", materialId);

		String sql = """
            WITH
            -- ① 공정별 불량수량/건수 (발생일 기준)
            def AS (
                SELECT p."Code"                          AS proc_code
                     , SUM(COALESCE(d."DefectQty", 0))::decimal AS defect_qty
                     , COUNT(*)                          AS defect_cnt
                  FROM defect_regist d
                  LEFT JOIN process p ON p.id = d."Process_id"
                 WHERE d."DefectDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
                   AND COALESCE(d."State",'') = 'confirmed'
                   AND COALESCE(d._status, 'a') = 'a'
                   AND (CAST(:material_id AS integer) IS NULL OR d."Material_id" = CAST(:material_id AS integer))
                 GROUP BY p."Code"
            ),
            -- ② 공정별 생산량 (생산형 + 세척 + 멸균)
            out_src AS (
                SELECT pr."Code" AS proc_code
                     , COALESCE(mp."GoodQty", 0) AS qty
                  FROM mat_produce mp
                  LEFT JOIN work_center wc ON wc.id = mp."WorkCenter_id"
                  LEFT JOIN process     pr ON pr.id = wc."Process_id"
                 WHERE COALESCE(mp."EndTime", mp."StartTime", mp."_created")
                       BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date) + 1
                   AND COALESCE(mp._status, 'a') = 'a'

                UNION ALL
                SELECT 'bsc01', COALESCE(wi."Qty", 0)
                  FROM wash_work_item wi
                  JOIN wash_work w ON w.id = wi."WashWork_id"
                 WHERE w."WashDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
                   AND COALESCE(w._status,  'a') = 'a'
                   AND COALESCE(wi._status, 'a') = 'a'

                UNION ALL
                SELECT 'bsc04'
                     , CASE WHEN COALESCE(b."BiResult",'') = 'fail' THEN 0
                            ELSE COALESCE(si."Qty", 0) END
                  FROM steril_batch_item si
                  JOIN steril_batch b ON b.id = si."SterilBatch_id"
                 WHERE b."SterilDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
                   AND COALESCE(b._status,  'a') = 'a'
                   AND COALESCE(si._status, 'a') = 'a'
            ),
            outp AS (
                SELECT proc_code, SUM(qty)::decimal AS output_qty
                  FROM out_src
                 GROUP BY proc_code
            )
            SELECT p."Code"                         AS proc_code
                 , p."Name"                         AS proc_name
                 , COALESCE(p."Factory_id", 1)      AS factory_id
                 , COALESCE(outp.output_qty, 0)     AS output_qty
                 , COALESCE(def.defect_qty, 0)      AS defect_qty
                 , COALESCE(def.defect_cnt, 0)      AS defect_cnt
                 , CASE WHEN COALESCE(outp.output_qty, 0) > 0
                        THEN ROUND(COALESCE(def.defect_qty,0) / outp.output_qty * 100, 2)
                        ELSE NULL END               AS defect_rate
              FROM process p
              LEFT JOIN def  ON def.proc_code  = p."Code"
              LEFT JOIN outp ON outp.proc_code = p."Code"
             WHERE COALESCE(p._status, 'a') = 'a'
               AND (CAST(:proc_code AS varchar) IS NULL OR p."Code" = CAST(:proc_code AS varchar))
               -- 제품 필터가 있으면 그 제품 불량이 난 공정만,
               -- 없으면 실적(생산 or 불량)이 하나라도 있는 공정을 보여준다
               AND (
                     (CAST(:material_id AS integer) IS NOT NULL AND COALESCE(def.defect_qty,0) > 0)
                  OR (CAST(:material_id AS integer) IS NULL
                      AND (COALESCE(outp.output_qty,0) > 0 OR COALESCE(def.defect_qty,0) > 0))
                   )
             ORDER BY COALESCE(p."Factory_id", 1), p."Code"
            """;

		return nz(this.sqlRunner.getRows(sql, p));
	}

	/** 공정 콤보 */
	public List<Map<String, Object>> getProcessCombo() {
		return nz(this.sqlRunner.getRows("""
            SELECT p."Code" AS code, p."Name" AS name, COALESCE(p."Factory_id", 1) AS factory_id
              FROM process p
             WHERE COALESCE(p._status, 'a') = 'a'
             ORDER BY COALESCE(p."Factory_id", 1), p."Code"
            """, new MapSqlParameterSource()));
	}
}
