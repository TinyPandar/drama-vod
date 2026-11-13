<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { bytes, post, tenantApi, type Job, type Template } from "../api";
import Notice from "../components/Notice.vue";
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
const { busy, message, error, run } = useAction();

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

<style scoped>
.poster { display: block; max-width: min(320px, 100%); max-height: 320px; border-radius: 8px; margin: 8px 0; background: #171512; }
</style>

<template>
  <p><RouterLink to="/assets">← 资产列表</RouterLink></p>
  <h1>资产 #{{ id }}</h1>
  <Notice :text="message" :error="error" />
  <div v-if="asset" class="card">
    <p>状态 <strong>{{ asset.status }}</strong> · 源文件 {{ bytes(asset.source_size) }} · {{ asset.encrypted ? "加密" : "不加密" }}
      <template v-if="asset.width"> · {{ asset.width }}×{{ asset.height }} · {{ ((asset.duration_ms ?? 0) / 1000).toFixed(1) }}s</template></p>
    <p class="muted small mono">当前播放前缀：{{ asset.hls_prefix ?? "—" }}</p>
    <img v-if="asset.poster_url" class="poster" :src="asset.poster_url" :alt="`资产 #${asset.id} 封面`" />
    <p v-else class="muted small">暂无封面（Phase 6 之前转码的资产要重新转码才会生成；抽帧失败也不影响播放）</p>
    <div class="row">
      <button :disabled="busy || asset.status !== 'READY'" @click="play">申请播放 token 并试播</button>
      <button class="ghost" :disabled="busy" @click="run(load)">刷新</button>
    </div>
    <video v-if="playing" ref="video" controls playsinline></video>
  </div>

  <h2>按模板转码 / 重新转码</h2>
  <div class="row">
    <div class="field">
      <label>模板</label>
      <select v-model="template"><option v-for="t in templates" :key="t.id" :value="t.name">{{ t.name }}{{ t.system ? "（系统）" : "" }}</option></select>
    </div>
    <button :disabled="busy" @click="transcode">发起</button>
  </div>
  <p class="muted small">同一模板的任务还在排队或运行时返回 409；已结束的任务会复用同一行、claim_version +1，成功后才切换播放前缀，旧前缀进入清理。</p>

  <h2>任务</h2>
  <table>
    <thead><tr><th>任务</th><th>模板</th><th>状态</th><th>尝试</th><th>版本</th></tr></thead>
    <tbody>
      <tr v-for="j in jobs" :key="j.id">
        <td><RouterLink :to="`/jobs/${j.id}`">#{{ j.id }}</RouterLink></td><td>{{ j.profile }}</td><td>{{ j.status }}</td>
        <td>{{ j.attempts }}</td><td>{{ j.claim_version }}</td>
      </tr>
    </tbody>
  </table>
</template>
