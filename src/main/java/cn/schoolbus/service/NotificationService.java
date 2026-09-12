package cn.schoolbus.service;

import cn.schoolbus.domain.Notification;
import cn.schoolbus.domain.User;
import cn.schoolbus.store.RedisStore;
import cn.schoolbus.support.ApiException;
import cn.schoolbus.support.ForbiddenException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 通知与回执：每个异常/状态变更向司机、安全员、班主任、家长、管理员逐人生成可确认的通知。 */
@Service
public class NotificationService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RedisStore store;

    public NotificationService(RedisStore store) {
        this.store = store;
    }

    /** 向一组用户发送同一信息（同一 eventKey 标识一次广播，学校可据此核对是否同一份信息）。 */
    public List<Long> broadcast(String eventKey, Long incidentId, Collection<String> usernames,
                                String title, String content) {
        List<Long> ids = new ArrayList<>();
        String now = LocalDateTime.now().format(TS);
        for (String username : usernames) {
            User u = store.getUser(username);
            if (u == null) continue;
            long id = store.nextId("notif");
            store.saveNotification(new Notification(
                    id, now, incidentId, eventKey, username, u.role(),
                    "APP_PUSH", title, content, false, null));
            ids.add(id);
        }
        return ids;
    }

    public Notification ack(long notificationId, String username) {
        Notification n = store.getNotification(notificationId);
        if (n == null) throw new ApiException("通知不存在");
        if (!n.recipientUsername().equals(username)) {
            throw new ForbiddenException("越权：只能确认本人收到的通知");
        }
        if (!n.ack()) {
            n = new Notification(n.id(), n.createdAt(), n.incidentId(), n.eventKey(),
                    n.recipientUsername(), n.recipientRole(), n.channel(),
                    n.title(), n.content(), true, LocalDateTime.now().format(TS));
            store.saveNotification(n);
        }
        return n;
    }

    /** 对事件下尚未回执的角色再次提醒，返回补发数量。 */
    public int nudge(long incidentId) {
        int sent = 0;
        String now = LocalDateTime.now().format(TS);
        for (Notification n : store.listNotificationsByIncident(incidentId)) {
            if (!n.ack()) {
                long id = store.nextId("notif");
                store.saveNotification(new Notification(
                        id, now, incidentId, n.eventKey() + ":NUDGE",
                        n.recipientUsername(), n.recipientRole(), n.channel(),
                        "【再次提醒】" + n.title(), n.content(), false, null));
                sent++;
            }
        }
        return sent;
    }
}
