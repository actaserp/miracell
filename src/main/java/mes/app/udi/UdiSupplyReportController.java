package mes.app.udi;

import java.util.List;
import java.util.Map;

import mes.app.udi.service.UdiApiClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.bind.annotation.*;

import mes.app.udi.enums.SupplyFlagCode;
import mes.app.udi.service.UdiSupplyReportService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;

/**
 * UDI 공급내역 보고자료 컨트롤러.
 * 납품(1)/반품(2)/폐기(3) 화면이 공유한다. 화면에서 supply_flag_code 로 구분만 넘긴다.
 */
@RestController
@RequestMapping("/api/udi/supply_report")
public class UdiSupplyReportController {

	@Autowired
	private UdiSupplyReportService supplyReportService;

	@Autowired
	private mes.app.udi.service.UdiReportSubmitService reportSubmitService;

	@Autowired
	private UdiApiClient udiApiClient;

	/**
	 * 식약처 연동 점검(토큰 발급 테스트).
	 * Access Token 만 발급해보고 결과를 반환한다. 보고(26/34) 호출은 하지 않으므로 안전하다.
	 * 사용: GET /api/udi/supply_report/ping
	 */
	@GetMapping("/ping")
	public AjaxResult ping() {
		AjaxResult result = new AjaxResult();
		mes.app.udi.service.UdiApiClient.Result r = this.udiApiClient.ping();
		result.success = r.success;
		result.message = r.message;
		return result;
	}

	/**
	 * 거래처 목록 조회 (식약처 18번 API 중계).
	 * 거래처 조회 팝업에서 호출한다. 식약처 등록 거래처만 26번 보고에 쓸 수 있으므로
	 * 여기서 조회한 bcncCode 를 화면에 채운다.
	 *
	 * 사용: GET /api/udi/supply_report/bcnc_list?offset=1&limit=20&company_name=...
	 */
	@GetMapping("/bcnc_list")
	public AjaxResult getBcncList(
			@RequestParam(value = "offset", defaultValue = "1") int offset,
			@RequestParam(value = "limit", defaultValue = "20") int limit,
			@RequestParam(value = "company_name", required = false) String companyName,
			@RequestParam(value = "tax_no", required = false) String taxNo,
			@RequestParam(value = "bcnc_code", required = false) String bcncCode) {

		AjaxResult result = new AjaxResult();
		try {
			Map<String, Object> body = this.udiApiClient.getBcncList(
					offset, limit, companyName, taxNo, bcncCode);
			result.data = body;
			result.success = true;
		} catch (Exception ex) {
			result.success = false;
			result.message = ex.getMessage();
		}
		return result;
	}

	/**
	 * 고유식별자(UDI-DI) 품목 정보 조회 (식약처 24번 API 중계).
	 * 바코드 조회 팝업에서 행 선택 시 호출한다. UDI-DI 코드로
	 * meddevItemSeq/seq/udiDiSeq 등 보고 필수 식별자를 받아 화면 폼에 채운다.
	 *
	 * 사용: GET /api/udi/supply_report/udidi_product?udi_di_code=08809286200461
	 */
	@GetMapping("/udidi_product")
	public AjaxResult getUdiDiProduct(@RequestParam("udi_di_code") String udiDiCode) {
		AjaxResult result = new AjaxResult();
		try {
			Map<String, Object> item = this.udiApiClient.getUdiDiProduct(udiDiCode);
			if (item == null) {
				result.success = false;
				result.message = "해당 UDI-DI 코드의 품목정보를 식약처에서 찾을 수 없습니다.";
				return result;
			}
			// 화면에서 바로 쓰도록 필요한 식별자만 정리해 내려준다.
			java.util.Map<String, Object> data = new java.util.HashMap<>();
			data.put("meddev_item_seq", item.get("meddevItemSeq"));
			data.put("model_seq", item.get("seq"));           // 모델 일련번호 = seq
			data.put("udi_di_seq", item.get("udiDiSeq"));
			data.put("item_name", item.get("itemName"));
			data.put("type_name", item.get("typeName"));
			data.put("permit_no", item.get("permitNo"));
			data.put("use_lot_no", item.get("useLotNo"));
			data.put("use_item_seq", item.get("useItemSeq"));
			data.put("use_manuf_ym", item.get("useManufYm"));
			data.put("use_time_limit", item.get("useTimeLimit"));
			data.put("pack_quantity", item.get("packQuantity"));
			// 원본 전체도 함께 (필요 시 참조)
			data.put("_raw", item);
			result.data = data;
			result.success = true;
		} catch (Exception ex) {
			result.success = false;
			result.message = ex.getMessage();
		}
		return result;
	}

