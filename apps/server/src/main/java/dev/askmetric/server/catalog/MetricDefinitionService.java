package dev.askmetric.server.catalog;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 提供受 Workspace 边界保护的指标版本查询。 */
@Service
public class MetricDefinitionService {
    private final MetricDefinitionMapper mapper;

    public MetricDefinitionService(MetricDefinitionMapper mapper) {
        this.mapper = mapper;
    }

    /** 查询当前 Workspace 的最新标准指标版本。 */
    @Transactional(readOnly = true)
    public Optional<MetricDefinitionVersion> latestStandard(
            String userSubject, String workspaceId, String metricKey) {
        return mapper.findLatestStandard(userSubject, workspaceId, metricKey);
    }

    /** 以标准版本为基线保存业务用户提交的自定义计算规则。 */
    @Transactional
    public MetricDefinitionVersion createCustom(
            String userSubject,
            String workspaceId,
            MetricDefinitionVersion standard,
            String calculationRule) {
        String suffix = UUID.randomUUID().toString();
        MetricDefinitionVersion custom = new MetricDefinitionVersion();
        custom.setMetricDefinitionVersionId("metric_definition_custom_" + suffix);
        custom.setWorkspaceId(workspaceId);
        custom.setMetricKey(standard.getMetricKey());
        custom.setDisplayName(standard.getDisplayName());
        custom.setVersionLabel("custom-" + suffix);
        custom.setDefinitionSource(MetricDefinitionSource.CUSTOM);
        custom.setCalculationRule(calculationRule);
        custom.setTimeBoundary("由自定义口径中的完整规则确定");
        custom.setExclusions("由自定义口径中的完整规则确定");
        custom.setBasedOnVersionId(standard.getMetricDefinitionVersionId());
        custom.setCreatedBySubject(userSubject);
        if (mapper.insertCustom(userSubject, custom) != 1) {
            throw new IllegalStateException("无法保存自定义指标定义");
        }
        return custom;
    }
}
