package mes.app.sales;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.app.sales.service.SalesSummaryService;
import mes.domain.model.AjaxResult;

@RestController
@RequestMapping("/api/sales/sales_summary")
public class SalesSummaryController {

	@Autowired
	SalesSummaryService salesSummaryService;

	// 영업현황 집계 조회
	@GetMapping("/read")
	public AjaxResult getSummaryList(
		@RequestParam(value = "start", required = false) String start,             // YYYY-MM
		@RequestParam(value = "end", required = false) String end,                 // YYYY-MM
		@RequestParam(value = "groupKind", required = false) String groupKind,     // company/product/group/month
		@RequestParam(value = "amountKind", required = false) String amountKind,   // amount/qty
		@RequestParam(value = "company", required = false) String company,
		@RequestParam(value = "product", required = false) String product,
		@RequestParam(value = "spjangcd", required = false) String spjangcd) {

		List<Map<String, Object>> items =
			this.salesSummaryService.getSummaryList(start, end, groupKind, amountKind, company, product, spjangcd);

		AjaxResult result = new AjaxResult();
		result.data = items;
		return result;
	}

	// 드릴다운 - 선택 그룹의 수주/출하 라인 내역
	@GetMapping("/drill_list")
	public AjaxResult getDrillList(
		@RequestParam(value = "start", required = false) String start,
		@RequestParam(value = "end", required = false) String end,
		@RequestParam(value = "groupKind", required = false) String groupKind,
		@RequestParam(value = "groupKey", required = false) String groupKey,
		@RequestParam(value = "company", required = false) String company,
		@RequestParam(value = "product", required = false) String product,
		@RequestParam(value = "spjangcd", required = false) String spjangcd) {

		List<Map<String, Object>> items =
			this.salesSummaryService.getDrillList(start, end, groupKind, groupKey, company, product, spjangcd);

		AjaxResult result = new AjaxResult();
		result.data = items;
		return result;
	}
}