<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { post, tenantApi, when, type Delivery, type Webhook } from "../api";
import Icon from "../components/Icon.vue";
import ListState from "../components/ListState.vue";
import Notice from "../components/Notice.vue";
import SecretBox from "../components/SecretBox.vue";
import StatusBadge from "../components/StatusBadge.vue";
import { useAction } from "../useAction";

const EVENTS = ["transcode.succeeded", "transcode.failed", "transcode.cancelled", "transcode.requested"];
const hooks = ref<Webhook[]>([]);
const deliveries = ref<Delivery[]>([]);
const url = ref("https://");
const chosen = reactive<Record<string, boolean>>({ "transcode.succeeded": true, "transcode.failed": true, "transcode.cancelled": true });
const secret = ref("");  // 签名密钥只在登记响应里出现一次
const { busy, message, raw, error, loaded, run } = useAction();

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
  <div class="page-head">
    <p>只接受公网 HTTPS 地址（登记和每次投递都会解析并检查，连接钉在检查过的 IP 上）。请求体用 HMAC-SHA256 签名，放在 X-Vod-Signature；按 X-Vod-Event-Id 去重。</p>
    <div class="btn-row"><button class="btn" type="button" :disabled="busy" @click="run(load)"><Icon name="refresh" />刷新</button></div>
  </div>

  <Notice :text="message" :error="error" :raw="raw" />

  <SecretBox v-if="secret" title="签名密钥只显示这一次" :value="secret" @close="secret = ''" />

  <section class="card" aria-labelledby="reg-title">
    <div class="card-head"><h3 id="reg-title">登记回调地址</h3></div>
    <form class="card-body stack" @submit.prevent="register">
      <div class="field" style="max-width: 640px">
        <label for="u">回调地址</label>
        <input id="u" v-model="url" class="input mono" />
      </div>
      <div class="field">
        <span class="label">订阅的事件</span>
        <div class="btn-row" style="gap: 16px">
          <label v-for="e in EVENTS" :key="e" class="check"><input v-model="chosen[e]" type="checkbox" /> <span class="mono">{{ e }}</span></label>
        </div>
      </div>
      <div class="btn-row"><button class="btn primary" type="submit" :disabled="busy"><Icon name="plus" />登记</button></div>
    </form>
  </section>

  <section class="card" aria-labelledby="hooks-title">
    <div class="card-head"><h3 id="hooks-title">已登记的端点</h3></div>
    <div v-if="hooks.length" class="table-wrap">
      <table class="tbl">
        <thead><tr><th>ID</th><th>地址</th><th>事件</th><th>状态</th><th>创建时间</th><th class="ops">操作</th></tr></thead>
        <tbody>
          <tr v-for="h in hooks" :key="h.id">
            <td class="mono">{{ h.id }}</td><td class="mono break">{{ h.url }}</td><td class="mono small wrap">{{ h.events }}</td>
            <td><StatusBadge :value="h.status" /></td><td class="num">{{ when(h.created_at) }}</td>
            <td class="ops"><button class="btn sm" :class="{ danger: h.status === 'ACTIVE' }" type="button" :disabled="busy" @click="toggle(h)">{{ h.status === "ACTIVE" ? "停用" : "启用" }}</button></td>
          </tr>
        </tbody>
      </table>
    </div>
    <ListState :loaded="loaded" :count="hooks.length" :failed="error" title="还没有登记回调地址" hint="在上面登记一个公网 HTTPS 地址。签名密钥只在登记成功后显示一次。" />
  </section>

  <section class="card" aria-labelledby="del-title">
    <div class="card-head"><h3 id="del-title">投递记录<span class="sub">最近 50 条</span></h3></div>
    <div v-if="deliveries.length" class="table-wrap">
      <table class="tbl">
        <thead><tr><th>ID</th><th>端点</th><th>事件</th><th>任务</th><th>状态</th><th class="r">次数</th><th class="r">响应</th><th>下次</th><th>错误</th></tr></thead>
        <tbody>
          <tr v-for="d in deliveries" :key="d.id">
            <td class="mono">{{ d.id }}</td><td class="mono">{{ d.endpoint_id }}</td><td class="mono">{{ d.event_type }}</td>
            <td class="mono"><RouterLink v-if="d.job_id" :to="`/jobs/${d.job_id}`">#{{ d.job_id }}</RouterLink><span v-else class="faint">—</span></td>
            <td><StatusBadge :value="d.status" /></td><td class="r num">{{ d.attempts }}</td><td class="r num">{{ d.response_code ?? "—" }}</td>
            <td class="num">{{ when(d.next_attempt_at) }}</td><td class="small wrap">{{ d.last_error ?? "" }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <ListState :loaded="loaded" :count="deliveries.length" :failed="error" title="还没有投递记录" />
  </section>
</template>
