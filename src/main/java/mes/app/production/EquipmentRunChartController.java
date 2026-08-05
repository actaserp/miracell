package mes.app.production;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.app.production.service.EquipmentRunChartService;
import mes.domain.entity.EquRun;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.repository.EquRunRepository;

/**
 * 설비 가동현황 API.
 *
 * ★ 비가동(stop) 파생을 걷어냈다.
 *   이전 구현은 「이번 구간 끝 ~ 다음 구간 시작」의 빈틈마다 stop 행을 만들어
 *   목록에 끼워 넣었다. 문제가 셋이었다.
 *     - 생산에 중지 개념이 없다. 빈틈은 사유를 물을 대상이 아니다
 *       (점심시간·퇴근 후·그날 안 돌린 것이 전부 같은 빈칸)
 *     - 겹치는 구간에서 빈틈이 음수가 되어 GapTime 이 -60분 같은 유령이 나온다
 *     - uptime 의 RunState 를 'run' 으로 덮어써서 상태 칼럼이 무의미했다
 *   → 가동 구간만 있는 그대로 내리고, 가동률은 서비스의 union 집계가 낸다.
 *
 * ★ 그룹핑도 없앴다. 이전에는 설비명(Name)으로 묶어서 동명 설비가 섞였다.
 *   정렬은 SQL 이 Name, StartDate 로 하고, 화면 병합은 Equipment_id 로 한다.
 *
 * ★ @ExceptionHandler 필수 — 없으면 SQL 오류에 HTML 에러 페이지가 내려가고
 *   AjaxUtil 이 「페이지를 찾을 수 없습니다」 네이티브 alert 를 띄운다.
 */
@RestController
@RequestMapping("/api/production/equipment_run_chart")
public class EquipmentRunChartController {

	@Autowired
	EquipmentRunChartService equipmentRunChartService;

	@Autowired
	EquRunRepository equRunRepository;

	/**
	 * 목록 + 설비별 요약.
	 *
	 * 둘을 한 번에 내린다 — 따로 부르면 그 사이 갱신으로
	 * 목록의 구간과 요약의 가동률이 서로 다른 시점을 말하게 된다.
	 */
	@GetMapping("/read")
	public AjaxResult getEquipmentRunChart(
			@RequestParam(value = "date_from") String dateFrom,
			@RequestParam(value = "date_to") String dateTo,
			@RequestParam(value = "equipment_id", required = false) Integer equipmentId,
			@RequestParam String spjangcd) {

		List<Map<String, Object>> rows =
				this.equipmentRunChartService.getEquipmentRunChart(dateFrom, dateTo, equipmentId, spjangcd);
		List<Map<String, Object>> summary =
				this.equipmentRunChartService.getEquipmentSummary(dateFrom, dateTo, equipmentId, spjangcd);

		Map<String, Object> data = new HashMap<>();
		data.put("rows", rows);
		data.put("summary", summary);

		AjaxResult result = new AjaxResult();
		result.data = data;
		return result;
	}

