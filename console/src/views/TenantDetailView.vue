<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { adminApi, bytes, post, when, type ApiKey, type Delivery, type Template, type Tenant, type Usage } from "../api";
import { setTenantKey } from "../session";
import Notice from "../components/Notice.vue";
import { useAction } from "../useAction";

const route = useRoute();
const router = useRouter();
const id = computed(() => Number(route.params.id));
const tenant = ref<Tenant | null>(null);
const keys = ref<ApiKey[]>([]);
const usage = ref<Usage | null>(null);
const templates = ref<Template[]>([]);
const deliveries = ref<Delivery[]>([]);
const quota = reactive({ storage: "", concurrent: "", daily: "" });
const newKey = reactive({ name: "", upload: true, asset: true, job: true, ttl: "" });
const fresh = ref<ApiKey | null>(null);  // 明文只在这里显示一次，离开页面就没了
const tpl = reactive({ name: "", ladder: '[["720p",720,2500],["360p",360,600]]', audio: "96" });
const { busy, message, error, run } = useAction();

function num(v: string): number | null {
  return v.trim() === "" ? null : Number(v);
}

async function load() {
  tenant.value = await adminApi<Tenant>(`/tenants/${id.value}`);
  quota.storage = tenant.value.storage_quota_bytes?.toString() ?? "";
  quota.concurrent = tenant.value.max_concurrent_jobs?.toString() ?? "";
  quota.daily = tenant.value.max_daily_transcodes?.toString() ?? "";
  keys.value = (await adminApi<{ keys: ApiKey[] }>(`/tenants/${id.value}/keys`)).keys;
  usage.value = await adminApi<Usage>(`/tenants/${id.value}/usage?days=7`);
  templates.value = (await adminApi<{ templates: Template[] }>(`/templates?tenant_id=${id.value}`)).templates;
  deliveries.value = (await adminApi<{ deliveries: Delivery[] }>(`/webhook-deliveries?tenant_id=${id.value}&limit=20`)).deliveries;
}

function saveQuota() {
  run(async () => {
    await adminApi(`/tenants/${id.value}`, {
      method: "PATCH",
      body: JSON.stringify({ storage_quota_bytes: num(quota.storage), max_concurrent_jobs: num(quota.concurrent),
        max_daily_transcodes: num(quota.daily) })
    });
    await load();
    return "配额已保存";
  });
}

function createKey() {
  run(async () => {
    const scopes = (["upload", "asset", "job"] as const).filter((s) => newKey[s]);
    const body: Record<string, unknown> = { scopes };
    if (newKey.name) body.name = newKey.name;
    if (newKey.ttl) body.ttl_seconds = Number(newKey.ttl);
    fresh.value = await adminApi<ApiKey>(`/tenants/${id.value}/keys`, post(body));
    await load();
  });
}

function rotate(k: ApiKey) {
  run(async () => {
    fresh.value = await adminApi<ApiKey>(`/keys/${k.id}/rotate`, post({ grace_seconds: 0 }));
    await load();
  });
}

function revoke(k: ApiKey) {
  if (!confirm(`撤销 Key #${k.id}？撤销后立即不能再调用接口。`)) return;
  run(async () => {
    await adminApi(`/keys/${k.id}/revoke`, post());
    await load();
    return `Key #${k.id} 已撤销`;
  });
}

function useFresh() {
  if (!fresh.value?.api_key || !tenant.value) return;
  setTenantKey(fresh.value.api_key, `${tenant.value.name} · ${fresh.value.key_prefix}…`);
  fresh.value = null;
  router.push("/assets");
}

function createTemplate() {
  run(async () => {
    await adminApi(`/tenants/${id.value}/templates`, post({ name: tpl.name, ladder: JSON.parse(tpl.ladder), audio_kbps: Number(tpl.audio) }));
    await load();
    return `模板 ${tpl.name} 已创建`;
  });
}

onMounted(() => run(load));
</script>

