import { session, clearAdmin } from "./session";

/** 接口错误：status 是 HTTP 状态码；code 是 rate_limited / quota_exceeded 等；retryAfter 来自 Retry-After。 */
export class ApiError extends Error {
  constructor(message: string, public status: number, public code?: string, public retryAfter?: string) {
    super(message);
  }
}

type Json = Record<string, unknown>;

async function request<T>(path: string, init: RequestInit, auth: Record<string, string>): Promise<T> {
  const headers: Record<string, string> = { ...auth, ...((init.headers as Record<string, string>) ?? {}) };
  if (init.body && !headers["Content-Type"]) headers["Content-Type"] = "application/json";
  const response = await fetch(path, { ...init, headers });
  const text = await response.text();
  let body: Json = {};
  try {
    body = text ? (JSON.parse(text) as Json) : {};
  } catch {
    body = { detail: text.slice(0, 200) };
  }
  if (!response.ok) {
    const detail = typeof body.detail === "string" ? body.detail : `HTTP ${response.status}`;
    throw new ApiError(detail, response.status, body.code as string | undefined, response.headers.get("Retry-After") ?? undefined);
  }
  return body as T;
}

let onAdminExpired: () => void = () => undefined;
/** main.ts 注册：管理登录被服务端判为失效时，带着原因回登录页。 */
export function setAdminExpiredHandler(fn: () => void) {
  onAdminExpired = fn;
}

/** 管理端：Authorization: Bearer 管理 JWT。401 时清掉登录状态并回登录页说明原因。 */
export async function adminApi<T>(path: string, init: RequestInit = {}): Promise<T> {
  if (!session.adminToken) throw new ApiError("请先登录管理端", 401);
  try {
    return await request<T>(`/api/v1/admin${path}`, init, { Authorization: `Bearer ${session.adminToken}` });
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      clearAdmin();
      onAdminExpired();
    }
    throw e;
  }
}

/** 租户端：X-Api-Key 用当前选用的租户 Key（管理 JWT 在这里无效）。401 = 这把 Key 被服务端拒绝，外壳据此显示「Key 已失效」。 */
export async function tenantApi<T>(path: string, init: RequestInit = {}): Promise<T> {
  if (!session.tenantKey) throw new ApiError("请先选用一个租户 API Key", 401);
  try {
    return await request<T>(`/api/v1${path}`, init, { "X-Api-Key": session.tenantKey });
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) session.tenantKeyRejected = true;
    throw e;
  }
}

export async function login(username: string, password: string) {
  return request<{ token: string; expires_at: string; username: string }>("/api/v1/admin/login", {
    method: "POST",
    body: JSON.stringify({ username, password })
  }, {});
}

export function post(body?: unknown): RequestInit {
  return { method: "POST", body: body === undefined ? undefined : JSON.stringify(body) };
}

/** 接口原文：状态码、code、Retry-After 原样拼出来，放在提示的次要位置供排查。 */
export function describe(error: unknown): string {
  if (error instanceof ApiError) {
    const extra = error.retryAfter ? `，${error.retryAfter} 秒后再试` : "";
    return `${error.message}（${error.status}${error.code ? " " + error.code : ""}${extra}）`;
  }
  return error instanceof Error ? error.message : String(error);
}

/**
 * 把失败说成人话：text 是主要说明，raw 是接口原文。
 * 这里只按状态码和 code 归类，不改变服务端的任何判断。
 */
