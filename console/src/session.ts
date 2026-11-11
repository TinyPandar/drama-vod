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
  tenantLabel: read("vod.key.label")
});

export function setAdmin(token: string, user: string, expiresAt: string) {
  session.adminToken = token; session.adminUser = user; session.adminExpiresAt = expiresAt;
  write("vod.admin.token", token); write("vod.admin.user", user); write("vod.admin.expires", expiresAt);
}

export function clearAdmin() {
  setAdmin("", "", "");
}

export function setTenantKey(key: string, label: string) {
  session.tenantKey = key; session.tenantLabel = label;
  write("vod.key", key); write("vod.key.label", label);
}
