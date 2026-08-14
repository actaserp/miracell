package mes.sse.Transaction;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class SseSubject {

    /** spjangcd → 여러 client */
    private final Map<String, List<SseClient>> clients = new ConcurrentHashMap<>();

    /**
     * ★ @Scheduled 를 쓰지 않는다.
     *   이 프로젝트에는 @EnableScheduling 이 없어 @Scheduled 가 동작하지 않고,
     *   그걸 켜면 잠들어 있던 ScheduledTaskRunner 의 배치들이 함께 깨어난다.
     *   heartbeat 하나 때문에 그런 부작용을 만들 이유가 없으므로 전용 스레드를 쓴다.
     */
    private ScheduledExecutorService scheduler;

    @PostConstruct
    void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::heartbeat, 20, 20, TimeUnit.SECONDS);
        log.info("[sse] heartbeat 시작 (20초 주기)");
    }

    public void addObserver(String spjangcd, SseClient client) {
        clients.computeIfAbsent(spjangcd, k -> new CopyOnWriteArrayList<>())
                .add(client);
    }

    public void removeObserver(String spjangcd, SseClient client) {
        List<SseClient> list = clients.get(spjangcd);
        if (list == null) return;
        list.remove(client);
        if (list.isEmpty()) {
            clients.remove(spjangcd);
        }
    }

    /** 🔔 SYSTEM : spjangcd 전체 */
    public void notifySystem(String spjangcd, String message) {
        List<SseClient> list = clients.get(spjangcd);
        if (list == null) return;

        List<SseClient> deadList = new ArrayList<>();
        for (SseClient client : list) {
            if (!client.send("SYSTEM", message)) deadList.add(client);
        }
        sweep(spjangcd, deadList);
    }

    /**
     * 🔔 개인 알림
     *
     * <p>같은 계정으로 여러 기기에 접속해 있으면 전부 보낸다.
     * 전송에 실패한 연결은 그 자리에서 목록에서 걷어낸다.</p>
     */
    public void notifyUser(String spjangcd, String userId,
                           String eventName, Object data) {

        List<SseClient> list = clients.get(spjangcd);
        if (list == null) return;

        List<SseClient> deadList = new ArrayList<>();
        for (SseClient client : list) {
            if (client.isDead()) { deadList.add(client); continue; }
            if (!userId.equals(client.getUserId())) continue;

            // ★ send 는 예외를 던지지 않는다. 하나가 죽어도 뒤의 사용자들은 알림을 받는다.
            if (!client.send(eventName, data)) deadList.add(client);
        }
        sweep(spjangcd, deadList);
    }

    /**
     * heartbeat.
     *
     * <p>이게 없으면 끊긴 연결을 서버가 알 방법이 없다.
     * 태블릿이 절전에 들어가거나 WiFi 가 끊기면 서버 입장에서는 연결이 살아 있는 것처럼
     * 보이고, 죽은 SseClient 가 목록에 계속 쌓인다. 그 상태로 알림을 쏘면
     * 톰캣 async context 관련 예외가 로그를 채운다.</p>
     *
     * <p>nginx 등 프록시의 유휴 타임아웃을 막는 효과도 같이 있다.</p>
     */
    void heartbeat() {
        try {
            for (Map.Entry<String, List<SseClient>> e : clients.entrySet()) {
                List<SseClient> deadList = new ArrayList<>();
                for (SseClient client : e.getValue()) {
                    if (client.isDead() || !client.ping()) deadList.add(client);
                }
                sweep(e.getKey(), deadList);
            }
        } catch (Exception ex) {
            // ★ 여기서 예외가 새어나가면 scheduleWithFixedDelay 가 영구 정지한다
            log.warn("[sse] heartbeat 오류", ex);
        }
    }

    private void sweep(String spjangcd, List<SseClient> deadList) {
        if (deadList.isEmpty()) return;
        for (SseClient c : deadList) {
            removeObserver(spjangcd, c);
        }
        log.debug("[sse] 죽은 연결 {}건 정리 spjangcd={}", deadList.size(), spjangcd);
    }

    /** 현재 접속 수 — 모니터링용 */
    public int count(String spjangcd) {
        List<SseClient> list = clients.get(spjangcd);
        return list == null ? 0 : list.size();
    }

    public int countAll() {
        return clients.values().stream().mapToInt(List::size).sum();
    }

    @PreDestroy
    void shutdown() {
        if (scheduler != null) scheduler.shutdownNow();
        for (List<SseClient> list : clients.values()) {
            for (SseClient c : list) c.complete();
        }
        clients.clear();
    }
}