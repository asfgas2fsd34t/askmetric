package dev.askmetric.server.query;

/** 受治理查询的最终状态。 */
public enum QueryStatus {
    /** 查询成功并返回受限结果。 */
    SUCCEEDED,
    /** 查询被 Java 治理边界拒绝。 */
    REJECTED,
    /** 查询执行失败。 */
    FAILED
}
