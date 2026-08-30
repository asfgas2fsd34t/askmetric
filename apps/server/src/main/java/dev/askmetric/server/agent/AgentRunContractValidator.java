package dev.askmetric.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 将 contracts/ 中版本化 JSON Schema 作为跨 Java/Python 边界的唯一有效载荷约束。
 */
@Component
public class AgentRunContractValidator {
    private static final String REQUEST_SCHEMA = "/contracts/events/agent-run-request.v1.json";
    private static final String EVENT_SCHEMA = "/contracts/events/agent-run-event.v1.json";

    private final ObjectMapper objectMapper;
    private final Schema requestSchema;
    private final Schema eventSchema;

    public AgentRunContractValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        this.requestSchema = loadSchema(registry, REQUEST_SCHEMA);
        this.eventSchema = loadSchema(registry, EVENT_SCHEMA);
    }

    public void validateRequest(AgentRunRequest request) {
        validate(requestSchema, objectMapper.valueToTree(request), "Agent Run request");
    }

    public void validateEvent(AgentRunEvent event) {
        validate(eventSchema, objectMapper.valueToTree(event), "Agent Run event");
    }

    public void validateEventJson(JsonNode payload) {
        validate(eventSchema, payload, "Agent Run event");
    }

    private static Schema loadSchema(SchemaRegistry registry, String resource) {
        // Schema 随服务资源打包，部署时不依赖源码目录或外部文件系统路径。
        try (InputStream stream = AgentRunContractValidator.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing Agent Run contract resource: " + resource);
            }
            return registry.getSchema(stream);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load Agent Run contract: " + resource, exception);
        }
    }

    private static void validate(Schema schema, JsonNode payload, String name) {
        List<Error> errors = schema.validate(payload);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(name + " does not match schema: " + errors.getFirst().getMessage());
        }
    }
}
