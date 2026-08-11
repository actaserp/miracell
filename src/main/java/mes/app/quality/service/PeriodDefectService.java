package mes.app.quality.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import mes.domain.services.SqlRunner;

/**
 * 기간별불량현황 — 일자별 불량률.
 *
 * 기간 내 날짜별로 (생산수량, 불량수량, 불량률)을 한 행으로 집계한다.
 * 제품불량현황(ProcessDefectService)과 데이터 소스는 같고 집계 축만 공정→일자로 바꾼 것.
 *
 *   - 불량   : defect_regist (발생일 DefectDate, State='confirmed')
 *   - 생산량 : mat_produce(생산형) + wash_work(세척) + steril_batch(멸균)
 *   불량률 = 불량수량 / 생산수량 × 100 (전수 육안검사라 검사수량 = 생산수량)
 */
@Service
public class PeriodDefectService {

	@Autowired
	SqlRunner sqlRunner;

	private static List<Map<String, Object>> nz(List<Map<String, Object>> rows) {
		return (rows == null) ? new ArrayList<>() : rows;
	}

	/**
	 * 일자별 불량률 집계.
	 *
	 * @param procCode 공정 코드. null/blank 면 전체 공정
	 */
	public List<Map<String, Object>> getPeriodDefect(String dateFrom, String dateTo, String procCode) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("date_from", dateFrom);
		p.addValue("date_to", dateTo);
		p.addValue("proc_code", (procCode == null || procCode.isBlank()) ? null : procCode);

		String sql = """
            WITH
            -- ① 일자별 불량수량/건수 (발생일 기준)
            def AS (
                SELECT d."DefectDate"                       AS work_date
                     , SUM(COALESCE(d."DefectQty", 0))::decimal AS defect_qty
                     , COUNT(*)                             AS defect_cnt
                  FROM defect_regist d
                  LEFT JOIN process p ON p.id = d."Process_id"
                 WHERE d."DefectDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
                   AND COALESCE(d."State",'') = 'confirmed'
                   AND COALESCE(d._status, 'a') = 'a'
                   AND (CAST(:proc_code AS varchar) IS NULL OR p."Code" = CAST(:proc_code AS varchar))
                 GROUP BY d."DefectDate"
            ),
            -- ② 일자별 생산량 (생산형 + 세척 + 멸균)
            out_src AS (
                SELECT COALESCE(mp."EndTime", mp."StartTime", mp."_created")::date AS work_date
                     , pr."Code" AS proc_code
                     , COALESCE(mp."GoodQty", 0) AS qty
                  FROM mat_produce mp
                  LEFT JOIN work_center wc ON wc.id = mp."WorkCenter_id"
                  LEFT JOIN process     pr ON pr.id = wc."Process_id"
                 WHERE COALESCE(mp."EndTime", mp."StartTime", mp."_created")
                       BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date) + 1
                   AND COALESCE(mp._status, 'a') = 'a'

                UNION ALL
                SELECT w."WashDate", 'bsc01', COALESCE(wi."Qty", 0)
                  FROM wash_work_item wi
                  JOIN wash_work w ON w.id = wi."WashWork_id"
                 WHERE w."WashDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
                   AND COALESCE(w._status,  'a') = 'a'
                   AND COALESCE(wi._status, 'a') = 'a'

                UNION ALL
                SELECT b."SterilDate", 'bsc04'
                     , CASE WHEN COALESCE(b."BiResult",'') = 'fail' THEN 0
                            ELSE COALESCE(si."Qty", 0) END
                  FROM steril_batch_item si
                  JOIN steril_batch b ON b.id = si."SterilBatch_id"
                 WHERE b."SterilDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
                   AND COALESCE(b._status,  'a') = 'a'
                   AND COALESCE(si._status, 'a') = 'a'
            ),
            outp AS (
                SELECT work_date, SUM(qty)::decimal AS output_qty
                  FROM out_src
                 WHERE work_date BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
                   AND (CAST(:proc_code AS varchar) IS NULL OR proc_code = CAST(:proc_code AS varchar))
                 GROUP BY work_date
            ),
            -- ③ 두 소스의 날짜를 합집합으로 모은다 (한쪽만 있는 날도 행이 나오게)
            days AS (
                SELECT work_date FROM def
                UNION
                SELECT work_date FROM outp
            )
            SELECT to_char(days.work_date, 'yyyy-mm-dd')  AS work_date
                 , to_char(days.work_date, 'DD')          AS day_no
                 , COALESCE(outp.output_qty, 0)           AS output_qty
                 , COALESCE(def.defect_qty, 0)            AS defect_qty
                 , COALESCE(def.defect_cnt, 0)            AS defect_cnt
                 , CASE WHEN COALESCE(outp.output_qty, 0) > 0
                        THEN ROUND(COALESCE(def.defect_qty,0) / outp.output_qty * 100, 2)
                        ELSE NULL END                     AS defect_rate
              FROM days
              LEFT JOIN def  ON def.work_date  = days.work_date
              LEFT JOIN outp ON outp.work_date = days.work_date
             ORDER BY days.work_date
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
