package com.example.mathbuddy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户账号与会话：注册、登录、token 校验。
 * 密码使用 PBKDF2WithHmacSHA256 + 随机盐哈希，数据落在 data/users.json 与 data/sessions.json。
 */
@Component
public class UserStore {

    private static final Logger log = LoggerFactory.getLogger(UserStore.class);

    public record User(String id, String username, String salt, String hash, long createdAt, String nickname, String avatar) {
        public User {
            if (nickname == null || nickname.isBlank()) nickname = username;
            if (avatar == null || avatar.isBlank()) avatar = "preset-bear";
        }

        public User(String id, String username, String salt, String hash, long createdAt) {
            this(id, username, salt, hash, createdAt, username, "preset-bear");
        }
    }

    public record Session(String token, String userId, long expiresAt) {
    }

    public record AuthResult(String token, User user) {
    }

    private static final int SALT_BYTES = 16;
    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final long SESSION_TTL_MS = 30L * 24 * 60 * 60 * 1000;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final Set<String> ALLOWED_AVATARS = Set.of(
            "preset-bear", "preset-panther", "preset-cactus", "preset-dino",
            "preset-gameboy", "preset-ghost", "preset-swan", "preset-planet"
    );
    private static final Set<String> LEGACY_AVATARS = Set.of(
            "blue", "sky", "emerald", "violet", "fuchsia", "amber", "rose", "slate"
    );
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif"
    );
    private static final long MAX_AVATAR_BYTES = 2 * 1024 * 1024;

    private final Map<String, User> usersById = new ConcurrentHashMap<>();
    private final Map<String, User> usersByName = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper objectMapper;
    private final Path usersFile;
    private final Path sessionsFile;
    private final Path avatarsDir;
    private final Object fileLock = new Object();

    public UserStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.usersFile = Paths.get("data", "users.json");
        this.sessionsFile = Paths.get("data", "sessions.json");
        this.avatarsDir = Paths.get("data", "avatars");
        try {
            Files.createDirectories(avatarsDir);
        } catch (IOException e) {
            log.warn("创建头像目录失败: {}", e.getMessage());
        }
    }

    @PostConstruct
    public void load() {
        loadUsers();
        loadSessions();
    }

    private void loadUsers() {
        if (!Files.exists(usersFile)) {
            return;
        }
        try {
            List<User> list = objectMapper.readValue(usersFile.toFile(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, User.class));
            boolean migrated = false;
            for (User user : list) {
                String avatar = user.avatar();
                if (LEGACY_AVATARS.contains(avatar)) {
                    avatar = "preset-bear";
                    user = new User(user.id(), user.username(), user.salt(), user.hash(),
                            user.createdAt(), user.nickname(), avatar);
                    migrated = true;
                }
                usersById.put(user.id(), user);
                usersByName.put(user.username().toLowerCase(Locale.ROOT), user);
            }
            log.info("账号数据已加载 {} 个", usersById.size());
            if (migrated) {
                saveUsers();
                log.info("旧版颜色头像已迁移为新版预设头像");
            }
        } catch (IOException e) {
            log.warn("加载账号数据失败: {}", e.getMessage());
        }
    }

    private void loadSessions() {
        if (!Files.exists(sessionsFile)) {
            return;
        }
        try {
            List<Session> list = objectMapper.readValue(sessionsFile.toFile(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Session.class));
            long now = System.currentTimeMillis();
            boolean changed = false;
            for (Session session : list) {
                if (session.expiresAt() <= now || !usersById.containsKey(session.userId())) {
                    changed = true;
                    continue;
                }
                sessions.put(session.token(), session);
            }
            if (changed) {
                saveSessions();
            }
            log.info("登录会话已加载 {} 个", sessions.size());
        } catch (IOException e) {
            log.warn("加载登录会话失败: {}", e.getMessage());
        }
    }

    private void saveUsers() {
        List<User> list = new ArrayList<>(usersById.values());
        list.sort(Comparator.comparingLong(User::createdAt));
        writeJson(usersFile, list);
    }

    private void saveSessions() {
        writeJson(sessionsFile, new ArrayList<>(sessions.values()));
    }

    private void writeJson(Path file, Object value) {
        synchronized (fileLock) {
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                log.warn("保存 {} 失败: {}", file.getFileName(), e.getMessage());
            }
        }
    }

    public synchronized AuthResult register(String username, String password) {
        String name = username == null ? "" : username.trim();
        String pwd = password == null ? "" : password;
        if (name.length() < 2 || name.length() > 20) {
            throw new IllegalArgumentException("用户名请输入 2-20 个字符。");
        }
        if (name.contains(" ")) {
            throw new IllegalArgumentException("用户名不能包含空格。");
        }
        if (pwd.length() < 5 || pwd.length() > 32) {
            throw new IllegalArgumentException("密码请输入 5-32 位。");
        }
        if (usersByName.containsKey(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("这个用户名已经被注册了。");
        }
        User user = createUser(name, pwd);
        return new AuthResult(createSession(user.id()), user);
    }

    public synchronized AuthResult login(String username, String password) {
        String name = username == null ? "" : username.trim();
        User user = usersByName.get(name.toLowerCase(Locale.ROOT));
        if (user == null || !verify(password == null ? "" : password, user)) {
            throw new IllegalArgumentException("用户名或密码不正确。");
        }
        return new AuthResult(createSession(user.id()), user);
    }

    private User createUser(String username, String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = hash(password, salt);
        User user = new User("u-" + UUID.randomUUID().toString().substring(0, 8),
                username, Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(hash), System.currentTimeMillis(),
                username, "blue");
        usersById.put(user.id(), user);
        usersByName.put(username.toLowerCase(Locale.ROOT), user);
        saveUsers();
        return user;
    }

    /** 首次启动时预置演示账号；已有账号时直接返回最早注册的账号 */
    public synchronized User ensureBootstrapUser() {
        if (usersById.isEmpty()) {
            createUser("text", "12345");
            log.info("已预置演示账号 text");
        }
        return firstUser();
    }

    public User firstUser() {
        return usersById.values().stream()
                .min(Comparator.comparingLong(User::createdAt))
                .orElse(null);
    }

    private String createSession(String userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        Session session = new Session(token, userId, System.currentTimeMillis() + SESSION_TTL_MS);
        sessions.put(token, session);
        saveSessions();
        return token;
    }

    /** 校验 token，过期或不存在返回 null */
    public String validate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Session session = sessions.get(token);
        if (session == null) {
            return null;
        }
        if (session.expiresAt() <= System.currentTimeMillis()) {
            sessions.remove(token);
            saveSessions();
            return null;
        }
        return usersById.containsKey(session.userId()) ? session.userId() : null;
    }

    public void logout(String token) {
        if (token == null) {
            return;
        }
        if (sessions.remove(token) != null) {
            saveSessions();
        }
    }

    public User findById(String userId) {
        return usersById.get(userId);
    }

    private boolean verify(String password, User user) {
        try {
            byte[] salt = Base64.getDecoder().decode(user.salt());
            byte[] expected = Base64.getDecoder().decode(user.hash());
            byte[] actual = hash(password, salt);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private byte[] hash(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("密码加密失败", e);
        }
    }

    /** 修改昵称/头像 */
    public synchronized User updateProfile(String userId, String nickname, String avatar) {
        User user = usersById.get(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        String newNickname = nickname == null ? user.nickname() : nickname.trim();
        if (newNickname.isBlank()) {
            newNickname = user.username();
        }
        if (newNickname.length() > 20) {
            throw new IllegalArgumentException("昵称最长 20 个字符");
        }
        String newAvatar = avatar == null || avatar.isBlank() ? user.avatar() : avatar.trim();
        if (!isValidAvatar(newAvatar)) {
            newAvatar = user.avatar();
        }
        if (!isPresetAvatar(user.avatar()) && !user.avatar().equals(newAvatar)) {
            deleteCustomAvatar(user.avatar());
        }
        User updated = new User(user.id(), user.username(), user.salt(), user.hash(),
                user.createdAt(), newNickname, newAvatar);
        usersById.put(updated.id(), updated);
        usersByName.put(updated.username().toLowerCase(Locale.ROOT), updated);
        saveUsers();
        return updated;
    }

    public boolean isPresetAvatar(String avatar) {
        return ALLOWED_AVATARS.contains(avatar);
    }

    public boolean isCustomAvatar(String avatar) {
        return avatar != null && avatar.startsWith("avatar-") && avatar.contains(".");
    }

    public boolean isValidAvatar(String avatar) {
        return isPresetAvatar(avatar) || isCustomAvatar(avatar);
    }

    public Path avatarPath(String filename) {
        if (!isCustomAvatar(filename)) {
            return null;
        }
        Path path = avatarsDir.resolve(filename).normalize();
        if (!path.startsWith(avatarsDir.normalize())) {
            return null;
        }
        return path;
    }

    /** 保存上传的头像文件，返回文件名 */
    public synchronized String saveAvatar(String userId, String contentType, byte[] data) {
        if (!ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("仅支持 png、jpg、webp、gif 格式的图片");
        }
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("图片内容为空");
        }
        if (data.length > MAX_AVATAR_BYTES) {
            throw new IllegalArgumentException("图片大小不能超过 2MB");
        }
        User user = usersById.get(userId);
        if (user != null && !isPresetAvatar(user.avatar())) {
            deleteCustomAvatar(user.avatar());
        }
        String ext = extensionFromContentType(contentType);
        String filename = "avatar-" + userId + "-" + System.currentTimeMillis() + ext;
        Path target = avatarsDir.resolve(filename);
        try {
            Files.write(target, data);
            return filename;
        } catch (IOException e) {
            throw new IllegalStateException("头像保存失败: " + e.getMessage(), e);
        }
    }

    private void deleteCustomAvatar(String filename) {
        Path path = avatarPath(filename);
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除旧头像失败: {}", e.getMessage());
        }
    }

    private String extensionFromContentType(String contentType) {
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
    }

    /** 给接口返回用的最小用户信息 */
    public static Map<String, Object> publicUser(User user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.id());
        map.put("username", user.username());
        map.put("nickname", user.nickname());
        map.put("avatar", user.avatar());
        return map;
    }
}
