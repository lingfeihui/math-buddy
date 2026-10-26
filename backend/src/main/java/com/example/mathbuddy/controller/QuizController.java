package com.example.mathbuddy.controller;

import com.example.mathbuddy.service.MistakeBook;
import com.example.mathbuddy.service.QwenService;
import com.example.mathbuddy.service.WeaknessTracker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识点自测 + 学习报告：AI 出选择题、服务端判分、内存统计掌握度
 */
@RestController
@RequestMapping("/api")
public class QuizController {

    private static final Logger log = LoggerFactory.getLogger(QuizController.class);

    /** 出题提示词：只要 JSON，答案存在服务端，提交后才返回 */
    private static final String QUIZ_PROMPT = """
            你是一位严谨的高中数学命题老师，围绕「%s」出 5 道单项选择题。

            强制规则（违反任何一条都算失败）：
            1. 每题 4 个选项，有且仅有一个正确答案，难度为高考基础到中档。
            2. 严格限定高中数学（人教A版）和高考范围，禁止罗尔定理、拉格朗日中值定理、洛必达法则、泰勒展开、ε-δ 极限定义等大学内容。
            3. 数学公式只能用 LaTeX 行内公式 $...$，绝对不要在公式后面再重复写一遍纯文本。
            4. 每个题干、每个选项、每条解析都必须是单行，不要换行，也不要在 $...$ 中间插入换行。
            5. 5 道题覆盖不同考查角度，不要重复。
            6. 解析（explain）极简短：直接说清正确选项对在哪、其他选项错在哪，控制在 60 字以内，一两句话结束。
            7. 解析里绝对禁止出现自我怀疑、反复验算、"等等""重新计算""更正""发现错误"等内容；只给最终结论，不写思考过程。
            8. 只输出一个 JSON 对象，不要任何前后缀文字、不要 Markdown 代码块。
            9. JSON 结构：
            {"questions":[{"stem":"题干","options":["A","B","C","D"],"answer":"A","explain":"解析"}]}
            10. answer 只能是 A、B、C、D 之一。
            """;

    public record Question(String id, String stem, List<String> options, String answer, String explain) {
    }

    private record Quiz(String id, String userId, String point, List<Question> questions) {
    }

    private final QwenService qwenService;
    private final WeaknessTracker weaknessTracker;
    private final MistakeBook mistakeBook;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Quiz> quizzes = new ConcurrentHashMap<>();

    public QuizController(QwenService qwenService, WeaknessTracker weaknessTracker, MistakeBook mistakeBook) {
        this.qwenService = qwenService;
        this.weaknessTracker = weaknessTracker;
        this.mistakeBook = mistakeBook;
    }

    /** 可选的知识点列表，供前端下拉框使用 */
    @GetMapping("/points")
    public List<String> points() {
        return weaknessTracker.points();
    }

    /**
     * 生成一组自测题（5 道选择题），只把题干和选项发给前端
     */
    @PostMapping("/quiz")
    public ResponseEntity<Object> createQuiz(@RequestBody(required = false) Map<String, Object> body,
                                             @RequestAttribute("userId") String userId) {
        Object raw = body == null ? null : body.get("point");
        String point = raw == null ? "" : raw.toString().trim();
        if (point.isEmpty()) {
            Map.Entry<String, Integer> top = weaknessTracker.topPoint(userId);
            point = top != null ? top.getKey() : "函数与导数";
        }

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", String.format(QUIZ_PROMPT, point)),
                Map.of("role", "user", "content", "请围绕「" + point + "」出 5 道题。")
        );

        String text;
        try {
            text = qwenService.complete(messages, true);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        }

        List<Question> questions = parseQuestions(text);
        if (questions.isEmpty()) {
            log.warn("AI 出题解析失败: {}", text);
            return ResponseEntity.status(502).body(Map.of("error", "AI 出题格式异常，请再试一次。"));
        }

        String quizId = "q-" + UUID.randomUUID().toString().substring(0, 8);
        Quiz quiz = new Quiz(quizId, userId, point, questions);
        quizzes.put(quizId, quiz);

        List<Map<String, Object>> safe = new ArrayList<>();
        for (Question q : questions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", q.id());
            item.put("stem", q.stem());
            item.put("options", q.options());
            safe.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("quizId", quizId);
        result.put("point", point);
        result.put("questions", safe);
        return ResponseEntity.ok(result);
    }

