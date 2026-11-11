<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { post, tenantApi, when, type Delivery, type Webhook } from "../api";
import Notice from "../components/Notice.vue";
import { useAction } from "../useAction";

const EVENTS = ["transcode.succeeded", "transcode.failed", "transcode.cancelled", "transcode.requested"];
const hooks = ref<Webhook[]>([]);
const deliveries = ref<Delivery[]>([]);
const url = ref("https://");
const chosen = reactive<Record<string, boolean>>({ "transcode.succeeded": true, "transcode.failed": true, "transcode.cancelled": true });
const secret = ref("");  // 签名密钥只在登记响应里出现一次
const { busy, message, error, run } = useAction();

async function load() {
  hooks.value = (await tenantApi<{ webhooks: Webhook[] }>("/webhooks")).webhooks;
  deliveries.value = (await tenantApi<{ deliveries: Delivery[] }>("/webhook-deliveries?limit=50")).deliveries;
}

function register() {
  run(async () => {
    const created = await tenantApi<Webhook>("/webhooks", post({ url: url.value, events: EVENTS.filter((e) => chosen[e]) }));
    secret.value = created.secret ?? "";
    await load();
  });
}

function toggle(h: Webhook) {
  run(async () => {
    await tenantApi(`/webhooks/${h.id}/${h.status === "ACTIVE" ? "disable" : "enable"}`, post());
    await load();
  });
}

onMounted(() => run(load));
</script>

<template>
  <h1>Webhook</h1>
  <p class="muted small">只接受公网 HTTPS 地址（登记和每次投递都会解析并检查，连接钉在检查过的 IP 上）。请求体用 HMAC-SHA256 签名，放在 X-Vod-Signature；按 X-Vod-Event-Id 去重。</p>
  <form @submit.prevent="register">
    <label for="u">回调地址</label>
    <input id="u" v-model="url" />
    <div class="row">
      <label v-for="e in EVENTS" :key="e" class="small"><input v-model="chosen[e]" type="checkbox" style="width:auto" /> {{ e }}</label>
      <button type="submit" :disabled="busy">登记</button>
    </div>
  </form>
  <Notice :text="message" :error="error" />
  <div v-if="secret" class="card">
    <strong>签名密钥（只显示这一次）</strong>
    <p class="secret">{{ secret }}</p>
    <button class="ghost" @click="secret = ''">我已保存，关闭</button>
  </div>
  <table>
    <thead><tr><th>ID</th><th>地址</th><th>事件</th><th>状态</th><th>创建</th><th></th></tr></thead>
    <tbody>
      <tr v-for="h in hooks" :key="h.id">
        <td>{{ h.id }}</td><td class="mono">{{ h.url }}</td><td class="small">{{ h.events }}</td><td>{{ h.status }}</td>
        <td>{{ when(h.created_at) }}</td>
        <td><button class="ghost" :disabled="busy" @click="toggle(h)">{{ h.status === "ACTIVE" ? "停用" : "启用" }}</button></td>
      </tr>
    </tbody>
  </table>
  <h2>投递记录</h2>
  <div class="row"><button class="ghost" :disabled="busy" @click="run(load)">刷新</button></div>
  <table>
    <thead><tr><th>ID</th><th>端点</th><th>事件</th><th>任务</th><th>状态</th><th>次数</th><th>响应</th><th>下次</th><th>错误</th></tr></thead>
    <tbody>
      <tr v-for="d in deliveries" :key="d.id">
        <td>{{ d.id }}</td><td>{{ d.endpoint_id }}</td><td>{{ d.event_type }}</td>
        <td><RouterLink v-if="d.job_id" :to="`/jobs/${d.job_id}`">#{{ d.job_id }}</RouterLink></td>
        <td>{{ d.status }}</td><td>{{ d.attempts }}</td><td>{{ d.response_code ?? "—" }}</td>
        <td>{{ when(d.next_attempt_at) }}</td><td class="small">{{ d.last_error ?? "" }}</td>
      </tr>
    </tbody>
  </table>
</template>
