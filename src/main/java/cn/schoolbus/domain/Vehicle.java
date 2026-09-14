package cn.schoolbus.domain;

/** 值班车辆：nearestStop 为当前定位最近的本线站点（随司机上报更新，用于上错车最近安全交接点选择） */
public record Vehicle(
        String id,
        String plate,
        int seats,
        String routeId,
        String driverUsername,
        String attendantUsername,
        String status,
        String locationText,
        String nearestStop,
        int delayMinutes,
        String updatedAt
) {
}
