package mes.app.summary.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import mes.domain.services.SqlRunner;

/**
 * 시간당 생산량 (UPH, Units Per Hour).
 *
 * UPH = 생산수량 / 작업시간.  (사업계획서 기준)
 *
 * ★ 공정별·월별로 집계한다. (품목별은 불가 — 작업시간이 설비/공정 단위라
 *    한 공정에 여러 품목이 섞이면 품목별로 시간을 못 나눈다.)
 *
 * ★ 데이터 소스
 *   - 생산수량 : mat_produce(생산형) + wash_work_item(세척 bsc01) + steril_batch_item(멸균 bsc04)
 *               (공정불량률·제품불량현황과 동일)
 *   - 작업시간 : equ_run(설비 가동이력)을 공정별로 union.
 *               경로 equ_run → equ(WorkCenter_id) → work_center(Process_id) → process.
 *               같은 공정에 설비/작업자가 여럿 할당되어 구간이 겹치므로
 *               단순 합이 아니라 구간 합집합(union)으로 센다.
 *               (EquipmentRunChartService 의 검증된 병합 로직과 동일한 패턴)
 *               열린 구간(EndDate NULL)·역전 구간(EndDate<=StartDate)은 제외.
 *
 * 화면은 { proc_code, proc_name, qty_1..12, hour_1..12 } 한 공정 = 한 행을 받아,
 * gubn 3행(생산수량/작업시간/시간당생산량)으로 펼치고 '전체' 요약을 얹는다.
 *
 * ★ qty(생산)와 hour(가동)는 서로 없는 달이 있을 수 있으므로
 *   (생산만 있고 가동이력 없는 공정/월, 그 반대) 공정별로 각각 12개월 피벗한 뒤
 *   공정 키로 합친다. 한쪽 기준으로 조인하면 다른 쪽 달이 누락된다.
 */
@Service
public class UphService {

	@Autowired
	SqlRunner sqlRunner;

	private static List<Map<String, Object>> nz(List<Map<String, Object>> rows) {
		return (rows == null) ? new ArrayList<>() : rows;
	}

	/** 공정별·월별 생산수량 / 작업시간(union). 한 공정 = 한 행. */
	public List<Map<String, Object>> getUphByProcess(int year) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("date_from", year + "-01-01");
		p.addValue("date_to", (year + 1) + "-01-01");

