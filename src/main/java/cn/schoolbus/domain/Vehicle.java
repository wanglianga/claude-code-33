package cn.schoolbus.domain;

/** 校车：物理座位数 seats，绑定线路与司乘人员 */
public record Vehicle(
        String id,
        String plate,
        int seats,
        String routeId,
        String driverUsername,
        String attendantUsername,
        String status,          // ON_DUTY / ON_TIME / DELAYED / RUNNING / FINISHED
        String locationText,
        int delayMinutes,
        String updatedAt
) {
}
