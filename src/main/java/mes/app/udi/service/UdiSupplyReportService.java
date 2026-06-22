package mes.app.udi.service;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.util.StringUtils;
import mes.domain.services.SqlRunner;

/**
 * UDI 공급내역 보고자료 서비스
 * 식약처 UDI OpenAPI V3.4의 보고자료(추가/수정/삭제/보고) 흐름을 udi_supply_report 테이블로 관리.
 *
 * 납품/반품/폐기 화면이 공유하며 supplyFlagCode(1/2/3) 로만 구분한다.
 */
@Service
public class UdiSupplyReportService {

	@Autowired
	SqlRunner sqlRunner;

	/** 보고자료 목록 조회 (화면 그리드) */
	public List<Map<String, Object>> getReportList(String stdMonth, String supplyFlagCode,
												   String dateFrom, String dateTo,
												   String reportState, String keyword) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("stdMonth", stdMonth);
		paramMap.addValue("supplyFlagCode", supplyFlagCode);
		paramMap.addValue("dateFrom", dateFrom);
		paramMap.addValue("dateTo", dateTo);
		paramMap.addValue("reportState", reportState);

		String sql = """
				select r.id
				, r."StdMonth"            as std_month
				, r."SupplyFlagCode"      as supply_flag_code
				, r."SupplyTypeCode"      as supply_type_code
				, r."StdCode"             as std_code
				, r."UdiDiCode"           as udi_di_code
				, r."UdiPiCode"           as udi_pi_code
				, r."LotNo"               as lot_no
				, r."ItemSerialNo"        as item_serial_no
				, r."ManufYm"             as manuf_ym
				, r."UseTmlmt"            as use_tmlmt
				, r."BcncCode"            as bcnc_code
				, r."IsDiffDvyfg"         as is_diff_dvyfg
				, r."DvyfgPlaceBcncCode"  as dvyfg_place_bcnc_code
				, r."SupplyDate"          as supply_date
				, r."SupplyQty"           as supply_qty
				, r."SupplyUnitPrice"     as supply_unit_price
				, r."SupplyAmt"           as supply_amt
				, r."Remark"              as remark
				, r."ReportState"         as report_state
				, case r."ReportState" when 't' then '임시'
				                       when 'r' then '보고확정'
				                       when 'c' then '취소'
				                       else r."ReportState" end as report_state_name
				, r."ReportedAt"          as reported_at
				from udi_supply_report r
				where r."StdMonth" = :stdMonth
				  and r."SupplyFlagCode" = :supplyFlagCode
				""";

		if (StringUtils.isEmpty(dateFrom) == false)
			sql += " and r.\"SupplyDate\" >= :dateFrom ";
		if (StringUtils.isEmpty(dateTo) == false)
			sql += " and r.\"SupplyDate\" <= :dateTo ";
		if (StringUtils.isEmpty(reportState) == false)
			sql += " and r.\"ReportState\" = :reportState ";
		if (StringUtils.isEmpty(keyword) == false) {
			sql += " and ( r.\"UdiDiCode\" ilike concat('%',:keyword,'%') or r.\"StdCode\" ilike concat('%',:keyword,'%') ) ";
			paramMap.addValue("keyword", keyword);
		}

		sql += " order by r.\"SupplyDate\" desc, r.id desc ";

