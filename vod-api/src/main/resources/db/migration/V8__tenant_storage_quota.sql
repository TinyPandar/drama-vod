-- storage_quota_bytes：NULL 时取配置 vod.quota.default-storage-bytes；<= 0 表示不限制。
-- storage_used_bytes：已通过 complete 校验的源对象字节数，complete 时原子累加并判断上限。
ALTER TABLE vod_tenant ADD COLUMN storage_quota_bytes BIGINT NULL;
ALTER TABLE vod_tenant ADD COLUMN storage_used_bytes BIGINT NOT NULL DEFAULT 0;

UPDATE vod_tenant SET storage_used_bytes = (
  SELECT COALESCE(SUM(o.size), 0) FROM vod_storage_object o
  WHERE o.tenant_id = vod_tenant.id AND o.role = 'SOURCE');
