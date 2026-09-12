package cn.schoolbus.domain;

/**
 * 家长申请：LEAVE 请假 / CHANGE_BUS 临时改乘 / CHANGE_STOP 改下车点 / ALTERNATE_PICKUP 他人代接。
 * 校验链：线路容量 → 车辆座位 → 随车安全员确认 → 学校规则。
 * status: PENDING 待安全员确认 / APPROVED 已通过 / REJECTED 驳回
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
        String capacityCheck,
        String seatCheck,
        String ruleCheck,
        String reviewedBy,
        String reviewedAt,
        String reviewNote
) {
}