	/**
	 * 현재 식약처 연동 모드 조회 (테스트/운영).
	 * 화면 상단 '테스트 모드' 배지 및 보고확정 안내에 사용한다.
	 * 사용: GET /api/udi/supply_report/mode
	 */
	@GetMapping("/mode")
	public AjaxResult getMode() {
		AjaxResult result = new AjaxResult();
		boolean test = this.udiApiClient.isTestMode();
		java.util.Map<String, Object> data = new java.util.HashMap<>();
		data.put("test_mode", test);
		data.put("mode_label", test ? "테스트 모드" : "운영 모드");
		result.data = data;
		result.success = true;
		return result;
	}

	/**
	 * 보고확정/취소 팝업용 월 목록 조회.
	 * 기준월별 임시/확정/취소 건수를 집계해 내려준다. 화면은 월을 골라 그 달 전체를 보고/취소한다.
	 * 사용: GET /api/udi/supply_report/months?supply_flag_code=1
	 */
	@GetMapping("/months")
	public AjaxResult getReportMonths(@RequestParam("supply_flag_code") String supplyFlagCode) {
		AjaxResult result = new AjaxResult();
		result.data = this.supplyReportService.getReportMonths(supplyFlagCode);
		return result;
	}

	/**
	 * 반품/폐기 대상 조회 — 확정된 납품 보고건 목록.
	 * 반품/폐기 화면의 "납품건 조회" 팝업에서 호출한다.
	 * 사용: GET /api/udi/supply_report/confirmed_deliveries?date_from=&date_to=&keyword=
	 */
	@GetMapping("/confirmed_deliveries")
	public AjaxResult getConfirmedDeliveries(
			@RequestParam(value = "date_from", required = false) String dateFrom,
			@RequestParam(value = "date_to", required = false) String dateTo,
			@RequestParam(value = "keyword", required = false) String keyword) {

		AjaxResult result = new AjaxResult();
		result.data = this.supplyReportService.getConfirmedDeliveries(dateFrom, dateTo, keyword);
		return result;
	}

	/** 보고자료 목록 조회 */
	@GetMapping("/list")
	public AjaxResult getList(
			@RequestParam(value = "std_month", required = false) String stdMonth,
			@RequestParam("supply_flag_code") String supplyFlagCode,
			@RequestParam(value = "date_from", required = false) String dateFrom,
			@RequestParam(value = "date_to", required = false) String dateTo,
			@RequestParam(value = "report_state", required = false) String reportState,
			@RequestParam(value = "keyword", required = false) String keyword) {

		AjaxResult result = new AjaxResult();
		result.data = this.supplyReportService.getReportList(
				stdMonth, supplyFlagCode, dateFrom, dateTo, reportState, keyword);
		return result;
	}

	/** 현황집계표 — 기준월 범위 내 납품/반품/폐기 품목별 집계 */
	@GetMapping("/summary")
	public AjaxResult getSummary(
			@RequestParam("std_from") String stdFrom,
			@RequestParam("std_to") String stdTo,
			@RequestParam(value = "report_state", required = false) String reportState,
			@RequestParam(value = "keyword", required = false) String keyword) {

		AjaxResult result = new AjaxResult();
		result.data = this.supplyReportService.getSummary(stdFrom, stdTo, reportState, keyword);
		return result;
	}

	/** 보고자료 저장 (신규/수정) — 임시 't' 상태로 저장 */
	@Transactional
	@PostMapping("/save")
	public AjaxResult save(@RequestParam Map<String, Object> params, Authentication auth) {

		AjaxResult result = new AjaxResult();
		User user = (User) auth.getPrincipal();

		// 서버측 조건부 필수 검증 (매뉴얼 규칙)
		String flagCode = str(params.get("supply_flag_code"));
		String validationError = validate(params, flagCode);
		if (validationError != null) {
			result.success = false;
			result.message = validationError;
			return result;
		}

		try {
			MapSqlParameterSource p = bind(params, user.getId());
			String idStr = str(params.get("id"));

			if (idStr == null || idStr.isBlank() || "0".equals(idStr)) {
				Integer newId = this.supplyReportService.insertReport(p);
				result.data = newId;
				result.message = "저장되었습니다.";
			} else {
				int reportId = Integer.parseInt(idStr);
				// 확정('r') 상태는 수정 불가 — 먼저 보고취소해야 함
				Map<String, Object> cur = this.supplyReportService.getReport(reportId);
				if (cur != null && "r".equals(str(cur.get("ReportState")))) {
					result.success = false;
					result.message = "보고확정된 자료는 수정할 수 없습니다. 먼저 해당 월을 보고취소한 후 수정하세요.";
					return result;
				}
				p.addValue("id", reportId);
				this.supplyReportService.updateReport(p);
				result.message = "수정되었습니다.";
			}
			result.success = true;

		} catch (Exception ex) {
			result.success = false;
			result.message = "저장 중 오류 발생: " + ex.getMessage();
			ex.printStackTrace();
			TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
		}
		return result;
	}

