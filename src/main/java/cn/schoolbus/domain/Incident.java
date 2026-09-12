package cn.schoolbus.domain;

import java.util.List;

/**
 * 乘车异常事件，把司机、安全员、班主任、家长、校车管理员五方串到同一事件。
 * type: NOT_BOARDED 未上车 / WRONG_BUS 上错车 / NO_PICKUP 下车点无人接 /
 *       BUS_DELAY 车辆延误 / PICKUP_CHANGE 家长临时变更接送人
 * status: OPEN 处理中 / RESOLVED 已关闭（关闭后归档进学生乘车档案）
 */
public record Incident(
        Long id,
        String openedAt,
        String closedAt,
        String type,
        String date,
        String studentId,
        String routeId,
        String vehicleId,
        String stop,
        String openedBy,
        String description,
        String status,
        List<TimelineItem> timeline,
        String resolution,
        String responsibility
) {
    public record TimelineItem(String at, String actor, String actorRole, String action) {
    }
}
