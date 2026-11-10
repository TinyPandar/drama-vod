package vod.qoe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import vod.media.CorePlayToken;
import vod.security.SecretGuard;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * POST /api/v1/qoe：播放器上报 QoE。ApiKeyFilter 对这个路径放行（sendBeacon 不能带头），
 * 用 body 里的 Core 播放 token 鉴权；token 过期后还接受 vod.qoe.token-grace-seconds 秒（播放时长常超过 token 有效期）。
 *
 * <p>body：{"token": "...", "session_id": "客户端随机串", "events": [
 *   {"type": "startup", "ms": 首帧毫秒}, {"type": "rebuffer", "ms": 卡顿毫秒}, {"type": "error", "code": "fragLoadError"},
 *   {"type": "ended"}]}
 * 租户与资产只从 token 取，不信 body。未知事件类型忽略。body 超过 max-body-bytes 或事件超过 max-events 返回 413。
 * 不落库，只记 Micrometer 指标，返回 204。所有响应 no-store。
 */
@RestController
public class QoeController {
    private static final Logger log = LoggerFactory.getLogger(QoeController.class);
    private static final Pattern SESSION = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final CacheControl NO_STORE = CacheControl.noStore();

    private final ObjectMapper json = new ObjectMapper();
    private final QoeMetrics metrics;
    private final SecretGuard secrets;
    private final long graceSeconds;
    private final int maxBodyBytes;
    private final int maxEvents;

    /** 宽限期、body 与批大小都是配置项，默认值只是开发默认。 */
    public QoeController(QoeMetrics metrics, SecretGuard secrets,
                         @Value("${vod.qoe.token-grace-seconds:3600}") long graceSeconds,
                         @Value("${vod.qoe.max-body-bytes:16384}") int maxBodyBytes,
                         @Value("${vod.qoe.max-events:50}") int maxEvents) {
        this.metrics = metrics;
        this.secrets = secrets;
        this.graceSeconds = Math.max(0, graceSeconds);
        this.maxBodyBytes = Math.max(256, maxBodyBytes);
        this.maxEvents = Math.max(1, maxEvents);
    }

    @PostMapping("/api/v1/qoe")
    public ResponseEntity<?> report(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > maxBodyBytes) return error(413, "body too large");
        byte[] raw = readLimited(request.getInputStream(), maxBodyBytes);
        if (raw == null) return error(413, "body too large");
        JsonNode body;
        try {
            body = json.readTree(raw);
        } catch (IOException e) {
            return error(400, "invalid json");
        }
        if (body == null || !body.isObject()) return error(400, "invalid json");
        JsonNode token = body.get("token");
        var claims = token != null && token.isTextual()
                ? CorePlayToken.verify(token.asText(), secrets.playTokenSecret(), Instant.now().getEpochSecond(), graceSeconds)
                : null;
        if (claims == null) return error(401, "invalid or expired play token");
        JsonNode session = body.get("session_id");
        if (session == null || !session.isTextual() || !SESSION.matcher(session.asText()).matches()) {
            return error(400, "session_id must match [A-Za-z0-9_-]{1,64}");
        }
        JsonNode events = body.get("events");
        if (events == null || !events.isArray()) return error(400, "events must be an array");
        if (events.size() > maxEvents) return error(413, "too many events");
        int accepted = 0;
        for (JsonNode event : events) {
            if (record(event)) accepted++;
        }
        log.debug("qoe tenant={} asset={} viewer={} session={} events={} accepted={}", claims.tenantId(),
                claims.assetId(), claims.viewerId(), session.asText(), events.size(), accepted);
        return ResponseEntity.noContent().cacheControl(NO_STORE).build();
    }

    private boolean record(JsonNode event) {
        if (event == null || !event.isObject() || !event.path("type").isTextual()) return false;
        switch (event.path("type").asText()) {
            case "startup" -> {
                long ms = millis(event.get("ms"));
                if (ms < 0) return false;
                metrics.startup(ms);
            }
            case "rebuffer" -> {
                long ms = millis(event.get("ms"));
                if (ms < 0) return false;
                metrics.rebuffer(ms);
            }
            case "error" -> metrics.error(event.path("code").isTextual() ? event.path("code").asText() : null);
            case "ended" -> {
                // 只确认会话正常结束；没有对应指标，不建表。
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** 非负、有限的毫秒数；否则 -1（忽略该事件）。 */
    private static long millis(JsonNode n) {
        if (n == null || !n.isNumber()) return -1;
        double v = n.asDouble();
        if (!Double.isFinite(v) || v < 0) return -1;
        return (long) v;
    }

    /** 读到 limit+1 字节就判定超限，返回 null。 */
    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        byte[] data = in.readNBytes(limit + 1);
        return data.length > limit ? null : data;
    }

    private static ResponseEntity<Map<String, String>> error(int status, String detail) {
        return ResponseEntity.status(status).cacheControl(NO_STORE).body(Map.of("detail", detail));
    }
}
