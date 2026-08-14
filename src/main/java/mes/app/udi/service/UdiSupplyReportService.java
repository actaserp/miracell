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

	/**
	 * 반품/폐기 대상 조회 — 확정된 납품(flag=1, ReportState='r') 보고건 목록.
	 * 반품/폐기는 "이미 식약처에 보고된 납품"만 대상이 되므로 확정건만 조회한다.
	 * 식별자(seq)/표준코드/거래처 등 반품·폐기 보고에 필요한 값을 모두 포함해
	 * 화면에서 그대로 복사할 수 있게 한다.
	 */
	public List<Map<String, Object>> getConfirmedDeliveries(String dateFrom, String dateTo, String keyword) {
		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("dateFrom", dateFrom);
		paramMap.addValue("dateTo", dateTo);

		String sql = """
				select r.id                 as delivery_id
				, r."SupplyTypeCode"        as supply_type_code
				, r."MeddevItemSeq"         as meddev_item_seq
				, r."ModelSeq"              as model_seq
				, r."UdiDiSeq"              as udi_di_seq
				, r."StdCode"               as std_code
				, r."UdiDiCode"             as udi_di_code
				, r."UdiPiCode"             as udi_pi_code
				, r."LotNo"                 as lot_no
				, r."ItemSerialNo"          as item_serial_no
				, r."ManufYm"               as manuf_ym
				, r."UseTmlmt"              as use_tmlmt
				, r."BcncCode"              as bcnc_code
				, r."IsDiffDvyfg"           as is_diff_dvyfg
				, r."DvyfgPlaceBcncCode"    as dvyfg_place_bcnc_code
				, r."SupplyDate"            as supply_date
				, r."SupplyQty"             as supply_qty
				, r."StdMonth"              as std_month
				from udi_supply_report r
				where r."SupplyFlagCode" = '1'
				  and r."ReportState" = 'r'
				""";

		if (StringUtils.isEmpty(dateFrom) == false)
			sql += " and r.\"SupplyDate\" >= :dateFrom ";
		if (StringUtils.isEmpty(dateTo) == false)
			sql += " and r.\"SupplyDate\" <= :dateTo ";
		if (StringUtils.isEmpty(keyword) == false) {
			sql += " and ( r.\"UdiDiCode\" ilike concat('%',:keyword,'%') or r.\"StdCode\" ilike concat('%',:keyword,'%') or r.\"LotNo\" ilike concat('%',:keyword,'%') ) ";
			paramMap.addValue("keyword", keyword);
		}

		sql += " order by r.\"SupplyDate\" desc, r.id desc ";

		return this.sqlRunner.getRows(sql, paramMap);
	}

	/**
	 * 보고확정/취소 팝업용 월 목록.
	 * 공급구분별로 기준월(StdMonth)마다 임시('t')/확정('r')/취소('c') 건수와 수량을 집계한다.
	 * 화면은 이 목록에서 월을 골라 그 달 전체를 보고/취소한다(월 단위 보고 원칙).
	 */
	public List<Map<String, Object>> getReportMonths(String supplyFlagCode) {
		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("supplyFlagCode", supplyFlagCode);
		String sql = """
				select r."StdMonth"                                                as std_month
				, count(*)                                                          as total_cnt
				, sum(case when r."ReportState" = 't' then 1 else 0 end)            as temp_cnt
				, sum(case when r."ReportState" = 'r' then 1 else 0 end)            as confirmed_cnt
				, sum(case when r."ReportState" = 'c' then 1 else 0 end)            as canceled_cnt
				, coalesce(sum(r."SupplyQty"), 0)                                   as total_qty
				, max(r."ReportedAt")                                               as last_reported_at
				from udi_supply_report r
				where r."SupplyFlagCode" = :supplyFlagCode
				group by r."StdMonth"
				order by r."StdMonth" desc
				""";
		return this.sqlRunner.getRows(sql, paramMap);
	}

	/** 특정 기준월의 특정 상태 건 id 목록 (월 단위 보고/취소용) */
	public List<Integer> getReportIdsByMonth(String stdMonth, String supplyFlagCode, String reportState) {
		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("stdMonth", stdMonth);
		paramMap.addValue("supplyFlagCode", supplyFlagCode);
		paramMap.addValue("reportState", reportState);
		String sql = """
				select r.id
				from udi_supply_report r
				where r."StdMonth" = :stdMonth
				  and r."SupplyFlagCode" = :supplyFlagCode
				  and r."ReportState" = :reportState
				order by r.id
				""";
		List<Map<String, Object>> rows = this.sqlRunner.getRows(sql, paramMap);
		List<Integer> ids = new java.util.ArrayList<>();
		if (rows != null) {
			for (Map<String, Object> row : rows) {
				ids.add(((Number) row.get("id")).intValue());
			}
		}
		return ids;
	}

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
				, r."MeddevItemSeq"       as meddev_item_seq
				, r."ModelSeq"            as model_seq
				, r."UdiDiSeq"            as udi_di_seq
				, r."StdCode"             as std_code
				, r."UdiDiCode"           as udi_di_code
				, r."UdiPiCode"           as udi_pi_code
				, r."LotNo"               as lot_no
				, r."ItemSerialNo"        as item_serial_no
				, r."ManufYm"             as manuf_ym
				, r."UseTmlmt"            as use_tmlmt
				, r."BcncCode"            as bcnc_code
				, r."BcncIsRcper"         as bcnc_is_rcper
				, r."IsDiffDvyfg"         as is_diff_dvyfg
				, r."DvyfgPlaceBcncCode"  as dvyfg_place_bcnc_code
				, r."SupplyDate"          as supply_date
				, r."SupplyQty"           as supply_qty
				, r."IndvdlzSupplyQty"    as indvdlz_supply_qty
				, r."SupplyUnitPrice"     as supply_unit_price
				, r."SupplyAmt"           as supply_amt
				, r."Remark"              as remark
				, r."MaterialName"        as material_name
				, r."CompanyName"         as company_name
				, r."ReportState"         as report_state
				, case r."ReportState" when 't' then '임시'
				                       when 'r' then '보고확정'
				                       when 'c' then '취소'
				                       else r."ReportState" end as report_state_name
				, r."ReportedAt"          as reported_at
				from udi_supply_report r
				where r."SupplyFlagCode" = :supplyFlagCode
				""";

		if (StringUtils.isEmpty(stdMonth) == false)
			sql += " and r.\"StdMonth\" = :stdMonth ";
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
				  "Remark","BcncIsRcper","MaterialName","CompanyName",
				  "ReportState","_status","_created","_creater_id"
				) values (
				  :stdMonth,:supplyFlagCode,:supplyTypeCode,
				  :meddevItemSeq,:modelSeq,:udiDiSeq,
				  :stdCode,:udiDiCode,:udiPiCode,
				  :lotNo,:itemSerialNo,:manufYm,:useTmlmt,
				  :bcncCode,:isDiffDvyfg,:dvyfgPlaceBcncCode,
				  :supplyDate, cast(nullif(:supplyQty,'') as numeric), cast(nullif(:indvdlzSupplyQty,'') as numeric),
				  cast(nullif(:supplyUnitPrice,'') as numeric), cast(nullif(:supplyAmt,'') as numeric),
				  :remark,:bcncIsRcper,:materialName,:companyName,
				  't','t', now(), :userId
				)
				returning id
				""";
		Map<String, Object> row = this.sqlRunner.getRow(sql, p);
		return row == null ? null : ((Number) row.get("id")).intValue();
	}

	/**
	 * 보고자료 수정.
	 * 임시('t')는 그대로 수정한다.
	 * 취소('c')된 건을 수정하면 재보고 대상이 되도록 임시('t')로 되돌린다.
	 * 확정('r')된 건은 수정 불가(먼저 보고취소해야 함).
	 */
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
				  "SupplyQty"          = cast(nullif(:supplyQty,'') as numeric),
				  "IndvdlzSupplyQty"   = cast(nullif(:indvdlzSupplyQty,'') as numeric),
				  "SupplyUnitPrice"    = cast(nullif(:supplyUnitPrice,'') as numeric),
				  "SupplyAmt"          = cast(nullif(:supplyAmt,'') as numeric),
				  "Remark"             = :remark,
				  "BcncIsRcper"        = :bcncIsRcper,
				  "MaterialName"       = :materialName,
				  "CompanyName"        = :companyName,
				  "ReportState"        = 't',
				  "_modified"          = now(),
				  "_modifier_id"       = :userId
				where id = :id and "ReportState" in ('t','c')
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
				, max(r."MaterialName")                                                              as material_name
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

	// ===================== 식약처 보고확정 연동용 =====================

	/**
	 * 보고확정 대상(임시 't') 조회.
	 * 식약처 26번(보고자료 추가) 본문에 바로 매핑할 수 있도록 API 필드명으로 alias 한다.
	 *
	 * 매뉴얼 1.5.2: 선택(X) 필드도 키를 포함해 빈 문자열로 전송해야 함.
	 * → 모든 선택 필드는 coalesce(..., '') 로 null → 빈 문자열 처리.
	 * → suplyQty 등 수량은 string(10) 타입이므로 문자열로 변환.
	 * → meddevItemSeq/seq/udiDiSeq 는 number 타입이므로 숫자 그대로 반환.
	 * → suplyTypeCode 는 flag=3(폐기) 등에서도 항상 포함 (빈 문자열로라도 전송).
	 * → isDiffDvyfg 는 boolean 타입. null 이면 false.
	 */
	public List<Map<String, Object>> getReportsByIds(List<Integer> ids) {
		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("ids", ids);
		String sql = """
				select r.id
				, r."StdMonth"                                                         as "suplyContStdmt"
				, r."SupplyFlagCode"                                                   as "suplyFlagCode"
				, coalesce(r."SupplyTypeCode", '')                                     as "suplyTypeCode"
				, r."MeddevItemSeq"                                                    as "meddevItemSeq"
				, r."ModelSeq"                                                         as "seq"
				, r."UdiDiSeq"                                                         as "udiDiSeq"
				, r."StdCode"                                                          as "stdCode"
				, r."UdiDiCode"                                                        as "udiDiCode"
				, r."UdiPiCode"                                                        as "udiPiCode"
				, coalesce(r."LotNo", '')                                              as "lotNo"
				, coalesce(r."ItemSerialNo", '')                                       as "itemSeq"
				, coalesce(r."ManufYm", '')                                            as "manufYm"
				, coalesce(r."UseTmlmt", '')                                           as "useTmlmt"
				, coalesce(r."BcncCode", '')                                           as "bcncCode"
				, coalesce(r."IsDiffDvyfg", false)                                     as "isDiffDvyfg"
				, coalesce(r."DvyfgPlaceBcncCode", '')                                 as "dvyfgPlaceBcncCode"
				, r."SupplyDate"                                                       as "suplyDate"
				, trim(to_char(r."SupplyQty", 'FM999999999990'))                       as "suplyQty"
				, coalesce(trim(to_char(r."IndvdlzSupplyQty", 'FM999999999990')), '')  as "indvdlzSuplyQty"
				, coalesce(trim(to_char(r."SupplyUnitPrice",  'FM999999999990')), '')  as "suplyUntpc"
				, coalesce(trim(to_char(r."SupplyAmt",        'FM999999999990')), '')  as "suplyAmt"
				, coalesce(r."Remark", '')                                             as "remark"
				from udi_supply_report r
				where r.id in (:ids) and r."ReportState" in ('t','c')
				order by r."StdMonth", r.id
				""";
		return this.sqlRunner.getRows(sql, paramMap);
	}

	/** 보고취소 처리: 확정('r') → 취소('c') 상태로 전환. 이후 수정 후 재보고 가능 */
	public void markCanceled(List<Integer> ids, String message, Integer userId) {
		if (ids == null || ids.isEmpty()) return;
		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("ids", ids);
		paramMap.addValue("message", message);
		paramMap.addValue("userId", userId);
		String sql = """
				update udi_supply_report set
				  "ReportState"       = 'c',
				  "MfdsResultMessage" = :message,
				  "_modified"         = now(),
				  "_modifier_id"      = :userId
				where id in (:ids) and "ReportState" = 'r'
				""";
		this.sqlRunner.execute(sql, paramMap);
	}

	/** 보고확정 성공 처리: 상태 'r' + 보고일시 + 식약처 응답 메시지 저장 */
	public void markReported(List<Integer> ids, String message, Integer userId) {
		if (ids == null || ids.isEmpty()) return;
		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("ids", ids);
		paramMap.addValue("message", message);
		paramMap.addValue("userId", userId);
		String sql = """
				update udi_supply_report set
				  "ReportState"       = 'r',
				  "ReportedAt"        = now(),
				  "MfdsResultMessage" = :message,
				  "_modified"         = now(),
				  "_modifier_id"      = :userId
				where id in (:ids) and "ReportState" in ('t','c')
				""";
		this.sqlRunner.execute(sql, paramMap);
	}

	/** 보고확정 실패 처리: 상태는 't' 유지, 식약처 오류 메시지만 저장 */
	public void markReportFailed(List<Integer> ids, String message, Integer userId) {
		if (ids == null || ids.isEmpty()) return;
		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("ids", ids);
		paramMap.addValue("message", message);
		paramMap.addValue("userId", userId);
		String sql = """
				update udi_supply_report set
				  "MfdsResultMessage" = :message,
				  "_modified"         = now(),
				  "_modifier_id"      = :userId
				where id in (:ids) and "ReportState" in ('t','c')
				""";
		this.sqlRunner.execute(sql, paramMap);
	}
}
