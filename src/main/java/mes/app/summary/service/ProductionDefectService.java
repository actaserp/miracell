package mes.app.summary.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import mes.domain.services.SqlRunner;

/**
 * 생산 실적 부적합 현황 — 부적합유형 × 일자 매트릭스.
 *
 * ─────────────────────────────────────────────────────────────────────
 * ★ 왜 갈아엎었나
 *
 *   ① job_res_defect 를 읽고 있었다. 부적합 등록 화면(defect_regist)이
 *      유일한 등록처가 되면서 그 테이블은 비었다 — 화면이 항상 0건이었다.
 *
 *   ② 기간을 jr."ProductionDate" 로 걸었다. 그 값은 작지 지시일이라
 *      7/28 작지로 8/4 에 작업하면 8/4 조회에서 사라진다.
 *      부적합은 d."DefectDate"(발생일)가 진실이다.
 *
 *   ③ 워크센터로 걸렀다. 세척·멸균·M-CELL 검사는 work_center 를 거치지 않고,
 *      defect_regist 자체가 Process_id 를 갖는다 → 축을 공정으로 바꿨다.
 *
 *   ④ 분모(검사수)를 job_res 합계로 냈는데 Parent_id 를 안 걸어
 *      하위 작지가 중복 합산됐다. 라우팅을 타는 공정 작지는 전부 자식이다.
 *
 * ─────────────────────────────────────────────────────────────────────
 * ★ 분모는 「생산량」이다
 *
 *   사업계획서 1.5 의 KPI 산식이 공정 불량률 = 불량수량 / 검사수량 × 100 인데,
 *   근거자료(p.15)를 보면 12월 검사수량 4,573 = 생산수량 4,573 이다.
 *   전수 육안검사라 실제 운영에서 둘이 같다. 별도 검사수량 테이블을 만들지 않고
 *   생산량을 분모로 쓴다 — KPI 화면과 이 화면의 불량률이 어긋나지 않아야 한다.
 */
@Service
public class ProductionDefectService {

	@Autowired
	SqlRunner sqlRunner;

	/** SqlRunner.getRows 는 오류 시 null 을 반환한다 (빈 리스트 아님) */
	private static List<Map<String, Object>> nz(List<Map<String, Object>> rows) {
		return (rows == null) ? new ArrayList<>() : rows;
	}

	/**
	 * 부적합유형 × 발생일 집계.
	 *
	 * @param procCode 공정 코드(bsc01 …). null 이면 전체
	 * @param factoryId 공장. null 이면 전체.
	 *                  defect_regist 에는 공장이 없어 공정(process)의 공장으로 건다.
	 */
	public List<Map<String, Object>> getList(String date_from, String date_to, String procCode, Integer factoryId) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("date_from", date_from);
		p.addValue("date_to", date_to);
		p.addValue("proc_code", (procCode == null || procCode.isBlank()) ? null : procCode);
		p.addValue("factory_id", factoryId);

		String sql = """
            SELECT d."DefectDate"                                   AS defect_date
                 , to_char(d."DefectDate", 'yyyy-mm-dd')            AS date_key
                 , EXTRACT(day FROM d."DefectDate")::int            AS day_no
                 , COALESCE(dt.id, -1)                              AS defect_pk
                 -- 유형 미지정(기타 직접입력)도 버리지 않는다.
                 -- 등록했는데 어느 집계에도 안 나오면 작업자는 등록이 안 된 줄 안다.
                 , COALESCE(dt."Name", NULLIF(d."DefectTypeEtc", ''), '기타') AS defect_type
                 , p."Code"                                         AS proc_code
                 , p."Name"                                         AS proc_name
                 , SUM(COALESCE(d."DefectQty", 0))::decimal         AS defect_qty
                 , COUNT(*)                                         AS defect_cnt
              FROM defect_regist d
              LEFT JOIN defect_type dt ON dt.id = d."DefectType_id"
              LEFT JOIN process     p  ON p.id  = d."Process_id"
             WHERE d."DefectDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
               AND COALESCE(d."State",'') = 'confirmed'
               AND COALESCE(d._status, 'a') = 'a'
               AND (CAST(:proc_code AS varchar) IS NULL OR p."Code" = CAST(:proc_code AS varchar))
               AND (CAST(:factory_id AS integer) IS NULL
                    OR COALESCE(p."Factory_id", 1) = CAST(:factory_id AS integer))
             GROUP BY d."DefectDate", dt.id, dt."Name", d."DefectTypeEtc", p."Code", p."Name"
             ORDER BY defect_type, d."DefectDate"
            """;

