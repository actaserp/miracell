package mes.app.push;

import lombok.RequiredArgsConstructor;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 웹 푸시 구독 등록/해제.
 *
 * <p>★ 요청은 form-urlencoded 다. AjaxUtil.postAsyncData 가 그렇게 보낸다.
 * {@code @RequestBody}(JSON)로 받으면 HttpMediaTypeNotSupportedException 이 난다.</p>
 *
 * <p>★ CSRF 예외를 넣을 필요가 없다. AjaxUtil 이 _csrf 를 자동으로 붙인다.</p>
 */
@RestController
@RequestMapping("/api/push")
@RequiredArgsConstructor
public class PushController {

    private final PushService pushService;

    /** 레이아웃 부팅 시 VAPID 공개키를 내려준다 */
    @GetMapping("/key")
    public AjaxResult publicKey() {
        AjaxResult result = new AjaxResult();
        Map<String, Object> data = new HashMap<>();
        data.put("enabled", pushService.isEnabled());
        data.put("publicKey", pushService.getPublicKey());
        result.data = data;
        return result;
    }

    @PostMapping("/subscribe")
    public AjaxResult subscribe(@RequestParam String endpoint,
                                @RequestParam String p256dh,
                                @RequestParam String auth,
                                @RequestParam(required = false) String user_agent,
                                @RequestParam(required = false) String spjangcd,
                                Authentication authentication) {

        AjaxResult result = new AjaxResult();

        User user = (User) authentication.getPrincipal();
        // ★ username = notification.receiver_user_id. 같은 키로 맞춰야 발송이 붙는다.
        pushService.save(user.getUsername(), endpoint, p256dh, auth, user_agent, spjangcd);

        return result;
    }

    @PostMapping("/unsubscribe")
    public AjaxResult unsubscribe(@RequestParam String endpoint) {
        AjaxResult result = new AjaxResult();
        pushService.disable(endpoint);
        return result;
    }

    /**
     * 브라우저가 구독을 자동 갱신했을 때 sw.js 가 부르는 경로.
     * fetch 로 오므로 CSRF 예외가 필요하다(SecurityConfiguration 참고).
     */
    @PostMapping("/resubscribe")
    public AjaxResult resubscribe(@RequestParam String endpoint,
                                  @RequestParam String p256dh,
                                  @RequestParam String auth,
                                  Authentication authentication) {

        AjaxResult result = new AjaxResult();
        if (authentication == null) return result;

        User user = (User) authentication.getPrincipal();
        pushService.save(user.getUsername(), endpoint, p256dh, auth, "resubscribe", null);
        return result;
    }
}
