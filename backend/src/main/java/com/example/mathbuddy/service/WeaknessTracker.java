package com.example.mathbuddy.service;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 学情统计：按用户记录知识点提问次数、每日趋势与自测正确率。
 * 数据持久化到 data/weakness.json，重启不丢失。
 */
@Component
public class WeaknessTracker {

    private static final Logger log = LoggerFactory.getLogger(WeaknessTracker.class);

    /** 持久化用的快照结构（Map<String, Integer>，避免 AtomicInteger 序列化问题） */
    public static class State {
        public Map<String, Integer> counter = new LinkedHashMap<>();
        public Map<String, Integer> dailyCounter = new LinkedHashMap<>();
        public Map<String, Integer> quizAnswered = new LinkedHashMap<>();
        public Map<String, Integer> quizCorrect = new LinkedHashMap<>();
    }

    /** 新版文件结构：userId -> State */
    public static class Store {
        public Map<String, State> users = new LinkedHashMap<>();
    }

    private static class UserState {
        final Map<String, AtomicInteger> counter = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> dailyCounter = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> quizAnswered = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> quizCorrect = new ConcurrentHashMap<>();

        static UserState from(State state) {
            UserState result = new UserState();
            if (state == null) {
                return result;
            }
            copy(state.counter, result.counter);
            copy(state.dailyCounter, result.dailyCounter);
            copy(state.quizAnswered, result.quizAnswered);
            copy(state.quizCorrect, result.quizCorrect);
            return result;
        }

        private static void copy(Map<String, Integer> from, Map<String, AtomicInteger> to) {
            if (from == null) {
                return;
            }
            from.forEach((k, v) -> to.put(k, new AtomicInteger(v == null ? 0 : v)));
        }

        State toState() {
            State state = new State();
            counter.forEach((k, v) -> state.counter.put(k, v.get()));
            dailyCounter.forEach((k, v) -> state.dailyCounter.put(k, v.get()));
            quizAnswered.forEach((k, v) -> state.quizAnswered.put(k, v.get()));
            quizCorrect.forEach((k, v) -> state.quizCorrect.put(k, v.get()));
            return state;
        }
    }

    /** 旧版全局数据在首次登录时归给最早创建的账号 */
    private static final String LEGACY_USER = "legacy";

    /** 知识点 -> 触发关键词（先匹配长词，避免“圆锥曲线”被“圆”误判） */
    private static final Map<String, List<String>> KNOWLEDGE_POINTS = new LinkedHashMap<>();

    static {
        KNOWLEDGE_POINTS.put("集合与逻辑", List.of("集合", "充分条件", "必要条件", "充要"));
        KNOWLEDGE_POINTS.put("函数与导数", List.of("函数", "导数", "单调性", "极值", "切线", "定义域", "值域"));
        KNOWLEDGE_POINTS.put("三角函数", List.of("三角函数", "正弦", "余弦", "正切", "诱导公式", "三角恒等"));
        KNOWLEDGE_POINTS.put("解三角形", List.of("解三角形", "余弦定理", "正弦定理"));
        KNOWLEDGE_POINTS.put("数列", List.of("数列", "等差", "等比", "通项", "求和", "递推"));
        KNOWLEDGE_POINTS.put("不等式", List.of("不等式", "均值不等式", "基本不等式"));
        KNOWLEDGE_POINTS.put("立体几何", List.of("立体几何", "线面", "面面", "二面角", "棱锥", "棱柱", "三视图"));
        KNOWLEDGE_POINTS.put("平面向量", List.of("向量", "数量积", "共线"));
        KNOWLEDGE_POINTS.put("直线与圆", List.of("直线与圆", "圆的方程", "弦长"));
        KNOWLEDGE_POINTS.put("圆锥曲线", List.of("圆锥曲线", "椭圆", "双曲线", "抛物线", "离心率", "焦点"));
        KNOWLEDGE_POINTS.put("计数原理", List.of("排列", "组合", "二项式", "计数原理"));
        KNOWLEDGE_POINTS.put("概率与统计", List.of("概率", "统计", "期望", "方差", "分布列", "抽样"));
        KNOWLEDGE_POINTS.put("复数", List.of("复数", "虚数", "共轭"));
    }

    private final Map<String, UserState> states = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final Path dataFile;
    private final Object fileLock = new Object();

