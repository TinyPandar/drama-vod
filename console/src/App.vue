<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api, type Asset, type Job } from "./api";

const key = ref(sessionStorage.getItem("vod.key") || "dev-demo-key");
const message = ref("");
const assets = ref<Asset[]>([]);
const jobs = ref<Job[]>([]);
const usage = ref("");
const hookUrl = ref("https://hooks.example.com/vod");

async function refresh() {
  sessionStorage.setItem("vod.key", key.value);
  const assetBody = await api<{ assets: Asset[] }>(key.value, "/api/v1/assets");
  const jobBody = await api<{ jobs: Job[] }>(key.value, "/api/v1/jobs");
  const usageBody = await api<{ storage_bytes: number; play_requests: number; transcode_jobs: number }>(key.value, "/api/v1/usage");
  assets.value = assetBody.assets;
  jobs.value = jobBody.jobs;
  usage.value = `存储 ${usageBody.storage_bytes} 字节 · 播放 ${usageBody.play_requests} · 转码 ${usageBody.transcode_jobs}`;
  message.value = "已刷新";
}

function registerHook() {
  api(key.value, "/api/v1/webhooks", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url: hookUrl.value })
  }).then(() => {
    message.value = "Webhook 已登记，签名密钥只显示在接口响应里";
  }).catch((error: unknown) => {
    message.value = error instanceof Error ? error.message : "登记失败";
  });
}

function onRefresh() {
  refresh().catch((error: unknown) => {
    message.value = error instanceof Error ? error.message : "刷新失败";
  });
}

function onUpload(event: Event) {
  upload(event).catch((error: unknown) => {
    message.value = error instanceof Error ? error.message : "上传失败";
  });
}

async function upload(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  message.value = "申请直传地址";
  const opened = await api<{ session_id: number; put_url: string }>(key.value, "/api/v1/uploads", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: "{}"
  });
  message.value = "直传中";
  const put = await fetch(opened.put_url, { method: "PUT", body: file });
  if (!put.ok) throw new Error(`直传失败 ${put.status}`);
  message.value = "提交转码";
  await api(key.value, `/api/v1/uploads/${opened.session_id}/complete`, { method: "POST" });
  await refresh();
  message.value = "已投递转码任务";
}

onMounted(() => {
  refresh().catch((error: Error) => {
    message.value = error.message;
  });
});
</script>

<template>
  <main>
    <p><a href="/">返回短剧场</a></p>
    <h1>视频云控制台</h1>
    <p class="muted">Vue 3。只显示当前 API Key 的租户数据。解锁仍在短剧页。</p>
    <label for="key">API Key</label>
    <input id="key" v-model="key" autocomplete="off" />
    <div class="row">
      <button type="button" @click="onRefresh">刷新</button>
      <label class="file">上传视频<input type="file" accept="video/*" hidden @change="onUpload" /></label>
    </div>
    <p class="muted">{{ message }}</p>
    <h2>今日用量</h2>
    <p>{{ usage }}</p>
    <h2>Webhook</h2>
    <input v-model="hookUrl" />
    <div class="row"><button type="button" @click="registerHook">登记 HTTPS 回调</button></div>
    <h2>资产</h2>
    <table>
      <thead><tr><th>ID</th><th>状态</th><th>大小</th></tr></thead>
      <tbody>
        <tr v-for="item in assets" :key="item.id">
          <td>{{ item.id }}</td><td>{{ item.status }}</td><td>{{ item.source_size }}</td>
        </tr>
      </tbody>
    </table>
    <h2>转码任务</h2>
    <table>
      <thead><tr><th>任务</th><th>资产</th><th>状态</th><th>尝试</th><th>版本</th></tr></thead>
      <tbody>
        <tr v-for="item in jobs" :key="item.id">
          <td>{{ item.id }}</td><td>{{ item.asset_id }}</td><td>{{ item.status }}</td>
          <td>{{ item.attempts }}</td><td>{{ item.claim_version }}</td>
        </tr>
      </tbody>
    </table>
  </main>
</template>

<style>
:root { color-scheme: dark; }
body { margin: 0; background: #0e0d0c; color: #f3eee6; font: 15px/1.45 "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif; }
main { width: min(880px, 100%); margin: 0 auto; padding: 20px 16px 48px; }
h1 { font-size: 28px; margin: 8px 0; }
a { color: #e0b15c; }
label { display: block; color: #a79d92; font-size: 13px; margin: 14px 0 6px; }
input, button { font: inherit; color: inherit; }
input { width: 100%; background: #171512; border: 1px solid rgba(243,238,230,.16); border-radius: 10px; padding: 10px 12px; }
button, .file { min-height: 44px; border-radius: 999px; border: 0; background: #e0b15c; color: #1c140b; font-weight: 700; padding: 0 16px; display: inline-flex; align-items: center; }
.row { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 12px; }
table { width: 100%; border-collapse: collapse; }
th, td { text-align: left; padding: 8px 6px; border-bottom: 1px solid rgba(243,238,230,.12); }
.muted { color: #a79d92; }
@media (max-width: 640px) { table { display: block; overflow-x: auto; } }
</style>