		return this.sqlRunner.getRows(sql, paramMap);
	}

	/** 단건 조회 */
	public Map<String, Object> getReport(Integer id) {
		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("id", id);
		String sql = "select * from udi_supply_report where id = :id";
		return this.sqlRunner.getRow(sql, paramMap);
	}

	/** 보고자료 신규 등록 (임시 't' 상태) */
	public Integer insertReport(MapSqlParameterSource p) {
		String sql = """
				insert into udi_supply_report (
				  "StdMonth","SupplyFlagCode","SupplyTypeCode",
				  "MeddevItemSeq","ModelSeq","UdiDiSeq",
				  "StdCode","UdiDiCode","UdiPiCode",
				  "LotNo","ItemSerialNo","ManufYm","UseTmlmt",
				  "BcncCode","IsDiffDvyfg","DvyfgPlaceBcncCode",
				  "SupplyDate","SupplyQty","IndvdlzSupplyQty","SupplyUnitPrice","SupplyAmt",
				  "Remark","ReportState","_status","_created","_creater_id"
				) values (
				  :stdMonth,:supplyFlagCode,:supplyTypeCode,
				  :meddevItemSeq,:modelSeq,:udiDiSeq,
				  :stdCode,:udiDiCode,:udiPiCode,
				  :lotNo,:itemSerialNo,:manufYm,:useTmlmt,
				  :bcncCode,:isDiffDvyfg,:dvyfgPlaceBcncCode,
				  :supplyDate, cast(:supplyQty as numeric), cast(:indvdlzSupplyQty as numeric),
				  cast(:supplyUnitPrice as numeric), cast(:supplyAmt as numeric),
				  :remark,'t','t', now(), :userId
				)
				returning id
				""";
		Map<String, Object> row = this.sqlRunner.getRow(sql, p);
		return row == null ? null : ((Number) row.get("id")).intValue();
	}

	/** 보고자료 수정 (임시 't' 상태인 건만) */
	public void updateReport(MapSqlParameterSource p) {
		String sql = """
				update udi_supply_report set
				  "SupplyTypeCode"     = :supplyTypeCode,
				  "StdCode"            = :stdCode,
				  "UdiDiCode"          = :udiDiCode,
				  "UdiPiCode"          = :udiPiCode,
				  "LotNo"              = :lotNo,
				  "ItemSerialNo"       = :itemSerialNo,
				  "ManufYm"            = :manufYm,
				  "UseTmlmt"           = :useTmlmt,
				  "BcncCode"           = :bcncCode,
				  "IsDiffDvyfg"        = :isDiffDvyfg,
				  "DvyfgPlaceBcncCode" = :dvyfgPlaceBcncCode,
				  "SupplyDate"         = :supplyDate,
				  "SupplyQty"          = cast(:supplyQty as numeric),
				  "IndvdlzSupplyQty"   = cast(:indvdlzSupplyQty as numeric),
				  "SupplyUnitPrice"    = cast(:supplyUnitPrice as numeric),
				  "SupplyAmt"          = cast(:supplyAmt as numeric),
				  "Remark"             = :remark,
				  "_modified"          = now(),
				  "_modifier_id"       = :userId
				where id = :id and "ReportState" = 't'
				""";
		this.sqlRunner.execute(sql, p);
	}

	/** 보고자료 삭제 (임시 't' 상태인 건만) */
	public void deleteReports(List<Integer> ids, Integer userId) {
		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("ids", ids);
		String sql = """
				delete from udi_supply_report
				where id in (:ids) and "ReportState" = 't'
				""";
		this.sqlRunner.execute(sql, paramMap);
	}

	/**
	 * 보고확정 처리.
	 * 실제로는 여기서 식약처 OpenAPI(34.공급내역 보고 및 취소)를 호출해야 한다.
	 * 현재는 상태만 'r'(보고확정)로 전환한다. (API 클라이언트 연동은 다음 단계)
	 */
	public void confirmReports(List<Integer> ids, Integer userId) {
		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("ids", ids);
		paramMap.addValue("userId", userId);
		String sql = """
				update udi_supply_report set
				  "ReportState" = 'r',
				  "ReportedAt"  = now(),
				  "_modified"   = now(),
				  "_modifier_id"= :userId
				where id in (:ids) and "ReportState" = 't'
				""";
		this.sqlRunner.execute(sql, paramMap);
	}

	/**
	 * 현황집계표.
	 * 기준월 범위(stdFrom~stdTo) 내 보고자료를 품목(UDI-DI + 표준코드)별로 집계하여
	 * 납품(1)/반품(2)/폐기(3) 수량을 피벗하고, 보고상태별 건수를 함께 제공한다.
	 */
	public List<Map<String, Object>> getSummary(String stdFrom, String stdTo,
												 String reportState, String keyword) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("stdFrom", stdFrom);
		paramMap.addValue("stdTo", stdTo);
		paramMap.addValue("reportState", reportState);

		String sql = """
				select r."UdiDiCode"  as udi_di_code
				, r."StdCode"         as std_code
				, coalesce(sum(case when r."SupplyFlagCode" = '1' then r."SupplyQty" else 0 end), 0) as delivery_qty
				, coalesce(sum(case when r."SupplyFlagCode" = '2' then r."SupplyQty" else 0 end), 0) as return_qty
				, coalesce(sum(case when r."SupplyFlagCode" = '3' then r."SupplyQty" else 0 end), 0) as disposal_qty
				, coalesce(sum(case when r."SupplyFlagCode" = '1' then r."SupplyAmt" else 0 end), 0) as delivery_amt
				, count(*)                                                          as report_cnt
				, sum(case when r."ReportState" = 'r' then 1 else 0 end)            as confirmed_cnt
				, sum(case when r."ReportState" = 't' then 1 else 0 end)            as temp_cnt
				from udi_supply_report r
				where r."StdMonth" between :stdFrom and :stdTo
				""";

		if (StringUtils.isEmpty(reportState) == false)
			sql += " and r.\"ReportState\" = :reportState ";
		if (StringUtils.isEmpty(keyword) == false) {
			sql += " and ( r.\"UdiDiCode\" ilike concat('%',:keyword,'%') or r.\"StdCode\" ilike concat('%',:keyword,'%') ) ";
			paramMap.addValue("keyword", keyword);
		}

		sql += """
				 group by r."UdiDiCode", r."StdCode"
				 order by r."UdiDiCode", r."StdCode"
				""";

		return this.sqlRunner.getRows(sql, paramMap);
	}
}
