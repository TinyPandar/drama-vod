-- NULL 表示不过期（例如本地 dev key）。
ALTER TABLE vod_api_credential ADD COLUMN expires_at TIMESTAMP NULL;
