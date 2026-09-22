package mes.app.maintenance;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.app.maintenance.service.CalibService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;

/** 교정계획점검표 (F706-3) */
@RestController
@RequestMapping("/api/definition/calib")
public class CalibController {

	@Autowired
	private CalibService calibService;

	/** 한 해의 점검표 — 계측기 + 그 해의 결과 */
	@GetMapping("/plan")
	public AjaxResult plan(
			@RequestParam("year") int year,
			@RequestParam(value = "factory_id", required = false) Integer factoryId,
			@RequestParam(value = "keyword", required = false) String keyword,
			@RequestParam(value = "include_unused", required = false) String includeUnused,
			@RequestParam("spjangcd") String spjangcd) {
		AjaxResult r = new AjaxResult();
		r.data = this.calibService.getPlan(year, factoryId, keyword, "Y".equals(includeUnused), spjangcd);
		return r;
	}

	@PostMapping("/instrument_save")
	public AjaxResult instrumentSave(
			@RequestParam(value = "id", required = false) Integer id,
			@RequestParam(value = "mgmt_no", required = false) String mgmtNo,
			@RequestParam(value = "device_no", required = false) String deviceNo,
			@RequestParam("name") String name,
			@RequestParam(value = "cycle_month", required = false) Integer cycleMonth,
			@RequestParam(value = "base_ym", required = false) String baseYm,
			@RequestParam(value = "factory_id", required = false) Integer factoryId,
			@RequestParam(value = "use_yn", required = false) String useYn,
			@RequestParam(value = "sort_no", required = false) Integer sortNo,
			@RequestParam(value = "description", required = false) String description,
			@RequestParam("spjangcd") String spjangcd,
			Authentication auth) {
		return this.calibService.saveInstrument(id, mgmtNo, deviceNo, name, cycleMonth, baseYm,
				factoryId, useYn, sortNo, description, (User) auth.getPrincipal(), spjangcd);
	}

	@PostMapping("/instrument_delete")
	public AjaxResult instrumentDelete(@RequestParam("id") int id, Authentication auth) {
		return this.calibService.deleteInstrument(id, (User) auth.getPrincipal());
	}

	@PostMapping("/record_save")
	public AjaxResult recordSave(
			@RequestParam("instrument_id") int instrumentId,
			@RequestParam("plan_ym") String planYm,
			@RequestParam(value = "done_date", required = false) String doneDate,
			@RequestParam(value = "result", required = false) String result,
			@RequestParam(value = "agency", required = false) String agency,
			@RequestParam(value = "cert_no", required = false) String certNo,
			@RequestParam(value = "description", required = false) String description,
			@RequestParam("spjangcd") String spjangcd,
			Authentication auth) {
		return this.calibService.saveRecord(instrumentId, planYm, doneDate, result, agency, certNo,
				description, (User) auth.getPrincipal(), spjangcd);
	}

	@PostMapping("/record_delete")
	public AjaxResult recordDelete(@RequestParam("id") int id, Authentication auth) {
		return this.calibService.deleteRecord(id, (User) auth.getPrincipal());
	}

	/** SQL 오류가 HTML 에러 페이지로 내려가면 화면이 JSON 을 못 읽는다 — 메시지로 돌려준다 */
	@ExceptionHandler(Exception.class)
	public AjaxResult handle(Exception e) {
		e.printStackTrace();
		AjaxResult r = new AjaxResult();
		r.success = false;
		r.message = "처리 중 오류가 발생했습니다: " + e.getMessage();
		return r;
	}
}