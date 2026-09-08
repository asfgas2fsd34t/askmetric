package dev.askmetric.server.catalog;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 语义目录中不可变的指标定义版本。 */
@Data
@NoArgsConstructor
public class MetricDefinitionVersion {
    /** 指标定义版本的全局唯一标识。 */
    private String metricDefinitionVersionId;
    /** 指标定义所属 Workspace 标识。 */
    private String workspaceId;
    /** 指标在语义目录中的稳定键。 */
    private String metricKey;
    /** 指标的用户可见名称。 */
    private String displayName;
    /** 指标的用户可见版本标识。 */
    private String versionLabel;
    /** 指标定义来自标准目录还是业务用户自定义。 */
    private MetricDefinitionSource definitionSource;
    /** 将业务数据转换为指标值的计算规则。 */
    private String calculationRule;
    /** 计算指标时采用的时间边界。 */
    private String timeBoundary;
    /** 计算指标时明确排除的项目。 */
    private String exclusions;
    /** 自定义定义所基于的标准指标版本；标准定义为空。 */
    private String basedOnVersionId;
    /** 创建自定义定义的业务用户身份；标准定义为空。 */
    private String createdBySubject;
    /** 指标定义版本的创建时间。 */
    private Instant createdAt;
}
