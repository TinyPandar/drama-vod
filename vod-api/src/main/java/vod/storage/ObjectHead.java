package vod.storage;

/** HEAD 得到的对象元数据。etag 去掉了引号；存储没返回的字段为 null，不编造。 */
public record ObjectHead(long size, String etag, String contentType, String checksum) {
}
