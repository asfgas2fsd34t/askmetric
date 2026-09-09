package dev.askmetric.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunEventContractTest {
    // 与 Spring Boot 应用一致的 ObjectMapper 行为：Instant 序列化为 ISO 字符串。
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void acceptsAValidVersionOneEvent() {
        var event = new AgentRunEvent(
                "evt-1", 1, AgentRunEventType.COMPLETED, 2,
                Instant.parse("2026-08-28T02:00:00Z"), "conv-1", "run-1",
                "Synthetic Agent Run completed", AgentRunEventSource.PYTHON);

        assertThat(event.getSchemaVersion()).isEqualTo(1);
        assertThat(event.getSequence()).isEqualTo(2);
    }

    @Test
    void deserializesAFindingEventThroughTheRocketMqBoundary() throws Exception {
        // RocketMQ 消费端用 Jackson 反序列化完整事件；finding 载荷必须随构造器一起绑定。
        String payload = """
                {
                  "eventId": "evt-finding-1",
                  "schemaVersion": 1,
                  "eventType": "agent.run.finding",
                  "sequence": 4,
                  "occurredAt": "2026-09-09T02:00:00Z",
                  "conversationId": "conv-1",
                  "runId": "run-1",
                  "message": "已验证：2025-06-01 MRR 下降 180000 美分",
                  "source": "python",
                  "finding": {
                    "conclusion": "已验证：2025-06-01 MRR 下降 180000 美分",
                    "metricDefinitionVersionId": "metric_definition_mrr_v3",
                    "verified": true,
                    "evidenceSnapshotIds": ["evidence_snapshot_1", "evidence_snapshot_2"],
                    "assumptions": ["月末 MRR 由订阅事件累计重建"],
                    "uncertainties": []
                  }
                }
                """;

        AgentRunEvent event = objectMapper.readValue(payload, AgentRunEvent.class);

        assertThat(event.getEventType()).isEqualTo(AgentRunEventType.FINDING);
        assertThat(event.getFinding()).isNotNull();
        assertThat(event.getFinding().isVerified()).isTrue();
        assertThat(event.getFinding().getEvidenceSnapshotIds())
                .containsExactly("evidence_snapshot_1", "evidence_snapshot_2");
        assertThat(objectMapper.writeValueAsString(event)).contains("agent.run.finding");
    }

    @Test
    void deserializedNonFindingEventsCarryNoFindingPayload() throws Exception {
        String payload = """
                {
                  "eventId": "evt-progress-1",
                  "schemaVersion": 1,
                  "eventType": "agent.run.progress",
                  "sequence": 3,
                  "occurredAt": "2026-09-09T02:00:00Z",
                  "conversationId": "conv-1",
                  "runId": "run-1",
                  "message": "阶段 retrieving：检索受治理证据",
                  "source": "python"
                }
                """;

        AgentRunEvent event = objectMapper.readValue(payload, AgentRunEvent.class);

        assertThat(event.getEventType()).isEqualTo(AgentRunEventType.PROGRESS);
        assertThat(event.getFinding()).isNull();
    }

    @Test
    void rejectsAFindingEventWithoutAPayloadAfterDeserialization() {
        String payload = """
                {
                  "eventId": "evt-finding-2",
                  "schemaVersion": 1,
                  "eventType": "agent.run.finding",
                  "sequence": 4,
                  "occurredAt": "2026-09-09T02:00:00Z",
                  "conversationId": "conv-1",
                  "runId": "run-1",
                  "message": "缺少载荷的发现事件",
                  "source": "python"
                }
                """;

        assertThatThrownBy(() -> objectMapper.readValue(payload, AgentRunEvent.class))
                .hasMessageContaining("finding");
    }

    @Test
    void validatesTheFullFindingEventAgainstTheVersionedSchema() {
        var validator = new AgentRunContractValidator(objectMapper);
        var event = new AgentRunEvent(
                "evt-finding-3", 1, AgentRunEventType.FINDING, 4,
                Instant.parse("2026-09-09T02:00:00Z"), "conv-1", "run-1",
                "已验证：下降 180000 美分",
                AgentRunEventSource.PYTHON,
                new AgentRunFinding(
                        "已验证：下降 180000 美分",
                        "metric_definition_mrr_v3",
                        true,
                        List.of("evidence_snapshot_1"),
                        List.of("月末 MRR 由订阅事件累计重建"),
                        List.of(),
                        List.of()));

        validator.validateEvent(event);
    }
}