	/** 보고자료 삭제 (임시 't' 만) */
	@Transactional
	@PostMapping("/delete")
	public AjaxResult delete(
			@RequestParam(value = "ids[]", required = false) List<Integer> ids,
			Authentication auth) {

		AjaxResult result = new AjaxResult();
		User user = (User) auth.getPrincipal();

		if (ids == null || ids.isEmpty()) {
			result.success = false;
			result.message = "선택된 항목이 없습니다.";
			return result;
		}
		try {
			this.supplyReportService.deleteReports(ids, user.getId());
			result.success = true;
			result.message = "삭제되었습니다.";
		} catch (Exception ex) {
			result.success = false;
			result.message = "삭제 중 오류 발생: " + ex.getMessage();
			ex.printStackTrace();
			TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
		}
		return result;
	}

	/**
	 * 월 단위 보고확정 (식약처 26번 등록 → 34번 보고확정).
	 * 해당 기준월의 임시('t') 건 전체를 보고한다.
	 * 사용: POST /api/udi/supply_report/report_month  (std_month, supply_flag_code)
	 */
	@PostMapping("/report_month")
	public AjaxResult reportMonth(
			@RequestParam("std_month") String stdMonth,
			@RequestParam("supply_flag_code") String supplyFlagCode,
			Authentication auth) {

		AjaxResult result = new AjaxResult();
		User user = (User) auth.getPrincipal();
		try {
			mes.app.udi.service.UdiReportSubmitService.SubmitResult sr =
					this.reportSubmitService.submitMonth(stdMonth, supplyFlagCode, user.getId());
			result.success = sr.success;
			result.message = sr.message;
			result.data = java.util.Map.of("reported", sr.reportedCount, "failed", sr.failedCount);
		} catch (Exception ex) {
			result.success = false;
			result.message = "보고확정 중 오류 발생: " + ex.getMessage();
			ex.printStackTrace();
		}
		return result;
	}

	/**
	 * 월 단위 보고취소 (식약처 34번 토글).
	 * 해당 기준월의 확정('r') 건을 취소('c')로 되돌린다. 이후 수정 → 재보고 가능.
	 * 사용: POST /api/udi/supply_report/cancel_month  (std_month, supply_flag_code)
	 */
	@PostMapping("/cancel_month")
	public AjaxResult cancelMonth(
			@RequestParam("std_month") String stdMonth,
			@RequestParam("supply_flag_code") String supplyFlagCode,
			Authentication auth) {

		AjaxResult result = new AjaxResult();
		User user = (User) auth.getPrincipal();
		try {
			mes.app.udi.service.UdiReportSubmitService.SubmitResult sr =
					this.reportSubmitService.cancelMonth(stdMonth, supplyFlagCode, user.getId());
			result.success = sr.success;
			result.message = sr.message;
			result.data = java.util.Map.of("canceled", sr.reportedCount, "failed", sr.failedCount);
		} catch (Exception ex) {
			result.success = false;
			result.message = "보고취소 중 오류 발생: " + ex.getMessage();
			ex.printStackTrace();
		}
		return result;
	}

	/** 보고확정 (식약처 OpenAPI 26 등록 → 34 보고확정 실연동) */
	@PostMapping("/confirm")
	public AjaxResult confirm(
			@RequestParam(value = "ids[]", required = false) List<Integer> ids,
			Authentication auth) {

		AjaxResult result = new AjaxResult();
		User user = (User) auth.getPrincipal();

		if (ids == null || ids.isEmpty()) {
			result.success = false;
			result.message = "선택된 항목이 없습니다.";
			return result;
		}
		try {
			mes.app.udi.service.UdiReportSubmitService.SubmitResult sr =
					this.reportSubmitService.submit(ids, user.getId());
			result.success = sr.success;
			result.message = (sr.message == null || sr.message.isBlank())
					? (sr.success ? "보고확정 처리되었습니다." : "보고확정에 실패했습니다.")
					: sr.message;
			result.data = java.util.Map.of(
					"reported", sr.reportedCount,
					"failed", sr.failedCount);
		} catch (Exception ex) {
			result.success = false;
			result.message = "보고확정 중 오류 발생: " + ex.getMessage();
			ex.printStackTrace();
		}
		return result;
	}

	// ===== 내부 헬퍼 =====

