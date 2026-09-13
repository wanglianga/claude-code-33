package cn.schoolbus.domain;

import java.util.List;

/**
 * 线路：有序站点 stops + 线路售票容量 capacity（与车辆物理座位相互独立的两道校验）。
 * stopCapacity：同一站点上/下车学生人数上限（安全员现场照护能力）。
 * estMinutes：线路全程预计分钟数，用于改乘绕行时间校验。
 */
public record Route(
        String id,
        String name,
        String direction,       // MORNING / AFTERNOON / BOTH
        String vehicleId,
        List<String> stops,
        int capacity,
        int stopCapacity,
        int estMinutes
) {
}
