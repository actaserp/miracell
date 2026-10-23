package mes.app.dashboard;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.app.dashboard.service.DashBoardF1Service;
import mes.domain.model.AjaxResult;

/**
 * 1공장 대시보드 API.
 *
 *   /read   생산·인원·재고   30초
 *   /env    온습도·차압       5초  (가볍다 — env_sensor 행 수만큼 인덱스 탐색)
 *
 * 환경만 따로 뺀 것은 주기가 달라서다. 생산 쿼리를 5초마다 돌릴 이유는 없다.
 *
 * ★ 센서 이름·기준 저장은 여기 두지 않는다.
 *   통합 대시보드와 같은 /api/dashboard/main/env_limit(/save) 를 쓴다 —
 *   저장 경로가 둘이면 두 화면 설정이 언젠가 갈라진다.
 *
 * ★ @ExceptionHandler 필수 — 없으면 SQL 오류에 HTML 에러 페이지가 내려가고
 *   AjaxUtil 이 네이티브 alert 를 띄운다. 벽걸이에서는 아무도 못 끈다.
 * ★ AjaxUtil 은 form-urlencoded. @RequestBody 쓰면 415.
 */
@RestController
@RequestMapping("/api/dashboard/f1")
public class DashBoardF1Controller {

	@Autowired
	private DashBoardF1Service f1Service;

	@GetMapping("/read")
	public AjaxResult read(@RequestParam("spjangcd") String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.data = this.f1Service.getDashboard(spjangcd);
		return result;
	}

	@GetMapping("/env")
	public AjaxResult env(@RequestParam("spjangcd") String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.data = this.f1Service.getEnv(spjangcd);
		return result;
	}

	@ExceptionHandler(Exception.class)
	public AjaxResult handle(Exception e) {
		e.printStackTrace();
		AjaxResult result = new AjaxResult();
		result.success = false;
		result.message = "1공장 대시보드 조회 중 오류가 발생했습니다: " + e.getMessage();
		return result;
	}
}