import { reactive } from "vue";

/**
 * 控制台会话，只放在本标签页的 sessionStorage：
 * - 管理 JWT：调 /api/v1/admin/**；
 * - 租户 API Key：调租户接口。明文只在创建/轮换的响应里出现一次，管理员可以选择「在本标签页使用」，之后不再显示。
 */
function read(key: string) {
  try { return sessionStorage.getItem(key) || ""; } catch { return ""; }
}
function write(key: string, value: string) {
  try { value ? sessionStorage.setItem(key, value) : sessionStorage.removeItem(key); } catch { /* 隐私模式 */ }
}

export const session = reactive({
  adminToken: read("vod.admin.token"),
  adminUser: read("vod.admin.user"),
  adminExpiresAt: read("vod.admin.expires"),
  tenantKey: read("vod.key"),
  tenantLabel: read("vod.key.label"),
  /** 服务端用 401 拒绝了当前这把 Key。只是界面状态（不持久化），用来显示「Key 已失效」引导；真正的判断始终在服务端。 */
  tenantKeyRejected: false
});

export function setAdmin(token: string, user: string, expiresAt: string) {
  session.adminToken = token; session.adminUser = user; session.adminExpiresAt = expiresAt;
  write("vod.admin.token", token); write("vod.admin.user", user); write("vod.admin.expires", expiresAt);
}

export function clearAdmin() {
  setAdmin("", "", "");
}

export function setTenantKey(key: string, label: string) {
  session.tenantKey = key; session.tenantLabel = label; session.tenantKeyRejected = false;
  write("vod.key", key); write("vod.key.label", label);
}

/** 管理 JWT 的过期时间已过（只看登录响应里的 expires_at；解析不了就交给服务端判断）。 */
export function adminExpired(): boolean {
  if (!session.adminToken || !session.adminExpiresAt) return false;
  const at = Date.parse(session.adminExpiresAt);
  return !Number.isNaN(at) && at <= Date.now();
}
