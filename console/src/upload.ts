import { post, tenantApi, type AssetMeta } from "./api";

/** GET /uploads/config：分片大小与阈值都来自服务端配置，前端不写死。 */
export type UploadConfig = {
  part_size_bytes: number; multipart_threshold_bytes: number; max_parts: number; max_parts_per_request: number;
  max_object_bytes: number | null;
};

/** 一个未完成的 multipart 会话：失败后可以「继续上传」（先列出已传分片再补缺的），或取消。 */
export type PendingUpload = { file: File; sessionId: number; assetId: number; partSize: number };

type Progress = (text: string) => void;

const PART_RETRIES = 3;
const PARALLEL = 3;

let cached: UploadConfig | null = null;

export async function uploadConfig(): Promise<UploadConfig> {
  cached ??= await tenantApi<UploadConfig>("/uploads/config");
  return cached;
}

/** 小文件：一次预签名 PUT（与原来完全一样）。返回任务号。 */
export async function putUpload(file: File, encrypted: boolean, progress: Progress, signal: AbortSignal, meta: AssetMeta = {}) {
  progress("申请直传地址");
  const opened = await tenantApi<{ session_id: number; put_url: string; asset_id: number }>("/uploads", post({
    filename: file.name, encrypted, ...meta
  }));
  progress("直传对象存储中（不经过 API）");
  const put = await fetch(opened.put_url, { method: "PUT", body: file, signal });
  if (!put.ok) throw new Error(`直传失败 ${put.status}`);
  const done = await tenantApi<{ job_id: number }>(`/uploads/${opened.session_id}/complete`, post());
  return { assetId: opened.asset_id, jobId: done.job_id };
}

/** 大文件：开 multipart 会话。 */
export async function openMultipart(file: File, encrypted: boolean, meta: AssetMeta = {}): Promise<PendingUpload> {
  const opened = await tenantApi<{ session_id: number; asset_id: number; part_size_bytes: number }>("/uploads", post({
    filename: file.name, encrypted, mode: "multipart", content_type: file.type || undefined, ...meta
  }));
  return { file, sessionId: opened.session_id, assetId: opened.asset_id, partSize: opened.part_size_bytes };
}

/**
 * 断点续传：先问服务端已经收到哪些 part，只传缺的；每个 part 失败重签 URL 再试，最多 PART_RETRIES 次；
 * 全部到齐后带 {part_number, etag} complete。signal 用于「取消」时停掉进行中的 PUT。
 */
export async function runMultipart(up: PendingUpload, progress: Progress, signal: AbortSignal) {
  const cfg = await uploadConfig();
  const total = Math.max(1, Math.ceil(up.file.size / up.partSize));
  const listed = await tenantApi<{ parts: { part_number: number; etag: string; size: number }[] }>(`/uploads/${up.sessionId}/parts`);
  const etags = new Map<number, string>();
  for (const p of listed.parts) {
    // 已传的分片大小对得上才跳过（重选了别的文件时不复用）
    const expected = Math.min(up.partSize, up.file.size - (p.part_number - 1) * up.partSize);
    if (p.part_number <= total && p.size === expected) etags.set(p.part_number, p.etag);
  }
  const missing: number[] = [];
  for (let n = 1; n <= total; n++) if (!etags.has(n)) missing.push(n);
  const report = () => progress(`分片上传 ${etags.size}/${total}（每片 ${Math.round(up.partSize / 1048576)} MB）`);
  report();

  async function sign(numbers: number[]) {
    const out = new Map<number, string>();
    for (let i = 0; i < numbers.length; i += cfg.max_parts_per_request) {
      const batch = numbers.slice(i, i + cfg.max_parts_per_request);
      const signed = await tenantApi<{ parts: { part_number: number; url: string }[] }>(
        `/uploads/${up.sessionId}/parts`, post({ part_numbers: batch }));
      for (const p of signed.parts) out.set(p.part_number, p.url);
    }
    return out;
  }

  const urls = missing.length ? await sign(missing) : new Map<number, string>();
  const queue = [...missing];

  async function sendPart(n: number) {
    const blob = up.file.slice((n - 1) * up.partSize, Math.min(n * up.partSize, up.file.size));
    for (let attempt = 1; ; attempt++) {
      if (signal.aborted) throw new DOMException("已取消", "AbortError");
      try {
        const url = attempt === 1 ? urls.get(n)! : (await sign([n])).get(n)!;
        const res = await fetch(url, { method: "PUT", body: blob, signal });
        const etag = res.headers.get("ETag");
        if (!res.ok || !etag) throw new Error(res.ok ? "存储没有返回 ETag（上传网关需暴露 ETag）" : `HTTP ${res.status}`);
        etags.set(n, etag.replace(/"/g, ""));
        report();
        return;
      } catch (e) {
        if (signal.aborted || attempt >= PART_RETRIES) throw new Error(`分片 ${n} 上传失败：${e instanceof Error ? e.message : e}`);
      }
    }
  }

  // 某片最终失败（或取消）后不再领新分片，但等进行中的分片收尾再报错：
  // 这样失败时服务端的已传列表是完整的，「继续上传」只补真正缺的。
  let firstError: unknown = null;
  async function worker() {
    while (firstError === null && queue.length) {
      const n = queue.shift()!;
      try {
        await sendPart(n);
      } catch (e) {
        firstError ??= e;
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(PARALLEL, queue.length) }, worker));
  if (firstError !== null) throw firstError;

  progress("组合分片并提交转码");
  const parts = [...etags.entries()].sort((a, b) => a[0] - b[0]).map(([part_number, etag]) => ({ part_number, etag }));
  const done = await tenantApi<{ job_id: number }>(`/uploads/${up.sessionId}/complete`, post({ parts }));
  return { assetId: up.assetId, jobId: done.job_id };
}

export async function abortUpload(sessionId: number) {
  await tenantApi(`/uploads/${sessionId}/abort`, post());
}
