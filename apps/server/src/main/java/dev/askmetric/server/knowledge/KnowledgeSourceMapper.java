package dev.askmetric.server.knowledge;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KnowledgeSourceMapper {
    /** 上传登记：来源进入生命周期。 */
    @Insert("""
            insert into knowledge_source (
                knowledge_source_id, workspace_id, title, filename, content_type,
                byte_size, status, content_text
            )
            select #{knowledgeSourceId}, workspace.workspace_id, #{title}, #{filename},
                   #{contentType}, #{byteSize}, #{status}, #{contentText}
            from workspace_membership membership
            join workspace workspace on workspace.workspace_id = membership.workspace_id
            where membership.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
            """)
    int insert(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("knowledgeSourceId") String knowledgeSourceId,
            @Param("title") String title,
            @Param("filename") String filename,
            @Param("contentType") String contentType,
            @Param("byteSize") long byteSize,
            @Param("status") KnowledgeSource.Status status,
            @Param("contentText") String contentText);

    /** 摄取完成：记录状态、段落数、抽取全文与时间。 */
    @Update("""
            update knowledge_source
            set status = #{status}, passage_count = #{passageCount},
                failure_reason = #{failureReason}, content_text = #{contentText},
                ingested_at = current_timestamp
            where knowledge_source_id = #{knowledgeSourceId}
              and workspace_id = #{workspaceId}
            """)
    int finishIngestion(
            @Param("workspaceId") String workspaceId,
            @Param("knowledgeSourceId") String knowledgeSourceId,
            @Param("status") KnowledgeSource.Status status,
            @Param("passageCount") int passageCount,
            @Param("failureReason") String failureReason,
            @Param("contentText") String contentText);

    /** 读取单个知识来源；list 的 @Results 复用。 */
    @ResultMap("knowledgeSourceSummary")
    @Select("""
            select source.knowledge_source_id, source.workspace_id, source.title, source.filename,
                   source.content_type, source.byte_size, source.status, source.failure_reason,
                   source.passage_count, source.created_at, source.ingested_at
            from knowledge_source source
            join workspace_membership membership on membership.workspace_id = source.workspace_id
            where source.knowledge_source_id = #{knowledgeSourceId}
              and source.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
            """)
    Optional<KnowledgeSource> findById(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId,
            @Param("knowledgeSourceId") String knowledgeSourceId);

    /** 读取当前用户 Workspace 内的知识来源列表，不返回全文文本。 */
    @Results(id = "knowledgeSourceSummary", value = {
            @Result(column = "knowledge_source_id", property = "knowledgeSourceId"),
            @Result(column = "workspace_id", property = "workspaceId"),
            @Result(column = "content_type", property = "contentType"),
            @Result(column = "byte_size", property = "byteSize"),
            @Result(column = "failure_reason", property = "failureReason"),
            @Result(column = "passage_count", property = "passageCount"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "ingested_at", property = "ingestedAt")
    })
    @Select("""
            select source.knowledge_source_id, source.workspace_id, source.title, source.filename,
                   source.content_type, source.byte_size, source.status, source.failure_reason,
                   source.passage_count, source.created_at, source.ingested_at
            from knowledge_source source
            join workspace_membership membership on membership.workspace_id = source.workspace_id
            where source.workspace_id = #{workspaceId}
              and membership.user_subject = #{userSubject}
            order by source.created_at desc, source.knowledge_source_id desc
            """)
    List<KnowledgeSource> list(
            @Param("userSubject") String userSubject,
            @Param("workspaceId") String workspaceId);

    /** 摄取段落；段落冗余工作区标识用于检索隔离与引用归属校验。 */
    @Insert("""
            <script>
            insert into knowledge_passage (
                knowledge_passage_id, knowledge_source_id, workspace_id, passage_number, text
            )
            values
            <foreach collection="passages" item="passage" separator=",">
                (#{passage.knowledgePassageId}, #{passage.knowledgeSourceId}, #{passage.workspaceId},
                 #{passage.passageNumber}, #{passage.text})
            </foreach>
            </script>
            """)
    int insertPassages(@Param("passages") List<KnowledgePassage> passages);

    /** 读取 Workspace 全部段落用于确定性检索评分。 */
    @Results(id = "knowledgePassageRow", value = {
            @Result(column = "knowledge_source_id", property = "knowledgeSourceId"),
            @Result(column = "passage_number", property = "passageNumber"),
            @Result(column = "text", property = "text")
    })
    @Select("""
            select passage.knowledge_source_id, source.title, passage.passage_number, passage.text
            from knowledge_passage passage
            join knowledge_source source on source.knowledge_source_id = passage.knowledge_source_id
            where passage.workspace_id = #{workspaceId}
              and source.status = 'READY'
            order by passage.knowledge_source_id, passage.passage_number
            """)
    List<KnowledgePassageRow> listPassages(@Param("workspaceId") String workspaceId);
}