		return nz(this.sqlRunner.getRows(sql, p));
	}

	/**
	 * 일자별 생산량 — 불량률의 분모.
	 *
	 * ★ 부적합과 소스가 다르다. 부적합은 defect_regist 한 곳이지만
	 *   생산량은 공정마다 남는 테이블이 다르다. 세척·멸균을 빼면
	 *   그 공정의 불량률 분모가 0 이 되어 「—」로만 나온다.
	 *
	 * ★ Parent_id 로 거르지 않는다. 라우팅을 타는 공정 작지는 전부 자식이라
	 *   걸면 조립·블리스터·융착·포장이 통째로 빠진다.
	 *   대신 2공장은 유닛으로 세야 하므로 여기서는 1공장 생산형만 본다
	 *   (2공장 부적합은 공정 필터로 갈라 본다).
	 */
	public List<Map<String, Object>> getOutputList(String date_from, String date_to, String procCode, Integer factoryId) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("date_from", date_from);
		p.addValue("date_to", date_to);
		p.addValue("proc_code", (procCode == null || procCode.isBlank()) ? null : procCode);
		p.addValue("factory_id", factoryId);

		String sql = """
            WITH out_src AS (
                -- 생산형 (조립·블리스터·융착·포장)
                SELECT COALESCE(mp."EndTime", mp."StartTime", mp."_created")::date AS work_date
                     , pr."Code"                    AS proc_code
                     , COALESCE(mp."GoodQty", 0)    AS qty
                  FROM mat_produce mp
                  LEFT JOIN work_center wc ON wc.id = mp."WorkCenter_id"
                  LEFT JOIN process     pr ON pr.id = wc."Process_id"
                 WHERE COALESCE(mp."EndTime", mp."StartTime", mp."_created")
                       BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date) + 1
                   AND COALESCE(mp._status, 'a') = 'a'

                UNION ALL

                -- 세척
                SELECT w."WashDate", 'bsc01', COALESCE(wi."Qty", 0)
                  FROM wash_work_item wi
                  JOIN wash_work w ON w.id = wi."WashWork_id"
                 WHERE w."WashDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
                   AND COALESCE(w._status,  'a') = 'a'
                   AND COALESCE(wi._status, 'a') = 'a'

                UNION ALL

                -- 멸균 (FAIL 배치는 파기라 산출로 세지 않는다)
                SELECT b."SterilDate", 'bsc04'
                     , CASE WHEN COALESCE(b."BiResult",'') = 'fail' THEN 0
                            ELSE COALESCE(si."Qty", 0) END
                  FROM steril_batch_item si
                  JOIN steril_batch b ON b.id = si."SterilBatch_id"
                 WHERE b."SterilDate" BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
                   AND COALESCE(b._status,  'a') = 'a'
                   AND COALESCE(si._status, 'a') = 'a'
            )
            SELECT to_char(work_date, 'yyyy-mm-dd')     AS date_key
                 , EXTRACT(day FROM work_date)::int     AS day_no
                 , SUM(qty)::decimal                    AS output_qty
              FROM out_src
             WHERE work_date BETWEEN CAST(:date_from AS date) AND CAST(:date_to AS date)
               AND (CAST(:proc_code AS varchar) IS NULL OR proc_code = CAST(:proc_code AS varchar))
               -- 공장은 공정으로 유도한다. out_src 는 소스가 여러 개라 공정코드만 공통이다.
               AND (CAST(:factory_id AS integer) IS NULL
                    OR proc_code IN (SELECT pf."Code" FROM process pf
                                      WHERE COALESCE(pf."Factory_id", 1) = CAST(:factory_id AS integer)))
             GROUP BY work_date
             ORDER BY work_date
            """;

		return nz(this.sqlRunner.getRows(sql, p));
	}

	/** 공정 콤보 — 실적이 없어도 목록에는 있어야 고를 수 있다. factoryId null 이면 전체 */
	public List<Map<String, Object>> getProcessCombo(Integer factoryId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("factory_id", factoryId);
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