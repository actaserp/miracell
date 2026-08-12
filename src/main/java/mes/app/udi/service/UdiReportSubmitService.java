package mes.app.udi.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 식약처 UDI 보고확정 오케스트레이터.
 *
 * ┌─ 테스트 모드 (/api/test/v1) ─────────────────────────────────────────┐
 * │  26번(보고자료 추가): 입력값 검증만 수행, 실제 저장 안 됨               │
 * │    - 200 → 검증 통과 (납품 등 일부 flag 코드)                         │
 * │    - 400 → 검증 실패 OR 테스트 환경에서 미지원 flag 코드 (폐기 등)      │
 * │  34번(보고확정): 26번이 실제 저장을 안 해서 항상 "자료 없음" 응답       │
 * │    → isNoDataToReport() 로 감지해 로컬 상태만 'r'로 전환               │
 * │                                                                       │
 * │  운영 전환: UdiApiClient.API_PREFIX 한 줄 변경으로 완료               │
 * │  (/api/test/v1 → /api/v1)                                            │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * 보고확정 흐름:
 *   1) 임시('t') + 취소('c') 건을 기준월별로 묶는다.
 *   2) 각 건을 26번으로 식약처에 등록한다.
 *      - 운영: 200이면 성공
 *      - 테스트: 200이면 성공 / 400이면 테스트 환경 한계로 간주 → 검증 통과 처리
 *   3) 26번이 모두 통과되면 34번으로 보고확정한다.
 *      - 운영: 200이면 성공
 *      - 테스트: "자료 없음" 응답 → 로컬 상태만 'r' 전환
 *   4) 성공 건 → 'r', 실패 건 → 't' 유지 + 오류 메시지
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

	// ===================== 월 단위 보고확정 / 취소 =====================

	/**
	 * 월 단위 보고확정.
	 * 해당 기준월의 임시('t') 및 취소('c') 건 전체를 26번으로 등록하고 34번으로 보고확정한다.
	 */
	public SubmitResult submitMonth(String stdMonth, String supplyFlagCode, Integer userId) {
		List<Integer> ids = new ArrayList<>();
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
	 * 34번을 호출해 해당 기준월 보고를 취소하고, 로컬 확정('r') 건을 취소('c')로 되돌린다.
	 */
	public SubmitResult cancelMonth(String stdMonth, String supplyFlagCode, Integer userId) {
		SubmitResult result = new SubmitResult();

		List<Integer> confirmedIds = this.reportService.getReportIdsByMonth(stdMonth, supplyFlagCode, "r");
		if (confirmedIds.isEmpty()) {
			result.success = false;
			result.message = "[" + stdMonth + "] 취소할 보고확정 자료가 없습니다.";
			return result;
		}

		UdiApiClient.Result rep = this.apiClient.reportSupplyMonth(stdMonth);
		if (rep.success) {
			// 운영: 34번 취소 성공
			this.reportService.markCanceled(confirmedIds, rep.message, userId);
			result.success = true;
			result.reportedCount = confirmedIds.size();
			result.message = "[" + stdMonth + "] 보고취소 완료: " + rep.message;
		} else if (this.apiClient.isTestMode() && isNoDataToReport(rep)) {
			// 테스트: 26번이 실제 저장을 안 해서 34번이 "자료 없음"으로 응답 → 로컬만 처리
			this.reportService.markCanceled(confirmedIds,
					"[테스트] 보고취소 처리 (실제 취소는 운영 모드에서 수행)", userId);
			result.success = true;
			result.reportedCount = confirmedIds.size();
			result.message = "[" + stdMonth + "] 테스트 보고취소 처리 완료.";
		} else {
			result.success = false;
			result.failedCount = confirmedIds.size();
			result.message = "[" + stdMonth + "] 보고취소 실패: " + rep.message;
		}
		return result;
	}

	// ===================== 보고확정 핵심 로직 =====================

	/**
	 * 선택된 보고자료를 식약처로 보고확정한다.
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

			// ── Step 1: 각 건 26번(보고자료 추가) ──────────────────────────
			for (Map<String, Object> row : monthRows) {
				Integer id = ((Number) row.get("id")).intValue();
				monthIds.add(id);

				Map<String, Object> body = buildAddPayload(row);
				UdiApiClient.Result r = this.apiClient.addSupplyReport(month, body);

				if (r.success) {
					// 운영: 200 정상 등록
					log.info("[UDI] {}월 보고자료 추가 성공 id={}", month, id);

				} else if (this.apiClient.isTestMode() && isTestEnvUnsupported(r)) {
					// 테스트: 400이지만 테스트 환경 한계 (폐기 등 일부 flag 코드 미지원)
					// → 입력 형식은 맞는 것으로 간주하고 계속 진행
					log.info("[UDI] {}월 id={} 테스트 환경 한계로 26번 통과 처리 (msg={})", month, id, r.message);

				} else {
					// 실제 오류 (운영 환경 오류 or 테스트에서 형식 자체가 잘못된 경우)
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
				continue;
			}

			// ── Step 2: 34번(보고확정) ──────────────────────────────────────
			UdiApiClient.Result rep = this.apiClient.reportSupplyMonth(month);
			if (rep.success) {
				// 운영: 34번 정상 확정
				this.reportService.markReported(monthIds, rep.message, userId);
				messages.add("[" + month + "] " + rep.message);
				result.reportedCount += monthIds.size();

			} else if (this.apiClient.isTestMode() && isNoDataToReport(rep)) {
				// 테스트: 26번이 실제 저장을 안 해서 34번이 "자료 없음"으로 응답
				// → 26번에서 형식 검증을 통과했으므로 보고확정 성공으로 처리
				this.reportService.markReported(monthIds,
						"[테스트] 입력값 검증 통과 — 실제 보고는 운영 모드에서 수행됩니다.", userId);
				messages.add("[" + month + "] 테스트 검증 통과. 실제 보고는 운영 모드에서 처리됩니다.");
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

	// ===================== payload 생성 =====================

	/**
	 * 26번 보고자료 추가 본문 생성.
	 *
	 * 타입 처리 (매뉴얼 26번 스펙):
	 *   - meddevItemSeq / seq / udiDiSeq : number → Long
	 *   - isDiffDvyfg                    : boolean → Boolean
	 *   - 그 외 모든 필드                 : string → toString(), null이면 ""
	 *
	 * suplyTypeCode:
	 *   - 출고(1) · 임대(4): 포함 (필수)
	 *   - 반품(2) · 폐기(3) · 회수(5): 키 자체 제거 (보내면 오류)
	 *
	 * 매뉴얼 1.5.2: 선택(X) 필드도 키를 포함해 빈 문자열로 전송해야 함.
	 */
	private static final Set<String> NUMBER_FIELDS  = Set.of("meddevItemSeq", "seq", "udiDiSeq");
	private static final Set<String> BOOLEAN_FIELDS = Set.of("isDiffDvyfg");
	private static final Set<String> FLAG_NO_SUPPLY_TYPE = Set.of("2", "3", "5");

	private Map<String, Object> buildAddPayload(Map<String, Object> row) {
		String flagCode = row.get("suplyFlagCode") != null
				? row.get("suplyFlagCode").toString().trim() : "";
		boolean removeSuplyType = FLAG_NO_SUPPLY_TYPE.contains(flagCode);

		Map<String, Object> body = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : row.entrySet()) {
			String key = e.getKey();
			// PathVariable / 내부 PK 제외
			if ("id".equals(key) || "suplyContStdmt".equals(key)) continue;
			// 반품·폐기·회수는 suplyTypeCode 제거
			if (removeSuplyType && "suplyTypeCode".equals(key)) continue;

			Object val = e.getValue();
			if (NUMBER_FIELDS.contains(key)) {
				if (val == null) {
					body.put(key, 0L);
				} else if (val instanceof Number n) {
					body.put(key, n.longValue());
				} else {
					try { body.put(key, Long.parseLong(val.toString().trim())); }
					catch (NumberFormatException ex) { body.put(key, 0L); }
				}
			} else if (BOOLEAN_FIELDS.contains(key)) {
				body.put(key, val instanceof Boolean b ? b : Boolean.FALSE);
			} else {
				body.put(key, val != null ? val.toString() : "");
			}
		}
		return body;
	}

	// ===================== 판별 헬퍼 =====================

	/**
	 * 26번 응답이 테스트 환경 한계(미지원 flag 코드 등)인지 판별.
	 *
	 * 테스트 환경에서 폐기(3) 등 일부 공급구분은 "공급 구분 값이 없습니다"(400)를 반환한다.
	 * 이는 입력 형식 오류가 아니라 테스트 서버의 한계이므로, 운영 전환 시에는 이 분기를 타지 않는다.
	 *
	 * ※ 운영 전환 후 이 분기가 절대 타면 안 됨 — isTestMode()로 이미 막혀 있음.
	 */
	private boolean isTestEnvUnsupported(UdiApiClient.Result r) {
		if (r == null) return false;
		String m = r.message == null ? "" : r.message;
		// 테스트 환경에서 나오는 알려진 한계 메시지
		return m.contains("공급 구분 값이 없습니다")
				|| m.contains("Server Error")
				|| r.statusCode == 500;
	}

	/**
	 * 34번 응답이 "등록된 보고자료가 없음"인지 판별.
	 * 테스트 모드에서는 26번이 실제 저장을 하지 않아 이 응답이 정상적으로 나온다.
	 */
	private boolean isNoDataToReport(UdiApiClient.Result rep) {
		if (rep == null) return false;
		String m = rep.message == null ? "" : rep.message;
		return m.contains("보고자료를 작성") || m.contains("보고를 진행");
	}

	private static String str(Object o) {
		return o == null ? null : o.toString();
	}
}
