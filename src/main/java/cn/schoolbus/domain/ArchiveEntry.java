package cn.schoolbus.domain;

import java.util.List;

/** 事件关闭后生成的学生乘车档案条目：通知记录、定位、点名、家长确认与责任结论一并归档 */
public record ArchiveEntry(
        String id,
        String archivedAt,
        String date,
        String studentId,
        Long incidentId,
        String incidentType,
        String resolution,
        String responsibility,
        String morningStatus,
        String afternoonStatus,
        String parentConfirmed,
        String vehicleSnapshot,
        List<Long> notificationIds
) {
}
