<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { bytes, post, tenantApi, type Job, type Template } from "../api";
import Icon from "../components/Icon.vue";
import ListState from "../components/ListState.vue";
import Notice from "../components/Notice.vue";
import StatusBadge from "../components/StatusBadge.vue";
import { useAction } from "../useAction";

type AssetDetail = {
  id: number; status: string; source_size: number; encrypted: boolean; hls_prefix: string | null;
  duration_ms: number | null; width: number | null; height: number | null; poster_url: string | null;
};
type Hls = { loadSource(url: string): void; attachMedia(v: HTMLVideoElement): void; destroy(): void };
type HlsCtor = { new (cfg: object): Hls; isSupported(): boolean };

const route = useRoute();
const router = useRouter();
const id = computed(() => Number(route.params.id));
const asset = ref<AssetDetail | null>(null);
const jobs = ref<Job[]>([]);
const templates = ref<Template[]>([]);
const template = ref("ladder_v1");
const video = ref<HTMLVideoElement | null>(null);
const playing = ref(false);
let hls: Hls | null = null;
const { busy, message, raw, error, loaded, run } = useAction();

async function load() {
  asset.value = await tenantApi<AssetDetail>(`/assets/${id.value}`);
  jobs.value = (await tenantApi<{ jobs: Job[] }>("/jobs")).jobs.filter((j) => j.asset_id === id.value);
  templates.value = (await tenantApi<{ templates: Template[] }>("/templates")).templates;
}

function transcode() {
  run(async () => {
    const job = await tenantApi<Job>(`/assets/${id.value}/transcodes`, {
      ...post({ template: template.value }),
      headers: { "Idempotency-Key": `console-${id.value}-${template.value}-${Date.now()}` }
    });
    await router.push(`/jobs/${job.id}`);
  });
}

/** hls.js 与短剧页用同一个 CDN 版本；Safari 原生 HLS 不需要它（原生播放器不能给 key 请求加头，加密资产用 hls.js）。 */
function loadHlsJs(): Promise<HlsCtor | null> {
  const w = window as unknown as { Hls?: HlsCtor };
  if (w.Hls) return Promise.resolve(w.Hls);
  return new Promise((resolve) => {
    const s = document.createElement("script");
    s.src = "https://cdn.jsdelivr.net/npm/hls.js@1/dist/hls.min.js";
    s.onload = () => resolve(w.Hls ?? null);
    s.onerror = () => resolve(null);
    document.head.appendChild(s);
  });
}

function play() {
  run(async () => {
    const out = await tenantApi<{ token: string; master_url: string; expires_at: string; encrypted: boolean }>(
      `/assets/${id.value}/playback-token`, post({ ttl_seconds: 600, viewer_id: "console" }));
    playing.value = true;
    await nextTick();
    const el = video.value!;
    hls?.destroy();
    hls = null;
    const Ctor = await loadHlsJs();
    if (Ctor && Ctor.isSupported()) {
      hls = new Ctor({
        xhrSetup(xhr: XMLHttpRequest, url: string) {
          if (url.includes("/keys/v1/")) xhr.setRequestHeader("Authorization", `Bearer ${out.token}`);
        }
      });
      hls.loadSource(out.master_url);
      hls.attachMedia(el);
    } else if (el.canPlayType("application/vnd.apple.mpegurl")) {
      el.src = out.master_url;
    } else {
      throw new Error("浏览器不支持 HLS，也没能加载 hls.js");
    }
    el.play().catch(() => undefined);
    return `播放授权到 ${new Date(out.expires_at).toLocaleTimeString()}${out.encrypted ? "（加密，key 走 /keys/v1/ + token）" : ""}`;
  });
}

onMounted(() => run(load));
onBeforeUnmount(() => hls?.destroy());
</script>

