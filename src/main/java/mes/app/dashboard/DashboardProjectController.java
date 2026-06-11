package mes.app.dashboard;

import java.util.List;
import java.util.Map;

import mes.app.dashboard.service.DashboardProjectService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.domain.model.AjaxResult;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardProjectController {

	@Autowired
	private DashboardProjectService dashboardService;

	/** 프로젝트 목록 + 진척률 */
	@GetMapping("/projects")
	public AjaxResult getProjects(
			@RequestParam(value = "spjangcd", required = false) String spjangcd) {

		List<Map<String, Object>> items = this.dashboardService.getProjectList(spjangcd);
		AjaxResult result = new AjaxResult();
		result.data = items;
		result.success = true;
		return result;
	}

	/** 프로젝트 상세 - 부품/반제품/공정 전개 */
	@GetMapping("/project_detail")
	public AjaxResult getProjectDetail(
			@RequestParam("projno") String projno) {

		List<Map<String, Object>> items = this.dashboardService.getProjectDetail(projno);
		AjaxResult result = new AjaxResult();
		result.data = items;
		result.success = true;
		return result;
	}

	/** 현재 작업중 현황 */
	@GetMapping("/working_now")
	public AjaxResult getWorkingNow(
			@RequestParam("projno") String projno) {

		List<Map<String, Object>> items = this.dashboardService.getWorkingNow(projno);
		AjaxResult result = new AjaxResult();
		result.data = items;
		result.success = true;
		return result;
	}
}