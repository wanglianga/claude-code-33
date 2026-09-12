package cn.schoolbus.support;

import cn.schoolbus.domain.User;
import cn.schoolbus.store.RedisStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 演示态鉴权：登录后前端在 X-User 头携带用户名；生产应换为会话/JWT。 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String CURRENT_USER = "currentUser";

    private final RedisStore store;

    public AuthInterceptor(RedisStore store) {
        this.store = store;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())
                || path.equals("/api/health")
                || path.equals("/api/login")
                || !path.startsWith("/api/")) {
            return true;
        }
        String username = request.getHeader("X-User");
        if (username == null || username.isBlank()) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"message\":\"未登录\"}");
            return false;
        }
        User u = store.getUser(username.trim());
        if (u == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"message\":\"登录态失效，请重新登录\"}");
            return false;
        }
        request.setAttribute(CURRENT_USER, u);
        return true;
    }
}
