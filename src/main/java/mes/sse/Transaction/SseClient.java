package mes.sse.Transaction;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class SseClient {

    private final String userId;
    private final SseEmitter emitter;

    /** 이미 죽은 연결에 다시 손대지 않기 위한 플래그 */
    private final AtomicBoolean dead = new AtomicBoolean(false);

    /** 마지막으로 성공적으로 보낸 시각 (유휴 연결 판단용) */
    private volatile long lastSentAt = System.currentTimeMillis();

    public SseClient(String userId, SseEmitter emitter) {
        this.userId = userId;
        this.emitter = emitter;
    }

    public String getUserId() {
        return userId;
    }

    public boolean isDead() {
        return dead.get();
    }

    public long getLastSentAt() {
        return lastSentAt;
    }

    /**
     * 전송. 실패하면 스스로 죽은 것으로 표시한다.
     *
     * <p>★ 여기서 예외를 절대 밖으로 던지면 안 된다.
     * SseSubject.notifyUser 가 목록을 순회하며 부르기 때문에,
     * 죽은 연결 하나가 예외를 던지면 뒤에 있는 다른 사용자들이 알림을 못 받는다.</p>
     *
     * @return 전송 성공 여부
     */
    public boolean send(String eventName, Object data) {
        if (dead.get()) return false;

        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data));
            lastSentAt = System.currentTimeMillis();
            return true;

        } catch (IOException e) {
            // 클라이언트가 끊김 — 정상적인 상황이다. 조용히 정리.
            kill(e);
            return false;

        } catch (IllegalStateException e) {
            // ★ 이미 complete/timeout 된 emitter. completeWithError 도 여기서 터진다.
            kill(null);
            return false;

        } catch (Exception e) {
            log.warn("[sse] 전송 실패 user={} : {}", userId, e.getMessage());
            kill(null);
            return false;
        }
    }

    /** heartbeat. 이벤트 이름 없이 주석만 보내 클라이언트 핸들러를 건드리지 않는다. */
    public boolean ping() {
        if (dead.get()) return false;
        try {
            emitter.send(SseEmitter.event().comment("ping"));
            return true;
        } catch (Exception e) {
            kill(null);
            return false;
        }
    }

    public void complete() {
        if (dead.compareAndSet(false, true)) {
            try {
                emitter.complete();
            } catch (Exception ignore) {
                // 이미 닫혔으면 그만이다
            }
        }
    }

    private void kill(Throwable cause) {
        if (!dead.compareAndSet(false, true)) return;
        try {
            if (cause != null) emitter.completeWithError(cause);
            else               emitter.complete();
        } catch (Exception ignore) {
            // 톰캣이 이미 async context 를 정리한 뒤라면 여기서 터진다. 무시해도 된다.
        }
    }
}