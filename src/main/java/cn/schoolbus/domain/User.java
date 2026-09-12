package cn.schoolbus.domain;

import java.util.List;

/** 系统用户：ADMIN 校车管理员 / DRIVER 司机 / ATTENDANT 随车安全员 / TEACHER 班主任 / PARENT 家长 */
public record User(
        String username,
        String password,
        String name,
        String role,
        String phone,
        List<String> scopeIds
) {
}
