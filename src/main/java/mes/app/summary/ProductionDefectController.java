package mes.app.summary;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.app.summary.service.ProductionDefectService;
import mes.domain.model.AjaxResult;

/**
 * 생산 실적 부적합 현황 — 부적합유형 × 일자.
 *
 * ★ 워크센터가 아니라 공정으로 거른다.
 *   defect_regist 는 Process_id 를 갖고 WorkCenter_id 는 없다.
 *   세척·멸균·M-CELL 검사는 work_center 를 거치지도 않는다.
 */
@RestController
@RequestMapping("/api/summary/production_defect_portion")
public class ProductionDefectController {

	@Autowired
	ProductionDefectService productionDefectService;

	/** 부적합유형 × 발생일 */
	@GetMapping("/read")
	public AjaxResult getDefectList(
			@RequestParam(value = "date_from", required = false) String dateFrom,
			@RequestParam(value = "date_to", required = false) String dateTo,
			@RequestParam(value = "proc_code", required = false) String procCode) {

		AjaxResult result = new AjaxResult();
		result.data = this.productionDefectService.getList(dateFrom, dateTo, procCode);
		return result;
	}

	/** 일자별 생산량 — 불량률의 분모 */
	@GetMapping("/output")
	public AjaxResult getOutputList(
			@RequestParam(value = "date_from", required = false) String dateFrom,
			@RequestParam(value = "date_to", required = false) String dateTo,
			@RequestParam(value = "proc_code", required = false) String procCode) {

		AjaxResult result = new AjaxResult();
		result.data = this.productionDefectService.getOutputList(dateFrom, dateTo, procCode);
		return result;
	}

	/** 공정 콤보 */
	@GetMapping("/process_combo")
	public AjaxResult getProcessCombo() {
		AjaxResult result = new AjaxResult();
		result.data = this.productionDefectService.getProcessCombo();
		return result;
	}

	/**
	 * SQL 오류까지 AjaxResult 로 변환한다.
	 * 안 잡으면 스프링이 HTML 에러 페이지를 내리고
	 * AjaxUtil 이 「페이지를 찾을 수 없습니다」 네이티브 alert 를 띄운다.
	 */
	@ExceptionHandler(Exception.class)
	public AjaxResult handle(Exception e) {
		AjaxResult result = new AjaxResult();
		result.success = false;
		result.message = e.getMessage();
		e.printStackTrace();
		return result;
	}
}