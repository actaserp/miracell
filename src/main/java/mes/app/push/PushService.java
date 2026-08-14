package mes.app.push;

import lombok.extern.slf4j.Slf4j;
import mes.domain.entity.Notification;
import mes.domain.services.SqlRunner;
import nl.martijndwars.webpush.Subscription;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.security.Security;
import java.util.List;
import java.util.Map;

/**
 * 웹 푸시 발송.
 *
 * <p>SSE 와 짝이 되는 두 번째 출구다. 화면이 켜져 있으면 SSE 토스트,
 * 꺼져 있으면 OS 알림. 같은 notification 행에서 나온다.</p>
 *
 * <p>★ 발송은 반드시 비동기다. FCM 왕복이 외부 HTTP 라서
 * 동기로 두면 NotificationService.save 의 트랜잭션이 같이 늘어진다.
 * 그리고 푸시 실패가 업무 트랜잭션을 깨서는 안 된다.</p>
 */
@Slf4j
@Service
public class PushService {

    @Autowired
    private SqlRunner sqlRunner;

    @Value("${push.public-key:}")
    private String publicKey;

    @Value("${push.private-key:}")
    private String privateKey;

    @Value("${push.subject:mailto:admin@miracell.co.kr}")
    private String subject;

    /** 알림 클릭 시 기본 이동 경로 */
    @Value("${push.base-url:/}")
    private String baseUrl;

    private nl.martijndwars.webpush.PushService client;
    private boolean enabled = false;

    @PostConstruct
    void init() {
        if (publicKey == null || publicKey.isBlank()
                || privateKey == null || privateKey.isBlank()) {
            log.warn("[push] VAPID 키 미설정 — 웹 푸시 비활성. application.yml 의 push.public-key / push.private-key 확인");
            return;
        }
        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            client = new nl.martijndwars.webpush.PushService(publicKey, privateKey, subject);
            enabled = true;
            log.info("[push] 웹 푸시 활성화");
        } catch (Exception e) {
            log.error("[push] 초기화 실패 — 웹 푸시 비활성", e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getPublicKey() {
        return enabled ? publicKey : null;
    }

    // ────────────────────────────────────────────────────────────
    //  구독 관리
    // ────────────────────────────────────────────────────────────

    /**
     * 구독 등록. 같은 endpoint 가 오면 갱신한다.
     * 태블릿을 다른 계정으로 다시 로그인하면 user_id 가 그 계정으로 넘어간다.
     */
    public void save(String userId, String endpoint, String p256dh, String auth,
                     String userAgent, String spjangcd) {

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("u", userId);
        p.addValue("e", endpoint);
        p.addValue("p", p256dh);
        p.addValue("a", auth);
        p.addValue("ua", userAgent == null ? "" : userAgent);
        p.addValue("sp", spjangcd == null ? "ZZ" : spjangcd);

        String sql = """
            INSERT INTO push_sub (user_id, endpoint, p256dh, auth, user_agent, spjangcd, use_yn)
            VALUES (:u, :e, :p, :a, :ua, :sp, 'Y')
            ON CONFLICT (md5(endpoint)) DO UPDATE SET
                  user_id    = EXCLUDED.user_id
                , p256dh     = EXCLUDED.p256dh
                , auth       = EXCLUDED.auth
                , user_agent = EXCLUDED.user_agent
                , spjangcd   = EXCLUDED.spjangcd
                , use_yn     = 'Y'
                , fail_count = 0
            """;

        this.sqlRunner.execute(sql, p);
    }

    /** 사용자가 이 기기 알림을 껐을 때 */
    public void disable(String endpoint) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("e", endpoint);
        this.sqlRunner.execute(
                "UPDATE push_sub SET use_yn = 'N' WHERE md5(endpoint) = md5(:e)", p);
    }

    // ────────────────────────────────────────────────────────────
    //  발송
    // ────────────────────────────────────────────────────────────

    /**
     * 알림 1건을 수신자의 모든 기기로 발송.
     * SseService.sendNotification / sendComment 에서 호출된다.
     */
    @Async
    public void sendAsync(Notification noti) {
        if (!enabled || noti == null) return;

        String userId = noti.getReceiverUserId();
        if (userId == null || userId.isBlank()) return;

        // 자기가 보낸 알림은 푸시하지 않는다
        if (userId.equals(noti.getSenderUserId())) return;

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("u", userId);

        List<Map<String, Object>> subs = this.sqlRunner.getRows(
                "SELECT id, endpoint, p256dh, auth FROM push_sub " +
                " WHERE user_id = :u AND use_yn = 'Y'", p);

        if (subs == null || subs.isEmpty()) return;   // ★ getRows 는 오류 시 null 을 줄 수 있다

        String payload = buildPayload(noti);

        for (Map<String, Object> s : subs) {
            Object subId = s.get("id");
            try {
                Subscription sub = new Subscription(
                        (String) s.get("endpoint"),
                        new Subscription.Keys((String) s.get("p256dh"), (String) s.get("auth"))
                );

                HttpResponse res = client.send(new nl.martijndwars.webpush.Notification(sub, payload));
                int code = res.getStatusLine().getStatusCode();

                if (code == 404 || code == 410) {
                    // 구독 만료 — 죽은 구독에 계속 쏘지 않도록 끈다
                    markDead(subId);
                    log.info("[push] 만료 구독 정리 id={} user={}", subId, userId);
                } else if (code >= 200 && code < 300) {
                    markSent(subId);
                } else {
                    markFail(subId);
                    log.warn("[push] 발송 응답 {} id={} user={}", code, subId, userId);
                }
            } catch (Exception e) {
                markFail(subId);
                log.warn("[push] 발송 실패 id={} user={} : {}", subId, userId, e.getMessage());
            }
        }
    }

    // ────────────────────────────────────────────────────────────

    private String buildPayload(Notification n) {
        String tag = safe(n.getDomain()) + "-" + safe(n.getAction());
        if (n.getTargetId() != null && !n.getTargetId().isBlank()) {
            tag = tag + "-" + n.getTargetId();
        }

        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"title\":").append(json(n.getTitle() == null ? "MIRACELL MES" : n.getTitle()));
        sb.append(",\"body\":").append(json(abbr(n.getMessage(), 120)));
        sb.append(",\"tag\":").append(json(tag));
        sb.append(",\"url\":").append(json(resolveUrl(n)));
        if (n.getNotiId() != null) sb.append(",\"notiId\":").append(n.getNotiId());
        sb.append('}');
        return sb.toString();
    }

