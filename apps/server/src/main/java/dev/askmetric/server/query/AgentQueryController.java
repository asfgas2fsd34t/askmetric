package dev.askmetric.server.query;

import dev.askmetric.server.agent.AgentQueryGrantService;
import dev.askmetric.server.agent.AgentRunContext;
import dev.askmetric.server.agent.AgentRunMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Python Agent Runtime 的受治理查询入口：以 Java 签发的短期查询授权替代终端用户 JWT，
 * 查询仍以发起运行的成员身份执行全部治理校验并写入同一套审计与 Evidence Snapshot。
 */
@RestController
@RequestMapping("/api/v1/agent-run-queries")
public class AgentQueryController {
    private final QueryGateway gateway;
    private final AgentRunMapper agentRunMapper;
    private final AgentQueryGrantService grantService;

    public AgentQueryController(
            QueryGateway gateway,
            AgentRunMapper agentRunMapper,
            AgentQueryGrantService grantService) {
        this.gateway = gateway;
        this.agentRunMapper = agentRunMapper;
        this.grantService = grantService;
    }

    /** 校验授权后，代表运行发起人执行一次受治理只读查询。 */
    @PostMapping
    public ResponseEntity<QueryResult> execute(
            @RequestHeader(value = "X-Query-Grant", required = false) String grant,
            @RequestBody(required = false) AgentQueryRequest request) {
        if (request == null || request.runId() == null || request.runId().isBlank()
                || request.sql() == null || request.sql().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runId and sql are required");
        }
        if (!grantService.validate(request.runId(), grant, Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Query grant is invalid or expired");
        }
        AgentRunContext context = agentRunMapper.findRunContext(request.runId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent Run not found"));
        QueryRequest governed = new QueryRequest();
        governed.setWorkspaceId(context.getWorkspaceId());
        governed.setRunId(request.runId());
        governed.setSql(request.sql());
        governed.setParameters(request.parameters() == null ? List.of() : request.parameters());
        return ResponseEntity.ok(gateway.execute(context.getAuthorSubject(), governed));
    }

    @ExceptionHandler(QueryValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(QueryValidationException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleStatusException(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(new ErrorResponse(exception.getReason()));
    }

    /** Agent 查询请求体：授权决定身份，不接受调用方自报工作区。 */
    public record AgentQueryRequest(String runId, String sql, List<Object> parameters) {
    }

    /** 查询边界返回给调用方的错误说明。 */
    public record ErrorResponse(String error) {
    }
}
