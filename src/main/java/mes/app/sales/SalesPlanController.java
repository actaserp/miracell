package mes.app.sales;

import lombok.extern.slf4j.Slf4j;
import mes.app.sales.service.SalesPlanService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.transaction.Transactional;
import java.util.List;
import java.util.Map;

/**
 * 영업계획등록 (연도 × 월 × 거래처 × 품목)
 *
 * sales_plan_head (헤더: 연도/월/거래처)
 *   └ sales_plan  (상세: 품목별 계획수량/단가/금액)
 */
@Slf4j
@RestController
@RequestMapping("/api/sales/plan")
public class SalesPlanController {

	@Autowired
	SalesPlanService salesPlanService;

	@Autowired
	SqlRunner sqlRunner;

	// 영업계획 목록 조회
	@GetMapping("/read")
	public AjaxResult getPlanList(
			@RequestParam(value = "year") String year,
			@RequestParam(value = "month", required = false) String month,
			@RequestParam(value = "matGrp", required = false) String matGrp,
			@RequestParam(value = "keyword", required = false) String keyword,
			// 공장 필터. 빈 값 = 전체.
			@RequestParam(value = "factory_id", required = false) String factoryId,
			@RequestParam(value = "spjangcd") String spjangcd,
			HttpServletRequest request) {

		List<Map<String, Object>> items =
				this.salesPlanService.getPlanList(year, month, matGrp, keyword, factoryId, spjangcd);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	// 영업계획 상세 조회 (수정 팝업)
	@GetMapping("/detail")
	public AjaxResult getPlanDetail(
			@RequestParam("id") int id,
			HttpServletRequest request) {

		Map<String, Object> item = this.salesPlanService.getPlanDetail(id);

		AjaxResult result = new AjaxResult();
		result.data = item;

		return result;
	}

	// 영업계획 등록 / 수정
	@PostMapping("/manual_save")
	@Transactional
	public AjaxResult planSave(@RequestBody Map<String, Object> payload, Authentication auth) {

		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		String planYear  = (String) payload.get("PlanYear");
		String planMonth = (String) payload.get("PlanMonth");
		String spjangcd  = (String) payload.get("spjangcd");

		if (planYear == null || planYear.isBlank() || planMonth == null || planMonth.isBlank()) {
			result.success = false;
			result.message = "계획연도와 계획월은 필수입니다.";
			return result;
		}

		Integer headId   = toIntegerOrNull(payload.get("id"));
		Integer companyId = toIntegerOrNull(payload.get("Company_id"));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");

		if (items == null || items.isEmpty()) {
			result.success = false;
			result.message = "등록할 품목이 없습니다.";
			return result;
		}

		// ── 수정 시: 수주 발생 여부 확인 ────────────────────────────
		if (headId != null) {
			int sujuCnt = this.salesPlanService.countSujuByPlanHead(headId);
			if (sujuCnt > 0) {
				result.success = false;
				result.message = "수주가 발생한 계획은 수정할 수 없습니다.";
				TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
				return result;
			}
		}

		// ── 동일 연월 + 거래처 중복 계획 방지 ───────────────────────
		int dupCnt = this.salesPlanService.countDuplicatePlan(headId, planYear, planMonth, companyId, spjangcd);
		if (dupCnt > 0) {
			result.success = false;
			result.message = planYear + "년 " + planMonth + "월 계획이 이미 등록되어 있습니다. 기존 계획을 수정해주세요.";
			TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
			return result;
		}

		// ── 상세 검증 (API 직접 호출 방어) ─────────────────────────
		int row = 1;
		java.util.List<Integer> seenMaterial = new java.util.ArrayList<>();
		for (Map<String, Object> item : items) {
			Integer matId = toIntegerOrNull(item.get("Material_id"));
			Double  qty   = toDouble(item.get("planQty"));

			if (matId == null) {
				result.success = false;
				result.message = "(" + row + "번째 행) 품목을 선택하세요.";
				TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
				return result;
			}
			if (qty == null || qty <= 0) {
				result.success = false;
				result.message = "(" + row + "번째 행) 계획수량은 0보다 커야 합니다.";
				TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
				return result;
			}
			if (seenMaterial.contains(matId)) {
				result.success = false;
				result.message = "(" + row + "번째 행) 같은 품목이 중복 입력되었습니다.";
				TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
				return result;
			}
			seenMaterial.add(matId);
			row++;
		}

		try {
			Integer savedId = this.salesPlanService.savePlan(
					headId, planYear, planMonth, companyId,
					(String) payload.get("CompanyName"),
					(String) payload.get("Description"), spjangcd, items, user);

			result.success = true;
			result.data = Map.of("id", savedId);
			return result;

		} catch (Exception e) {
			log.error("영업계획 저장 실패", e);
			result.success = false;
			result.message = "저장 실패: " + e.getMessage();
			TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
			return result;
		}
	}

	// 영업계획 삭제
	@PostMapping("/delete")
	@Transactional
	public AjaxResult deletePlan(@RequestParam("id") Integer id) {

		AjaxResult result = new AjaxResult();

		int sujuCnt = this.salesPlanService.countSujuByPlanHead(id);
		if (sujuCnt > 0) {
			result.success = false;
			result.message = "수주가 발생한 계획은 삭제할 수 없습니다.";
			return result;
		}

		this.salesPlanService.deletePlan(id);

		result.success = true;
		return result;
	}

	// 거래처 + 품목 단가 조회 (수주등록의 readPriceSuju 와 동일 마스터)
	@GetMapping("/read_price")
	public AjaxResult getPlanPrice(
			@RequestParam(value = "company_id", required = false) Integer companyId,
			@RequestParam("mat_pk") int matPk,
			@RequestParam("baseDate") String baseDate) {

		List<Map<String, Object>> items = this.salesPlanService.getPriceByMatAndComp(matPk, companyId, baseDate);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	// ── 유틸 ────────────────────────────────────────────────────

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

	private static Double toDouble(Object v) {
		if (v == null) return null;
		if (v instanceof Number) return ((Number) v).doubleValue();

		String s = v.toString().trim().replace(",", "");
		if (s.isEmpty() || s.equals("-") || s.equals(".")) return null;

		try {
			return Double.valueOf(s);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}