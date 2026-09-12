package cn.schoolbus.support;

/** 角色或数据范围（scope）越权 -> HTTP 403；抛出前不得产生任何 Redis 写操作 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
