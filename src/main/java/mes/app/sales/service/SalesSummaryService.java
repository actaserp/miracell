package mes.app.sales.service;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;

@Slf4j
@Service
public class SalesSummaryService {

	@Autowired
	SqlRunner sqlRunner;

	/**
	 * 영업현황 집계.
	 * 판매계획 테이블이 없으므로 수주 / 출하실적 / 수주잔량 / 출하율만 산출한다.
	 * 월 구분은 shipment 에 일자 컬럼이 없어 수주일(suju_head.JumunDate) 기준.
	 */
	public List<Map<String, Object>> getSummaryList(String start, String end, String groupKind, String amountKind,
																									String company, String product, String spjangcd) {

		if (groupKind == null || groupKind.isEmpty()) groupKind = "company";
		if (spjangcd == null || spjangcd.isEmpty()) spjangcd = "ZZ";

		YearMonth ymStart = YearMonth.parse(start);
		YearMonth ymEnd = YearMonth.parse(end);
		List<String> months = monthRange(ymStart, ymEnd);

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("date_from", ymStart.atDay(1).toString());
		paramMap.addValue("date_to_excl", ymEnd.plusMonths(1).atDay(1).toString());
		paramMap.addValue("spjangcd", spjangcd);

		boolean isQty = "qty".equals(amountKind);

		// 수주 값 / 출하 값 (수량 or 금액)
		String orderVal = isQty
												? " coalesce(s.\"SujuQty\", 0) "
												: " coalesce(s.\"Price\", 0) + coalesce(s.\"Vat\", 0) ";
		String actualVal = isQty
												 ? " coalesce(shp.shipped_qty, 0) "
												 : " coalesce(shp.shipped_qty, 0) * coalesce(s.\"UnitPrice\", 0) ";

		String sql = " with base as ( "
									 + "   select s.id                                as suju_id "
									 + "        , sh.\"Company_id\"                   as company_id "
									 + "        , c.\"Name\"                          as company_name "
									 + "        , s.\"Material_id\"                   as material_id "
									 + "        , m.\"Code\"                          as mat_code "
									 + "        , coalesce(m.\"Name\", s.\"Material_Name\") as mat_name "
									 + "        , s.\"Standard\"                      as standard "
									 + "        , mg.id                               as mat_grp_id "
									 + "        , mg.\"Name\"                         as mat_grp_name "
									 + "        , to_char(sh.\"JumunDate\", 'YYYY-MM') as data_month "
									 + "        , " + orderVal + " as order_val "
									 + "        , " + actualVal + " as actual_val "
									 + "     from suju s "
									 + "     join suju_head sh on sh.id = s.\"SujuHead_id\" "
									 + "     left join company  c  on c.id  = sh.\"Company_id\" "
									 + "     left join material m  on m.id  = s.\"Material_id\" "
									 + "     left join mat_grp  mg on mg.id = m.\"MaterialGroup_id\" "
									 + "     left join ( select \"SourceDataPk\", sum(\"Qty\") as shipped_qty "
									 + "                   from shipment group by \"SourceDataPk\" ) shp "
									 + "            on shp.\"SourceDataPk\" = s.id "
									 + "    where sh.spjangcd = :spjangcd "
									 + "      and sh.\"JumunDate\" >= cast(:date_from as date) "
									 + "      and sh.\"JumunDate\" <  cast(:date_to_excl as date) ";

		if (company != null && !company.isEmpty()) {
			paramMap.addValue("company", "%" + company + "%");
			sql += " and ( c.\"Name\" ilike :company or cast(c.id as text) ilike :company ) ";
		}
		if (product != null && !product.isEmpty()) {
			paramMap.addValue("product", "%" + product + "%");
			sql += " and ( m.\"Code\" ilike :product or m.\"Name\" ilike :product ) ";
		}

		sql += " ) ";

		// 집계기준별 키 컬럼
		String selectCols;
		String groupBy;
		String orderBy;

		if ("product".equals(groupKind)) {
			selectCols = " b.material_id as \"GroupKey\", b.mat_code as product_code, b.mat_name as product_name, b.standard as \"Standard\" ";
			groupBy = " b.material_id, b.mat_code, b.mat_name, b.standard ";
			orderBy = " b.mat_code, b.mat_name ";
		} else if ("group".equals(groupKind)) {
			selectCols = " b.mat_grp_id as \"GroupKey\", b.mat_grp_name as \"MaterialGroupName\" ";
			groupBy = " b.mat_grp_id, b.mat_grp_name ";
			orderBy = " b.mat_grp_name ";
		} else if ("month".equals(groupKind)) {
			selectCols = " b.data_month as \"GroupKey\", b.data_month as \"Month\" ";
			groupBy = " b.data_month ";
			orderBy = " b.data_month ";
		} else { // company
			selectCols = " b.company_id as \"GroupKey\", b.company_name as \"CompanyName\" ";
			groupBy = " b.company_id, b.company_name ";
			orderBy = " b.company_name ";
		}

		sql += " select " + selectCols
						 + "      , sum(b.order_val)  as \"OrderValue\" "
						 + "      , sum(b.actual_val) as \"ActualValue\" "
						 + "      , greatest(sum(b.order_val) - sum(b.actual_val), 0) as \"Backlog\" "
						 + "      , case when sum(b.order_val) > 0 "
						 + "             then round(cast(sum(b.actual_val) / sum(b.order_val) * 100 as numeric), 1) "
						 + "             else 0 end as \"ShipRate\" ";

		// 월별 피벗 (월별 집계는 첫 컬럼이 이미 월이므로 생략)
		if (!"month".equals(groupKind)) {
			for (String ym : months) {
				sql += " , sum(case when b.data_month = '" + ym + "' then b.order_val else 0 end) as m_"
								 + ym.replace("-", "") + " ";
			}
		}

		sql += "   from base b "
						 + "  group by " + groupBy
						 + "  order by " + orderBy;

		// log.info("영업현황집계 SQL: {}", sql);
		return this.sqlRunner.getRows(sql, paramMap);
	}