    public WeaknessTracker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.dataFile = Paths.get("data", "weakness.json");
    }

    @PostConstruct
    public void load() {
        if (!Files.exists(dataFile)) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(dataFile.toFile());
            if (root.has("users")) {
                Store store = objectMapper.treeToValue(root, Store.class);
                if (store.users != null) {
                    store.users.forEach((userId, state) -> states.put(userId, UserState.from(state)));
                }
            } else {
                // 兼容旧版单用户结构
                State legacy = objectMapper.treeToValue(root, State.class);
                states.put(LEGACY_USER, UserState.from(legacy));
                save();
            }
            log.info("学情统计已加载 {} 个用户", states.size());
        } catch (IOException e) {
            log.warn("加载学情统计失败: {}", e.getMessage());
        }
    }

    private void save() {
        synchronized (fileLock) {
            try {
                Store store = new Store();
                states.forEach((userId, state) -> store.users.put(userId, state.toState()));
                Files.createDirectories(dataFile.getParent());
                Path tmp = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), store);
                try {
                    Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                log.warn("保存学情统计失败: {}", e.getMessage());
            }
        }
    }

    private UserState stateOf(String userId) {
        return states.computeIfAbsent(userId, k -> new UserState());
    }

    /**
     * 扫描学生输入，命中几个知识点就各记一次
     */
    public void record(String userId, String text) {
        if (userId == null || userId.isBlank() || text == null || text.isBlank()) {
            return;
        }
        UserState state = stateOf(userId);
        for (String point : matchPoints(text)) {
            state.counter.computeIfAbsent(point, k -> new AtomicInteger()).incrementAndGet();
        }
        state.dailyCounter.computeIfAbsent(LocalDate.now().toString(), k -> new AtomicInteger()).incrementAndGet();
        save();
    }

    /**
     * 返回这段文字命中的知识点（按配置顺序，长词优先，避免“圆”被当成“圆锥曲线”）
     */
    public List<String> matchPoints(String text) {
        List<String> hits = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return hits;
        }
        for (Map.Entry<String, List<String>> entry : KNOWLEDGE_POINTS.entrySet()) {
            if (entry.getValue().stream().anyMatch(text::contains)) {
                hits.add(entry.getKey());
            }
        }
        return hits;
    }

    /** 全部知识点名称，供自测面板选择 */
    public List<String> points() {
        return new ArrayList<>(KNOWLEDGE_POINTS.keySet());
    }

    /** 自测结果：某个知识点答了 total 题，对 correct 题 */
    public void recordQuiz(String userId, String point, int correct, int total) {
        if (userId == null || userId.isBlank() || point == null || point.isBlank() || total <= 0) {
            return;
        }
        UserState state = stateOf(userId);
        state.quizAnswered.computeIfAbsent(point, k -> new AtomicInteger()).addAndGet(total);
        state.quizCorrect.computeIfAbsent(point, k -> new AtomicInteger())
                .addAndGet(Math.max(0, Math.min(correct, total)));
        save();
    }

    /** 掌握度列表，按正确率升序（最需要补的排在前面） */
    public List<Map<String, Object>> mastery(String userId) {
        List<Map<String, Object>> list = new ArrayList<>();
        UserState state = states.get(userId);
        if (state == null) {
            return list;
        }
        state.quizAnswered.forEach((point, answered) -> {
            int total = answered.get();
            AtomicInteger correctAtomic = state.quizCorrect.get(point);
            int correct = correctAtomic == null ? 0 : correctAtomic.get();
            int rate = total == 0 ? 0 : (int) Math.round(correct * 100.0 / total);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("point", point);
            item.put("total", total);
            item.put("correct", correct);
            item.put("rate", rate);
            list.add(item);
        });
        list.sort(Comparator.comparingInt(m -> (int) m.get("rate")));
        return list;
    }

    /** 最近 days 天的每日提问次数 */
    public List<Map<String, Object>> trend(String userId, int days) {
        List<Map<String, Object>> list = new ArrayList<>();
        UserState state = states.get(userId);
        LocalDate today = LocalDate.now();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            AtomicInteger count = state == null ? null : state.dailyCounter.get(date.toString());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", date.toString());
            item.put("label", (date.getMonthValue()) + "/" + date.getDayOfMonth());
            item.put("count", count == null ? 0 : count.get());
            list.add(item);
        }
        return list;
    }

    /**
     * 返回按次数倒序的快照，如 {"导数": 2, "圆锥曲线": 1}
     */
    public Map<String, Integer> snapshot(String userId) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>();
        UserState state = states.get(userId);
        if (state != null) {
            state.counter.forEach((k, v) -> list.add(Map.entry(k, v.get())));
        }
        list.sort(Comparator.comparing(Map.Entry<String, Integer>::getValue).reversed());

        Map<String, Integer> result = new LinkedHashMap<>();
        list.forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    /**
     * 返回当前提问次数最多的（知识点, 次数）；若无任何记录则返回 null
     */
    public Map.Entry<String, Integer> topPoint(String userId) {
        UserState state = states.get(userId);
        if (state == null) {
            return null;
        }
        return state.counter.entrySet().stream()
                .max(Comparator.comparingInt(e -> e.getValue().get()))
                .map(e -> Map.entry(e.getKey(), e.getValue().get()))
                .orElse(null);
    }

    /**
     * 把旧版全局学情合并给最早创建的账号
     */
    public synchronized void claimLegacy(String userId) {
        if (userId == null || userId.isBlank() || LEGACY_USER.equals(userId)) {
            return;
        }
        UserState legacy = states.remove(LEGACY_USER);
        if (legacy == null) {
            return;
        }
        UserState target = states.computeIfAbsent(userId, k -> new UserState());
        merge(legacy.counter, target.counter);
        merge(legacy.dailyCounter, target.dailyCounter);
        merge(legacy.quizAnswered, target.quizAnswered);
        merge(legacy.quizCorrect, target.quizCorrect);
        save();
        log.info("旧学情已划归账号 {}", userId);
    }

    private static void merge(Map<String, AtomicInteger> from, Map<String, AtomicInteger> to) {
        from.forEach((k, v) -> to.computeIfAbsent(k, x -> new AtomicInteger()).addAndGet(v.get()));
    }
}