    /**
     * 提交答案：判分 + 返回逐题解析，并把结果计入掌握度（错题自动进错题本）
     */
    @PostMapping("/quiz/submit")
    public ResponseEntity<Object> submit(@RequestBody Map<String, Object> body,
                                         @RequestAttribute("userId") String userId) {
        String quizId = body.get("quizId") == null ? "" : body.get("quizId").toString();
        Quiz quiz = quizzes.get(quizId);
        if (quiz == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "这组题已过期，请重新生成。"));
        }
        if (!userId.equals(quiz.userId())) {
            return ResponseEntity.status(404).body(Map.of("error", "这组题已过期，请重新生成。"));
        }

        List<?> rawAnswers = body.get("answers") instanceof List<?> list ? list : List.of();
        List<Map<String, Object>> results = new ArrayList<>();
        int correctCount = 0;

        for (int i = 0; i < quiz.questions().size(); i++) {
            Question q = quiz.questions().get(i);
            String your = i < rawAnswers.size() && rawAnswers.get(i) != null
                    ? rawAnswers.get(i).toString().trim().toUpperCase()
                    : "";
            boolean correct = your.equals(q.answer());
            if (correct) {
                correctCount++;
            } else {
                // 错题要保留选项，否则带进对话或复习时题目是残缺的
                StringBuilder full = new StringBuilder(q.stem());
                for (int k = 0; k < q.options().size(); k++) {
                    full.append("\n").append((char) ('A' + k)).append(". ").append(q.options().get(k));
                }
                mistakeBook.add(userId, full.toString(), List.of(quiz.point()));
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", q.id());
            item.put("yourAnswer", your);
            item.put("answer", q.answer());
            item.put("correct", correct);
            item.put("explain", q.explain());
            results.add(item);
        }

        weaknessTracker.recordQuiz(userId, quiz.point(), correctCount, quiz.questions().size());
        quizzes.remove(quizId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("point", quiz.point());
        result.put("score", correctCount);
        result.put("total", quiz.questions().size());
        result.put("results", results);
        return ResponseEntity.ok(result);
    }

    /** 掌握度：每个知识点的答题数与正确率 */
    @GetMapping("/mastery")
    public List<Map<String, Object>> mastery(@RequestAttribute("userId") String userId) {
        return weaknessTracker.mastery(userId);
    }

    /** 学习报告：薄弱点 + 7 日提问趋势 + 掌握度 + 错题本概况 */
    @GetMapping("/report")
    public Map<String, Object> report(@RequestAttribute("userId") String userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("weakness", weaknessTracker.snapshot(userId));
        result.put("trend", weaknessTracker.trend(userId, 7));
        result.put("mastery", weaknessTracker.mastery(userId));
        result.put("mistakes", mistakeBook.summary(userId));
        return result;
    }

    /**
     * 把模型返回的 JSON 解析成题目列表；容忍外面多包了一层或带了代码块
     */
    private List<Question> parseQuestions(String text) {
        List<Question> list = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return list;
        }

        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return list;
        }

        try {
            JsonNode root = objectMapper.readTree(text.substring(start, end + 1));
            JsonNode array = root.path("questions");
            if (!array.isArray()) {
                return list;
            }
            int index = 0;
            for (JsonNode node : array) {
                String stem = cleanText(node.path("stem").asText(""));
                String answer = node.path("answer").asText("").trim().toUpperCase();
                JsonNode optionsNode = node.path("options");
                List<String> options = new ArrayList<>();
                if (optionsNode.isArray()) {
                    for (JsonNode option : optionsNode) {
                        options.add(cleanText(option.asText("")));
                    }
                }
                if (stem.isEmpty() || options.size() != 4 || !answer.matches("[ABCD]")) {
                    continue;
                }
                String explain = cleanText(node.path("explain").asText(""));
                if (explain.length() > 90) {
                    explain = explain.substring(0, 90) + "…";
                }
                index++;
                list.add(new Question("q" + index, stem, options, answer, explain));
                if (list.size() >= 5) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("解析出题 JSON 失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * 把 AI 输出里的多余空白（包括 $...$ 中间被插入的换行）压成单个空格，避免显示错乱
     */
    private String cleanText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }
}
