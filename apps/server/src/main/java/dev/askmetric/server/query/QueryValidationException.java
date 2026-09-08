package dev.askmetric.server.query;

/** 查询违反只读、语义目录或资源治理规则时抛出的异常。 */
public class QueryValidationException extends RuntimeException {
    public QueryValidationException(String message) {
        super(message);
    }
}
