package dev.askmetric.server.agent;

public class AgentRunPublishException extends IllegalStateException {
    private final AgentRunAccepted accepted;

    public AgentRunPublishException(AgentRunAccepted accepted, RuntimeException cause) {
        super(cause.getMessage(), cause);
        this.accepted = accepted;
    }

    public AgentRunAccepted accepted() {
        return accepted;
    }
}
