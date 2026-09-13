package cn.schoolbus.domain;

import java.util.List;

/** 事件关闭后生成的学生乘车档案条目：通知记录、定位、点名、家长确认、责任结论与上错车复盘根因一并归档 */
public record ArchiveEntry(
        String id,
        String archivedAt,
        String date,
        String studentId,
        Long incidentId,
        String incidentType,
        String resolution,
        String responsibility,
        String rootCause,
        String morningStatus,
        String afternoonStatus,
        String parentConfirmed,
        String vehicleSnapshot,
        List<Long> notificationIds
) {
}