	/** 드릴다운 - 선택 그룹에 속한 수주 라인 내역 */
	public List<Map<String, Object>> getDrillList(String start, String end, String groupKind, String groupKey,
																								String company, String product, String spjangcd) {

		if (groupKind == null || groupKind.isEmpty()) groupKind = "company";
		if (spjangcd == null || spjangcd.isEmpty()) spjangcd = "ZZ";

		YearMonth ymStart = YearMonth.parse(start);
		YearMonth ymEnd = YearMonth.parse(end);

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("date_from", ymStart.atDay(1).toString());
		paramMap.addValue("date_to_excl", ymEnd.plusMonths(1).atDay(1).toString());
		paramMap.addValue("spjangcd", spjangcd);
		paramMap.addValue("groupKey", groupKey);

		String sql = " select to_char(sh.\"JumunDate\", 'yyyy-mm-dd') as \"JumunDate\" "
									 + "      , sh.\"JumunNumber\"                        as \"JumunNumber\" "
									 + "      , c.\"Name\"                                as \"CompanyName\" "
									 + "      , m.\"Code\"                                as product_code "
									 + "      , coalesce(m.\"Name\", s.\"Material_Name\") as product_name "
									 + "      , s.\"Standard\"                            as \"Standard\" "
									 + "      , u.\"Name\"                                as \"UnitName\" "
									 + "      , s.\"SujuQty\"                             as \"OrderQty\" "
									 + "      , coalesce(shp.shipped_qty, 0)              as \"ActualQty\" "
									 + "      , s.\"UnitPrice\"                           as \"UnitPrice\" "
									 + "      , coalesce(shp.shipped_qty, 0) * coalesce(s.\"UnitPrice\", 0) as \"ActualAmount\" "
									 + "      , coalesce(s.\"Price\", 0) + coalesce(s.\"Vat\", 0)           as \"OrderAmount\" "
									 + "      , s.\"Description\"                         as \"Description\" "
									 + "   from suju s "
									 + "   join suju_head sh on sh.id = s.\"SujuHead_id\" "
									 + "   left join company  c  on c.id  = sh.\"Company_id\" "
									 + "   left join material m  on m.id  = s.\"Material_id\" "
									 + "   left join mat_grp  mg on mg.id = m.\"MaterialGroup_id\" "
									 + "   left join unit     u  on u.id  = m.\"Unit_id\" "
									 + "   left join ( select \"SourceDataPk\", sum(\"Qty\") as shipped_qty "
									 + "                 from shipment group by \"SourceDataPk\" ) shp "
									 + "          on shp.\"SourceDataPk\" = s.id "
									 + "  where sh.spjangcd = :spjangcd "
									 + "    and sh.\"JumunDate\" >= cast(:date_from as date) "
									 + "    and sh.\"JumunDate\" <  cast(:date_to_excl as date) ";

		if ("product".equals(groupKind)) {
			sql += " and s.\"Material_id\" = cast(:groupKey as int) ";
		} else if ("group".equals(groupKind)) {
			sql += " and mg.id = cast(:groupKey as int) ";
		} else if ("month".equals(groupKind)) {
			sql += " and to_char(sh.\"JumunDate\", 'YYYY-MM') = :groupKey ";
		} else {
			sql += " and sh.\"Company_id\" = cast(:groupKey as int) ";
		}

		if (company != null && !company.isEmpty()) {
			paramMap.addValue("company", "%" + company + "%");
			sql += " and ( c.\"Name\" ilike :company or cast(c.id as text) ilike :company ) ";
		}
		if (product != null && !product.isEmpty()) {
			paramMap.addValue("product", "%" + product + "%");
			sql += " and ( m.\"Code\" ilike :product or m.\"Name\" ilike :product ) ";
		}

		sql += " order by sh.\"JumunDate\" desc, sh.id desc, s.id ";

		return this.sqlRunner.getRows(sql, paramMap);
	}

	/** 조회 월 목록 (최대 36개월) */
	private List<String> monthRange(YearMonth start, YearMonth end) {
		List<String> list = new ArrayList<>();
		YearMonth cur = start;
		while (!cur.isAfter(end)) {
			list.add(cur.toString());   // YYYY-MM
			cur = cur.plusMonths(1);
			if (list.size() > 36) break;
		}
		return list;
	}
}