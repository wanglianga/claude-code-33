package cn.schoolbus.support;

/** 业务校验失败 -> HTTP 400 */
public class ApiException extends RuntimeException {
    public ApiException(String message) {
        super(message);
    }
}
