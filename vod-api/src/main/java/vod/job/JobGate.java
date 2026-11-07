package vod.job;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** (assetId, profile) 唯一。重复提交返回同一个任务号，对应 transcode_job 的唯一键。 */
public final class JobGate {
    private final Map<String, Long> jobs = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    public long getOrCreate(long assetId, String profile) {
        if (profile == null || profile.isBlank()) throw new IllegalArgumentException("profile");
        return jobs.computeIfAbsent(assetId + "\0" + profile, key -> seq.getAndIncrement());
    }
}