	/** 매뉴얼 조건부 필수 검증 */
	private String validate(Map<String, Object> p, String flagCode) {
		if (flagCode == null || flagCode.isBlank()) {
			return "공급구분이 없습니다.";
		}
		SupplyFlagCode flag;
		try {
			flag = SupplyFlagCode.fromCode(flagCode);
		} catch (Exception e) {
			return "잘못된 공급구분 코드입니다.";
		}

		if (isBlank(p.get("std_month")))   return "보고 기준월은 필수입니다.";
		if (isBlank(p.get("supply_date"))) return "공급일자는 필수입니다.";
		if (isBlank(p.get("supply_qty")))  return "수량은 필수입니다.";
		if (isBlank(p.get("udi_di_code"))) return "UDI-DI 코드는 필수입니다.";

		// 출고/임대 → 공급형태 필수
		if (flag.requiresSupplyType() && isBlank(p.get("supply_type_code"))) {
			return "출고/임대인 경우 공급형태는 필수입니다.";
		}
		// 출고/반품 → 거래처 + 납품장소다름 필수
		if (flag.requiresBcnc()) {
			if (isBlank(p.get("bcnc_code")))     return "출고/반품인 경우 거래처는 필수입니다.";
			if (isBlank(p.get("is_diff_dvyfg"))) return "출고/반품인 경우 납품장소 다름 여부는 필수입니다.";
			if ("true".equalsIgnoreCase(str(p.get("is_diff_dvyfg")))
					&& isBlank(p.get("dvyfg_place_bcnc_code"))) {
				return "납품장소가 다른 경우 납품장소 거래처는 필수입니다.";
			}
		}
		// 출고 + 요양기관 → 단가/금액 필수
		boolean isRcper = "true".equalsIgnoreCase(str(p.get("bcnc_is_rcper")));
		if (flag == SupplyFlagCode.OUT && isRcper) {
			if (isBlank(p.get("supply_unit_price"))) return "출고+요양기관인 경우 공급단가는 필수입니다.";
			if (isBlank(p.get("supply_amt")))        return "출고+요양기관인 경우 공급금액은 필수입니다.";
		}
		// 요양기관(의료기관) 거래처 → 공급형태는 반드시 '의료기관에 공급'(2)
		if (isRcper && !"2".equals(str(p.get("supply_type_code")))) {
			return "거래처가 요양기관(의료기관)인 경우 공급형태를 '의료기관에 공급'으로 선택해야 합니다.";
		}
		return null;
	}

	private MapSqlParameterSource bind(Map<String, Object> p, Integer userId) {
		MapSqlParameterSource s = new MapSqlParameterSource();
		s.addValue("stdMonth", str(p.get("std_month")));
		s.addValue("supplyFlagCode", str(p.get("supply_flag_code")));
		s.addValue("supplyTypeCode", str(p.get("supply_type_code")));
		s.addValue("meddevItemSeq", str(p.get("meddev_item_seq")));
		s.addValue("modelSeq", str(p.get("model_seq")));
		s.addValue("udiDiSeq", str(p.get("udi_di_seq")));
		s.addValue("stdCode", str(p.get("std_code")));
		s.addValue("udiDiCode", str(p.get("udi_di_code")));
		s.addValue("udiPiCode", str(p.get("udi_pi_code")));
		s.addValue("lotNo", str(p.get("lot_no")));
		s.addValue("itemSerialNo", str(p.get("item_serial_no")));
		s.addValue("manufYm", str(p.get("manuf_ym")));
		s.addValue("useTmlmt", str(p.get("use_tmlmt")));
		s.addValue("bcncCode", str(p.get("bcnc_code")));
		s.addValue("bcncIsRcper", toBool(p.get("bcnc_is_rcper")));
		s.addValue("isDiffDvyfg", toBool(p.get("is_diff_dvyfg")));
		s.addValue("dvyfgPlaceBcncCode", str(p.get("dvyfg_place_bcnc_code")));
		s.addValue("supplyDate", str(p.get("supply_date")));
		s.addValue("supplyQty", str(p.get("supply_qty")));
		s.addValue("indvdlzSupplyQty", str(p.get("indvdlz_supply_qty")));
		s.addValue("supplyUnitPrice", str(p.get("supply_unit_price")));
		s.addValue("supplyAmt", str(p.get("supply_amt")));
		s.addValue("remark", str(p.get("remark")));
		s.addValue("materialName", str(p.get("material_name")));
		s.addValue("companyName", str(p.get("company_name")));
		s.addValue("userId", userId);
		return s;
	}

	private static String str(Object o) {
		return o == null ? null : o.toString();
	}

	private static boolean isBlank(Object o) {
		return o == null || o.toString().isBlank();
	}

	private static Boolean toBool(Object o) {
		if (o == null || o.toString().isBlank()) return null;
		return "true".equalsIgnoreCase(o.toString()) || "Y".equalsIgnoreCase(o.toString());
	}
}
