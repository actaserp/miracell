package mes.app.dashboard;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;

import mes.domain.entity.User;

import mes.app.dashboard.service.DashBoardMainService;
import mes.domain.model.AjaxResult;

/**
 * 통합 생산 대시보드 API.
 *
 * 엔드포인트가 하나뿐인 것이 의도다 — 패널마다 나누면 폴링이 겹칠 때
 * KPI 와 목록이 서로 다른 시점을 말한다.
 *
 * ★ @ExceptionHandler 필수. 없으면 SQL 오류에 HTML 에러 페이지가 내려가고
 *   AjaxUtil 이 「페이지를 찾을 수 없습니다」 네이티브 alert 를 띄운다.
 *   대시보드는 벽에 띄워두는 화면이라 alert 가 뜨면 아무도 못 끈다.
 */
@RestController
@RequestMapping("/api/dashboard/main")
public class DashBoardMainController {

	@Autowired
	private DashBoardMainService dashBoardMainService;

	@GetMapping("/read")
	public AjaxResult read(@RequestParam("spjangcd") String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.data = this.dashBoardMainService.getDashboard(spjangcd);
		return result;
	}

	/** 클린룸 관리기준 조회 (설정 모달) */
	@GetMapping("/env_limit")
	public AjaxResult envLimit(@RequestParam("spjangcd") String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.data = this.dashBoardMainService.getEnvZones(spjangcd);
		return result;
	}

	/**
	 * 클린룸 관리기준 저장.
	 *
	 * ★ AjaxUtil 은 form-urlencoded 로 보낸다. @RequestBody(JSON) 을 쓰면 415.
	 *   존 하나씩 보낸다 — 4개를 한 번에 묶으면 파라미터 이름이 24개가 되고,
	 *   한 존만 고쳐도 전부 덮어쓰게 된다.
	 */
	@PostMapping("/env_limit/save")
	public AjaxResult saveEnvLimit(
			@RequestParam("spjangcd") String spjangcd,
			@RequestParam("zone_code") String zoneCode,
			@RequestParam(value = "temp_min", required = false) Double tempMin,
			@RequestParam(value = "temp_max", required = false) Double tempMax,
			@RequestParam(value = "humi_min", required = false) Double humiMin,
			@RequestParam(value = "humi_max", required = false) Double humiMax,
			@RequestParam(value = "press_min", required = false) Double pressMin,
			@RequestParam(value = "press_max", required = false) Double pressMax,
			Authentication auth) {

		AjaxResult result = new AjaxResult();

		Integer userId = null;
		if (auth != null && auth.getPrincipal() instanceof User) {
			userId = ((User) auth.getPrincipal()).getId();
		}

		String err = this.dashBoardMainService.saveEnvLimit(
				spjangcd, zoneCode, tempMin, tempMax, humiMin, humiMax, pressMin, pressMax, userId);

		if (err != null) {
			result.success = false;
			result.message = err;
			return result;
		}

		result.success = true;
		result.message = "저장하였습니다.";
		return result;
	}

	@ExceptionHandler(Exception.class)
	public AjaxResult handle(Exception e) {
		e.printStackTrace();
		AjaxResult result = new AjaxResult();
		result.success = false;
		result.message = "대시보드 조회 중 오류가 발생했습니다: " + e.getMessage();
		return result;
	}
}