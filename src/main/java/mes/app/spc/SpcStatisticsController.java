package mes.app.spc;

import lombok.extern.slf4j.Slf4j;
import mes.app.spc.Service.SpcStatisticsService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/spc/SPCStatistics")
public class SpcStatisticsController {
	@Autowired
	SpcStatisticsService spcStatisticsService;

	// ★ 데이터 소스는 멸균 배치 첨부(attach_file)에서 파일 경로를 얻어 읽는다.
	//   기존 하드코딩 폴더(C:\temp\mes21\Reflow\PV)는 제거됨.

	@GetMapping("/measureCodes")
	public AjaxResult getMeasureCodes(@RequestParam String process_code) {
		AjaxResult r = new AjaxResult();
		try {
			// tb_spc_std01에서 process_code에 해당하는 measure_code/distinct
			List<Map<String, Object>> rows = spcStatisticsService.getMeasureCodes(process_code);
			r.success = true;
			r.data = rows; // 예: [{value:"TEMP_CH", text:"챔버온도"}, ...]
		} catch (Exception e) {
			r.success = false;
			r.message = e.getMessage();
		}
		return r;
	}

	/** SPC 관리기준이 등록된 공정 목록 (통계 화면 공정 콤보용) */
	@GetMapping("/processes")
	public AjaxResult getProcesses() {
		AjaxResult r = new AjaxResult();
		try {
			r.data = spcStatisticsService.getSpcProcesses();
			r.success = true;
		} catch (Exception e) {
			r.success = false;
			r.message = e.getMessage();
		}
		return r;
	}

	@GetMapping("/spcList")
	public AjaxResult getSPCList(
		@RequestParam String date_from,
		@RequestParam String date_to,
		@RequestParam(value="item_name", required=false) String item_name,
		@RequestParam(value="process_cd") String processCode,
		@RequestParam(value="measure_code") String measureCode,
		@RequestParam(value="recipe", required=false) String recipe,
		@RequestParam(required=false) String spjangcd
	) {
		AjaxResult ar = new AjaxResult();
		try {
			ar.data = spcStatisticsService.getSpcListResult(
				spjangcd, date_from, date_to, item_name, processCode, measureCode, recipe
			);
			ar.success = true;
			ar.message = "";
		} catch (Exception e) {
			ar.success = false;
			ar.message = e.getMessage();
			ar.code = "ERR_SPC_LIST";
		}
		return ar;
	}

}
