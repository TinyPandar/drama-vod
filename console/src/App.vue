<script setup lang="ts">
import { useRouter } from "vue-router";
import { clearAdmin, session, setTenantKey } from "./session";

const router = useRouter();

function logout() {
  clearAdmin();
  router.push("/login");
}

function forgetKey() {
  setTenantKey("", "");
  router.push("/key");
}
</script>

<template>
  <header>
    <div class="bar">
      <strong>视频云控制台</strong>
      <span class="muted small">
        <template v-if="session.adminUser">管理员 {{ session.adminUser }} · <a href="#" @click.prevent="logout">退出</a></template>
        <template v-else><RouterLink to="/login">管理登录</RouterLink></template>
        ·
        <template v-if="session.tenantKey">租户 Key {{ session.tenantLabel || "已选用" }} · <a href="#" @click.prevent="forgetKey">换 Key</a></template>
        <template v-else><RouterLink to="/key">选用租户 Key</RouterLink></template>
      </span>
    </div>
    <nav>
      <span class="group">管理端</span>
      <RouterLink to="/tenants">租户与 Key</RouterLink>
      <RouterLink to="/templates">模板</RouterLink>
      <span class="group">租户端</span>
      <RouterLink to="/assets">资产</RouterLink>
      <RouterLink to="/jobs">任务</RouterLink>
      <RouterLink to="/webhooks">Webhook</RouterLink>
      <RouterLink to="/usage">用量与配额</RouterLink>
      <a href="/">短剧场</a>
    </nav>
  </header>
  <main><RouterView /></main>
</template>

<style>
:root { color-scheme: dark; }
* { box-sizing: border-box; }
body { margin: 0; background: #0e0d0c; color: #f3eee6; font: 15px/1.45 "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif; }
header { border-bottom: 1px solid rgba(243,238,230,.12); padding: 10px 16px; position: sticky; top: 0; background: #0e0d0c; z-index: 2; }
.bar { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; align-items: baseline; }
nav { display: flex; gap: 14px; flex-wrap: wrap; margin-top: 8px; align-items: baseline; }
nav .group { color: #7d746b; font-size: 12px; }
nav a.router-link-active { color: #f3eee6; font-weight: 700; }
main { width: min(1000px, 100%); margin: 0 auto; padding: 16px 16px 48px; }
h1 { font-size: 24px; margin: 8px 0 12px; }
h2 { font-size: 18px; margin: 24px 0 8px; }
a { color: #e0b15c; }
label { display: block; color: #a79d92; font-size: 13px; margin: 10px 0 4px; }
input, button, select, textarea { font: inherit; color: inherit; }
input, select, textarea { width: 100%; background: #171512; border: 1px solid rgba(243,238,230,.16); border-radius: 8px; padding: 8px 10px; }
button { min-height: 36px; border-radius: 999px; border: 0; background: #e0b15c; color: #1c140b; font-weight: 700; padding: 0 14px; cursor: pointer; }
button.ghost { background: transparent; color: #e0b15c; border: 1px solid #e0b15c; }
button.danger { background: #c4543d; color: #fff; }
button:disabled { opacity: .5; cursor: default; }
.row { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 10px; align-items: end; }
.row > .field { flex: 1 1 160px; }
table { width: 100%; border-collapse: collapse; margin-top: 8px; }
th, td { text-align: left; padding: 6px; border-bottom: 1px solid rgba(243,238,230,.12); font-size: 14px; vertical-align: top; }
.muted { color: #a79d92; }
.small { font-size: 13px; }
.notice { padding: 8px 12px; border-radius: 8px; background: #1d1a16; margin: 10px 0; }
.notice.error { background: #3a1c16; }
.secret { font-family: ui-monospace, Menlo, monospace; word-break: break-all; background: #241f18; border: 1px dashed #e0b15c; padding: 10px; border-radius: 8px; }
.mono { font-family: ui-monospace, Menlo, monospace; font-size: 13px; word-break: break-all; }
.card { border: 1px solid rgba(243,238,230,.12); border-radius: 10px; padding: 12px; margin-top: 12px; }
video { width: 100%; max-height: 70vh; background: #000; border-radius: 8px; margin-top: 10px; }
@media (max-width: 640px) { table { display: block; overflow-x: auto; } }
</style>