		StringBuilder sb = new StringBuilder();
		sb.append("""
            WITH
            -- ① 공정별·월별 생산수량
            out_src AS (
                SELECT pr."Code" AS proc_code,
                       EXTRACT(MONTH FROM COALESCE(mp."EndTime", mp."StartTime", mp."_created"))::int AS mon,
                       COALESCE(mp."GoodQty", 0)::numeric AS qty
                  FROM mat_produce mp
                  LEFT JOIN work_center wc ON wc.id = mp."WorkCenter_id"
                  LEFT JOIN process     pr ON pr.id = wc."Process_id"
                 WHERE COALESCE(mp."EndTime", mp."StartTime", mp."_created") >= CAST(:date_from AS date)
                   AND COALESCE(mp."EndTime", mp."StartTime", mp."_created") <  CAST(:date_to AS date)
                   AND COALESCE(mp._status, 'a') = 'a'
                UNION ALL
                SELECT 'bsc01',
                       EXTRACT(MONTH FROM w."WashDate")::int,
                       COALESCE(wi."Qty", 0)::numeric
                  FROM wash_work_item wi
                  JOIN wash_work w ON w.id = wi."WashWork_id"
                 WHERE w."WashDate" >= CAST(:date_from AS date) AND w."WashDate" < CAST(:date_to AS date)
                   AND COALESCE(w._status,'a')='a' AND COALESCE(wi._status,'a')='a'
                UNION ALL
                SELECT 'bsc04',
                       EXTRACT(MONTH FROM b."SterilDate")::int,
                       CASE WHEN COALESCE(b."BiResult",'')='fail' THEN 0 ELSE COALESCE(si."Qty",0) END::numeric
                  FROM steril_batch_item si
                  JOIN steril_batch b ON b.id = si."SterilBatch_id"
                 WHERE b."SterilDate" >= CAST(:date_from AS date) AND b."SterilDate" < CAST(:date_to AS date)
                   AND COALESCE(b._status,'a')='a' AND COALESCE(si._status,'a')='a'
            ),
            qty_m AS (
                SELECT proc_code, mon, SUM(qty)::numeric AS qty
                  FROM out_src
                 GROUP BY proc_code, mon
            ),
            -- ② 공정별·월별 작업시간 (equ_run union)
            run_src AS (
                SELECT p2."Code" AS proc_code,
                       EXTRACT(MONTH FROM er."StartDate")::int AS mon,
                       er."StartDate" AS s,
                       er."EndDate"   AS e
                  FROM equ_run er
                  JOIN equ e2         ON e2.id = er."Equipment_id"
                  JOIN work_center wc ON wc.id = e2."WorkCenter_id"
                  JOIN process p2     ON p2.id = wc."Process_id"
                 WHERE er."EndDate" IS NOT NULL
                   AND er."EndDate" > er."StartDate"
                   AND er."StartDate" >= CAST(:date_from AS date)
                   AND er."StartDate" <  CAST(:date_to AS date)
            ),
            run_ord AS (
                SELECT proc_code, mon, s, e,
                       MAX(e) OVER (PARTITION BY proc_code, mon ORDER BY s, e
                                    ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS prev_max
                  FROM run_src
            ),
            run_grp AS (
                SELECT proc_code, mon, s, e,
                       SUM(CASE WHEN prev_max IS NULL OR s > prev_max THEN 1 ELSE 0 END)
                       OVER (PARTITION BY proc_code, mon ORDER BY s, e ROWS UNBOUNDED PRECEDING) AS g
                  FROM run_ord
            ),
            run_merged AS (
                SELECT proc_code, mon, g, MIN(s) AS s, MAX(e) AS e
                  FROM run_grp
                 GROUP BY proc_code, mon, g
            ),
            hour_m AS (
                SELECT proc_code, mon,
                       ROUND((SUM(EXTRACT(epoch FROM (e - s))) / 3600.0)::numeric, 2) AS hour
                  FROM run_merged
                 GROUP BY proc_code, mon
            ),
            -- ③ 공정별 12개월 피벗 (qty / hour 각각 독립)
            qty_pivot AS (
                SELECT proc_code
            """);
		for (int i = 1; i <= 12; i++) {
			sb.append(", COALESCE(SUM(CASE WHEN mon = ").append(i).append(" THEN qty END), 0) AS qty_").append(i).append("\n");
		}
		sb.append("""
                  FROM qty_m GROUP BY proc_code
            ),
            hour_pivot AS (
                SELECT proc_code
            """);
		for (int i = 1; i <= 12; i++) {
			sb.append(", COALESCE(SUM(CASE WHEN mon = ").append(i).append(" THEN hour END), 0) AS hour_").append(i).append("\n");
		}
		sb.append("""
                  FROM hour_m GROUP BY proc_code
            ),
            keys AS (
                SELECT proc_code FROM qty_pivot
                UNION
                SELECT proc_code FROM hour_pivot
            )
            SELECT k.proc_code                    AS proc_code,
                   pr."Name"                      AS proc_name,
                   COALESCE(pr."Factory_id", 1)   AS factory_id
            """);
		for (int i = 1; i <= 12; i++) {
			sb.append(", COALESCE(q.qty_").append(i).append(", 0) AS qty_").append(i).append("\n");
		}
		for (int i = 1; i <= 12; i++) {
			sb.append(", COALESCE(h.hour_").append(i).append(", 0) AS hour_").append(i).append("\n");
		}
		sb.append("""
              FROM keys k
              LEFT JOIN process pr    ON pr."Code" = k.proc_code
              LEFT JOIN qty_pivot  q  ON q.proc_code = k.proc_code
              LEFT JOIN hour_pivot h  ON h.proc_code = k.proc_code
             ORDER BY COALESCE(pr."Factory_id",1), k.proc_code
            """);

		return nz(this.sqlRunner.getRows(sb.toString(), p));
	}
}
