package mes.app.sales.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.entity.User;
import mes.domain.services.SqlRunner;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class SalesPlanService {

	@Autowired
	SqlRunner sqlRunner;

	// ────────────────────────────────────────────────────────────
	// 조회
	// ────────────────────────────────────────────────────────────

	/**
	 * 영업계획 목록.
	 * 계획 대비 수주 실적(수주수량/달성률)을 함께 계산해서 내려준다.
	 * 조인 기준 = 계획연월 + 품목 (+거래처 지정 시 거래처)
	 */
	/**
	 * @param factoryId 공장 필터. 빈 값/null 이면 전체 공장.
	 *                  영업계획에는 공장이 없어 품목(material)의 공장으로 건다.
	 */
	public List<Map<String, Object>> getPlanList(String year, String month, String matGrp,
												 String keyword, String factoryId, String spjangcd) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("year", year);
		dicParam.addValue("spjangcd", spjangcd);

		String sql = """
        WITH suju_actual AS (
          SELECT
            to_char(s."JumunDate", 'yyyy')  AS plan_year,
            to_char(s."JumunDate", 'mm')    AS plan_month,
            s."Material_id",
            s."Company_id",
            SUM(s."SujuQty")                AS suju_qty,
            SUM(s."Price")                  AS suju_price
          FROM suju s
          WHERE s.spjangcd = :spjangcd
            AND to_char(s."JumunDate", 'yyyy') = :year
          GROUP BY 1, 2, 3, 4
        )
        SELECT
          p.id,
          ph.id                                 AS head_id,
          ph."PlanYear",
          ph."PlanMonth",
          ph."Company_id",
          c."Name"                              AS "CompanyName",
          mg."Name"                             AS mat_grp_name,
          m."Code"                              AS mat_code,
          COALESCE(m."Name", p."Material_Name") AS mat_name,
          p."Standard"                          AS standard,
          u."Name"                              AS unit_name,
          p."PlanQty",
          p."UnitPrice",
          p."Price"                             AS "PlanAmount",
          p."Vat",
          p."TotalAmount",
          COALESCE(sa.suju_qty, 0)              AS "SujuQty",
          COALESCE(sa.suju_price, 0)            AS "SujuPrice",
          CASE WHEN p."PlanQty" > 0
               THEN ROUND((COALESCE(sa.suju_qty, 0) / p."PlanQty" * 100)::numeric, 1)
               ELSE 0
          END                                   AS "AchieveRate",
          p."Description",
          to_char(ph."_created", 'yyyy-mm-dd')  AS create_date
        FROM sales_plan p
        INNER JOIN sales_plan_head ph ON ph.id = p."PlanHead_id"
        LEFT  JOIN material m  ON m.id  = p."Material_id"
        LEFT  JOIN mat_grp  mg ON mg.id = m."MaterialGroup_id"
        LEFT  JOIN unit     u  ON u.id  = m."Unit_id"
        LEFT  JOIN company  c  ON c.id  = ph."Company_id"
        LEFT  JOIN suju_actual sa
               ON sa.plan_year     = ph."PlanYear"
              AND sa.plan_month    = ph."PlanMonth"
              AND sa."Material_id" = p."Material_id"
              AND (ph."Company_id" IS NULL OR sa."Company_id" = ph."Company_id")
        WHERE 1 = 1
          AND ph.spjangcd = :spjangcd
          AND ph."PlanYear" = :year
        """;

		if (month != null && !month.isEmpty()) {
			dicParam.addValue("month", month);
			sql += """
          AND ph."PlanMonth" = :month
          """;
		}

		if (factoryId != null && !factoryId.isEmpty()) {
			dicParam.addValue("factoryId", Integer.parseInt(factoryId));
			sql += """
          AND m."Factory_id" = :factoryId
          """;
		}

		if (matGrp != null && !matGrp.isEmpty()) {
			dicParam.addValue("matGrp", Integer.parseInt(matGrp));
			sql += """
          AND m."MaterialGroup_id" = :matGrp
          """;
		}

		if (keyword != null && !keyword.isEmpty()) {
			dicParam.addValue("keyword", "%" + keyword + "%");
			sql += """
          AND (
              m."Code" ILIKE :keyword
              OR m."Name" ILIKE :keyword
              OR p."Material_Name" ILIKE :keyword
          )
          """;
		}

		sql += """
        ORDER BY ph."PlanYear", ph."PlanMonth", c."Name", m."Code", p."Seq", p.id
        """;

		return this.sqlRunner.getRows(sql, dicParam);
	}

	/**
	 * 영업계획 상세 (수정 팝업).
	 * getSujuDetail 과 동일하게 head + 목록(planList/sujuList) 구조로 반환.
	 */
	public Map<String, Object> getPlanDetail(int id) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("id", id);

		String headSql = """
        SELECT
          ph.id,
          ph."PlanYear",
          ph."PlanMonth",
          ph."PlanNumber",
          ph."Company_id",
          COALESCE(c."Name", ph."CompanyName") AS "CompanyName",
          ph."TotalAmount" AS "totalAmountSum",
          ph."Description",
          ph.spjangcd
        FROM sales_plan_head ph
        LEFT JOIN company c ON c.id = ph."Company_id"
        WHERE ph.id = :id
        """;

		String detailSql = """
        WITH plan_head AS (
          SELECT "PlanYear", "PlanMonth", "Company_id", spjangcd
          FROM sales_plan_head WHERE id = :id
        ),
        suju_actual AS (
          SELECT
            s."Material_id",
            SUM(s."SujuQty") AS suju_qty
          FROM suju s, plan_head ph
          WHERE to_char(s."JumunDate", 'yyyy') = ph."PlanYear"
            AND to_char(s."JumunDate", 'mm')   = ph."PlanMonth"
            AND (ph."Company_id" IS NULL OR s."Company_id" = ph."Company_id")
            AND s.spjangcd = ph.spjangcd
          GROUP BY s."Material_id"
        )
        SELECT
          p.id                                  AS plan_id,
          p."PlanHead_id",
          p."Material_id",
          m."Code"                              AS product_code,
          COALESCE(m."Name", p."Material_Name") AS "txtProductName",
          mg."Name"                             AS "MaterialGroupName",
          mg.id                                 AS "MaterialGroup_id",
          u."Name"                              AS unit,
          p."Standard"                          AS standard,
          p."PlanQty"                           AS "planQty",
          p."UnitPrice"                         AS "unitPrice",
          p."Price"                             AS "planAmount",
          p."Vat"                               AS "VatAmount",
          p."TotalAmount"                       AS "totalAmount",
          p."InVatYN"                           AS invatyn,
          p."Description"                       AS description,
          COALESCE(sa.suju_qty, 0)              AS "SujuQty"
        FROM sales_plan p
        LEFT JOIN material m  ON m.id  = p."Material_id"
        LEFT JOIN mat_grp  mg ON mg.id = m."MaterialGroup_id"
        LEFT JOIN unit     u  ON u.id  = m."Unit_id"
        LEFT JOIN suju_actual sa ON sa."Material_id" = p."Material_id"
        WHERE p."PlanHead_id" = :id
        ORDER BY p."Seq", p.id
        """;

		Map<String, Object> planHead = this.sqlRunner.getRow(headSql, paramMap);
		if (planHead == null) return null;

		List<Map<String, Object>> planList = this.sqlRunner.getRows(detailSql, paramMap);

		// 화면이 BindDataSujuForm(sujuList 규칙)을 그대로 쓰므로 두 키 모두 제공
		planHead.put("planList", planList);
		planHead.put("sujuList", planList);

		// 수정 잠금 판단용 — 상세 중 하나라도 수주가 있으면 수정 불가
		double sujuSum = planList.stream()
				.mapToDouble(r -> r.get("SujuQty") == null ? 0d : ((Number) r.get("SujuQty")).doubleValue())
				.sum();
		planHead.put("SujuQty", sujuSum);

		return planHead;
	}

	/** 거래처 + 품목 단가 (수주등록과 동일한 mat_comp_uprice 마스터) */
	public List<Map<String, Object>> getPriceByMatAndComp(int matPk, Integer companyId, String baseDate) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("mat_pk", matPk);
		dicParam.addValue("company_id", companyId);
		dicParam.addValue("baseDate", baseDate);

		String sql = """
        SELECT
          mcu.id,
          mcu."Company_id",
          c."Name" AS "CompanyName",
          mcu."UnitPrice",
          mcu."FormerUnitPrice",
          mcu."ApplyStartDate"::date,
          mcu."ApplyEndDate"::date
        FROM mat_comp_uprice mcu
        INNER JOIN company c ON c.id = mcu."Company_id"
        WHERE 1 = 1
          AND mcu."Material_id" = :mat_pk
          AND mcu."Company_id"  = :company_id
          AND to_date(:baseDate, 'YYYY-MM-DD')
              BETWEEN mcu."ApplyStartDate"::date AND mcu."ApplyEndDate"::date
          AND mcu."Type" = '02'
        ORDER BY mcu."ApplyStartDate" DESC
        """;

		return this.sqlRunner.getRows(sql, dicParam);
	}

	// ────────────────────────────────────────────────────────────
	// 검증
	// ────────────────────────────────────────────────────────────

	/** 동일 연월 + 거래처 계획 중복 건수 (수정 시 본인 제외) */
	public int countDuplicatePlan(Integer headId, String planYear, String planMonth,
								  Integer companyId, String spjangcd) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("headId", headId);
		param.addValue("planYear", planYear);
		param.addValue("planMonth", planMonth);
		param.addValue("companyId", companyId);
		param.addValue("spjangcd", spjangcd);

		String sql = """
    SELECT COUNT(*) AS cnt
    FROM sales_plan_head ph
    WHERE ph."PlanYear"  = :planYear
      AND ph."PlanMonth" = :planMonth
      AND ph.spjangcd    = :spjangcd
      AND ( (CAST(:companyId AS integer) IS NULL AND ph."Company_id" IS NULL)
            OR ph."Company_id" = CAST(:companyId AS integer) )
      AND (CAST(:headId AS integer) IS NULL OR ph.id <> CAST(:headId AS integer))
    """;

		Map<String, Object> row = this.sqlRunner.getRow(sql, param);
		return row == null ? 0 : ((Number) row.get("cnt")).intValue();
	}

	/** 해당 계획(연월 + 거래처 + 품목)에 대응하는 수주 건수. 0보다 크면 수정·삭제 차단 */
	public int countSujuByPlanHead(Integer headId) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("headId", headId);

		String sql = """
        SELECT COUNT(*) AS cnt
        FROM suju s
        INNER JOIN sales_plan_head ph ON ph.id = :headId
        INNER JOIN sales_plan p
                ON p."PlanHead_id" = ph.id
               AND p."Material_id" = s."Material_id"
        WHERE to_char(s."JumunDate", 'yyyy') = ph."PlanYear"
          AND to_char(s."JumunDate", 'mm')   = ph."PlanMonth"
          AND (ph."Company_id" IS NULL OR s."Company_id" = ph."Company_id")
          AND s.spjangcd = ph.spjangcd
        """;

		Map<String, Object> row = this.sqlRunner.getRow(sql, param);
		return row == null ? 0 : ((Number) row.get("cnt")).intValue();
	}

	// ────────────────────────────────────────────────────────────
	// 저장
	// ────────────────────────────────────────────────────────────

	/**
	 * 헤더 upsert + 상세 전량 재적재(delete-insert).
	 * 상세는 월 단위 수십 건이고 참조하는 하위 전표가 없어 안전하다.
	 */
	public Integer savePlan(Integer headId, String planYear, String planMonth, Integer companyId,
							String companyName, String description, String spjangcd,
							List<Map<String, Object>> items, User user) {

		// ── 1) 헤더 ──────────────────────────────────────────────
		MapSqlParameterSource headParam = new MapSqlParameterSource();
		headParam.addValue("planYear", planYear);
		headParam.addValue("planMonth", planMonth);
		headParam.addValue("companyId", companyId);
		headParam.addValue("companyName", companyName);
		headParam.addValue("description", description);
		headParam.addValue("spjangcd", spjangcd);
		headParam.addValue("userId", user.getId());

		if (headId == null) {
			String insertHead = """
          INSERT INTO sales_plan_head
            (_status, _created, _creater_id,
             "PlanYear", "PlanMonth", "Company_id", "CompanyName",
             "TotalAmount", "Description", spjangcd)
          VALUES
            ('manual', now(), :userId,
             :planYear, :planMonth, :companyId, :companyName,
             0, :description, :spjangcd)
          RETURNING id
          """;
			headId = this.sqlRunner.queryForObject(insertHead, headParam, (rs, rowNum) -> rs.getInt(1));

		} else {
			headParam.addValue("id", headId);
			String updateHead = """
          UPDATE sales_plan_head
             SET "PlanYear"    = :planYear,
                 "PlanMonth"   = :planMonth,
                 "Company_id"  = :companyId,
                 "CompanyName" = :companyName,
                 "Description" = :description,
                 _modified     = now(),
                 _modifier_id  = :userId
           WHERE id = :id
          """;
			this.sqlRunner.execute(updateHead, headParam);

			// 기존 상세 하드 삭제 (suju 와 동일 정책)
			MapSqlParameterSource delParam = new MapSqlParameterSource();
			delParam.addValue("headId", headId);
			this.sqlRunner.execute("""
          DELETE FROM sales_plan WHERE "PlanHead_id" = :headId
          """, delParam);
		}

		// ── 2) 상세 ──────────────────────────────────────────────
		String insertItem = """
        INSERT INTO sales_plan
          (_status, _created, _creater_id,
           "PlanHead_id", "Seq", "PlanYear", "PlanMonth",
           "Material_id", "Material_Name", "Standard",
           "PlanQty", "UnitPrice", "Price", "Vat", "TotalAmount", "InVatYN",
           "Company_id", "Description", spjangcd)
        VALUES
          ('manual', now(), :userId,
           :headId, :seq, :planYear, :planMonth,
           :materialId, :materialName, :standard,
           :planQty, :unitPrice, :price, :vat, :totalAmount, :invatyn,
           :companyId, :description, :spjangcd)
        """;

		double totalSum = 0d;
		int seq = 1;

		for (Map<String, Object> item : items) {

			double qty   = dnum(item.get("planQty"));
			double uprice = dnum(item.get("unitPrice"));

			// 금액은 화면 값을 믿지 않고 서버에서 재계산 (화면과 동일 규칙)
			String invatyn = str(item.get("VatIncluded")).isEmpty() ? "N" : str(item.get("VatIncluded"));
			double lineTotal = Math.floor(qty * uprice);

			double price;   // 공급가액
			double vat;     // 부가세

			if ("Y".equals(invatyn)) {
				price = Math.round(lineTotal / 1.1);
				vat   = lineTotal - price;
			} else {
				price = lineTotal;
				vat   = Math.floor(price * 0.1);
			}
			double totalAmount = price + vat;
			totalSum += totalAmount;

			MapSqlParameterSource itemParam = new MapSqlParameterSource();
			itemParam.addValue("headId", headId);
			itemParam.addValue("seq", seq++);
			itemParam.addValue("planYear", planYear);
			itemParam.addValue("planMonth", planMonth);
			itemParam.addValue("materialId", toIntegerOrNull(item.get("Material_id")));
			itemParam.addValue("materialName", str(item.get("txtProductName")));
			itemParam.addValue("standard", str(item.get("standard")));
			itemParam.addValue("planQty", qty);
			itemParam.addValue("unitPrice", uprice);
			itemParam.addValue("price", price);
			itemParam.addValue("vat", vat);
			itemParam.addValue("totalAmount", totalAmount);
			itemParam.addValue("invatyn", invatyn);
			itemParam.addValue("companyId", companyId);
			itemParam.addValue("description", str(item.get("description")));
			itemParam.addValue("userId", user.getId());
			itemParam.addValue("spjangcd", spjangcd);

			this.sqlRunner.execute(insertItem, itemParam);
		}

		// ── 3) 헤더 합계 갱신 ────────────────────────────────────
		MapSqlParameterSource sumParam = new MapSqlParameterSource();
		sumParam.addValue("headId", headId);
		sumParam.addValue("totalAmount", totalSum);
		sumParam.addValue("userId", user.getId());

		this.sqlRunner.execute("""
        UPDATE sales_plan_head
           SET "TotalAmount" = :totalAmount,
               _modified     = now(),
               _modifier_id  = :userId
         WHERE id = :headId
        """, sumParam);

		return headId;
	}

	// ────────────────────────────────────────────────────────────
	// 삭제 (하드 삭제 — suju 와 동일 정책)
	// ────────────────────────────────────────────────────────────

	public void deletePlan(Integer headId) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("headId", headId);

		this.sqlRunner.execute("""
        DELETE FROM sales_plan WHERE "PlanHead_id" = :headId
        """, param);

		this.sqlRunner.execute("""
        DELETE FROM sales_plan_head WHERE id = :headId
        """, param);
	}

	// ────────────────────────────────────────────────────────────
	// 유틸 (SujuController 와 동일 규칙)
	// ────────────────────────────────────────────────────────────

	private static String str(Object o) {
		return (o == null) ? "" : o.toString().trim();
	}

	private static double dnum(Object o) {
		if (o == null) return 0d;
		String v = o.toString().replace(",", "").trim();
		if (v.isEmpty() || v.equals("-") || v.equals(".")) return 0d;
		try {
			return Double.parseDouble(v);
		} catch (Exception e) {
			return 0d;
		}
	}

	private static Integer toIntegerOrNull(Object v) {
		if (v == null) return null;
		if (v instanceof Number) return ((Number) v).intValue();

		String s = v.toString().trim().replace(",", "");
		if (s.isEmpty() || s.equals("-") || s.equals(".")) return null;

		try {
			return Integer.valueOf(s);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}