package vod.qoe;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 播放 QoE 指标。Prometheus 名：
 * vod_qoe_startup_seconds（histogram）、vod_qoe_rebuffer_total、vod_qoe_rebuffer_seconds（summary：count/sum/max）、
 * vod_qoe_playback_errors_total{code}。
 *
 * <p>标签只有错误码，取值限定在白名单（vod.qoe.error-codes），白名单外归 other；不带 asset_id / session_id / 租户，
 * 避免基数失控。不设阈值或 SLO。
 */
@Component
public class QoeMetrics {
    public static final String OTHER = "other";

    private final MeterRegistry registry;
    private final Timer startup;
    private final Counter rebuffers;
    private final DistributionSummary rebufferSeconds;
    private final Set<String> errorCodes;

    public QoeMetrics(MeterRegistry registry,
                      @Value("${vod.qoe.error-codes:manifestLoadError,manifestLoadTimeOut,manifestParsingError,"
                              + "levelLoadError,levelLoadTimeOut,fragLoadError,fragLoadTimeOut,fragParsingError,"
                              + "fragDecryptError,keyLoadError,keyLoadTimeOut,bufferAppendError,bufferStalledError,"
                              + "mediaError,networkError,native}") String errorCodes) {
        this.registry = registry;
        this.startup = Timer.builder("vod.qoe.startup")
                .description("time from play request to first frame, reported by players")
                .publishPercentileHistogram()
                .register(registry);
        this.rebuffers = Counter.builder("vod.qoe.rebuffer")
                .description("rebuffer (stall) events reported by players").register(registry);
        this.rebufferSeconds = DistributionSummary.builder("vod.qoe.rebuffer.seconds")
                .description("stall durations in seconds reported by players").register(registry);
        this.errorCodes = Arrays.stream(errorCodes.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    public void startup(long millis) {
        startup.record(Duration.ofMillis(millis));
    }

    public void rebuffer(long millis) {
        rebuffers.increment();
        rebufferSeconds.record(millis / 1000.0);
    }

    public String error(String code) {
        String label = code != null && errorCodes.contains(code) ? code : OTHER;
        registry.counter("vod.qoe.playback.errors", "code", label).increment();
        return label;
    }
}
