package dev.askmetric.server.conversation;

/** 同一幂等键被用于不同消息内容时抛出的冲突。 */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
