package mes.app.support;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.app.support.service.KpiDefectRateService;
import mes.domain.model.AjaxResult;

/**
 * KPI 공정불량률.
 *
 * 화면(kpi_defect_rate.html)이 기대하는 { lines, details, months } 를 내려준다.
 * 공정불량률(%) = 불량수량 / 검사수량(=생산수량) * 100.
 */
@RestController
@RequestMapping("/api/support/kpi_defect_rate")
public class KpiDefectRateController {

	@Autowired
	KpiDefectRateService service;

	/** 공정 콤보 (동적) */
	@GetMapping("/processes")
	public AjaxResult getProcesses() {
		AjaxResult r = new AjaxResult();
		try {
			r.data = service.getProcessCombo();
			r.success = true;
		} catch (Exception e) {
			r.success = false;
			r.message = e.getMessage();
		}
		return r;
	}

	/**
	 * 불량률 데이터.
	 *   start_month / end_month : "yyyy-MM"
	 *   process_code            : 공정코드(선택)
	 *   material_id             : 제품 id(선택)
	 * 반환 data = { lines: [...], details: [...], months: [...] }
	 */
	@GetMapping("/read")
	public AjaxResult read(
			@RequestParam("start_month") String startMonth,
			@RequestParam("end_month") String endMonth,
			@RequestParam(value = "process_code", required = false) String processCode,
			@RequestParam(value = "material_id", required = false) Integer materialId
	) {
		AjaxResult r = new AjaxResult();
		try {
			List<Map<String, Object>> lines = service.getLines(startMonth, endMonth, processCode, materialId);
			List<Map<String, Object>> details = service.getDetails(startMonth, endMonth, processCode, materialId);

			Map<String, Object> data = new java.util.LinkedHashMap<>();
			data.put("lines", lines);
			data.put("details", details);
			r.data = data;
			r.success = true;
		} catch (Exception e) {
			r.success = false;
			r.message = e.getMessage();
		}
		return r;
	}
}
