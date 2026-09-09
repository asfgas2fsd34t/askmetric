package dev.askmetric.server.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识来源摄取生命周期与确定性检索。
 * 摄取后的段落文本是不可信数据：只作为带引用的检索结果返回，绝不参与权限、策略或工具决策。
 */
@Service
public class KnowledgeSourceService {
    private static final int MAX_UPLOAD_BYTES = 5 * 1024 * 1024;
    private static final int MAX_PASSAGE_CHARS = 800;
    private static final int MAX_RETRIEVAL_ITEMS = 3;
    private static final List<String> SUPPORTED_CONTENT_TYPES =
            List.of("text/plain", "text/markdown", "application/pdf");

    private final KnowledgeSourceMapper mapper;
    private final KnowledgeTextExtractor textExtractor;

    public KnowledgeSourceService(KnowledgeSourceMapper mapper, KnowledgeTextExtractor textExtractor) {
        this.mapper = mapper;
        this.textExtractor = textExtractor;
    }

    /** 上传并同步完成摄取：抽取文本、按段落切块、记录生命周期终态。 */
    @Transactional
    public KnowledgeSource upload(
            String userSubject, String workspaceId, String title, String filename,
            String contentType, byte[] content) {
        if (title == null || title.isBlank() || title.length() > 200) {
            throw new IllegalArgumentException("title must be 1-200 characters");
        }
        if (filename == null || filename.isBlank() || filename.length() > 200) {
            throw new IllegalArgumentException("filename must be 1-200 characters");
        }
        if (content == null || content.length == 0 || content.length > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("content must be 1-" + MAX_UPLOAD_BYTES + " bytes");
        }
        String normalizedType = contentType == null ? "" : contentType.split(";")[0].strip();
        if (!SUPPORTED_CONTENT_TYPES.contains(normalizedType)) {
            throw new IllegalArgumentException("不支持的知识来源类型: " + normalizedType);
        }
        String knowledgeSourceId = "knowledge_source_" + UUID.randomUUID();
        if (mapper.insert(
                userSubject, workspaceId, knowledgeSourceId, title.strip(), filename.strip(),
                contentType, content.length, KnowledgeSource.Status.UPLOADED, null) != 1) {
            throw new AccessDeniedException("Workspace Membership not found");
        }
        String extractedText = null;
        KnowledgeSource.Status finalStatus = KnowledgeSource.Status.FAILED;
        String failureReason = null;
        List<KnowledgePassage> passages = List.of();
        try {
            extractedText = textExtractor.extract(contentType, content);
            List<String> chunks = chunkPassages(extractedText);
            passages = new ArrayList<>();
            for (int index = 0; index < chunks.size(); index++) {
                KnowledgePassage passage = new KnowledgePassage();
                passage.setKnowledgePassageId("knowledge_passage_" + UUID.randomUUID());
                passage.setKnowledgeSourceId(knowledgeSourceId);
                passage.setWorkspaceId(workspaceId);
                passage.setPassageNumber(index + 1);
                passage.setText(chunks.get(index));
                passages.add(passage);
            }
            mapper.insertPassages(passages);
            finalStatus = KnowledgeSource.Status.READY;
        } catch (IllegalArgumentException exception) {
            failureReason = exception.getMessage();
        }
        mapper.finishIngestion(
                workspaceId, knowledgeSourceId, finalStatus, passages.size(), failureReason, extractedText);
        return mapper.findById(userSubject, workspaceId, knowledgeSourceId)
                .orElseThrow(() -> new IllegalStateException("Knowledge Source was not persisted"));
    }

    /** 读取当前用户 Workspace 内的知识来源列表。 */
    @Transactional(readOnly = true)
    public List<KnowledgeSource> list(String userSubject, String workspaceId) {
        return mapper.list(userSubject, workspaceId);
    }

    /**
     * 确定性全文检索：按查询词在段落中的命中数排序，返回来源标识、段落号与引文。
     * 检索结果只包含文本数据，不携带任何可执行的策略或指令语义。
     */
    @Transactional(readOnly = true)
    public List<KnowledgeRetrievalItem> retrieve(String workspaceId, String query) {
        List<String> terms = splitTerms(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        return mapper.listPassages(workspaceId).stream()
                .map(row -> new ScoredPassage(row, countTermHits(row.getText(), terms)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredPassage::score).reversed()
                        .thenComparing(scored -> scored.row().getKnowledgeSourceId())
                        .thenComparing(scored -> scored.row().getPassageNumber()))
                .limit(MAX_RETRIEVAL_ITEMS)
                .map(scored -> new KnowledgeRetrievalItem(
                        scored.row().getKnowledgeSourceId(),
                        scored.row().getTitle(),
                        scored.row().getPassageNumber(),
                        truncateQuote(scored.row().getText())))
                .toList();
    }

    /** 段落切块：按空行分段，超长段落按固定长度切分。 */
    static List<String> chunkPassages(String text) {
        List<String> passages = new ArrayList<>();
        for (String paragraph : text.replace("\r\n", "\n").split("\\n\\s*\\n")) {
            String normalized = paragraph.strip();
            if (normalized.isEmpty()) {
                continue;
            }
            for (int start = 0; start < normalized.length(); start += MAX_PASSAGE_CHARS) {
                int end = Math.min(start + MAX_PASSAGE_CHARS, normalized.length());
                passages.add(normalized.substring(start, end));
            }
        }
        if (passages.isEmpty()) {
            throw new IllegalArgumentException("知识来源没有可摄取的文本段落");
        }
        return passages;
    }

    private static List<String> splitTerms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\s+"))
                .map(String::strip)
                .filter(term -> !term.isEmpty())
                .distinct()
                .toList();
    }

    private static int countTermHits(String text, List<String> terms) {
        String lowered = text.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (lowered.contains(term)) {
                score++;
            }
        }
        return score;
    }

    private static String truncateQuote(String text) {
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private record ScoredPassage(KnowledgePassageRow row, int score) {
    }
}
