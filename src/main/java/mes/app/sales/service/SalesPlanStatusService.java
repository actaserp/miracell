package mes.app.sales.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 영업계획현황 (품목 × 월 피벗)
 *
 * 계획(sales_plan)과 수주(suju)를 품목 + 연월 기준으로 각각 집계한 뒤
 * FULL OUTER JOIN 하여 12개월 컬럼으로 피벗한다.
 *
 * 거래처 축은 사용하지 않는다.
 *  - 영업계획등록 화면에 거래처 입력이 없어 sales_plan_head."Company_id" 는 항상 NULL 이고,
 *    수주에만 거래처가 있으므로 거래처별로 나누면 계획/실적이 서로 대응되지 않는다.
 */
@Slf4j
@Service
public class SalesPlanStatusService {

	@Autowired
	SqlRunner sqlRunner;

	public List<Map<String, Object>> getPlanStatus(String year, String matGrp,
																								 String keyword, String dataDiv, String spjangcd) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("year", year);
		param.addValue("spjangcd", spjangcd);

		// ── 월별 / 연간 값 컬럼 생성 ──────────────────────────────
		StringBuilder monthCols = new StringBuilder();
		for (int m = 1; m <= 12; m++) {
			monthCols.append(",\n          ")
				.append(metricExpr(dataDiv, m))
				.append(" AS mon_").append(m);
		}
		String yearCol = metricExpr(dataDiv, 0) + " AS year_sum";

		String sql = """
        WITH plan_agg AS (
          SELECT
            p."Material_id"      AS mat_id,
            p."PlanMonth"::int   AS mm,
            SUM(p."PlanQty")     AS plan_qty,
            SUM(p."Price")       AS plan_money
          FROM sales_plan p
          INNER JOIN sales_plan_head ph ON ph.id = p."PlanHead_id"
          WHERE ph.spjangcd  = :spjangcd
            AND ph."PlanYear" = :year
            AND p."Material_id" IS NOT NULL
          GROUP BY 1, 2
        ),
        suju_agg AS (
          SELECT
            s."Material_id"                            AS mat_id,
            EXTRACT(MONTH FROM s."JumunDate")::int     AS mm,
            SUM(s."SujuQty")                           AS suju_qty,
            SUM(s."Price")                             AS suju_money
          FROM suju s
          WHERE s.spjangcd = :spjangcd
            AND to_char(s."JumunDate", 'yyyy') = :year
            AND s."Material_id" IS NOT NULL
          GROUP BY 1, 2
        ),
        merged AS (
          SELECT
            COALESCE(p.mat_id, s.mat_id)   AS mat_id,
            COALESCE(p.mm, s.mm)           AS mm,
            COALESCE(p.plan_qty,   0)      AS plan_qty,
            COALESCE(p.plan_money, 0)      AS plan_money,
            COALESCE(s.suju_qty,   0)      AS suju_qty,
            COALESCE(s.suju_money, 0)      AS suju_money
          FROM plan_agg p
          FULL OUTER JOIN suju_agg s
                 ON s.mat_id = p.mat_id
                AND s.mm     = p.mm
        )
        SELECT
          mg."Name"   AS mat_grp_name,
          m."Code"    AS mat_code,
          m."Name"    AS mat_name,
          u."Name"    AS unit_name,
          %s%s
        FROM merged mrg
        INNER JOIN material m  ON m.id  = mrg.mat_id
        LEFT  JOIN mat_grp  mg ON mg.id = m."MaterialGroup_id"
        LEFT  JOIN unit     u  ON u.id  = m."Unit_id"
        WHERE 1 = 1
        """.formatted(yearCol, monthCols.toString());

		if (matGrp != null && !matGrp.isEmpty()) {
			param.addValue("matGrp", Integer.parseInt(matGrp));
			sql += """
            AND m."MaterialGroup_id" = :matGrp
          """;
		}

		if (keyword != null && !keyword.isEmpty()) {
			param.addValue("keyword", "%" + keyword + "%");
			sql += """
            AND (m."Code" ILIKE :keyword OR m."Name" ILIKE :keyword)
          """;
		}

		sql += """
        GROUP BY mg."Name", m."Code", m."Name", u."Name"
        ORDER BY mg."Name", m."Code"
        """;

		return this.sqlRunner.getRows(sql, param);
	}

	/**
	 * 조회데이터 구분별 집계식.
	 * month = 0 이면 연간(월 필터 없음), 1~12 면 해당 월.
	 *
	 * 달성률/차이수량은 월별 값을 단순 합산할 수 없으므로
	 * 연간도 원천값(계획·수주 합계)에서 다시 계산한다.
	 */
	private String metricExpr(String dataDiv, int month) {

		String f = (month == 0) ? "" : " FILTER (WHERE mrg.mm = " + month + ")";

		String planQty   = "COALESCE(SUM(mrg.plan_qty)"   + f + ", 0)";
		String planMoney = "COALESCE(SUM(mrg.plan_money)" + f + ", 0)";
		String sujuQty   = "COALESCE(SUM(mrg.suju_qty)"   + f + ", 0)";
		String sujuMoney = "COALESCE(SUM(mrg.suju_money)" + f + ", 0)";

		if (dataDiv == null) dataDiv = "plan_qty";

		switch (dataDiv) {
			case "plan_money":
				return planMoney;
			case "suju_qty":
				return sujuQty;
			case "suju_money":
				return sujuMoney;
			case "rate":
				return "CASE WHEN " + planQty + " > 0"
								 + " THEN ROUND((" + sujuQty + " / " + planQty + " * 100)::numeric, 1)"
								 + " ELSE 0 END";
			case "diff_qty":
				return "(" + sujuQty + " - " + planQty + ")";
			case "plan_qty":
			default:
				return planQty;
		}
	}
}