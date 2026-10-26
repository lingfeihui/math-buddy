package com.example.mathbuddy.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 文档理解的第一道工序：把学生上传的 PDF / Word(docx) / TXT / Markdown 转成纯文本
 */
@Service
public class DocumentService {

    /** 最多保留的字符数，避免超长文档把提示词撑爆 */
    private static final int MAX_CHARS = 12000;

    public record Extracted(String filename, String text, int chars, boolean truncated) {
    }

    public Extracted extract(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文件是空的，换一份再试试。");
        }

        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String text;
        try {
            if (lower.endsWith(".pdf")) {
                text = fromPdf(bytes);
            } else if (lower.endsWith(".docx")) {
                text = fromDocx(bytes);
            } else if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown")
                    || lower.endsWith(".csv")) {
                text = fromPlainText(bytes);
            } else {
                throw new IllegalArgumentException("暂时只支持 PDF、Word(docx)、TXT、Markdown 文档。");
            }
        } catch (InvalidPasswordException e) {
            throw new IllegalStateException("这份 PDF 设置了密码，我没法读取，麻烦上传未加密的版本。");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("文档解析失败：" + e.getMessage());
        }

        text = normalize(text);
        if (text.isBlank()) {
            throw new IllegalStateException("没有从这份文档里读到文字，可能是扫描版 PDF。把题目截图当图片发给我也可以。");
        }

        boolean truncated = text.length() > MAX_CHARS;
        if (truncated) {
            text = text.substring(0, MAX_CHARS);
        }
        return new Extracted(filename, text, text.length(), truncated);
    }

    private String fromPdf(byte[] bytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setLineSeparator("\n");
            stripper.setParagraphEnd("\n");
            return stripper.getText(document);
        }
    }

    private String fromDocx(byte[] bytes) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String fromPlainText(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        // 中文 TXT 常见 GBK/GB18030 编码，UTF-8 解出乱码时回退
        if (text.indexOf('\uFFFD') >= 0) {
            text = new String(bytes, Charset.forName("GB18030"));
        }
        return text;
    }

    private String normalize(String text) {
        return text.replace("\uFEFF", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
