package dev.askmetric.server.memory;

import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户记忆生命周期：提议 → 所有者确认 → 可被 Agent 读取；删除即不可再使用。 */
@Service
public class UserMemoryService {
    private static final int MAX_CONTENT_LENGTH = 500;

    private final UserMemoryMapper mapper;

    public UserMemoryService(UserMemoryMapper mapper) {
        this.mapper = mapper;
    }

    /** 登记待确认记忆；未经确认的条目不会出现在任何 Agent 上下文中。 */
    @Transactional
    public UserMemory propose(
            String userSubject, String workspaceId, String content,
            String sourceConversationId, String sourceAgentRunId) {
        if (content == null || content.isBlank() || content.strip().length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("content must be 1-" + MAX_CONTENT_LENGTH + " characters");
        }
        String userMemoryId = "user_memory_" + java.util.UUID.randomUUID();
        if (mapper.insert(
                userSubject, workspaceId, userMemoryId, content.strip(),
                sourceConversationId, sourceAgentRunId) != 1) {
            throw new AccessDeniedException("Workspace Membership not found");
        }
        return mapper.list(userSubject, workspaceId).stream()
                .filter(memory -> userMemoryId.equals(memory.getUserMemoryId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("User Memory was not persisted"));
    }

    /** 所有者显式确认；只有确认后的记忆才对 Agent 可见。 */
    @Transactional
    public UserMemory confirm(String userSubject, String workspaceId, String userMemoryId) {
        if (mapper.confirm(userSubject, workspaceId, userMemoryId) != 1) {
            throw new IllegalArgumentException("User Memory 不是待确认状态或不存在");
        }
        return mapper.list(userSubject, workspaceId).stream()
                .filter(memory -> userMemoryId.equals(memory.getUserMemoryId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("User Memory was not persisted"));
    }

    /** 所有者删除记忆。 */
    @Transactional
    public void delete(String userSubject, String workspaceId, String userMemoryId) {
        if (mapper.delete(userSubject, workspaceId, userMemoryId) != 1) {
            throw new IllegalArgumentException("User Memory 不存在");
        }
    }

    /** 所有者查看自己的全部记忆（含待确认）。 */
    @Transactional(readOnly = true)
    public List<UserMemory> list(String userSubject, String workspaceId) {
        return mapper.list(userSubject, workspaceId);
    }

    /** Agent 上下文读取：仅当前工作区、运行发起人自己的已确认记忆。 */
    @Transactional(readOnly = true)
    public List<UserMemory> listConfirmedForAgent(String userSubject, String workspaceId) {
        return mapper.listConfirmed(userSubject, workspaceId);
    }
}