	/**
	 * 시각 수정.
	 *
	 * ★ 신규 등록 경로를 열지 않는다.
	 *   equ_run 은 생산 시작·완료가 만든다. 화면에서 새로 만들면 입력 경로가 둘이 되고,
	 *   어느 것이 실제 가동인지 가릴 방법이 없어진다(출처 컬럼도 없다).
	 *   여기서 할 수 있는 것은 「시스템이 남긴 구간의 시각을 바로잡는 것」뿐이다.
	 *   작업일보가 실적의 작업자·설비·시각만 수정하게 한 것과 같은 규칙이다.
	 *
	 * ★ 겹침은 차단하지 않는다. 겹침이 정상인 설비가 있다(세척기).
	 *   막으면 그 실적을 영영 고칠 수 없다. 겹치면 message 로 알리고 저장은 한다.
	 *
	 * ★ 설비는 바꾸지 않는다. 다른 설비로 옮기는 건 시각 교정이 아니라
	 *   「이 실적은 다른 기계에서 났다」는 다른 주장이고, 그건 실적 쪽에서 고쳐야 한다.
	 */
	@PostMapping("/addData")
	public AjaxResult saveTime(
			@RequestParam(value = "id") Integer id,
			@RequestParam(value = "spjangcd") String spjangcd,
			@RequestParam(value = "start_date", required = false) String startDateStr,
			@RequestParam(value = "StartTime", required = false) String startTime,
			@RequestParam(value = "end_date", required = false) String endDateStr,
			@RequestParam(value = "EndTime", required = false) String endTime,
			@RequestParam(value = "Description", required = false) String description,
			HttpServletRequest request,
			Authentication auth) {

		AjaxResult result = new AjaxResult();

		if (id == null) {
			result.success = false;
			result.message = "가동 구간은 생산 시작·완료 시 자동으로 만들어집니다. 여기서는 시각만 수정할 수 있습니다.";
			return result;
		}

		Map<String, Object> before = this.equipmentRunChartService.getRunById(id);
		if (before == null) {
			result.success = false;
			result.message = "대상을 찾을 수 없습니다. 목록을 새로고침해 주세요.";
			return result;
		}

		if (startDateStr == null || startDateStr.isEmpty() || startTime == null || startTime.isEmpty()) {
			result.success = false;
			result.message = "시작 일자와 시간을 입력해 주세요.";
			return result;
		}

		Timestamp startDate = Timestamp.valueOf(startDateStr + " " + startTime + ":00");

		/* 종료가 비면 「아직 진행중」으로 되돌리는 것이다. 시작만 남기고 닫지 않는다.
		 * 잘못 닫힌 구간을 되돌릴 수 있어야 하므로 허용한다. */
		Timestamp endDate = null;
		boolean hasEnd = (endDateStr != null && !endDateStr.isEmpty()
				&& endTime != null && !endTime.isEmpty());
		if (hasEnd) {
			endDate = Timestamp.valueOf(endDateStr + " " + endTime + ":00");
			if (!endDate.after(startDate)) {
				result.success = false;
				result.message = "종료가 시작보다 빠르거나 같습니다.";
				return result;
			}
		}

		Integer equipmentId = (Integer) before.get("Equipment_id");

		/* 겹침은 경고만. 차단하지 않는다 */
		String warn = "";
		if (hasEnd && equipmentId != null) {
			List<Map<String, Object>> overlaps =
					this.equipmentRunChartService.getOverlaps(startDate, endDate, equipmentId, id, spjangcd);
			if (!overlaps.isEmpty()) {
				warn = " (같은 설비에 겹치는 구간 " + overlaps.size() + "건이 있습니다."
						+ " 가동시간은 겹친 부분을 한 번만 셉니다)";
			}
		}

		User user = (User) auth.getPrincipal();

		EquRun er = this.equRunRepository.getEquRunById(id);
		er.setStartDate(startDate);
		er.setEndDate(endDate);
		er.setRunState(hasEnd ? "complete" : "run");
		if (description != null) {
			er.setDescription(description);
		}
		er.set_audit(user);
		this.equRunRepository.save(er);

		result.success = true;
		result.message = "저장하였습니다." + warn;
		result.data = er.getId();
		return result;
	}

	/**
	 * 삭제.
	 *
	 * 시작만 되고 닫히지 않은 유령 구간(작업 취소 등)을 지우는 용도다.
	 * 정상 구간을 지우면 그만큼 가동시간이 사라지므로 화면에서 한 번 더 묻는다.
	 */
	@PostMapping("/delData")
	public AjaxResult deleteEquipmentRunChart(@RequestParam("id") Integer id) {
		AjaxResult result = new AjaxResult();
		this.equRunRepository.deleteById(id);
		result.success = true;
		result.message = "삭제하였습니다.";
		return result;
	}

	@ExceptionHandler(Exception.class)
	public AjaxResult handle(Exception e) {
		e.printStackTrace();
		AjaxResult result = new AjaxResult();
		result.success = false;
		result.message = "처리 중 오류가 발생했습니다: " + e.getMessage();
		return result;
	}
}