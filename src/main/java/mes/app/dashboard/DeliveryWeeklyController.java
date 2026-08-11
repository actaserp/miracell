package mes.app.dashboard;

import lombok.extern.slf4j.Slf4j;
import mes.app.dashboard.service.DeliveryWeeklyService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /api/dashboard/delivery_weekly/*  — 주간 납품현황
 *
 * ★ sales 가 아니라 dashboard 에 둔다. 이 화면은 등록/수정이 없는 조회 전용이고,
 *   집계 방식이 영업의 개별 화면들과 다르다(요일 분해·전주 대비·추이).
 *   sales 에 두면 수주별납품현황과 비슷해 보여 한쪽 쿼리를 다른 쪽에 복사하게 된다.
 *
 * ★ spjangcd 를 받지 않는다. 단일 사업장이다.
 */
@Slf4j
@RestController
@RequestMapping("/api/dashboard/delivery_weekly")
public class DeliveryWeeklyController {

	@Autowired
	DeliveryWeeklyService deliveryWeeklyService;

	/**
	 * 주간 라인 + 지연 + 전주 대비.
	 *
	 * 화면이 lines 하나로 요일 카드·차트·랭킹·미납을 전부 만든다.
	 * 집계를 서버에서 또 내려주면 두 벌이 되어 어긋난다.
	 */
	@GetMapping("/dashboard")
	public AjaxResult getDashboard(
			@RequestParam(value = "start") String startStr,
			@RequestParam(value = "end") String endStr,
			@RequestParam(value = "factory_id", required = false) Integer factoryId) {

		LocalDate start = LocalDate.parse(startStr);
		LocalDate end   = LocalDate.parse(endStr);

		List<Map<String, Object>> lines = deliveryWeeklyService.getLines(start, end, factoryId);
		Map<String, Object> prevWeek    = deliveryWeeklyService.getPrevWeek(start, end, factoryId);

        /* 지연 건은 lines 안에 is_late='Y' 로 함께 온다.
           따로 내려주면 화면에서 같은 건이 두 번 세어진다. */
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("lines", lines);
		data.put("prevWeek", prevWeek);

		AjaxResult result = new AjaxResult();
		result.data = data;
		return result;
	}

	/** 최근 N 주 추이. 화면이 따로 부른다(주차 이동 시에도 재계산이 싸다) */
	@GetMapping("/trend")
	public AjaxResult getTrend(
			@RequestParam(value = "end") String endStr,
			@RequestParam(value = "weeks", required = false, defaultValue = "8") int weeks,
			@RequestParam(value = "factory_id", required = false) Integer factoryId) {

		LocalDate end = LocalDate.parse(endStr);
		if (weeks < 1)  weeks = 1;
		if (weeks > 52) weeks = 52;   // 화면 실수로 큰 값이 와도 통째로 스캔하지 않게

		AjaxResult result = new AjaxResult();
		result.data = deliveryWeeklyService.getTrend(end, weeks, factoryId);
		return result;
	}
}