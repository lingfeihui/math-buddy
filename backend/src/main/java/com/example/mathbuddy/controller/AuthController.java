package com.example.mathbuddy.controller;

import com.example.mathbuddy.service.UserStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 注册、登录、当前用户与退出。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserStore userStore;

    public AuthController(UserStore userStore) {
        this.userStore = userStore;
    }

    @PostMapping("/register")
    public ResponseEntity<Object> register(@RequestBody(required = false) Map<String, Object> body) {
        try {
            UserStore.AuthResult result = userStore.register(text(body, "username"), text(body, "password"));
            return ResponseEntity.ok(authResponse(result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Object> login(@RequestBody(required = false) Map<String, Object> body) {
        try {
            UserStore.AuthResult result = userStore.login(text(body, "username"), text(body, "password"));
            return ResponseEntity.ok(authResponse(result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<Object> me(@RequestAttribute("userId") String userId) {
        UserStore.User user = userStore.findById(userId);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        }
        return ResponseEntity.ok(Map.of("user", UserStore.publicUser(user)));
    }

    @PatchMapping("/profile")
    public ResponseEntity<Object> updateProfile(@RequestBody(required = false) Map<String, Object> body,
                                                @RequestAttribute("userId") String userId) {
        try {
            UserStore.User user = userStore.updateProfile(userId, text(body, "nickname"), text(body, "avatar"));
            return ResponseEntity.ok(Map.of("user", UserStore.publicUser(user)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/avatar")
    public ResponseEntity<Object> uploadAvatar(@RequestParam("file") MultipartFile file,
                                               @RequestAttribute("userId") String userId) {
        try {
            String filename = userStore.saveAvatar(userId, file.getContentType(), file.getBytes());
            return ResponseEntity.ok(Map.of("avatar", filename));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "读取图片失败"));
        }
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestAttribute("authToken") String token) {
        userStore.logout(token);
        return Map.of("ok", true);
    }

    private Map<String, Object> authResponse(UserStore.AuthResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("token", result.token());
        map.put("user", UserStore.publicUser(result.user()));
        return map;
    }

    private String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : value.toString();
    }
}
