package mes.sse.Service;

import lombok.RequiredArgsConstructor;
import mes.app.push.PushService;
import mes.domain.entity.Notification;
import mes.sse.Transaction.SseSubject;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SseService {

    private final SseSubject subject;
    private final PushService pushService;   // ★ 추가

    /** 시스템 공지 */
    public void sendSystem(String spjangcd, String message) {
        subject.notifySystem(spjangcd, message);
    }

    /** 개인 알림 */
    public void sendNotification(Notification noti) {
        subject.notifyUser(
                noti.getSpjangcd(),
                noti.getReceiverUserId(),
                "NOTIFICATION",
                noti
        );
        pushService.sendAsync(noti);         // ★ 추가
    }

    /** 답장 */
    public void sendComment(Notification comment) {
        subject.notifyUser(
                comment.getSpjangcd(),
                comment.getReceiverUserId(),
                "COMMENT",
                comment
        );
        pushService.sendAsync(comment);      // ★ 추가 — 「메시지」가 이 경로를 탄다
    }
}