export function explain(error: unknown): { text: string; raw: string } {
  const raw = describe(error);
  if (error instanceof ApiError) {
    const wait = error.retryAfter ? `，${error.retryAfter} 秒后可以再试` : "，请稍后再试";
    switch (error.status) {
      case 400: return { text: "提交的内容不符合要求，请检查后重试。", raw };
      case 401: return { text: "当前身份没有通过验证：登录或 Key 已失效。", raw };
      case 403:
        return /quota/i.test(error.message)
          ? { text: "租户的存储配额已用完，不能再上传。请管理员在「租户与 Key」里调整配额。", raw }
          : { text: "当前身份没有这项操作的权限（例如这把 Key 的权限范围不包含它）。", raw };
      case 404: return { text: "没有找到这条记录：可能已不存在，或不属于当前租户。", raw };
      case 409: return { text: "操作和当前状态冲突：对象正被处理或状态已变化。刷新后再试。", raw };
      case 429:
        return error.code === "quota_exceeded"
          ? { text: `租户配额已用尽：并发任务数或每日转码次数达到上限${wait}，或请管理员调整配额。`, raw }
          : { text: `操作太频繁${wait}。`, raw };
      case 503: return { text: `服务暂时忙${wait}。`, raw };
    }
    if (error.status >= 500) return { text: "服务端出错了，这次操作没有完成。请稍后再试。", raw };
    return { text: "这次操作没有完成。", raw };
  }
  if (error instanceof SyntaxError) return { text: "填写的 JSON 格式不对，请检查括号、引号和逗号。", raw };
  if (error instanceof TypeError) return { text: "连不上服务：可能是网络中断或服务未启动。", raw };
  // upload.ts 等前端自己抛的错误本身就是中文说明
  return { text: raw, raw: "" };
}

/** 接口状态值的中文说法与徽标样式；没收录的值原样显示。 */
const STATUS: Record<string, [string, string]> = {
  ACTIVE: ["启用", "ok"], DISABLED: ["已停用", ""], REVOKED: ["已撤销", ""], EXPIRED: ["已过期", ""],
  UPLOADING: ["上传中", "warn"], UPLOADED: ["已上传", ""], TRANSCODING: ["转码中", "run"], READY: ["可播放", "ok"],
  PENDING: ["排队中", "warn"], RETRYING: ["等待重试", "warn"], RUNNING: ["运行中", "run"], SUCCEEDED: ["成功", "ok"],
  FAILED: ["失败", "bad"], CANCELLED: ["已取消", ""], DELIVERED: ["已送达", "ok"]
};
export function statusInfo(value: string, kind?: "key"): { label: string; tone: string } {
  const hit = STATUS[value];
  if (!hit) return { label: value, tone: "" };
  return { label: kind === "key" && value === "ACTIVE" ? "有效" : hit[0], tone: hit[1] };
}

export type Tenant = {
  id: number; name: string; status: string; storage_quota_bytes: number | null; storage_used_bytes: number;
  max_concurrent_jobs: number | null; max_daily_transcodes: number | null; active_keys: number; created_at: string;
};
export type ApiKey = {
  id: number; tenant_id: number; name: string | null; key_prefix: string | null; scopes: string; status: string;
  expires_at: string | null; created_at: string; revoked_at: string | null; api_key?: string;
};
/** poster_url：Phase 6 封面签名地址（会过期，刷新列表重新签），没有封面为 null。 */
export type Asset = {
  id: number; status: string; source_size: number; encrypted: boolean; hls_prefix?: string | null; poster_url?: string | null;
};
export type Job = {
  id: number; asset_id: number; profile: string; status: string; attempts: number; claim_version: number;
  max_attempts?: number; last_error?: string | null; error_code?: string | null; next_attempt_at?: string | null;
  updated_at?: string | null; template_id?: number | null; idempotent_replay?: boolean;
};
export type Template = {
  id: number; name: string; system: boolean; tenant_id?: number; description: string | null; video_codec: string;
  container: string; ladder: [string, number, number][]; audio_kbps: number; status: string;
};
export type Webhook = { id: number; url: string; events: string; status: string; created_at: string; secret?: string };
export type Delivery = {
  id: number; endpoint_id: number; event_id: number | null; event_type: string; job_id: number | null; status: string;
  attempts: number; response_code: number | null; last_error: string | null; next_attempt_at: string | null;
  created_at: string; delivered_at: string | null; tenant_id: number;
};
export type Usage = {
  day: string; storage_bytes: number; play_requests: number; transcode_jobs: number; storage_used_bytes: number;
  quota: { storage_bytes: number | null; max_concurrent_jobs: number | null; max_daily_transcodes: number | null };
  concurrency: { active_jobs: number; running_jobs: number }; transcodes_today: number;
  history?: { day: string; storage_bytes: number; play_requests: number; transcode_jobs: number }[];
};

export function bytes(n: number | null | undefined): string {
  if (n === null || n === undefined) return "不限";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let v = n, i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(i ? 1 : 0)} ${units[i]}`;
}

export function when(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString() : "—";
}
