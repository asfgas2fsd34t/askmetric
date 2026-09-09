package dev.askmetric.server.knowledge;

import dev.askmetric.server.agent.AgentQueryGrantService;
import dev.askmetric.server.agent.AgentRunContext;
import dev.askmetric.server.agent.AgentRunMapper;
import dev.askmetric.server.workspace.WorkspaceAuthorizationService;
import dev.askmetric.server.workspace.WorkspacePermission;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Knowledge Source 的 HTTP 边界：工作区成员上传与查看来源；
 * Python Agent 以运行绑定的查询授权检索知识段落，检索结果只含引用数据。
 * 摄取失败（如 PDF 无文本层）会进入 FAILED 生命周期并正常返回来源状态。
 */
@RestController
public class KnowledgeController {
    private final KnowledgeSourceService service;
    private final WorkspaceAuthorizationService workspaceAuthorization;
    private final AgentRunMapper agentRunMapper;
    private final AgentQueryGrantService grantService;

    public KnowledgeController(
            KnowledgeSourceService service,
            WorkspaceAuthorizationService workspaceAuthorization,
            AgentRunMapper agentRunMapper,
            AgentQueryGrantService grantService) {
        this.service = service;
        this.workspaceAuthorization = workspaceAuthorization;
        this.agentRunMapper = agentRunMapper;
        this.grantService = grantService;
    }

    /** 上传文本型知识来源并同步摄取；类型不支持在入口拒绝，抽取失败返回 FAILED 状态。 */
    @PostMapping("/api/v1/knowledge-sources")
    public ResponseEntity<KnowledgeSource> upload(
            @AuthenticationPrincipal Jwt identity,
            @RequestHeader(value = "X-Workspace-Id", required = false) String requestedWorkspaceId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title) {
        String workspaceId = workspaceAuthorization
                .authorize(identity, Optional.ofNullable(requestedWorkspaceId), WorkspacePermission.CREATE_MESSAGE)
                .currentWorkspaceId();
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is required");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取上传内容");
        }
        KnowledgeSource source = service.upload(
                identity.getSubject(),
                workspaceId,
                title == null || title.isBlank() ? file.getOriginalFilename() : title,
                file.getOriginalFilename(),
                file.getContentType(),
                content);
        return ResponseEntity.status(HttpStatus.CREATED).body(source);
    }

    /** 读取当前工作区的知识来源列表。 */
    @GetMapping("/api/v1/knowledge-sources")
    public List<KnowledgeSource> list(
            @AuthenticationPrincipal Jwt identity,
            @RequestHeader(value = "X-Workspace-Id", required = false) String requestedWorkspaceId) {
        String workspaceId = workspaceAuthorization
                .authorize(identity, Optional.ofNullable(requestedWorkspaceId), WorkspacePermission.VIEW_AGENT_RUN)
                .currentWorkspaceId();
        return service.list(identity.getSubject(), workspaceId);
    }

    /** Python Agent 的知识检索入口：查询授权决定身份，检索结果只包含引用数据。 */
    @GetMapping("/api/v1/agent-run-knowledge")
    public List<KnowledgeRetrievalItem> retrieveForRun(
            @RequestHeader(value = "X-Query-Grant", required = false) String grant,
            @RequestParam("runId") String runId,
            @RequestParam("q") String query) {
        if (runId == null || runId.isBlank() || query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runId and q are required");
        }
        if (!grantService.validate(runId, grant, Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Query grant is invalid or expired");
        }
        AgentRunContext context = agentRunMapper.findRunContext(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent Run not found"));
        return service.retrieve(context.getWorkspaceId(), query);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleStatusException(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(new ErrorResponse(exception.getReason()));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(
            org.springframework.web.multipart.MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("上传内容超出大小限制"));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipartException(MultipartException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("上传内容格式无效"));
    }

    /** 知识边界返回给调用方的错误说明。 */
    public record ErrorResponse(String error) {
    }
}
