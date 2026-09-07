package dev.askmetric.server.catalog;

import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/** 工作区语义目录的持久化查询。 */
@Mapper
public interface MetricDefinitionMapper {
    /** 幂等导入一个不可变的标准指标版本。 */
    @Insert("""
            insert into metric_definition_version (
                metric_definition_version_id, workspace_id, metric_key, display_name,
                version_label, definition_source, calculation_rule, time_boundary, exclusions
            )
            select #{definition.metricDefinitionVersionId}, workspace.workspace_id,
                   #{definition.metricKey}, #{definition.displayName}, #{definition.versionLabel},
                   'STANDARD', #{definition.calculationRule}, #{definition.timeBoundary}, #{definition.exclusions}
            from workspace
            where workspace.workspace_id = #{definition.workspaceId}
            on conflict (metric_definition_version_id) do nothing
            """)
    int importStandard(@Param("definition") MetricDefinitionVersion definition);

    /** 查询当前用户在 Workspace 中可用的最新标准指标版本。 */
    @Results(id = "metricDefinitionVersion", value = {
            @Result(column = "metric_definition_version_id", property = "metricDefinitionVersionId"),
            @Result(column = "workspace_id", property = "workspaceId"),
            @Result(column = "metric_key", property = "metricKey"),
            @Result(column = "display_name", property = "displayName"),
            @Result(column = "version_label", property = "versionLabel"),
            @Result(column = "definition_source", property = "definitionSource"),
            @Result(column = "calculation_rule", property = "calculationRule"),
            @Result(column = "time_boundary", property = "timeBoundary"),
            @Result(column = "based_on_version_id", property = "basedOnVersionId"),
            @Result(column = "created_by_subject", property = "createdBySubject"),
            @Result(column = "created_at", property = "createdAt")
    })
    @Select("""
            select definition.*
            from metric_definition_version definition
            join workspace_membership membership on membership.workspace_id = definition.workspace_id
            where definition.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
              and definition.metric_key = #{metricKey}
              and definition.definition_source = 'STANDARD'
            order by definition.created_at desc, definition.version_label desc
            limit 1
            """)
    Optional<MetricDefinitionVersion> findLatestStandard(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("metricKey") String metricKey);

    /** 基于标准版本创建只属于当前 Workspace 的不可变自定义指标版本。 */
    @Insert("""
            insert into metric_definition_version (
                metric_definition_version_id, workspace_id, metric_key, display_name,
                version_label, definition_source, calculation_rule, time_boundary, exclusions,
                based_on_version_id, created_by_subject
            )
            select #{definition.metricDefinitionVersionId}, standard.workspace_id,
                   standard.metric_key, standard.display_name, #{definition.versionLabel},
                   'CUSTOM', #{definition.calculationRule}, #{definition.timeBoundary}, #{definition.exclusions},
                   standard.metric_definition_version_id, #{userSubject}
            from metric_definition_version standard
            join workspace_membership membership on membership.workspace_id = standard.workspace_id
            where standard.metric_definition_version_id = #{definition.basedOnVersionId}
              and standard.workspace_id = #{definition.workspaceId}
              and standard.definition_source = 'STANDARD'
              and membership.user_subject = #{userSubject}
            """)
    int insertCustom(
            @Param("userSubject") String userSubject,
            @Param("definition") MetricDefinitionVersion definition);
}
