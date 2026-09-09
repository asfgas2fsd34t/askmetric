package dev.askmetric.server.knowledge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/** 从上传内容抽取纯文本：Markdown/纯文本直读，文本型 PDF 用 PDFBox 抽取。 */
@Component
public class KnowledgeTextExtractor {
    private static final List<String> TEXT_CONTENT_TYPES = List.of("text/plain", "text/markdown");

    public String extract(String contentType, byte[] content) {
        if (contentTypesMatch(contentType)) {
            return new String(content, StandardCharsets.UTF_8);
        }
        if ("application/pdf".equals(contentType)) {
            return extractPdfText(content);
        }
        throw new IllegalArgumentException("不支持的知识来源类型: " + contentType);
    }

    private static boolean contentTypesMatch(String contentType) {
        String normalized = contentType == null ? "" : contentType.split(";")[0].strip();
        return TEXT_CONTENT_TYPES.contains(normalized);
    }

    private static String extractPdfText(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            String text = new PDFTextStripper().getText(document);
            if (text != null && !text.isBlank()) {
                return text;
            }
            throw new IllegalArgumentException("PDF 不包含可抽取的文本层");
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取 PDF 内容", exception);
        }
    }
}
