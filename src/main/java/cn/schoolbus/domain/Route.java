package cn.schoolbus.domain;

import java.util.List;

/** 线路：有序站点 stops + 线路售票容量 capacity（与车辆物理座位相互独立的两道校验） */
public record Route(
        String id,
        String name,
        String direction,       // MORNING / AFTERNOON / BOTH
        String vehicleId,
        List<String> stops,
        int capacity
) {
}
