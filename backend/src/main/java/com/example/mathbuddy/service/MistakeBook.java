package com.example.mathbuddy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 错题本：每个用户拥有独立错题，数据持久化到 data/mistakes.json。
 */
@Component
public class MistakeBook {

    private static final Logger log = LoggerFactory.getLogger(MistakeBook.class);

    public record Mistake(String id, String userId, String question, String point, boolean mastered,
                          int reviewCount, long createdAt) {
    }

    /** 旧版全局数据在首次登录时归给最早创建的账号 */
    public static final String LEGACY_USER = "legacy";

    /** 太短的输入（比如“在吗”）不算一道题 */
    private static final int MIN_QUESTION_LENGTH = 8;

    /** 引导解析带入对话时的提示语，收录错题时要剥掉，避免混进题干 */
    private static final String GUIDE_PREFIX = "这道错题我还没弄懂，带我一步步分析：";

    private static String stripGuidePrefix(String question) {
        String text = question == null ? "" : question.trim();
        while (text.startsWith(GUIDE_PREFIX)) {
            text = text.substring(GUIDE_PREFIX.length()).trim();
        }
        return text;
    }

    private final Map<String, Mistake> store = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final Path dataFile;
    private final Object fileLock = new Object();

    public MistakeBook(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.dataFile = Paths.get("data", "mistakes.json");
    }

    @PostConstruct
    public void load() {
        if (!Files.exists(dataFile)) {
            return;
        }
        try {
            List<Mistake> list = objectMapper.readValue(dataFile.toFile(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Mistake.class));
            // 清理历史数据：补齐 userId、剥掉引导提示语，并合并同一用户的重复题目
            Map<String, Mistake> unique = new LinkedHashMap<>();
            boolean changed = false;
            for (Mistake m : list) {
                String userId = m.userId() == null || m.userId().isBlank() ? LEGACY_USER : m.userId();
                String cleaned = stripGuidePrefix(m.question());
                if (cleaned.length() < MIN_QUESTION_LENGTH) {
                    changed = true;
                    continue;
                }
                if (!userId.equals(m.userId()) || !cleaned.equals(m.question())) {
                    m = new Mistake(m.id(), userId, cleaned, m.point(), m.mastered(), m.reviewCount(), m.createdAt());
                    changed = true;
                }
                String key = userId + "\u0000" + cleaned;
                Mistake prev = unique.get(key);
                if (prev == null) {
                    unique.put(key, m);
                } else {
                    changed = true;
                    if (m.reviewCount() > prev.reviewCount()
                            || (m.reviewCount() == prev.reviewCount() && m.mastered() && !prev.mastered())) {
                        unique.put(key, m);
                    }
                }
            }
            for (Mistake m : unique.values()) {
                store.put(m.id(), m);
            }
            if (changed) {
                save();
            }
            log.info("错题本已加载 {} 条", store.size());
        } catch (IOException e) {
            log.warn("加载错题本失败: {}", e.getMessage());
        }
    }

    private void save() {
        synchronized (fileLock) {
            try {
                List<Mistake> list = new ArrayList<>(store.values());
                list.sort(Comparator.comparingLong(Mistake::createdAt));
                Files.createDirectories(dataFile.getParent());
                Path tmp = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), list);
                try {
                    Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                log.warn("保存错题本失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 收录一道题；同一用户内文字完全相同的只保留一条
     */
    public Mistake add(String userId, String question, List<String> points) {
        String text = stripGuidePrefix(question);
        if (userId == null || userId.isBlank() || text.length() < MIN_QUESTION_LENGTH) {
            return null;
        }
        for (Mistake item : store.values()) {
            if (userId.equals(item.userId()) && item.question().equals(text)) {
                return item;
            }
        }

        String point = points == null || points.isEmpty() ? "待归类" : points.get(0);
        Mistake mistake = new Mistake(
                "m-" + UUID.randomUUID().toString().substring(0, 8),
                userId, text, point, false, 0, System.currentTimeMillis());
        store.put(mistake.id(), mistake);
        save();
        return mistake;
    }

    public List<Mistake> list(String userId) {
        List<Mistake> list = new ArrayList<>();
        for (Mistake item : store.values()) {
            if (userId != null && userId.equals(item.userId())) {
                list.add(item);
            }
        }
        list.sort(Comparator.comparingLong(Mistake::createdAt).reversed());
        return list;
    }

    /** 只能取到自己的错题，别人的 id 返回 null */
    public Mistake find(String userId, String id) {
        Mistake item = store.get(id);
        return item != null && item.userId().equals(userId) ? item : null;
    }

    public Mistake setMastered(String userId, String id, boolean mastered) {
        Mistake current = find(userId, id);
        if (current == null) {
            return null;
        }
        Mistake updated = new Mistake(current.id(), current.userId(), current.question(), current.point(),
                mastered, current.reviewCount(), current.createdAt());
        store.put(updated.id(), updated);
        save();
        return updated;
    }

    /** 练过一次变式题，就记一次复习 */
    public Mistake bumpReview(String userId, String id) {
        Mistake current = find(userId, id);
        if (current == null) {
            return null;
        }
        Mistake updated = new Mistake(current.id(), current.userId(), current.question(), current.point(),
                current.mastered(), current.reviewCount() + 1, current.createdAt());
        store.put(updated.id(), updated);
        save();
        return updated;
    }

    public boolean remove(String userId, String id) {
        Mistake current = find(userId, id);
        if (current == null) {
            return false;
        }
        store.remove(id);
        save();
        return true;
    }

    public Map<String, Object> summary(String userId) {
        List<Mistake> mine = list(userId);
        long mastered = mine.stream().filter(Mistake::mastered).count();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("total", mine.size());
        map.put("mastered", mastered);
        map.put("pending", mine.size() - mastered);
        return map;
    }

    /**
     * 把旧版全局错题划归最早创建的账号；没有旧数据时什么也不做
     */
    public void claimLegacy(String userId) {
        if (userId == null || userId.isBlank() || LEGACY_USER.equals(userId)) {
            return;
        }
        boolean changed = false;
        for (Mistake item : store.values()) {
            if (LEGACY_USER.equals(item.userId())) {
                Mistake updated = new Mistake(item.id(), userId, item.question(), item.point(),
                        item.mastered(), item.reviewCount(), item.createdAt());
                store.put(updated.id(), updated);
                changed = true;
            }
        }
        if (changed) {
            save();
            log.info("旧错题已划归账号 {}", userId);
        }
    }
}