    /**
     * 알림 클릭 시 이동할 경로.
     *
     * <p>지금은 MESSAGE/SEND·REPLY 뿐이라 전부 메인으로 보낸다.
     * 나중에 도메인(작업지시·검사불합격·환경이상 등)이 늘면 여기에만 추가한다.</p>
     */
    private String resolveUrl(Notification n) {
        String domain = safe(n.getDomain());
        switch (domain) {
            // case "WORKORDER": return "/production/prod_order";
            // case "ENV":       return "/dashboard/env";
            case "MESSAGE":
            default:
                return baseUrl;
        }
    }

    private void markSent(Object id) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", id);
        this.sqlRunner.execute(
                "UPDATE push_sub SET last_sent_at = now(), fail_count = 0 WHERE id = :id", p);
    }

    private void markDead(Object id) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", id);
        this.sqlRunner.execute("UPDATE push_sub SET use_yn = 'N' WHERE id = :id", p);
    }

    /** 연속 10회 실패하면 자동으로 끈다 */
    private void markFail(Object id) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", id);
        this.sqlRunner.execute(
                "UPDATE push_sub SET fail_count = COALESCE(fail_count,0) + 1, " +
                "       use_yn = CASE WHEN COALESCE(fail_count,0) + 1 >= 10 THEN 'N' ELSE use_yn END " +
                " WHERE id = :id", p);
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private String abbr(String s, int n) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }

    /** 의존성 추가 없이 쓰는 최소 JSON 문자열 이스케이프 */
    private String json(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
