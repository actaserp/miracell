package mes.app.udi.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 식약처 의료기기통합정보시스템(UDI) OpenAPI 클라이언트.
 * 식약처 UDI OpenAPI 가이드 V3.4 기준.
 *
 *  - 3.   OAUTH2 Access Token 요청   : POST /api/oauth/token            (테스트/운영 공통)
 *  - 26.  공급내역 보고자료 추가       : POST {API_PREFIX}/supply-info/manages/{suplyContStdmt}
 *  - 34.  공급내역 보고 및 취소        : POST {API_PREFIX}/supply-info/manages/report/{suplyContStdmt}
 *
 *  ※ API_PREFIX 는 기본 테스트(/api/test/v1). 운영 전환은 아래 상수에서 한 줄로 변경.
 *
 * ※ 이 클래스의 메서드는 호출될 때만 실제 HTTP 요청을 보낸다.
 *    보고확정(confirm) 흐름에서만 사용되며, 그 외에는 외부 통신이 발생하지 않는다.
 *
 * Access Token은 메모리에 캐싱하고 만료 60초 전에 자동 재발급한다.
 */
@Service
public class UdiApiClient {

	private static final Logger log = LoggerFactory.getLogger(UdiApiClient.class);

	@Autowired
	private RestTemplate restTemplate;

	@Value("${udi.api.base-url}")
	private String baseUrl;

	@Value("${udi.api.client-id}")
	private String clientId;

	@Value("${udi.api.client-secret}")
	private String clientSecret;

	private final ObjectMapper objectMapper = new ObjectMapper();

	// ============================================================
	// 식약처 API 경로 prefix (테스트 / 운영 전환 지점)
	//
	//  ※ 운영(실보고)으로 전환할 때:
	//     아래 'TEST' 줄을 주석 처리하고, 'PROD' 줄의 주석을 해제하세요.
	//     토큰 발급 경로(/api/oauth/token)는 테스트/운영 공통이라 바꾸지 않습니다.
	// ============================================================
	private static final String API_PREFIX = "/api/test/v1";   // ← 테스트 (기본)
	// private static final String API_PREFIX = "/api/v1";     // ← 운영 (실보고)

	// ---- 토큰 캐시 ----
	private volatile String accessToken;
	private volatile long expiresAtMillis = 0L;

	/** 호출 결과 래퍼 */
	public static class Result {
		public boolean success;
		public int statusCode;
		public String message;
		public String rawBody;

		public static Result ok(int code, String msg, String body) {
			Result r = new Result();
			r.success = true; r.statusCode = code; r.message = msg; r.rawBody = body;
			return r;
		}
		public static Result fail(int code, String msg, String body) {
			Result r = new Result();
			r.success = false; r.statusCode = code; r.message = msg; r.rawBody = body;
			return r;
		}
	}

	// ===================== 토큰 =====================

	/** 유효한 Access Token을 반환한다. 만료가 임박했으면 재발급한다. */
	public synchronized String getAccessToken() {
		long now = System.currentTimeMillis();
		if (this.accessToken != null && now < this.expiresAtMillis - 60_000L) {
			return this.accessToken;
		}
		return issueAccessToken();
	}

