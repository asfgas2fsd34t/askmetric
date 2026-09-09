package dev.askmetric.server.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentQueryGrantServiceTest {
    @Test
    void validatesAGrantMintedForTheSameRun() {
        AgentQueryGrantService service = new AgentQueryGrantService("test-secret", 3600);
        Instant now = Instant.parse("2026-09-09T00:00:00Z");
        String grant = service.mint("run-1", now);

        assertThat(service.validate("run-1", grant, now.plusSeconds(60))).isTrue();
    }

    @Test
    void rejectsAGrantMintedForAnotherRun() {
        AgentQueryGrantService service = new AgentQueryGrantService("test-secret", 3600);
        Instant now = Instant.parse("2026-09-09T00:00:00Z");
        String grant = service.mint("run-1", now);

        assertThat(service.validate("run-2", grant, now)).isFalse();
    }

    @Test
    void rejectsATamperedOrMalformedGrant() {
        AgentQueryGrantService service = new AgentQueryGrantService("test-secret", 3600);
        Instant now = Instant.parse("2026-09-09T00:00:00Z");
        String grant = service.mint("run-1", now);

        assertThat(service.validate("run-1", grant.substring(0, grant.length() - 2) + "xx", now)).isFalse();
        assertThat(service.validate("run-1", "garbage", now)).isFalse();
        assertThat(service.validate("run-1", null, now)).isFalse();
    }

    @Test
    void rejectsAnExpiredGrant() {
        AgentQueryGrantService service = new AgentQueryGrantService("test-secret", 10);
        Instant now = Instant.parse("2026-09-09T00:00:00Z");
        String grant = service.mint("run-1", now);

        assertThat(service.validate("run-1", grant, now.plusSeconds(11))).isFalse();
    }

    @Test
    void rejectsAGrantSignedWithADifferentSecret() {
        AgentQueryGrantService minting = new AgentQueryGrantService("secret-a", 3600);
        AgentQueryGrantService verifying = new AgentQueryGrantService("secret-b", 3600);
        Instant now = Instant.parse("2026-09-09T00:00:00Z");

        assertThat(verifying.validate("run-1", minting.mint("run-1", now), now)).isFalse();
    }
}
