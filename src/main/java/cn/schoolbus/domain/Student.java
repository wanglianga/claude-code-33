package cn.schoolbus.domain;

/**
 * 学生乘车档案的静态配置：默认线路、上车点、下车接送人、班级、家长联系方式、特殊照护。
 */
public record Student(
        String id,
        String name,
        String studentNo,
        String classId,
        String className,
        String routeId,
        String defaultStop,
        String pickupPerson,
        String parentPhone,
        String parentUsername,
        boolean specialCare,
        String note
) {
}
