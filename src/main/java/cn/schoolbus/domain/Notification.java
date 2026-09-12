package cn.schoolbus.domain;

/**
 * 通知 + 回执：每条异常/点名状态变更向相关角色各发一条通知，
 * 学校可凭此确认家长、班主任、安全员是否都收到同一信息。
 * ack: 是否已收到确认；ackAt: 确认时间。
 */
public record Notification(
        Long id,
        String createdAt,
        Long incidentId,
        String eventKey,
        String recipientUsername,
        String recipientRole,
        String channel,
        String title,
        String content,
        boolean ack,
        String ackAt
) {
}
