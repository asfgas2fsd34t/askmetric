package dev.askmetric.server.approval;

/** 受治理操作的确切类型；执行器语义由 Java 拥有，Agent 只能引用。 */
public enum ActionProposalType {
    /** 把本次分析使用的自定义口径升级为工作区共享版本（ADR-0009）。 */
    PROMOTE_CUSTOM_CALIBER,
    /** 修订语义目录中的标准口径；T23/T24 接入执行。 */
    REVISE_STANDARD_CALIBER
}