	/** Access Token 신규 발급 (3. OAUTH2 Access Token 요청) */
	private synchronized String issueAccessToken() {
		String url = baseUrl + "/api/oauth/token";

		String credential = clientId + ":" + clientSecret;
		String basic = "Basic " + Base64.getEncoder()
				.encodeToString(credential.getBytes(StandardCharsets.UTF_8));

		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.AUTHORIZATION, basic);
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));

		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "client_credentials");

		HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);

		try {
			ResponseEntity<String> res = this.restTemplate.exchange(
					url, HttpMethod.POST, entity, String.class);

			JsonNode body = this.objectMapper.readTree(res.getBody());
			String token = body.path("access_token").asText(null);
			long expiresIn = body.path("expires_in").asLong(86400L); // 초, 기본 1일

			if (token == null || token.isBlank()) {
				throw new IllegalStateException("access_token 응답이 비어 있습니다.");
			}
			this.accessToken = token;
			this.expiresAtMillis = System.currentTimeMillis() + (expiresIn * 1000L);
			log.info("[UDI] Access Token 발급 완료 (expiresIn={}s)", expiresIn);
			return token;

		} catch (Exception ex) {
			log.error("[UDI] Access Token 발급 실패: {}", ex.getMessage());
			throw new RuntimeException("UDI Access Token 발급 실패: " + ex.getMessage(), ex);
		}
	}

	/** 토큰 캐시 무효화 (401 발생 시) */
	private synchronized void invalidateToken() {
		this.accessToken = null;
		this.expiresAtMillis = 0L;
	}

	/**
	 * 연동 점검용 토큰 발급 테스트.
	 * Access Token 만 발급해보고 결과를 반환한다. 보고(26/34) 호출은 하지 않으므로 안전하다.
	 */
	public Result ping() {
		try {
			String token = issueAccessToken();
			long remainSec = (this.expiresAtMillis - System.currentTimeMillis()) / 1000L;
			String masked = token.length() > 8 ? token.substring(0, 8) + "..." : "***";
			return Result.ok(200,
					"토큰 발급 성공 (token=" + masked + ", 만료까지 약 " + remainSec + "초)", null);
		} catch (Exception ex) {
			return Result.fail(0, "토큰 발급 실패: " + ex.getMessage(), null);
		}
	}

	// ===================== 24. 고유식별자(UDI-DI) 품목 정보 조회 =====================

	/**
	 * 고유식별자(UDI-DI) 품목 정보 조회 (24번).
	 * UDI-DI 코드(GTIN 등)로 공급내역 보고에 필요한 식별자
	 * (meddevItemSeq/seq/udiDiSeq)와 품목정보를 조회한다.
	 *
	 * GET /api/v1/supply-info/udidi-product?udiDiCode={코드}
	 * udiDiCode 는 URL 인코딩하되 '+' → %2B, '&' → %26 은 별도 치환한다(가이드 24.1).
	 *
	 * @return 조회 결과(items 배열의 첫 항목)를 Map 으로. 없으면 null.
	 */
	public Map<String, Object> getUdiDiProduct(String udiDiCode) {
		if (udiDiCode == null || udiDiCode.isBlank()) {
			throw new IllegalArgumentException("UDI-DI 코드가 비어 있습니다.");
		}

		// 가이드 규칙: '+' → %2B, '&' → %26 선치환 후 표준 URL 인코딩
		String pre = udiDiCode.replace("+", "%2B").replace("&", "%26");
		String encoded = org.springframework.web.util.UriUtils
				.encodeQueryParam(pre, StandardCharsets.UTF_8);

		String url = baseUrl + API_PREFIX + "/supply-info/udidi-product?udiDiCode=" + encoded;

		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + getAccessToken());
		headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));

		HttpEntity<Void> entity = new HttpEntity<>(headers);

		try {
			ResponseEntity<String> res = this.restTemplate.exchange(
					url, HttpMethod.GET, entity, String.class);

			JsonNode body = this.objectMapper.readTree(res.getBody());
			JsonNode items = body.path("items");
			if (!items.isArray() || items.isEmpty()) {
				return null;
			}
			// 첫 항목을 Map 으로 변환
			@SuppressWarnings("unchecked")
			Map<String, Object> first = this.objectMapper.convertValue(items.get(0), Map.class);
			return first;

		} catch (org.springframework.web.client.HttpStatusCodeException ex) {
			int code = ex.getStatusCode().value();
			if (code == 401) {
				// 토큰 만료 → 1회 재발급 후 재시도
				invalidateToken();
				headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + getAccessToken());
				try {
					ResponseEntity<String> res2 = this.restTemplate.exchange(
							url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
					JsonNode body2 = this.objectMapper.readTree(res2.getBody());
					JsonNode items2 = body2.path("items");
					if (!items2.isArray() || items2.isEmpty()) return null;
					@SuppressWarnings("unchecked")
					Map<String, Object> first2 = this.objectMapper.convertValue(items2.get(0), Map.class);
					return first2;
				} catch (Exception ex2) {
					throw new RuntimeException("UDI-DI 품목정보 조회 실패: " + ex2.getMessage(), ex2);
				}
			}
			String msg = extractMessage(ex.getResponseBodyAsString(),
					"UDI-DI 품목정보 조회 실패 (HTTP " + code + ")");
			throw new RuntimeException(msg, ex);

		} catch (Exception ex) {
			throw new RuntimeException("UDI-DI 품목정보 조회 통신 오류: " + ex.getMessage(), ex);
		}
	}

	// ===================== 26. 공급내역 보고자료 추가 =====================

	/**
	 * 공급내역 보고자료 추가 (26번).
	 * @param stdMonth 보고 기준월(YYYYMM, PathVariable)
	 * @param body     보고자료 JSON 본문 (suplyFlagCode 등)
	 */
	public Result addSupplyReport(String stdMonth, Map<String, Object> body) {
		String path = API_PREFIX + "/supply-info/manages/" + stdMonth;
		return postJson(path, body, true);
	}

	// ===================== 34. 공급내역 보고 및 취소 =====================

	/**
	 * 공급내역 보고 및 재보고 처리 (34번).
	 * 해당 기준월에 등록(추가)된 보고자료 전체를 식약처로 '보고확정'한다.
	 * @param stdMonth 보고 기준월(YYYYMM, PathVariable)
	 */
	public Result reportSupplyMonth(String stdMonth) {
		String path = API_PREFIX + "/supply-info/manages/report/" + stdMonth;
		return postJson(path, null, true);
	}

	// ===================== 공통 POST =====================

	/**
	 * Bearer 인증 JSON POST 공통 처리.
	 * 401 응답 시 토큰을 재발급하여 1회 재시도한다.
	 */
	private Result postJson(String path, Map<String, Object> body, boolean retryOn401) {
		String url = UriComponentsBuilder.fromHttpUrl(baseUrl + path).toUriString();

		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + getAccessToken());
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));

		HttpEntity<Object> entity = new HttpEntity<>(body, headers);

		try {
			ResponseEntity<String> res = this.restTemplate.exchange(
					url, HttpMethod.POST, entity, String.class);
			int code = res.getStatusCode().value();
			String msg = extractMessage(res.getBody(), "정상 처리되었습니다.");
			log.info("[UDI] POST {} -> {} {}", path, code, msg);
			return Result.ok(code, msg, res.getBody());

		} catch (org.springframework.web.client.HttpStatusCodeException ex) {
			int code = ex.getStatusCode().value();
			String resp = ex.getResponseBodyAsString();

			if (code == 401 && retryOn401) {
				log.warn("[UDI] 401 수신 → 토큰 재발급 후 재시도");
				invalidateToken();
				return postJson(path, body, false);
			}
			String msg = extractMessage(resp, "요청 처리에 실패했습니다. (HTTP " + code + ")");
			log.error("[UDI] POST {} 실패 -> {} {}", path, code, msg);
			return Result.fail(code, msg, resp);

		} catch (Exception ex) {
			log.error("[UDI] POST {} 통신 오류: {}", path, ex.getMessage());
			return Result.fail(0, "통신 오류: " + ex.getMessage(), null);
		}
	}

	/** 응답 본문(JSON)에서 msg/message 필드를 추출한다. 없으면 기본값. */
	private String extractMessage(String responseBody, String defaultMsg) {
		if (responseBody == null || responseBody.isBlank()) return defaultMsg;
		try {
			JsonNode node = this.objectMapper.readTree(responseBody);
			if (node.hasNonNull("msg")) return node.get("msg").asText();
			if (node.hasNonNull("message")) return node.get("message").asText();
		} catch (Exception ignore) {
			// JSON 이 아니면 기본 메시지
		}
		return defaultMsg;
	}
}
