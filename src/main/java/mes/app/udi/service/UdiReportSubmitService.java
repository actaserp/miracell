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
	 * 월 단위 보고확정.
	 * 해당 기준월의 임시('t') 및 취소('c') 건 전체를 26번으로 등록하고 34번으로 보고확정한다.
	 * (취소된 건은 수정 없이도 바로 재보고할 수 있다. UDI 보고는 월 단위 전체 보고가 원칙.)
	 */
	public SubmitResult submitMonth(String stdMonth, String supplyFlagCode, Integer userId) {
		List<Integer> ids = new java.util.ArrayList<>();
		ids.addAll(this.reportService.getReportIdsByMonth(stdMonth, supplyFlagCode, "t"));
		ids.addAll(this.reportService.getReportIdsByMonth(stdMonth, supplyFlagCode, "c"));
		SubmitResult result = new SubmitResult();
		if (ids.isEmpty()) {
			result.success = false;
			result.message = "[" + stdMonth + "] 보고할 자료가 없습니다.";
			return result;
		}
		return submit(ids, userId);
	}

	/**
	 * 월 단위 보고취소.
	 * 34번(보고 및 취소)을 호출해 해당 기준월의 보고를 취소한다.
	 * 성공 시 그 달의 확정('r') 건들을 취소('c') 상태로 되돌린다.
	 * (취소된 건은 이후 수정 → 재보고 가능)
	 */
	public SubmitResult cancelMonth(String stdMonth, String supplyFlagCode, Integer userId) {
		SubmitResult result = new SubmitResult();

		List<Integer> confirmedIds = this.reportService.getReportIdsByMonth(stdMonth, supplyFlagCode, "r");
		if (confirmedIds.isEmpty()) {
			result.success = false;
			result.message = "[" + stdMonth + "] 취소할 보고확정 자료가 없습니다.";
			return result;
		}

		// 34번 토글 호출 = 취소
		UdiApiClient.Result rep = this.apiClient.reportSupplyMonth(stdMonth);
		if (rep.success) {
			this.reportService.markCanceled(confirmedIds, rep.message, userId);
			result.success = true;
			result.reportedCount = confirmedIds.size();
			result.message = "[" + stdMonth + "] 보고취소 완료: " + rep.message;
		} else if (this.apiClient.isTestMode() && isNoDataToReport(rep)) {
			// 테스트 모드에서는 26번(추가)이 실제 저장을 하지 않아 34번이 "자료 없음"으로
			// 응답한다. 로컬 상태 전환(확정→취소)만 수행해 재보고 흐름을 이어갈 수 있게 한다.
			this.reportService.markCanceled(confirmedIds,
					"[테스트] 보고취소 처리 (실제 취소는 운영 모드에서 수행)", userId);
			result.success = true;
			result.reportedCount = confirmedIds.size();
			result.message = "[" + stdMonth + "] 테스트 보고취소 처리 — 이후 수정·재보고할 수 있습니다.";
		} else {
			result.success = false;
			result.failedCount = confirmedIds.size();
			result.message = "[" + stdMonth + "] 보고취소 실패: " + rep.message;
		}
		return result;
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
			} else if (this.apiClient.isTestMode() && isNoDataToReport(rep)) {
				// 테스트 모드에서는 26번(추가)이 실제 저장을 하지 않으므로
				// 34번은 "등록된 자료 없음"으로 응답한다. 26번이 200(검증통과)이면
				// 데이터 형식이 모두 정상이라는 뜻이므로 검증 성공으로 처리한다.
				this.reportService.markReported(monthIds,
						"[테스트] 입력값 검증 통과 (실제 보고는 운영 모드에서 수행)", userId);
				messages.add("[" + month + "] 테스트 검증 통과 — 입력값이 모두 정상입니다. "
						+ "실제 보고는 운영 모드에서 처리됩니다.");
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

	/**
	 * 34번 응답이 "등록된 보고자료가 없음"인지 판별.
	 * 테스트 모드에서는 26번이 실제 저장을 하지 않아 이 응답이 정상적으로 나온다.
	 */
	private boolean isNoDataToReport(UdiApiClient.Result rep) {
		if (rep == null) return false;
		String m = rep.message == null ? "" : rep.message;
		// 식약처 메시지: "공급내역 보고자료를 작성 후 보고를 진행하여야 합니다."
		return m.contains("보고자료를 작성") || m.contains("보고를 진행");
	}

	private static String str(Object o) {
		return o == null ? null : o.toString();
	}
}
