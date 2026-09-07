package dev.askmetric.server.catalog;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 将受版本控制的标准指标 YAML 导入 Java 业务数据库。 */
@Component
public class SemanticCatalogImporter implements ApplicationRunner {
    private final MetricDefinitionMapper mapper;
    private final Resource catalogResource;

    public SemanticCatalogImporter(
            MetricDefinitionMapper mapper,
            @Value("classpath:semantic-catalog/mrr-v3.yaml") Resource catalogResource) {
        this.mapper = mapper;
        this.catalogResource = catalogResource;
    }

    /** 应用启动且 Flyway 完成后幂等导入标准目录。 */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        importCatalog(catalogResource);
    }

    /** 从指定 YAML 资源导入标准指标定义，供启动流程和导入测试复用。 */
    public void importCatalog(Resource resource) {
        YamlMapFactoryBean yaml = new YamlMapFactoryBean();
        yaml.setResources(resource);
        Map<String, Object> root = yaml.getObject();
        Object rawDefinitions = root == null ? null : root.get("definitions");
        if (!(rawDefinitions instanceof List<?> definitions)) {
            throw new IllegalStateException("语义目录必须包含 definitions 列表");
        }
        for (Object rawDefinition : definitions) {
            if (!(rawDefinition instanceof Map<?, ?> values)) {
                throw new IllegalStateException("语义目录中的指标定义必须是对象");
            }
            mapper.importStandard(toDefinition(values));
        }
    }

    private static MetricDefinitionVersion toDefinition(Map<?, ?> values) {
        MetricDefinitionVersion definition = new MetricDefinitionVersion();
        definition.setMetricDefinitionVersionId(required(values, "metricDefinitionVersionId"));
        definition.setWorkspaceId(required(values, "workspaceId"));
        definition.setMetricKey(required(values, "metricKey"));
        definition.setDisplayName(required(values, "displayName"));
        definition.setVersionLabel(required(values, "versionLabel"));
        definition.setDefinitionSource(MetricDefinitionSource.STANDARD);
        definition.setCalculationRule(required(values, "calculationRule"));
        definition.setTimeBoundary(required(values, "timeBoundary"));
        definition.setExclusions(required(values, "exclusions"));
        return definition;
    }

    private static String required(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("语义目录字段不能为空：" + key);
        }
        return text.strip();
    }
}
