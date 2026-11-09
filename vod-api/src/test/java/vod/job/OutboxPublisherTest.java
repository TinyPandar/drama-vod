package vod.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OutboxPublisher 多实例：条件 UPDATE 领取，每行只被一个实例发布；领取过期可接手（至少一次）。 */
@SpringBootTest
@ActiveProfiles("test")
class OutboxPublisherTest {
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM vod_outbox");
    }

    @Test
    void twoInstancesPublishEachRowOnce() throws Exception {
        int rows = 40;
        for (int i = 0; i < rows; i++) pending("{\"job_id\":" + i + "}");
        var sent = new ConcurrentLinkedQueue<String>();
        TranscodePublisher slow = payload -> {
            sent.add(payload);
            try {
                Thread.sleep(2);  // 拉长发布窗口，让两个实例的轮询交错
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return true;
        };
        var a = new OutboxPublisher(jdbc, slow, 7, 300, 60);
        var b = new OutboxPublisher(jdbc, slow, 7, 300, 60);
        var pool = Executors.newFixedThreadPool(2);
        var go = new CountDownLatch(1);
        try {
            for (OutboxPublisher p : List.of(a, b)) {
                pool.submit(() -> {
                    go.await();
                    while (jdbc.queryForObject("SELECT COUNT(*) FROM vod_outbox WHERE status = 'PENDING'", Integer.class) > 0) {
                        p.publishPending();
                    }
                    return null;
                });
            }
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        Map<String, Integer> counts = new HashMap<>();
        sent.forEach(p -> counts.merge(p, 1, Integer::sum));
        assertEquals(rows, counts.size());
        counts.forEach((payload, n) -> assertEquals(1, n, payload));
        assertEquals(rows, jdbc.queryForObject("SELECT COUNT(*) FROM vod_outbox WHERE status = 'PUBLISHED' "
                + "AND locked_by IS NULL AND locked_until IS NULL", Integer.class));
    }

    @Test
    void claimedRowIsSkippedUntilClaimExpiresThenRepublished() {
        long id = pending("{\"job_id\":1}");
        var sent = new ConcurrentLinkedQueue<String>();
        // 实例 A：broker 已确认，但标记 PUBLISHED 之前进程崩溃
        TranscodePublisher crashAfterPublish = payload -> {
            sent.add(payload);
            throw new IllegalStateException("crash before mark");
        };
        var a = new OutboxPublisher(jdbc, crashAfterPublish, 20, 300, 60);
        assertThrows(IllegalStateException.class, a::publishPending);
        assertEquals("PENDING", status(id));
        assertTrue(jdbc.queryForObject("SELECT locked_by FROM vod_outbox WHERE id = ?", String.class, id)
                .startsWith(a.instanceId()));

        var b = new OutboxPublisher(jdbc, payload -> sent.add(payload), 20, 300, 60);
        assertEquals(0, b.publishPending());  // 领取未过期：B 不碰
        assertEquals(1, sent.size());

        jdbc.update("UPDATE vod_outbox SET locked_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), id);  // A 的领取过期
        assertEquals(1, b.publishPending());
        assertEquals(2, sent.size());  // 至少一次：同一条被再次发布，下游按 job_id 幂等
        assertEquals("PUBLISHED", status(id));
    }

    @Test
    void batchSizeAndBackoffCapAreConfigurable() {
        for (int i = 0; i < 5; i++) pending("{\"job_id\":" + i + "}");
        var ok = new OutboxPublisher(jdbc, payload -> true, 2, 300, 60);
        assertEquals(2, ok.publishPending());

        jdbc.update("UPDATE vod_outbox SET attempts = 20 WHERE status = 'PENDING'");
        var failing = new OutboxPublisher(jdbc, payload -> false, 10, 7, 60);
        long before = System.currentTimeMillis();
        assertEquals(0, failing.publishPending());
        List<Timestamp> next = jdbc.queryForList("SELECT next_attempt_at FROM vod_outbox WHERE status = 'PENDING'", Timestamp.class);
        assertEquals(3, next.size());
        for (Timestamp t : next) {
            assertTrue(t.getTime() <= before + 7_000 + 1_000, "backoff capped at max-backoff-seconds");
        }
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM vod_outbox WHERE status = 'PENDING' "
                + "AND attempts = 21 AND locked_by IS NULL", Integer.class));
    }

    private long pending(String payload) {
        jdbc.update("INSERT INTO vod_outbox(tenant_id, event_type, payload, status) VALUES (1, 'transcode.requested', ?, 'PENDING')",
                payload);
        return jdbc.queryForObject("SELECT MAX(id) FROM vod_outbox", Long.class);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM vod_outbox WHERE id = ?", String.class, id);
    }
}
