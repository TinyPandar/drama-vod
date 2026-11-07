package vod.job;

public interface TranscodePublisher {
    /** 成功投递返回 true。失败返回 false，调用方应把 outbox 留在 PENDING。 */
    boolean publish(String payload);
}
