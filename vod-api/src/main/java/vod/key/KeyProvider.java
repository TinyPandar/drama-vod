package vod.key;

import java.util.Optional;

/**
 * Core 内容密钥的读取入口（路线图第 08 节）。Worker 侧写入对应 worker/key_provider.py。
 *
 * <p>本阶段只有开发用的明文实现 {@link PlaintextKeyProvider}；生产换 KMS / 信封加密时实现这个接口，
 * /keys/v1/{asset_id} 返回的 16 字节不变。
 */
public interface KeyProvider {
    /** 资产的 AES-128 key（16 字节）；没有 key 返回 empty。 */
    Optional<byte[]> contentKey(long assetId);
}