<template>
  <div class="page-head">
    <div>
      <h2>资产 #{{ id }} <StatusBadge v-if="asset" :value="asset.status" /></h2>
      <p>查看封面和状态，对「可播放」的资产申请播放 token 试播，或按模板发起转码。</p>
    </div>
    <div class="btn-row">
      <RouterLink class="btn" to="/assets">返回资产列表</RouterLink>
      <button class="btn" type="button" :disabled="busy" @click="run(load)"><Icon name="refresh" />刷新</button>
    </div>
  </div>

  <Notice :text="message" :error="error" :raw="raw" />

  <section class="card" aria-labelledby="info-title">
    <div class="card-head"><h3 id="info-title">基本信息与试播</h3></div>
    <template v-if="asset">
      <table class="kv">
        <tbody>
          <tr><th>状态</th><td><StatusBadge :value="asset.status" /></td></tr>
          <tr><th>源文件大小</th><td class="num">{{ bytes(asset.source_size) }}</td></tr>
          <tr><th>加密</th><td>{{ asset.encrypted ? "加密（AES-128 HLS）" : "不加密" }}</td></tr>
          <tr v-if="asset.width"><th>画面与时长</th><td class="num">{{ asset.width }}×{{ asset.height }} · {{ ((asset.duration_ms ?? 0) / 1000).toFixed(1) }}s</td></tr>
          <tr><th>当前播放前缀</th><td class="mono break">{{ asset.hls_prefix ?? "—" }}</td></tr>
          <tr><th>封面</th><td>
            <img v-if="asset.poster_url" class="poster" :src="asset.poster_url" :alt="`资产 #${asset.id} 封面`" />
            <span v-else class="muted small">暂无封面（Phase 6 之前转码的资产要重新转码才会生成；抽帧失败也不影响播放）</span>
          </td></tr>
        </tbody>
      </table>
      <div class="card-body stack" style="border-top: 1px solid var(--border)">
        <div class="btn-row">
          <button class="btn primary" type="button" :disabled="busy || asset.status !== 'READY'" @click="play"><Icon name="play" />申请播放 token 并试播</button>
          <span v-if="asset.status !== 'READY'" class="small muted">状态为「可播放 READY」后才能试播。</span>
        </div>
        <video v-if="playing" ref="video" class="player" controls playsinline></video>
      </div>
    </template>
    <ListState v-else :loaded="loaded" :count="0" :failed="error" title="没有找到这个资产" />
  </section>

  <section class="card" aria-labelledby="tc-title">
    <div class="card-head"><h3 id="tc-title">按模板转码 / 重新转码</h3></div>
    <div class="card-body">
      <form class="form-row" @submit.prevent="transcode">
        <div class="field" style="max-width: 360px">
          <label for="tpl">模板</label>
          <select id="tpl" v-model="template" class="input"><option v-for="t in templates" :key="t.id" :value="t.name">{{ t.name }}{{ t.system ? "（系统）" : "" }}</option></select>
        </div>
        <button class="btn primary" type="submit" :disabled="busy">发起转码</button>
      </form>
      <p class="small muted" style="margin-top: 8px">同一模板的任务还在排队或运行时会被拒绝（冲突 409）；已结束的任务会复用同一行、claim_version +1，成功后才切换播放前缀，旧前缀进入清理。</p>
    </div>
  </section>

  <section class="card" aria-labelledby="jobs-title">
    <div class="card-head"><h3 id="jobs-title">这个资产的任务</h3></div>
    <div v-if="jobs.length" class="table-wrap">
      <table class="tbl">
        <thead><tr><th>任务</th><th>模板</th><th>状态</th><th class="r">尝试</th><th class="r">版本</th></tr></thead>
        <tbody>
          <tr v-for="j in jobs" :key="j.id">
            <td class="mono"><RouterLink :to="`/jobs/${j.id}`">#{{ j.id }}</RouterLink></td><td class="mono">{{ j.profile }}</td>
            <td><StatusBadge :value="j.status" /></td><td class="r num">{{ j.attempts }}</td><td class="r num">{{ j.claim_version }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <ListState :loaded="loaded" :count="jobs.length" :failed="error" title="这个资产还没有转码任务" />
  </section>
</template>
