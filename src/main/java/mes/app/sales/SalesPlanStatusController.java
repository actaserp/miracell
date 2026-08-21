package mes.app.sales;

import lombok.extern.slf4j.Slf4j;
import mes.app.sales.service.SalesPlanStatusService;
import mes.domain.model.AjaxResult;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * 영업계획현황 (품목 × 월)
 */
@Slf4j
@RestController
@RequestMapping("/api/sales/plan_status")
public class SalesPlanStatusController {

	@Autowired
	SalesPlanStatusService salesPlanStatusService;

	@GetMapping("/read")
	public AjaxResult getPlanStatus(
			@RequestParam(value = "cboYear") String year,
			@RequestParam(value = "cboMatGrp", required = false) String matGrp,
			@RequestParam(value = "keyword", required = false) String keyword,
			@RequestParam(value = "cboDataDiv", required = false) String dataDiv,
			// 공장 필터. 빈 값 = 전체.
			@RequestParam(value = "factory_id", required = false) String factoryId,
			@RequestParam(value = "spjangcd") String spjangcd,
			HttpServletRequest request) {

		List<Map<String, Object>> items =
				this.salesPlanStatusService.getPlanStatus(year, matGrp, keyword, dataDiv, factoryId, spjangcd);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}
}