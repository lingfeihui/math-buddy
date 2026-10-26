package com.example.mathbuddy.config;

import com.example.mathbuddy.service.UserStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 校验 Authorization: Bearer <token>，把当前用户写入请求属性。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final UserStore userStore;

    public AuthInterceptor(UserStore userStore) {
        this.userStore = userStore;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = bearerToken(request);
        String userId = userStore.validate(token);
        if (userId == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"请先登录\"}");
            return false;
        }

        request.setAttribute("userId", userId);
        request.setAttribute("authToken", token);
        return true;
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring(7).trim();
    }
}
