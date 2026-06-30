package mes.app.udi.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 식약처 UDI 보고확정 오케스트레이터.
 *
 * 보고확정 흐름(식약처 UDI OpenAPI V3.4):
 *   1) 선택된 임시('t') 보고자료를 기준월별로 묶는다.
 *   2) 각 건을 26번(보고자료 추가)으로 식약처에 등록한다.
 *   3) 한 기준월의 등록이 모두 성공하면 34번(보고 및 취소)으로 그 달 전체를 보고확정한다.
 *   4) 성공한 건은 로컬 상태를 'r'(보고확정)로, 실패는 't' 유지 + 오류 메시지 저장.
 *
 * 외부 HTTP 호출이 포함되므로 DB 트랜잭션과 분리한다(클래스 레벨 @Transactional 미사용).
 * 상태 갱신은 UdiSupplyReportService 의 단건 update 메서드로 수행한다.
 */
@Service
public class UdiReportSubmitService {

	private static final Logger log = LoggerFactory.getLogger(UdiReportSubmitService.class);

	@Autowired
	private UdiApiClient apiClient;

	@Autowired
	private UdiSupplyReportService reportService;

	/** 보고확정 결과 요약 */
	public static class SubmitResult {
		public boolean success;
		public String message;
		public int reportedCount;
		public int failedCount;
	}

	/**
	 * 선택된 보고자료를 식약처로 보고확정한다.
	 * @param ids    보고확정할 보고자료 id 목록
	 * @param userId 처리 사용자 id
	 */
	public SubmitResult submit(List<Integer> ids, Integer userId) {
		SubmitResult result = new SubmitResult();

		List<Map<String, Object>> rows = this.reportService.getReportsByIds(ids);
		if (rows == null || rows.isEmpty()) {
			result.success = false;
			result.message = "보고확정 가능한 임시 상태 자료가 없습니다.";
			return result;
		}

		// 기준월별 그룹핑 (입력 순서 유지)
		Map<String, List<Map<String, Object>>> byMonth = new LinkedHashMap<>();
		for (Map<String, Object> row : rows) {
			String month = str(row.get("suplyContStdmt"));
			byMonth.computeIfAbsent(month, k -> new ArrayList<>()).add(row);
		}

		List<String> messages = new ArrayList<>();

		for (Map.Entry<String, List<Map<String, Object>>> entry : byMonth.entrySet()) {
			String month = entry.getKey();
			List<Map<String, Object>> monthRows = entry.getValue();

			List<Integer> monthIds = new ArrayList<>();
			boolean addFailed = false;
			String firstError = null;

			// 2) 각 건 26번 등록
			for (Map<String, Object> row : monthRows) {
				Integer id = ((Number) row.get("id")).intValue();
				monthIds.add(id);

				Map<String, Object> body = buildAddPayload(row);
				UdiApiClient.Result r = this.apiClient.addSupplyReport(month, body);

				if (!r.success) {
					addFailed = true;
					if (firstError == null) firstError = r.message;
					log.warn("[UDI] {}월 보고자료 추가 실패 id={} msg={}", month, id, r.message);
				}
			}

			if (addFailed) {
				String msg = "[" + month + "] 보고자료 추가 실패: " + firstError;
				this.reportService.markReportFailed(monthIds, msg, userId);
				messages.add(msg);
				result.failedCount += monthIds.size();
				continue; // 등록이 하나라도 실패하면 그 달은 보고확정하지 않음
			}

			// 3) 34번 보고확정
			UdiApiClient.Result rep = this.apiClient.reportSupplyMonth(month);
			if (rep.success) {
				this.reportService.markReported(monthIds, rep.message, userId);
				messages.add("[" + month + "] " + rep.message);
				result.reportedCount += monthIds.size();
			} else {
				String msg = "[" + month + "] 보고확정 실패: " + rep.message;
				this.reportService.markReportFailed(monthIds, msg, userId);
				messages.add(msg);
				result.failedCount += monthIds.size();
			}
		}

		result.success = result.failedCount == 0;
		result.message = String.join("\n", messages);
		return result;
	}

	/**
	 * 26번 보고자료 추가 본문 생성.
	 * getReportsByIds 가 이미 API 필드명으로 alias 했으므로 id/기준월(PathVariable)만 제외하고 복사한다.
	 * null/빈 값은 전송하지 않는다.
	 */
	private Map<String, Object> buildAddPayload(Map<String, Object> row) {
		Map<String, Object> body = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : row.entrySet()) {
			String key = e.getKey();
			Object val = e.getValue();
			if ("id".equals(key) || "suplyContStdmt".equals(key)) continue;
			if (val == null) continue;
			if (val instanceof String s && s.isBlank()) continue;
			body.put(key, val);
		}
		return body;
	}

	private static String str(Object o) {
		return o == null ? null : o.toString();
	}
}
