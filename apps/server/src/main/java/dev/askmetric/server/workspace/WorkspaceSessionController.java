package dev.askmetric.server.workspace;

import java.util.Optional;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/session")
class WorkspaceSessionController {
    private final WorkspaceSessionService service;

    WorkspaceSessionController(WorkspaceSessionService service) {
        this.service = service;
    }

    @GetMapping
    WorkspaceSession current(
            @AuthenticationPrincipal Jwt identity,
            @RequestHeader(value = "X-Workspace-Id", required = false) String requestedWorkspaceId) {
        return service.current(identity, Optional.ofNullable(requestedWorkspaceId));
    }
}
