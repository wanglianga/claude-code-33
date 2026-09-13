package cn.schoolbus.domain;

import java.util.List;

/**
 * 乘车异常事件，把司机、安全员、班主任、家长、校车管理员五方串到同一事件。
 * type: NOT_BOARDED 未上车 / WRONG_BUS 上错车 / NO_PICKUP 下车点无人接 /
 *       BUS_DELAY 车辆延误 / PICKUP_CHANGE 家长临时变更接送人
 * status: OPEN 处理中 / RESOLVED 已关闭（关闭后归档进学生乘车档案）
 * planJson：上错车处置方案（点名时间/车辆定位/目的地/最近安全站点）JSON
 * rootCause：上错车复盘根因 ROSTER_ERROR 名单错误 / ATTENDANT_MISS 安全员漏核 /
 *            TEMP_CHANGE_UNSYNC 学生临时变更未同步
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
        String responsibility,
        String planJson,
        String rootCause,
        String prevention
) {
    public record TimelineItem(String at, String actor, String actorRole, String action) {
    }
}
