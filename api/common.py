"""api 和 worker 共用的连接与队列拓扑（worker 目录里有同一份拷贝）。"""
import json, logging, os, sys, time

import boto3, pika, pymysql, redis
from botocore.config import Config

ENV = os.environ.get
SOURCE_BUCKET = ENV("SOURCE_BUCKET", "vod-source")
HLS_BUCKET = ENV("HLS_BUCKET", "vod-hls")

# ---- 队列拓扑：主队列 + 延迟重试队列（TTL 到期后死信回主队列）+ 死信队列 ----
Q_MAIN, Q_RETRY, Q_DLQ = "transcode", "transcode.retry", "transcode.dlq"


def log(event, **kw):
    """一行一条 JSON 日志，带 trace_id/job_id，方便按链路 grep 或进 Loki/ELK。"""
    print(json.dumps({"ts": time.strftime("%Y-%m-%dT%H:%M:%S"), "event": event, **kw}, ensure_ascii=False),
          file=sys.stdout, flush=True)


def db():
    return pymysql.connect(host=ENV("MYSQL_HOST", "mysql"), user=ENV("MYSQL_USER", "vod"),
                           password=ENV("MYSQL_PASSWORD", ""), database=ENV("MYSQL_DB", "vod"),
                           autocommit=True, cursorclass=pymysql.cursors.DictCursor, charset="utf8mb4")


def rds():
    return redis.Redis.from_url(ENV("REDIS_URL", "redis://redis:6379/0"), decode_responses=True)


def s3(endpoint=None):
    return boto3.client("s3", endpoint_url=endpoint or ENV("S3_ENDPOINT", "http://minio:9000"),
                        aws_access_key_id=ENV("S3_ACCESS_KEY"), aws_secret_access_key=ENV("S3_SECRET_KEY"),
                        region_name="us-east-1",
                        config=Config(signature_version="s3v4", s3={"addressing_style": "path"}))


def mq_channel():
    conn = pika.BlockingConnection(pika.URLParameters(ENV("AMQP_URL")))
    ch = conn.channel()
    ch.queue_declare(Q_MAIN, durable=True)
    ch.queue_declare(Q_RETRY, durable=True, arguments={
        "x-dead-letter-exchange": "", "x-dead-letter-routing-key": Q_MAIN})
    ch.queue_declare(Q_DLQ, durable=True)
    return conn, ch


def transcode_message(job_id: int, asset_id: int, trace_id: str | None) -> dict:
    """转码消息 v1。见 docs/contracts/transcode-message.v1.schema.json。
    消费者只认 job_id，忽略未知字段；没有 schema_version 的旧消息仍按 v1 处理。"""
    return {"schema_version": 1, "job_id": job_id, "asset_id": asset_id, "trace_id": trace_id}


def publish(ch, queue, body: dict, delay_ms: int | None = None):
    props = pika.BasicProperties(delivery_mode=2, content_type="application/json",
                                 expiration=str(delay_ms) if delay_ms else None)
    ch.basic_publish("", queue, json.dumps(body).encode(), props)


def wait_for(name, fn, tries=60):
    for i in range(tries):
        try:
            return fn()
        except Exception as e:  # 依赖还没起来就等一会
            log("wait_dependency", dep=name, attempt=i + 1, err=str(e)[:120])
            time.sleep(2)
    raise RuntimeError(f"{name} not ready")
