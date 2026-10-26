package com.example.mathbuddy.dto;

import java.util.List;

/**
 * 前端请求体：当前问题 + 最近几轮对话（用于多轮引导）
 * image / docText 为本轮随消息上传的题目图片（data URL）和文档解析出的文字
 */
public record ChatRequest(String message, List<Message> history, String image, String docName, String docText) {

    public record Message(String role, String content) {
    }
}
