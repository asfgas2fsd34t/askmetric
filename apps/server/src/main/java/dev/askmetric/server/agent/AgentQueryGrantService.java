package dev.askmetric.server.agent;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 为已派发的 Agent Run 签发短期 HMAC 查询授权。
 * 授权与 runId 绑定、带过期时间、由服务端密钥签名；Python 持有它才能调用 Agent 查询端点。
 */
@Component
public class AgentQueryGrantService {
    private final byte[] secret;
    private final long ttlSeconds;

    public AgentQueryGrantService(
            @Value("${askmetric.agent-query.secret}") String secret,
            @Value("${askmetric.agent-query.grant-ttl-seconds}") long ttlSeconds) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("askmetric.agent-query.secret must not be blank");
        }
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("askmetric.agent-query.grant-ttl-seconds must be positive");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    /** 为 runId 签发授权：格式为 {@code <过期时间戳秒>.<base64url(HMAC)>}。 */
    public String mint(String runId, Instant now) {
        long expiresAt = now.getEpochSecond() + ttlSeconds;
        return expiresAt + "." + signature(runId, expiresAt);
    }

    /** 校验授权是否属于 runId、签名是否匹配且未过期。 */
    public boolean validate(String runId, String grant, Instant now) {
        if (runId == null || grant == null) {
            return false;
        }
        int separator = grant.indexOf('.');
        if (separator <= 0 || separator == grant.length() - 1) {
            return false;
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(grant.substring(0, separator));
        } catch (NumberFormatException exception) {
            return false;
        }
        if (now.getEpochSecond() >= expiresAt) {
            return false;
        }
        return MessageDigest.isEqual(
                signature(runId, expiresAt).getBytes(StandardCharsets.UTF_8),
                grant.substring(separator + 1).getBytes(StandardCharsets.UTF_8));
    }

    private String signature(String runId, long expiresAt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal((runId + "." + expiresAt).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }
}
