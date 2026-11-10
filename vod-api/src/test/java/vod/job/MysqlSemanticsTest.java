package vod.job;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vod.upload.UploadService;
import vod.usage.UsageService;

import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连真实 MySQL（Connector/J）核对 affected rows 语义。默认跳过；设置后运行：
 * VOD_TEST_MYSQL_URL=jdbc:mysql://127.0.0.1:3306 VOD_TEST_MYSQL_USER=root VOD_TEST_MYSQL_PASSWORD=... mvn test
 * 会新建并删除 vod_cj_test 库（需要建库权限），在里面跑 Flyway V1..Vn。
 *
 * <p>Connector/J 默认 useAffectedRows=false，即带 CLIENT_FOUND_ROWS，返回「匹配」行数；PyMySQL 默认返回「变化」行数。
 * vod-api 的条件 UPDATE 依赖前者（例如配额 +0 字节时值不变），所以不要在 JDBC URL 里打开 useAffectedRows。
 */
@EnabledIfEnvironmentVariable(named = "VOD_TEST_MYSQL_URL", matches = ".+")
class MysqlSemanticsTest {
    private static final String DB = "vod_cj_test";
    private static JdbcTemplate jdbc;

    static DriverManagerDataSource ds(String database, String params) {
        String base = System.getenv("VOD_TEST_MYSQL_URL").replaceAll("/+$", "");
        String url = base + "/" + database + "?allowPublicKeyRetrieval=true&useSSL=false" + params;
        String user = System.getenv().getOrDefault("VOD_TEST_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("VOD_TEST_MYSQL_PASSWORD", "");
        return new DriverManagerDataSource(url, user, password);
    }

    @BeforeAll
    static void migrate() {
        var admin = new JdbcTemplate(ds("", ""));
        admin.execute("DROP DATABASE IF EXISTS " + DB);
        admin.execute("CREATE DATABASE " + DB + " CHARACTER SET utf8mb4");
        Flyway.configure().dataSource(ds(DB, "")).load().migrate();
        jdbc = new JdbcTemplate(ds(DB, ""));
    }

    @AfterAll
    static void drop() {
        if (jdbc != null && !"1".equals(System.getenv("VOD_TEST_MYSQL_KEEP"))) {
            new JdbcTemplate(ds("", "")).execute("DROP DATABASE IF EXISTS " + DB);
        }
    }

    @BeforeEach
    void clean() {
        for (String t : List.of("vod_output_prefix", "vod_outbox", "vod_transcode_job", "vod_media_asset", "vod_tenant")) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("INSERT INTO vod_tenant(id, name, storage_quota_bytes) VALUES (1, 'A', 200)");
    }

    @Test
    void connectorJCountsMatchedRowsByDefault() {
        String same = "UPDATE vod_tenant SET name = 'A' WHERE id = 1";
        assertEquals(1, jdbc.update(same));
        assertEquals(0, new JdbcTemplate(ds(DB, "&useAffectedRows=true")).update(same));
    }

    @Test
    void quotaConditionalUpdateOnMysql() {
        var usage = new UsageService(jdbc, 0);
        usage.reserveStorage(1, 128);
        assertThrows(UsageService.QuotaExceededException.class, () -> usage.reserveStorage(1, 100));
        assertDoesNotThrow(() -> usage.reserveStorage(1, 0));  // 值不变但匹配：默认语义返回 1
        var changedRowsOnly = new UsageService(new JdbcTemplate(ds(DB, "&useAffectedRows=true")), 0);
        assertThrows(UsageService.QuotaExceededException.class, () -> changedRowsOnly.reserveStorage(1, 0));
        assertEquals(128L, jdbc.queryForObject("SELECT storage_used_bytes FROM vod_tenant WHERE id = 1", Long.class));
    }

    @Test
    void outboxClaimOnMysqlPublishesEachRowOnce() throws Exception {
        int rows = 40;
        for (int i = 0; i < rows; i++) {
            jdbc.update("INSERT INTO vod_outbox(tenant_id, event_type, payload, status) VALUES (1, 'transcode.requested', ?, 'PENDING')",
                    "{\"job_id\":" + i + "}");
        }
        var sent = new ConcurrentLinkedQueue<String>();
        TranscodePublisher publisher = payload -> sent.add(payload);
        var pool = Executors.newFixedThreadPool(2);
        var go = new CountDownLatch(1);
        try {
            for (int i = 0; i < 2; i++) {
                var p = new OutboxPublisher(new JdbcTemplate(ds(DB, "")), publisher, 7, 300, 60);
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
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        Map<String, Integer> counts = new HashMap<>();
        sent.forEach(p -> counts.merge(p, 1, Integer::sum));
        assertEquals(rows, counts.size());
        counts.forEach((payload, n) -> assertEquals(1, n, payload));

        // 领取未过期不接手，过期后接手
        jdbc.update("INSERT INTO vod_outbox(tenant_id, event_type, payload, status, locked_by, locked_until) "
                        + "VALUES (1, 'transcode.requested', 'stuck', 'PENDING', 'dead', ?)",
                Timestamp.from(Instant.now().plusSeconds(600)));
        var p = new OutboxPublisher(jdbc, publisher, 20, 300, 60);
        assertEquals(0, p.publishPending());
        jdbc.update("UPDATE vod_outbox SET locked_until = ? WHERE payload = 'stuck'", Timestamp.from(Instant.now().minusSeconds(1)));
        assertEquals(1, p.publishPending());
    }

    @Test
    void cancelOnMysql() {
        jdbc.update("INSERT INTO vod_media_asset(id, tenant_id, status, source_bucket, source_key) VALUES (5, 1, 'TRANSCODING', 'b', 'k')");
        jdbc.update("INSERT INTO vod_transcode_job(id, tenant_id, asset_id, profile, status, claim_version, locked_until) "
                + "VALUES (9, 1, 5, 'ladder_v1', 'RUNNING', 2, ?)", Timestamp.from(Instant.now().plusSeconds(600)));
        jdbc.update("INSERT INTO vod_output_prefix(asset_id, job_id, claim_version, prefix, status) "
                + "VALUES (5, 9, 2, 'assets/v1/5/9-2/', 'WRITING')");
        var jobs = new JobService(jdbc);
        jobs.cancel(1, 9);
        assertEquals("GARBAGE", jdbc.queryForObject("SELECT status FROM vod_output_prefix WHERE job_id = 9", String.class));
        Map<String, Object> row = jdbc.queryForMap("SELECT status, claim_version, locked_until FROM vod_transcode_job WHERE id = 9");
        assertEquals("CANCELLED", row.get("status"));
        assertEquals(3, ((Number) row.get("claim_version")).intValue());
        assertEquals(null, row.get("locked_until"));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM vod_media_asset WHERE id = 5", String.class));
        jobs.cancel(1, 9);  // 幂等
        assertEquals(3, jdbc.queryForObject("SELECT claim_version FROM vod_transcode_job WHERE id = 9", Integer.class));
        jdbc.update("UPDATE vod_transcode_job SET status = 'SUCCEEDED' WHERE id = 9");
        assertThrows(UploadService.ConflictException.class, () -> jobs.cancel(1, 9));
    }

    /**
     * 真实死锁（不是模拟异常）：另一个事务先改了很多行、锁住资产，取消事务锁了任务行后去等资产，
     * 另一个事务再去锁任务行 → InnoDB 检测到死锁，回滚改动少的取消事务。核对 Connector/J 报的错
     * 经 JdbcTemplate 翻译后是 PessimisticLockingFailureException，cancelWithRetry 能接住并在外层重试成功。
     */
    @Test
    void realDeadlockIsTranslatedAndCancelRetrySucceeds() throws Exception {
        jdbc.update("INSERT INTO vod_media_asset(id, tenant_id, status, source_bucket, source_key) VALUES (5, 1, 'TRANSCODING', 'b', 'k')");
        jdbc.update("INSERT INTO vod_transcode_job(id, tenant_id, asset_id, profile, status, claim_version) "
                + "VALUES (9, 1, 5, 'ladder_v1', 'RUNNING', 2)");
        var ds = ds(DB, "");
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var jobs = new JobService(new JdbcTemplate(ds));
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        var pool = Executors.newSingleThreadExecutor();
        try (Connection other = ds.getConnection()) {
            other.setAutoCommit(false);
            try (Statement st = other.createStatement()) {
                for (int i = 0; i < 200; i++) {   // 让它比取消事务「重」，InnoDB 选取消当牺牲者
                    st.executeUpdate("INSERT INTO vod_outbox(tenant_id, event_type, payload, status) VALUES (1, 'x', 'y', 'PUBLISHED')");
                }
                st.executeUpdate("UPDATE vod_media_asset SET width = 1 WHERE id = 5");
            }
            var cancel = pool.submit(() -> JobService.cancelWithRetry(() -> {
                try {
                    tx.executeWithoutResult(status -> jobs.cancel(1, 9));
                } catch (RuntimeException e) {
                    failures.add(e);
                    throw e;
                }
            }));
            Thread.sleep(500);  // 取消已锁任务行、在等资产行
            try (Statement st = other.createStatement()) {
                st.executeUpdate("UPDATE vod_transcode_job SET worker_id = 'other' WHERE id = 9");  // 形成环
            }
            Thread.sleep(300);  // 重试的取消现在在等 other 的任务行锁
            other.commit();
            cancel.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, failures.size(), "exactly one deadlock victim attempt: " + failures);
        assertInstanceOf(PessimisticLockingFailureException.class, failures.get(0));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM vod_transcode_job WHERE id = 9", String.class));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM vod_media_asset WHERE id = 5", String.class));
    }
}
