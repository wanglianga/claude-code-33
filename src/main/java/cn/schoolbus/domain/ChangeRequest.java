package cn.schoolbus.domain;

/**
 * 家长申请：LEAVE 请假 / CHANGE_BUS 临时改乘 / CHANGE_STOP 改下车点 / ALTERNATE_PICKUP 他人代接。
 * 改乘四项校验：车辆座位 seatCheck、同站点学生 stopCheck、绕行时间 detourCheck、随车安全员确认。
 * status: PENDING 待安全员确认 / WAITLIST 候补中 / APPROVED 已通过 / REJECTED 驳回 / CANCELED 放弃
 * 双确认：attendantAckAt 安全员确认时间 + parentAckAt 家长确认知悉时间。
 */
public record ChangeRequest(
        Long id,
        String createdAt,
        String studentId,
        String parentUsername,
        String date,
        String type,
        String targetRouteId,
        String targetStop,
        String alternatePickupPerson,
        String reason,
        String status,
        boolean waitlist,
        String capacityCheck,
        String seatCheck,
        String stopCheck,
        String detourCheck,
        String ruleCheck,
        String reviewedBy,
        String reviewedAt,
        String reviewNote,
        String attendantAckAt,
        String parentAckAt
) {
}
