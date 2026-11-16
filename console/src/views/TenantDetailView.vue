<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { adminApi, bytes, post, when, type ApiKey, type Delivery, type Template, type Tenant, type Usage } from "../api";
import { setTenantKey } from "../session";
import Icon from "../components/Icon.vue";
import ListState from "../components/ListState.vue";
import Notice from "../components/Notice.vue";
import SecretBox from "../components/SecretBox.vue";
import StatusBadge from "../components/StatusBadge.vue";
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
const { busy, message, raw, error, loaded, run } = useAction();

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
  <div class="page-head">
    <div>
      <h2 v-if="tenant">{{ tenant.name }} <StatusBadge :value="tenant.status" /></h2>
      <h2 v-else>租户 #{{ id }}</h2>
      <p>这个租户的 Key 用来进入「租户端」页面；Key 明文只在新建或轮换后显示一次。</p>
    </div>
    <div class="btn-row">
      <RouterLink class="btn" to="/tenants">返回租户列表</RouterLink>
      <button class="btn" type="button" :disabled="busy" @click="run(load)"><Icon name="refresh" />刷新</button>
    </div>
  </div>

  <Notice :text="message" :error="error" :raw="raw" />

  <SecretBox v-if="fresh?.api_key" title="新 Key 明文只显示这一次" :value="fresh.api_key" @close="fresh = null">
    <button class="btn primary" type="button" @click="useFresh">在本标签页使用这把 Key</button>
  </SecretBox>

  <section class="card" aria-labelledby="keys-title">
    <div class="card-head">
      <h3 id="keys-title">API Key<span v-if="tenant" class="sub">{{ tenant.active_keys }} 把有效</span></h3>
      <span class="id-tag tenant">选用后进入租户端</span>
    </div>
    <div class="card-body" style="border-bottom: 1px solid var(--border)">
      <form class="form-row" @submit.prevent="createKey">
        <div class="field"><label for="kn">名称</label><input id="kn" v-model="newKey.name" class="input" maxlength="100" /></div>
        <div class="field" style="flex: 0 1 200px"><label for="kt">有效期（秒）</label><input id="kt" v-model="newKey.ttl" class="input" inputmode="numeric" placeholder="留空 = 不过期" /></div>
        <div class="field" style="flex: 0 0 auto">
          <span class="label">权限范围</span>
          <div class="btn-row" style="gap: 16px">
            <label class="check"><input v-model="newKey.upload" type="checkbox" /> <span class="mono">upload</span></label>
            <label class="check"><input v-model="newKey.asset" type="checkbox" /> <span class="mono">asset</span></label>
            <label class="check"><input v-model="newKey.job" type="checkbox" /> <span class="mono">job</span></label>
          </div>
        </div>
        <button class="btn primary" type="submit" :disabled="busy"><Icon name="plus" />新建 Key</button>
      </form>
    </div>
    <div v-if="keys.length" class="table-wrap">
      <table class="tbl">
        <thead><tr><th>ID</th><th>名称</th><th>前缀</th><th>权限范围</th><th>状态</th><th>过期时间</th><th>创建时间</th><th class="ops">操作</th></tr></thead>
        <tbody>
          <tr v-for="k in keys" :key="k.id">
            <td class="mono">{{ k.id }}</td>
            <td>{{ k.name || "—" }} <span v-if="fresh?.id === k.id" class="badge run plain">刚创建</span></td>
            <td class="mono">{{ k.key_prefix ? k.key_prefix + "…" : "（旧 key）" }}</td>
            <td class="mono">{{ k.scopes }}</td>
            <td><StatusBadge :value="k.status" kind="key" /></td>
            <td class="num">{{ when(k.expires_at) }}</td><td class="num">{{ when(k.created_at) }}</td>
            <td class="ops">
              <template v-if="k.status === 'ACTIVE'">
                <button class="btn sm" type="button" :disabled="busy" @click="rotate(k)">轮换</button>
                <button class="btn sm danger" type="button" :disabled="busy" @click="revoke(k)">撤销</button>
              </template>
              <span v-else class="faint small">无可用操作</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <ListState :loaded="loaded" :count="keys.length" :failed="error" title="这个租户还没有 Key" hint="在上面新建一把。明文只显示一次，可以直接「在本标签页使用」。" />
    <div class="card-foot">
      <span>列表只显示前缀，用来辨认是哪一把。已有 Key 无法再次查看明文；需要时请「轮换」得到新的明文（旧明文随即失效）。撤销后立即不能再调用接口，操作前会二次确认。</span>
    </div>
  </section>

  <section class="card" aria-labelledby="quota-title">
    <div class="card-head"><h3 id="quota-title">配额</h3></div>
    <div class="card-body">
      <form class="form-row" @submit.prevent="saveQuota">
        <div class="field"><label for="q1">存储配额（字节）</label><input id="q1" v-model="quota.storage" class="input num" inputmode="numeric" placeholder="默认" /></div>
        <div class="field"><label for="q2">并发任务上限</label><input id="q2" v-model="quota.concurrent" class="input num" inputmode="numeric" placeholder="默认" /></div>
        <div class="field"><label for="q3">每日转码次数上限</label><input id="q3" v-model="quota.daily" class="input num" inputmode="numeric" placeholder="默认" /></div>
        <button class="btn primary" type="submit" :disabled="busy">保存配额</button>
      </form>
      <p class="small muted" style="margin-top: 8px">留空 = 服务端默认，0 = 不限制。</p>
    </div>
  </section>

  <section class="card" aria-labelledby="usage-title">
    <div class="card-head"><h3 id="usage-title">用量<span class="sub">近 7 天</span></h3></div>
    <template v-if="usage">
      <div class="card-body stats" style="border-bottom: 1px solid var(--border)">
        <div class="stat"><span class="s-label">已用存储</span><span class="s-value">{{ bytes(usage.storage_used_bytes) }}</span><span class="s-sub">配额 {{ bytes(usage.quota.storage_bytes) }}</span></div>
        <div class="stat"><span class="s-label">进行中任务</span><span class="s-value">{{ usage.concurrency.active_jobs }}</span><span class="s-sub">其中运行 {{ usage.concurrency.running_jobs }} · 上限 {{ usage.quota.max_concurrent_jobs ?? "不限" }}</span></div>
        <div class="stat"><span class="s-label">今日转码</span><span class="s-value">{{ usage.transcodes_today }}</span><span class="s-sub">上限 {{ usage.quota.max_daily_transcodes ?? "不限" }}</span></div>
      </div>
      <div v-if="usage.history?.length" class="table-wrap">
        <table class="tbl">
          <thead><tr><th>日期</th><th class="r">上传字节</th><th class="r">播放</th><th class="r">转码</th></tr></thead>
          <tbody><tr v-for="d in usage.history" :key="d.day"><td class="num">{{ d.day }}</td><td class="r num">{{ bytes(d.storage_bytes) }}</td><td class="r num">{{ d.play_requests }}</td><td class="r num">{{ d.transcode_jobs }}</td></tr></tbody>
        </table>
      </div>
    </template>
    <ListState :loaded="loaded" :count="usage ? (usage.history?.length ?? 0) : 0" :failed="error" title="近 7 天没有用量记录" />
  </section>

  <section class="card" aria-labelledby="tpl-title">
    <div class="card-head"><h3 id="tpl-title">转码模板</h3></div>
    <div v-if="templates.length" class="table-wrap">
      <table class="tbl">
        <thead><tr><th>名称</th><th>归属</th><th>梯队</th><th>音频</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="t in templates" :key="t.id">
            <td class="mono">{{ t.name }}</td><td>{{ t.system ? "系统" : "租户" }}</td>
            <td class="mono">{{ t.ladder.map((r) => `${r[0]}@${r[2]}k`).join(" ") }}</td><td class="num">{{ t.audio_kbps }}k</td>
            <td><StatusBadge :value="t.status" /></td>
          </tr>
        </tbody>
      </table>
    </div>
    <ListState :loaded="loaded" :count="templates.length" :failed="error" title="没有可用的模板" />
    <div class="card-body" style="border-top: 1px solid var(--border)">
      <form class="form-row" @submit.prevent="createTemplate">
        <div class="field"><label for="t1">模板名</label><input id="t1" v-model="tpl.name" class="input mono" required /><span class="hint">只能用 a-z、0-9、下划线</span></div>
        <div class="field" style="flex: 2 1 320px"><label for="t2">梯队 JSON</label><input id="t2" v-model="tpl.ladder" class="input mono" /><span class="hint">[[档名, 短边, kbps], …]</span></div>
        <div class="field" style="flex: 0 1 140px"><label for="t3">音频 kbps</label><input id="t3" v-model="tpl.audio" class="input num" inputmode="numeric" /><span class="hint">&nbsp;</span></div>
        <button class="btn" type="submit" :disabled="busy" style="margin-bottom: 20px"><Icon name="plus" />新建租户模板</button>
      </form>
    </div>
  </section>

  <section class="card" aria-labelledby="dl-title">
    <div class="card-head"><h3 id="dl-title">最近的 Webhook 投递<span class="sub">最多 20 条</span></h3></div>
    <div v-if="deliveries.length" class="table-wrap">
      <table class="tbl">
        <thead><tr><th>ID</th><th>端点</th><th>事件</th><th>任务</th><th>状态</th><th class="r">次数</th><th class="r">响应</th><th>错误</th></tr></thead>
        <tbody>
          <tr v-for="d in deliveries" :key="d.id">
            <td class="mono">{{ d.id }}</td><td class="mono">{{ d.endpoint_id }}</td><td class="mono">{{ d.event_type }}</td><td class="mono">{{ d.job_id ?? "—" }}</td>
            <td><StatusBadge :value="d.status" /></td><td class="r num">{{ d.attempts }}</td><td class="r num">{{ d.response_code ?? "—" }}</td>
            <td class="small wrap">{{ d.last_error ?? "" }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <ListState :loaded="loaded" :count="deliveries.length" :failed="error" title="这个租户还没有 Webhook 投递" />
  </section>
</template>
