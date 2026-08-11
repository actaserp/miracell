package mes.app.quality;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.app.quality.service.PeriodDefectService;
import mes.domain.model.AjaxResult;

/**
 * 기간별불량현황 — 일자별 불량률.
 * URL: /api/quality/period_defect
 */
@RestController
@RequestMapping("/api/quality/period_defect")
public class PeriodDefectController {

	@Autowired
	PeriodDefectService periodDefectService;

	/** 일자별 불량률 */
	@GetMapping("/list")
	public AjaxResult getList(
			@RequestParam(value = "date_from", required = false) String dateFrom,
			@RequestParam(value = "date_to", required = false) String dateTo,
			@RequestParam(value = "proc_code", required = false) String procCode) {

		AjaxResult result = new AjaxResult();
		result.data = this.periodDefectService.getPeriodDefect(dateFrom, dateTo, procCode);
		return result;
	}

	/** 공정 콤보 */
	@GetMapping("/process_combo")
	public AjaxResult getProcessCombo() {
		AjaxResult result = new AjaxResult();
		result.data = this.periodDefectService.getProcessCombo();
		return result;
	}

	@ExceptionHandler(Exception.class)
	public AjaxResult handle(Exception e) {
		AjaxResult result = new AjaxResult();
		result.success = false;
		result.message = e.getMessage();
		e.printStackTrace();
		return result;
	}
}
