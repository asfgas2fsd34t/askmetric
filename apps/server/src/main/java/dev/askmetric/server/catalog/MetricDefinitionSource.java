package dev.askmetric.server.catalog;

/** 指标定义版本的来源。 */
public enum MetricDefinitionSource {
    /** 由数据分析师治理并通过语义目录导入的标准定义。 */
    STANDARD,
    /** 业务用户为一次分析明确提交的自定义定义。 */
    CUSTOM
}
