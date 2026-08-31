package dev.askmetric.server.report;

import java.util.List;
import java.util.Objects;

/**
 * 用于生成分析报告的结构化可审计分析。所有时间、版本和展示值均由调用方明确提供，
 * 渲染器不会通过当前时间或其他运行环境状态补充这些字段。
 *
 * @param reportId 分析报告当前不可变版本的唯一标识
 * @param title 展示给业务用户的报告标题
 * @param conclusion 基于现有证据得到的主要分析结论
 * @param metricName 指标定义的展示名称
 * @param metricValue 带单位或币种、已经格式化的指标展示值
 * @param period 指标值覆盖的统计周期或时间范围
 * @param dataAsOf 本次分析所使用证据的数据截止时间
 * @param metricVersion 生成结论时采用的指标定义版本
 * @param evidence 支撑结论且实际使用的证据快照条目
 * @param assumptions 得出结论所依赖的显式假设
 * @param uncertainties 证据覆盖、数据质量或结论适用范围方面的不确定性
 */
public record StructuredAnalysis(
        String reportId,
        String title,
        String conclusion,
        String metricName,
        String metricValue,
        String period,
        String dataAsOf,
        String metricVersion,
        List<Evidence> evidence,
        List<String> assumptions,
        List<String> uncertainties) {

    public StructuredAnalysis {
        Objects.requireNonNull(reportId);
        Objects.requireNonNull(title);
        Objects.requireNonNull(conclusion);
        Objects.requireNonNull(metricName);
        Objects.requireNonNull(metricValue);
        Objects.requireNonNull(period);
        Objects.requireNonNull(dataAsOf);
        Objects.requireNonNull(metricVersion);
        evidence = List.copyOf(evidence);
        assumptions = List.copyOf(assumptions);
        uncertainties = List.copyOf(uncertainties);
    }

    /**
     * 分析实际使用的一条受策略限制证据。
     *
     * @param source 证据快照或受治理知识来源的稳定名称
     * @param observation 从该来源得到并用于分析结论的事实
     */
    public record Evidence(String source, String observation) {
        public Evidence {
            Objects.requireNonNull(source);
            Objects.requireNonNull(observation);
        }
    }
}
