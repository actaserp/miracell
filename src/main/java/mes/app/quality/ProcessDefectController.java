package mes.app.quality;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.app.quality.service.ProcessDefectService;
import mes.domain.model.AjaxResult;

/**
 * 제품불량현황 — 공정별 불량률.
 * URL: /api/quality/process_defect
 */
@RestController
@RequestMapping("/api/quality/process_defect")
public class ProcessDefectController {

	@Autowired
	ProcessDefectService processDefectService;

	/** 공정별 불량률 (기간 합산) */
	@GetMapping("/list")
	public AjaxResult getList(
			@RequestParam(value = "date_from", required = false) String dateFrom,
			@RequestParam(value = "date_to", required = false) String dateTo,
			@RequestParam(value = "proc_code", required = false) String procCode,
			@RequestParam(value = "material_id", required = false) Integer materialId) {

		AjaxResult result = new AjaxResult();
		result.data = this.processDefectService.getProcessDefect(dateFrom, dateTo, procCode, materialId);
		return result;
	}

	/** 공정 콤보 */
	@GetMapping("/process_combo")
	public AjaxResult getProcessCombo() {
		AjaxResult result = new AjaxResult();
		result.data = this.processDefectService.getProcessCombo();
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
