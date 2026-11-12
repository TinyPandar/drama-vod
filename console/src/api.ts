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

/** 管理端：Authorization: Bearer 管理 JWT。401 时清掉登录状态。 */
export async function adminApi<T>(path: string, init: RequestInit = {}): Promise<T> {
  if (!session.adminToken) throw new ApiError("请先登录管理端", 401);
  try {
    return await request<T>(`/api/v1/admin${path}`, init, { Authorization: `Bearer ${session.adminToken}` });
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) clearAdmin();
    throw e;
  }
}

/** 租户端：X-Api-Key 用当前选用的租户 Key（管理 JWT 在这里无效）。 */
export async function tenantApi<T>(path: string, init: RequestInit = {}): Promise<T> {
  if (!session.tenantKey) throw new ApiError("请先选用一个租户 API Key", 401);
  return request<T>(`/api/v1${path}`, init, { "X-Api-Key": session.tenantKey });
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

export function describe(error: unknown): string {
  if (error instanceof ApiError) {
    const extra = error.retryAfter ? `，${error.retryAfter} 秒后再试` : "";
    return `${error.message}（${error.status}${error.code ? " " + error.code : ""}${extra}）`;
  }
  return error instanceof Error ? error.message : String(error);
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
