package com.example.mathbuddy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.mathbuddy.config.ApiKeyCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 调用通义千问（DashScope OpenAI 兼容模式）的流式补全接口，边收边把增量文本回调给上层
 */
@Service
public class QwenService {

    private static final Logger log = LoggerFactory.getLogger(QwenService.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiKey;
    private final String model;
    private final String vlModel;

    public QwenService(WebClient.Builder builder,
                       @Value("${qwen.api-key:}") String apiKey,
                       @Value("${qwen.api-key-master:}") String apiKeyMaster,
                       @Value("${qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
                       @Value("${qwen.model:qwen-plus}") String model,
                       @Value("${qwen.vl-model:qwen-vl-max}") String vlModel) {
        this.apiKey = ApiKeyCipher.decrypt(apiKey, apiKeyMaster);
        this.model = model;
        this.vlModel = vlModel;
        this.webClient = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 识图（多模态）用的模型名 */
    public String visionModel() {
        return vlModel;
    }

    /**
     * 纯文本对话，使用配置里的默认模型
     */
    public Disposable streamChat(List<Map<String, Object>> messages,
                                 Consumer<String> onToken,
                                 Runnable onDone,
                                 Consumer<Throwable> onError) {
        return streamChat(messages, model, onToken, onDone, onError);
    }

    /**
     * @param messages  完整的对话消息（含 system）；内容既可以是字符串，也可以是图文数组
     * @param modelName 本次使用的模型，带图片时传视觉模型
     * @param onToken   每收到一个增量文本回调一次
     * @param onDone    正常结束回调
     * @param onError   异常回调
     */
    public Disposable streamChat(List<Map<String, Object>> messages,
                                 String modelName,
                                 Consumer<String> onToken,
                                 Runnable onDone,
                                 Consumer<Throwable> onError) {

        Map<String, Object> body = Map.of(
                "model", modelName == null || modelName.isBlank() ? model : modelName,
                "messages", messages,
                "stream", true,
                "temperature", 0.7
        );

        StringBuilder pending = new StringBuilder();

        return webClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                        .doOnNext(err -> log.error("千问接口返回错误: {}", err))
                        .then(Mono.error(new IllegalStateException("千问接口返回 " + response.statusCode()))))
                .bodyToFlux(DataBuffer.class)
                .subscribe(
                        buffer -> {
                            String chunk = buffer.toString(StandardCharsets.UTF_8);
                            DataBufferUtils.release(buffer);
                            dispatch(chunk, pending, onToken);
                        },
                        error -> {
                            log.error("千问流式请求失败", error);
                            onError.accept(error);
                        },
                        onDone
                );
    }

    /**
     * 一次性拿完整回复（不出题时用流式没意义），返回模型输出的文本
     *
     * @param jsonMode 要求模型只输出 JSON 对象（用于结构化出题）
     */
    public String complete(List<Map<String, Object>> messages, boolean jsonMode) {
        if (!isConfigured()) {
            throw new IllegalStateException("还没有配置千问 API Key");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", false);
        body.put("temperature", 0.4);
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }

        String raw = webClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                        .doOnNext(err -> log.error("千问接口返回错误: {}", err))
                        .then(Mono.error(new IllegalStateException("千问接口返回 " + response.statusCode()))))
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(180));

        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("千问没有返回内容");
        }

        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            return content.isMissingNode() || content.isNull() ? "" : content.asText();
        } catch (Exception e) {
            log.error("解析千问返回失败: {}", raw, e);
            throw new IllegalStateException("解析千问返回失败");
        }
    }

    /**
     * SSE 以 \n\n 分隔事件，这里按行增量解析，避免跨 buffer 截断
     */
    private void dispatch(String chunk, StringBuilder pending, Consumer<String> onToken) {
        pending.append(chunk);
        int newlineIndex;
        while ((newlineIndex = pending.indexOf("\n")) >= 0) {
            String line = pending.substring(0, newlineIndex).trim();
            pending.delete(0, newlineIndex + 1);

            if (line.isEmpty() || !line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(payload);
                JsonNode content = root.path("choices").path(0).path("delta").path("content");
                if (!content.isMissingNode() && !content.isNull()) {
                    String token = content.asText();
                    if (!token.isEmpty()) {
                        onToken.accept(token);
                    }
                }
            } catch (Exception e) {
                log.warn("解析流式数据失败: {}", payload, e);
            }
        }
    }
}
