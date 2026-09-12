package cn.schoolbus.domain;

/**
 * 学生"当天统一乘车事实"——班主任、家长、安全员、司机、管理员看到的是同一份记录。
 * morningStatus: PLANNED 默认待乘 / ABSENT_NO_SHOW 未到 / LATE 迟到 / TEMP_BOARDED 临时上车 /
 *                LEAVE 请假 / BOARDED 已上车
 * afternoonStatus: PLANNED / ONBOARD 已上车 / WRONG_BUS 上错车 / NOT_BOARDED 未上车 /
 *                  DELIVERED 已送达 / NO_PICKUP 下车点无人接 / LEAVE
 * changeType: NONE / LEAVE / CHANGE_BUS / CHANGE_STOP / ALTERNATE_PICKUP
 */
public record RideStatus(
        String id,
        String date,
        String studentId,
        String classId,
        String routeId,
        String plannedStop,
        String morningStatus,
        String afternoonStatus,
        String changeType,
        Long changeRequestId,
        String targetRouteId,
        String targetStop,
        String alternatePickupPerson,
        boolean clubActivity,
        String parentNote,
        String boardedBusId,
        String morningMarkedBy,
        String morningMarkedAt,
        String afternoonMarkedBy,
        String afternoonMarkedAt,
        String parentConfirmed,
        String updatedAt,
        long version
) {
}
