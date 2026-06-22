package mes.app.udi;

import java.util.List;
import java.util.Map;

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

	/** 보고자료 목록 조회 */
	@GetMapping("/list")
	public AjaxResult getList(
			@RequestParam("std_month") String stdMonth,
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
				p.addValue("id", Integer.parseInt(idStr));
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

	/** 보고확정 (식약처 보고 처리 — 현재는 상태전환, API 연동은 다음 단계) */
	@Transactional
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
			this.supplyReportService.confirmReports(ids, user.getId());
			result.success = true;
			result.message = "보고확정 처리되었습니다.";
		} catch (Exception ex) {
			result.success = false;
			result.message = "보고확정 중 오류 발생: " + ex.getMessage();
			ex.printStackTrace();
			TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
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
		s.addValue("isDiffDvyfg", toBool(p.get("is_diff_dvyfg")));
		s.addValue("dvyfgPlaceBcncCode", str(p.get("dvyfg_place_bcnc_code")));
		s.addValue("supplyDate", str(p.get("supply_date")));
		s.addValue("supplyQty", str(p.get("supply_qty")));
		s.addValue("indvdlzSupplyQty", str(p.get("indvdlz_supply_qty")));
		s.addValue("supplyUnitPrice", str(p.get("supply_unit_price")));
		s.addValue("supplyAmt", str(p.get("supply_amt")));
		s.addValue("remark", str(p.get("remark")));
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
