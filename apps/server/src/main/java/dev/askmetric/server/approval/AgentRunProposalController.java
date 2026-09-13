package dev.askmetric.server.approval;

import dev.askmetric.server.agent.AgentQueryGrantService;
import dev.askmetric.server.agent.AgentRunMapper;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Python Agent Runtime 的提案提交入口：查询授权决定身份与运行来源，
 * 参数由服务端从运行绑定的口径定义快照推导——Agent 只能提议，不能执行。
 */
@RestController
public class AgentRunProposalController {
    private final ActionProposalService service;
    private final AgentQueryGrantService grantService;
    private final AgentRunMapper agentRunMapper;

    public AgentRunProposalController(
            ActionProposalService service,
            AgentQueryGrantService grantService,
            AgentRunMapper agentRunMapper) {
        this.service = service;
        this.grantService = grantService;
        this.agentRunMapper = agentRunMapper;
    }

    /** 提交操作提案草案；草案在发起者显式确认前不会进入等待审批。 */
    @PostMapping("/api/v1/agent-run-proposals")
    public ResponseEntity<ActionProposal> submit(
            @RequestHeader(value = "X-Query-Grant", required = false) String grant,
            @RequestBody(required = false) SubmitProposalRequest request) {
        if (request == null || request.runId() == null || request.runId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runId is required");
        }
        if (!grantService.validate(request.runId(), grant, Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Query grant is invalid or expired");
        }
        if (agentRunMapper.findRunContext(request.runId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent Run not found");
        }
        ActionProposal proposal = service.createFromRun(request.runId(), request.actionType());
        return ResponseEntity.status(HttpStatus.CREATED).body(proposal);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleStatusException(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(new ErrorResponse(exception.getReason()));
    }

    /** Agent 提案请求体：只携带运行与操作类型，口径参数不接受调用方自报。 */
    public record SubmitProposalRequest(String runId, String actionType) {
    }

    public record ErrorResponse(String error) {
    }
}
