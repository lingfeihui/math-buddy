package com.example.mathbuddy.controller;

import com.example.mathbuddy.dto.ChatRequest;
import com.example.mathbuddy.service.DocumentService;
import com.example.mathbuddy.service.MistakeBook;
import com.example.mathbuddy.service.QwenService;
import com.example.mathbuddy.service.WeaknessTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** 苏格拉底式数学老师：只引导，不给答案 */
    private static final String TUTOR_PROMPT = """
            你是一位经验丰富、耐心细致的高中数学老师，正在用启发式提问（苏格拉底法）辅导学生。

            必须严格遵守以下规则：
            1. 绝对禁止直接给出最终答案、完整解题过程或结论，也禁止给出可直接照抄的关键算式结果。
            2. 每次回复只推进一小步：先肯定学生已有的思路，再抛出一个反问，引导学生自己想下一步。
            3. 多用反问句，例如“你觉得要求这个最值，第一步该先确定什么？”“这个条件让你联想到哪个公式？”
            4. 学生直接索要答案时，不要妥协，用反问把问题抛回给他，并提示他回顾相关的定义或定理。
            5. 回复简洁，控制在 120 字以内，最多给一个提示点，不要说教。
            6. 数学式子统一使用 LaTeX：行内用 $...$，独立成行用 $$...$$；命令后必须留空格再接变量（如 $x \\in A$），不要写成 $x\\inA$ 这类粘连形式。
            7. 使用中文回答，语气像面对面辅导，可以偶尔称呼学生“同学”。
            """;

    /** 学生发了题目图片时的额外要求 */
    private static final String IMAGE_HINT = """

            本次学生上传了题目图片，请额外遵守：
            8. 先看清图片里的文字、数字、符号和图形，用一句话复述关键条件，确认自己没有看错（复述里不能出现答案或解法）。
            9. 如果图片模糊、缺条件或只拍到一半，直接指出哪一处看不清，让学生补发，不要凭空猜测。
            """;

    /** 变式训练出题人：只出题，不给答案 */
    private static final String VARIANT_PROMPT = """
            你是一位高中数学命题老师，擅长根据原题设计“同源变式题”。

            要求：
            1. 只输出一道题目，考查知识点与原题一致，但条件、数据或设问方式要有变化，难度与原题相当。
            2. 可以在题目开头用一句话说明这道题训练什么能力，但不要透露解法。
            3. 绝对禁止给出答案、提示或解析过程。
            4. 数学式子统一使用 LaTeX：行内用 $...$，独立成行用 $$...$$。
            5. 使用中文，篇幅控制在 200 字以内。
            6. 严格限定在高中数学（人教A版）课程标准和高考考纲范围内，禁止出现大学数学内容（如罗尔定理、拉格朗日中值定理、洛必达法则、泰勒展开、ε-δ 极限定义等），也不要出必须用高中未学知识才能求解的题。
            7. LaTeX 命令后必须留空格再接变量，例如写成 $x \\mid x^2-5x+6=0$，不要写成 $x\\midx^2-5x+6=0$。
            """;

    /** 思维导图大纲生成：只要缩进列表，方便前端解析成树 */
    private static final String MINDMAP_PROMPT = """
            你是一位熟悉现行高中数学教材（人教A版）和高考考纲的知识结构梳理专家，正在为高中生画一张“知识点思维导图”的文字大纲。

            必须严格遵守：
            1. 内容严格限定在高中数学课程标准和高考考纲范围内，绝对禁止出现大学数学内容，例如：罗尔定理、拉格朗日中值定理、柯西中值定理、洛必达法则、泰勒展开、ε-δ 极限定义、级数收敛等。
            2. 只围绕当前知识点本身展开，不要混入其他章节的内容（例如梳理“函数与导数”时，不要出现三角函数的周期性、解三角形等）。
            3. 只输出缩进列表，每行格式为“- 内容”，不要输出任何解释段落、标题、代码块、序号或 Markdown 加粗记号。
            4. 用两个空格表示一级缩进，层级最多 3 级。
            5. 第一行是根节点（知识点名称本身，不超过 10 个字），之后每行缩进两个空格。
            6. 第二级给出 4~5 个分支，覆盖：核心概念、常用公式/定理、典型题型、易错点。
            7. 每个二级分支下最多 4 个三级条目，每条不超过 18 个字，必须是高中生能直接理解的表述。
            8. 公式用普通文本简写（例如 f'(x)>0、a²+b²=c²），绝对不要使用 LaTeX 或 $ 符号。
            9. 使用中文，总行数不超过 28 行。
            """;

    /** 点击思维导图节点时，讲解该节点的概念与例题 */
    private static final String EXPLAIN_PROMPT = """
            你是一位高中数学老师，学生正在看某个知识点的思维导图，点击了其中一个节点，想把这个节点真正学懂。

            请围绕这个节点做一次“有信息增量”的扩展讲解，严格遵守：
            1. 内容严格限定在高中数学课程标准和高考考纲范围内，禁止大学数学内容（如罗尔定理、拉格朗日中值定理、洛必达法则、泰勒展开、ε-δ 极限定义）；反函数只讲定义和图像关于 y=x 对称，不要讲反函数求导公式，也不要讲反三角函数求导。
            2. 绝对不要复述导图节点上已有的文字，也不要把它换个说法再写一遍；每一条都要补充节点本身没写出来的信息。
            3. 按下面四个小标题输出，每个标题独占一行，标题后紧跟内容：
               【概念本质】说清它到底是什么、刻画了什么对象或关系，并给一个直观理解（2~3 句，可举生活化或图像化的例子）。
               【来龙去脉】这个结论或公式为什么成立、由哪个定义或定理推出来，并说明成立条件与适用范围（1~2 句）。
               【怎么用】解题时的识别信号、关键变形、常见误区（2~3 条短句）。
               【小例】一道紧扣该节点的典型小题，再用 2~3 句分析思路；可以点出关键中间结果，但不要写出完整解答过程。
            4. 总字数 350~550 字，宁可把一个点讲透，也不要面面俱到地罗列。
            5. 公式统一使用 LaTeX：行内用 $...$，独立成行用 $$...$$；不要输出代码块。
            6. 使用中文。
            """;

    /** 错题答疑：直接给出讲解和答案（不同于主聊天的苏格拉底引导） */
    private static final String ASK_PROMPT = """
            你是一位耐心的高中数学老师，学生在错题本里点了「请教 AI」，想把这道错题彻底搞懂。

            必须严格遵守：
            1. 直接讲清楚这道题怎么解，给出完整解题过程和最终答案，不要藏着掖着，也不要反问。
            2. 先点明考查的知识点，再分步讲解，每一步说明用了什么公式或定理。
            3. 最后用一句话总结这道题的易错点或关键思路。
            4. 内容限定在高中数学课程标准和高考范围内，禁止大学内容。
            5. 数学式子统一使用 LaTeX：行内用 $...$，独立成行用 $$...$$；不要输出代码块。
            6. LaTeX 命令后必须留空格再接变量或字母，例如 $x \\in A$、$q \\Rightarrow p$、$x \\mid x>0$，不要写成 $x\\inA$、$q\\Rightarrowp$。
            7. 用中文，篇幅控制在 300 字以内，简洁明了。
            """;

    private static final int MAX_HISTORY = 8;
    private static final int MAX_DOC_CHARS = 12000;
    private static final int MAX_IMAGE_CHARS = 8_000_000;
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final QwenService qwenService;
    private final WeaknessTracker weaknessTracker;
    private final DocumentService documentService;
    private final MistakeBook mistakeBook;

    public ChatController(QwenService qwenService, WeaknessTracker weaknessTracker,
                          DocumentService documentService, MistakeBook mistakeBook) {
        this.qwenService = qwenService;
        this.weaknessTracker = weaknessTracker;
        this.documentService = documentService;
        this.mistakeBook = mistakeBook;
    }

    /**
     * 任务 1 + 任务 2：流式答疑，同时把命中的知识点记进内存
     * 支持随消息带上题目图片（识图）和文档解析文字（理解文档）
     */
    @PostMapping(value = "/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(@RequestBody ChatRequest request,
                           @RequestAttribute("userId") String userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        String question = request.message() == null ? "" : request.message().trim();
        String image = normalizeImage(request.image());
        String docText = request.docText();

        if (question.isEmpty() && image == null && (docText == null || docText.isBlank())) {
            sendAndComplete(emitter, "同学，先把你的问题、题目图片或者资料发给我吧。");
            return emitter;
        }
        if (question.isEmpty()) {
            question = image != null ? "这道题我看不太懂，你先帮我看看题目条件。" : "请帮我理解这份资料里的题目。";
        }

        // 任务 2：命中知识点关键词就累加次数（文档前段也一起扫描）
        weaknessTracker.record(userId, question);
        if (docText != null && !docText.isBlank()) {
            weaknessTracker.record(userId, docText.substring(0, Math.min(docText.length(), 2000)));
        }

        // 错题本：能归到具体知识点的题目自动收进来（纯图片题、寒暄先不收）
        String asked = request.message() == null ? "" : request.message().trim();
        List<String> askedPoints = weaknessTracker.matchPoints(asked);
        if (!asked.isEmpty() && !askedPoints.isEmpty()) {
            mistakeBook.add(userId, asked, askedPoints);
        }

        String systemPrompt = image != null ? TUTOR_PROMPT + IMAGE_HINT : TUTOR_PROMPT;
        List<Map<String, Object>> messages = buildMessages(systemPrompt, request.history(),
                composeQuestion(question, request.docName(), docText), image);

        stream(emitter, messages, image != null ? qwenService.visionModel() : null);
        return emitter;
    }

    /**
     * 任务 5：举一反三，根据刚才的题目（文字/图片/文档）生成同类变式题
     */
    @PostMapping(value = "/variant", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter variant(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        String question = request.message() == null ? "" : request.message().trim();
        String image = normalizeImage(request.image());
        String docText = request.docText();

        if (question.isEmpty() && image == null && (docText == null || docText.isBlank())) {
            sendAndComplete(emitter, "还没有可以变式的题目，先问一道题吧。");
            return emitter;
        }

        String prompt = image != null
                ? VARIANT_PROMPT + "\n8. 学生用图片给出了原题，请先看清图片里的题目，再据此出一道同类变式题。\n"
                : VARIANT_PROMPT;

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", prompt));
        messages.add(userMessage(composeVariantContent(question, request.docName(), docText), image));

        stream(emitter, messages, image != null ? qwenService.visionModel() : null);
        return emitter;
    }

    /**
     * 文档理解：上传 PDF / Word(docx) / TXT / Markdown，返回解析出的文字
     */
    @PostMapping("/upload")
    public ResponseEntity<Object> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件是空的，换一份再试试。"));
        }
        try {
            return ResponseEntity.ok(documentService.extract(file.getOriginalFilename(), file.getBytes()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            log.warn("读取上传文件失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "读取文件失败，请重试一次。"));
        }
    }

    /**
     * 任务 2：返回学情统计，如 {"函数与导数": 2, "圆锥曲线": 1}
     */
    @GetMapping("/stats")
    public Map<String, Integer> stats(@RequestAttribute("userId") String userId) {
        return weaknessTracker.snapshot(userId);
    }

    /**
     * 智能错题本：列出自动收录的题目
     */
    @GetMapping("/mistakes")
    public List<MistakeBook.Mistake> mistakes(@RequestAttribute("userId") String userId) {
        return mistakeBook.list(userId);
    }

    /**
     * 标记 / 取消「已掌握」
     */
    @PatchMapping("/mistakes/{id}")
    public ResponseEntity<Object> updateMistake(@PathVariable String id,
                                                @RequestBody(required = false) Map<String, Object> body,
                                                @RequestAttribute("userId") String userId) {
        if (mistakeBook.find(userId, id) == null) {
            return ResponseEntity.notFound().build();
        }
        Object raw = body == null ? null : body.get("mastered");
        boolean mastered = raw != null && Boolean.parseBoolean(raw.toString());
        return ResponseEntity.ok(mistakeBook.setMastered(userId, id, mastered));
    }

    /**
     * 删除一条错题
     */
    @DeleteMapping("/mistakes/{id}")
    public ResponseEntity<Object> deleteMistake(@PathVariable String id,
                                                @RequestAttribute("userId") String userId) {
        return mistakeBook.remove(userId, id)
                ? ResponseEntity.ok(Map.of("removed", true))
                : ResponseEntity.notFound().build();
    }

    /**
     * 错题复习：根据这道错题再出一道同类变式题（流式，只出题不给答案）
     */
    @PostMapping(value = "/mistakes/{id}/retry", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter retryMistake(@PathVariable String id,
                                   @RequestAttribute("userId") String userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        MistakeBook.Mistake mistake = mistakeBook.find(userId, id);
        if (mistake == null) {
            sendAndComplete(emitter, "这道错题已经不在错题本里了。");
            return emitter;
        }
        mistakeBook.bumpReview(userId, id);

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", VARIANT_PROMPT),
                Map.of("role", "user", "content", "错题知识点：" + mistake.point()
                        + "\n原题：\n" + mistake.question() + "\n\n请出一道同类变式题。")
        );
        stream(emitter, messages, null);
        return emitter;
    }

    /**
     * 错题答疑：直接让 AI 讲解这道错题（给出解题过程和答案，流式）
     */
    @PostMapping(value = "/mistakes/{id}/ask", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter askMistake(@PathVariable String id,
                                 @RequestAttribute("userId") String userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        MistakeBook.Mistake mistake = mistakeBook.find(userId, id);
        if (mistake == null) {
            sendAndComplete(emitter, "这道错题已经不在错题本里了。");
            return emitter;
        }

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", ASK_PROMPT),
                Map.of("role", "user", "content", "知识点：" + mistake.point()
                        + "\n题目：\n" + mistake.question() + "\n\n请给我讲讲这道题怎么做。")
        );
        stream(emitter, messages, null);
        return emitter;
    }

    /**
     * 功能 #3：根据学情统计中最薄弱的知识点，让 AI 推一道针对性训练题（只出题，不给答案）
     */
    @PostMapping(value = "/recommend", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter recommend(@RequestAttribute("userId") String userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        Map.Entry<String, Integer> top = weaknessTracker.topPoint(userId);
        if (top == null) {
            sendAndComplete(emitter, "你还没问过题目，先把不会的题发过来，我才能知道你哪里最需要加强。");
            return emitter;
        }

        String point = top.getKey();
        int count = top.getValue();
        String system = String.format(
                "你是一位严谨的高中数学命题老师。已知学生在「%s」这个知识点上提问最多（共 %d 次），说明这是他的薄弱环节。" +
                "请针对这个知识点出 1 道有针对性的训练题。\n" +
                "严格遵守：\n" +
                "1. 只出题，不给答案、不给提示、不给解析过程；\n" +
                "2. 题目要典型，能考查「%s」的核心概念或方法，难度适中；\n" +
                "3. 数学式子统一使用 LaTeX：行内公式 $...$，独立公式 $$...$$；\n" +
                "4. 用中文，篇幅控制在 200 字以内。",
                point, count, point);

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", "请直接给出题目。")
        );
        stream(emitter, messages, null);
        return emitter;
    }

    /** 功能 #4：知识点思维导图 —— AI 输出缩进大纲，前端渲染成树状图 */
    @PostMapping(value = "/mindmap", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter mindmap(@RequestBody(required = false) Map<String, Object> body,
                              @RequestAttribute("userId") String userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        Object raw = body == null ? null : body.get("point");
        String point = raw == null ? "" : raw.toString().trim();
        if (point.isEmpty()) {
            Map.Entry<String, Integer> top = weaknessTracker.topPoint(userId);
            point = top != null ? top.getKey() : "函数与导数";
        }

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", MINDMAP_PROMPT),
                Map.of("role", "user", "content", "请为「" + point + "」生成思维导图大纲。")
        );
        stream(emitter, messages, null);
        return emitter;
    }

    /** 功能 #4 扩展：点击导图节点，返回该节点的概念与例题讲解 */
    @PostMapping(value = "/explain", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter explain(@RequestBody(required = false) Map<String, Object> body) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        String point = body == null || body.get("point") == null ? "该知识点" : body.get("point").toString().trim();
        String node = body == null || body.get("node") == null ? point : body.get("node").toString().trim();
        if (point.isEmpty()) {
            point = "该知识点";
        }
        if (node.isEmpty()) {
            node = point;
        }

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", EXPLAIN_PROMPT),
                Map.of("role", "user", "content",
                        "所属知识点：" + point + "\n点击的节点：" + node + "\n请讲解这个节点。")
        );
        stream(emitter, messages, null);
        return emitter;
    }

    /** 把文档内容和学生问题拼成一段话，交给模型 */
    private String composeQuestion(String question, String docName, String docText) {
        if (docText == null || docText.isBlank()) {
            return question;
        }
        String name = docName == null || docName.isBlank() ? "未命名资料" : docName.trim();
        String excerpt = docText.length() > MAX_DOC_CHARS ? docText.substring(0, MAX_DOC_CHARS) : docText;
        return "【我上传的资料《" + name + "》，内容如下】\n" + excerpt
                + "\n\n【我的问题】\n" + question;
    }

    private String composeVariantContent(String question, String docName, String docText) {
        String base = question.isEmpty() ? "请根据资料里的题目出变式题。" : "原题是：\n" + question;
        if (docText != null && !docText.isBlank()) {
            String name = docName == null || docName.isBlank() ? "未命名资料" : docName.trim();
            String excerpt = docText.length() > MAX_DOC_CHARS ? docText.substring(0, MAX_DOC_CHARS) : docText;
            base += "\n\n【参考资料《" + name + "》】\n" + excerpt;
        }
        return base + "\n\n请根据这道原题，给出 1 道同类变式题。";
    }

    private List<Map<String, Object>> buildMessages(String systemPrompt,
                                                    List<ChatRequest.Message> history,
                                                    String question,
                                                    String image) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));

        if (history != null) {
            List<ChatRequest.Message> recent = history.size() > MAX_HISTORY
                    ? history.subList(history.size() - MAX_HISTORY, history.size())
                    : history;
            for (ChatRequest.Message msg : recent) {
                if (msg == null || msg.content() == null || msg.content().isBlank()) {
                    continue;
                }
                String role = "assistant".equals(msg.role()) ? "assistant" : "user";
                messages.add(Map.of("role", role, "content", msg.content()));
            }
        }

        messages.add(userMessage(question, image));
        return messages;
    }

    /** 图片以 data URL 放在 content 数组里，没有图片时 content 就是纯字符串 */
    private Map<String, Object> userMessage(String text, String image) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        if (image == null) {
            message.put("content", text);
        } else {
            message.put("content", List.of(
                    Map.of("type", "image_url", "image_url", Map.of("url", image)),
                    Map.of("type", "text", "text", text)
            ));
        }
        return message;
    }

    /** 只接受前端读成的图片 data URL，其它形式一律忽略 */
    private String normalizeImage(String image) {
        if (image == null || image.isBlank()) {
            return null;
        }
        String trimmed = image.trim();
        if (!trimmed.startsWith("data:image/")) {
            log.warn("忽略非图片格式的附件");
            return null;
        }
        if (trimmed.length() > MAX_IMAGE_CHARS) {
            log.warn("图片过大，已忽略：{} 字符", trimmed.length());
            return null;
        }
        return trimmed;
    }

    private void stream(SseEmitter emitter, List<Map<String, Object>> messages, String modelOverride) {
        if (!qwenService.isConfigured()) {
            sendAndComplete(emitter, "还没有配置千问 API Key。请在 application.yml 填写 qwen.api-key，或设置环境变量 QWEN_API_KEY 后重启后端。");
            return;
        }

        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));

        // SSE 里纯空白的 data 会被前端解析器丢弃，这里先攒着，等下一个有内容的 token 一起发
        StringBuilder pendingWhitespace = new StringBuilder();

        Disposable disposable = qwenService.streamChat(
                messages,
                modelOverride,
                token -> {
                    if (closed.get()) {
                        return;
                    }
                    if (token.isBlank()) {
                        pendingWhitespace.append(token);
                        return;
                    }
                    String payload = pendingWhitespace.length() > 0
                            ? pendingWhitespace + token
                            : token;
                    pendingWhitespace.setLength(0);
                    try {
                        emitter.send(payload);
                    } catch (IOException | IllegalStateException e) {
                        log.warn("SSE 连接已断开：{}", e.getMessage());
                        closed.set(true);
                    }
                },
                () -> {
                    closed.set(true);
                    try {
                        emitter.complete();
                    } catch (Exception ignored) {
                        // 客户端已经关闭
                    }
                },
                error -> {
                    closed.set(true);
                    try {
                        emitter.send("\n\n【提示】AI 服务暂时不可用：" + error.getMessage());
                        emitter.complete();
                    } catch (Exception ignored) {
                        // 客户端已经关闭
                    }
                }
        );

        emitter.onCompletion(disposable::dispose);
        emitter.onError(e -> disposable.dispose());
    }

    private void sendAndComplete(SseEmitter emitter, String text) {
        try {
            emitter.send(text);
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }
}