<template>
  <p><RouterLink to="/tenants">← 租户列表</RouterLink></p>
  <h1 v-if="tenant">#{{ tenant.id }} {{ tenant.name }} <span class="muted small">{{ tenant.status }}</span></h1>
  <Notice :text="message" :error="error" />

  <div v-if="fresh?.api_key" class="card">
    <strong>新 Key 明文（只显示这一次，关闭或刷新后无法再看到）</strong>
    <p class="secret">{{ fresh.api_key }}</p>
    <div class="row">
      <button @click="useFresh">在本标签页用它调租户接口</button>
      <button class="ghost" @click="fresh = null">我已保存，关闭</button>
    </div>
  </div>

  <h2>配额</h2>
  <div class="row">
    <div class="field"><label>存储配额（字节）</label><input v-model="quota.storage" inputmode="numeric" placeholder="默认" /></div>
    <div class="field"><label>并发任务上限</label><input v-model="quota.concurrent" inputmode="numeric" placeholder="默认" /></div>
    <div class="field"><label>每日转码次数上限</label><input v-model="quota.daily" inputmode="numeric" placeholder="默认" /></div>
    <button :disabled="busy" @click="saveQuota">保存</button>
  </div>
  <p class="muted small">留空 = 服务端默认，0 = 不限制。</p>

  <h2>API Key</h2>
  <div class="row">
    <div class="field"><label>名称</label><input v-model="newKey.name" maxlength="100" /></div>
    <div class="field"><label>有效期（秒，可空）</label><input v-model="newKey.ttl" inputmode="numeric" /></div>
    <label><input v-model="newKey.upload" type="checkbox" style="width:auto" /> upload</label>
    <label><input v-model="newKey.asset" type="checkbox" style="width:auto" /> asset</label>
    <label><input v-model="newKey.job" type="checkbox" style="width:auto" /> job</label>
    <button :disabled="busy" @click="createKey">新建 Key</button>
  </div>
  <table>
    <thead><tr><th>ID</th><th>名称</th><th>前缀</th><th>scopes</th><th>状态</th><th>过期</th><th>创建</th><th></th></tr></thead>
    <tbody>
      <tr v-for="k in keys" :key="k.id">
        <td>{{ k.id }}</td><td>{{ k.name || "—" }}</td><td class="mono">{{ k.key_prefix ? k.key_prefix + "…" : "（旧 key）" }}</td>
        <td>{{ k.scopes }}</td><td>{{ k.status }}</td><td>{{ when(k.expires_at) }}</td><td>{{ when(k.created_at) }}</td>
        <td>
          <template v-if="k.status === 'ACTIVE'">
            <button class="ghost" :disabled="busy" @click="rotate(k)">轮换</button>
            <button class="danger" :disabled="busy" @click="revoke(k)">撤销</button>
          </template>
        </td>
      </tr>
    </tbody>
  </table>

  <h2>用量（近 7 天）</h2>
  <template v-if="usage">
    <p>已用存储 {{ bytes(usage.storage_used_bytes) }} / {{ bytes(usage.quota.storage_bytes) }} ·
      进行中任务 {{ usage.concurrency.active_jobs }}（运行 {{ usage.concurrency.running_jobs }}）/ {{ usage.quota.max_concurrent_jobs ?? "不限" }} ·
      今日转码 {{ usage.transcodes_today }} / {{ usage.quota.max_daily_transcodes ?? "不限" }}</p>
    <table>
      <thead><tr><th>日期</th><th>上传字节</th><th>播放</th><th>转码</th></tr></thead>
      <tbody><tr v-for="d in usage.history" :key="d.day"><td>{{ d.day }}</td><td>{{ bytes(d.storage_bytes) }}</td><td>{{ d.play_requests }}</td><td>{{ d.transcode_jobs }}</td></tr></tbody>
    </table>
  </template>

  <h2>转码模板</h2>
  <table>
    <thead><tr><th>名称</th><th>归属</th><th>梯队</th><th>音频</th><th>状态</th></tr></thead>
    <tbody>
      <tr v-for="t in templates" :key="t.id">
        <td>{{ t.name }}</td><td>{{ t.system ? "系统" : "租户" }}</td>
        <td class="mono">{{ t.ladder.map((r) => `${r[0]}@${r[2]}k`).join(" ") }}</td><td>{{ t.audio_kbps }}k</td><td>{{ t.status }}</td>
      </tr>
    </tbody>
  </table>
  <div class="row">
    <div class="field"><label>模板名（[a-z0-9_]）</label><input v-model="tpl.name" /></div>
    <div class="field"><label>梯队 JSON [[档名, 短边, kbps], …]</label><input v-model="tpl.ladder" class="mono" /></div>
    <div class="field"><label>音频 kbps</label><input v-model="tpl.audio" inputmode="numeric" /></div>
    <button :disabled="busy" @click="createTemplate">新建租户模板</button>
  </div>

  <h2>最近的 Webhook 投递</h2>
  <table>
    <thead><tr><th>ID</th><th>端点</th><th>事件</th><th>任务</th><th>状态</th><th>次数</th><th>响应</th><th>错误</th></tr></thead>
    <tbody>
      <tr v-for="d in deliveries" :key="d.id">
        <td>{{ d.id }}</td><td>{{ d.endpoint_id }}</td><td>{{ d.event_type }}</td><td>{{ d.job_id ?? "—" }}</td>
        <td>{{ d.status }}</td><td>{{ d.attempts }}</td><td>{{ d.response_code ?? "—" }}</td><td class="small">{{ d.last_error ?? "" }}</td>
      </tr>
    </tbody>
  </table>
</template>
