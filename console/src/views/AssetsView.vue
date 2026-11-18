<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import { bytes, parseTags, tenantApi, type Asset, type AssetMeta } from "../api";
import Icon from "../components/Icon.vue";
import ListState from "../components/ListState.vue";
import Notice from "../components/Notice.vue";
import StatusBadge from "../components/StatusBadge.vue";
import { abortUpload, openMultipart, putUpload, runMultipart, uploadConfig, type PendingUpload } from "../upload";
import { useAction } from "../useAction";

const route = useRoute();
const assets = ref<Asset[]>([]);
const next = ref<number | null>(null);
const encrypted = ref(false);
/** 分片上传失败后保留会话，可以「继续上传」或「取消」。 */
const pending = ref<PendingUpload | null>(null);
let controller: AbortController | null = null;
const { busy, message, raw, error, loaded, run } = useAction();
/** 上传进度文字（来自 upload.ts 的回调），和操作结果提示分开显示。 */
const step = ref("");
const uploadingName = ref("");
/** 上传时的可选标题与标签；标题留空时用文件名（去掉扩展名）。 */
const metaTitle = ref("");
const metaTags = ref("");
/** 列表筛选：关键字（标题包含或资产号）、状态、标签。只在点「筛选」或回车时生效。 */
const q = ref("");
const status = ref("");
const tag = ref("");
const applied = ref({ q: "", status: "", tag: "" });
const filtering = computed(() => !!(applied.value.q || applied.value.status || applied.value.tag));
const STATUSES = ["UPLOADING", "UPLOADED", "TRANSCODING", "READY", "FAILED", "CANCELLED", "DELETED"];

async function load(cursor?: number) {
  const params = new URLSearchParams();
  if (cursor) params.set("cursor", String(cursor));
  if (applied.value.q) params.set("q", applied.value.q);
  if (applied.value.status) params.set("status", applied.value.status);
  if (applied.value.tag) params.set("tag", applied.value.tag);
  const qs = params.toString();
  const page = await tenantApi<{ assets: Asset[]; next_cursor: number | null }>(`/assets${qs ? `?${qs}` : ""}`);
  assets.value = cursor ? assets.value.concat(page.assets) : page.assets;
  next.value = page.next_cursor;
}

function applyFilter() {
  applied.value = { q: q.value.trim(), status: status.value, tag: tag.value.trim() };
  run(() => load());
}

function clearFilter() {
  q.value = ""; status.value = ""; tag.value = "";
  applyFilter();
}

function meta(file: File): AssetMeta {
  const title = metaTitle.value.trim() || file.name.replace(/\.[^.]+$/, "");
  const tags = parseTags(metaTags.value);
  return tags.length ? { title, tags } : { title };
}

function progress(text: string) {
  step.value = text;
}

/** 达到服务端下发的阈值（默认等于分片大小）走 multipart，否则仍是单次 PUT。 */
function onUpload(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  uploadingName.value = `${file.name}（${bytes(file.size)}）`;
  run(async () => {
    const cfg = await uploadConfig();
    controller = new AbortController();
    try {
      if (file.size < cfg.multipart_threshold_bytes) {
        const done = await putUpload(file, encrypted.value, progress, controller.signal, meta(file));
        await load();
        return `资产 #${done.assetId} 已提交转码，任务 #${done.jobId}`;
      }
      pending.value = await openMultipart(file, encrypted.value, meta(file));
      return await finishMultipart();
    } finally {
      input.value = "";
      metaTitle.value = "";
      metaTags.value = "";
      step.value = "";
    }
  });
}

async function finishMultipart() {
  const up = pending.value!;
  controller = new AbortController();
  const done = await runMultipart(up, progress, controller.signal);
  pending.value = null;
  await load();
  return `资产 #${done.assetId} 分片上传完成，已提交转码，任务 #${done.jobId}`;
}

function resume() {
  uploadingName.value = pending.value ? `${pending.value.file.name}（${bytes(pending.value.file.size)}）` : "";
  run(async () => {
    try {
      return await finishMultipart();
    } finally {
      step.value = "";
    }
  });
}

/** 取消：停掉进行中的 PUT，再让服务端 abort（存储侧丢掉已传分片）。 */
async function cancel() {
  controller?.abort();
  const up = pending.value;
  if (!up) return;
  await run(async () => {
    await abortUpload(up.sessionId);
    pending.value = null;
    await load();
    return `已取消上传（资产 #${up.assetId} 保持 UPLOADING，不会转码）`;
  });
}

onMounted(() => run(async () => {
  await load();
  const deleted = route.query.deleted;
  if (typeof deleted === "string" && /^\d+$/.test(deleted)) return `资产 #${deleted} 已删除；源文件和转码产物会在后台清理。`;
}));
</script>

<template>
  <div class="page-head">
    <p>当前租户上传的视频。上传完成后自动提交转码，状态变为「可播放」后可在详情里试播。</p>
    <div class="btn-row"><button class="btn" type="button" :disabled="busy" @click="run(() => load())"><Icon name="refresh" />刷新</button></div>
  </div>

  <Notice :text="message" :error="error" :raw="raw" />

  <section class="card" aria-labelledby="up-title">
    <div class="card-head"><h3 id="up-title">上传视频</h3></div>
    <div class="card-body stack">
      <div v-if="step" class="stack" role="status">
        <div class="btn-row" style="justify-content: space-between">
          <span><b>{{ uploadingName }}</b> <span class="muted small">{{ step }}</span></span>
          <button v-if="pending" class="btn sm danger" type="button" @click="cancel">取消上传</button>
        </div>
        <div class="btn-row small muted"><span class="spin"></span>上传期间请不要关闭或刷新本标签页。</div>
      </div>

      <div v-else-if="pending && !busy" class="alert warn" role="status">
        <Icon name="warn" />
        <div class="a-body">
          <div class="a-title">分片上传中断，还没传完</div>
          <div class="a-text"><b>{{ pending.file.name }}</b>（{{ bytes(pending.file.size) }}，上传会话 #{{ pending.sessionId }}）已传的分片保留在服务端，可以从断点继续；取消则丢弃已传部分，这个资产不会转码。</div>
          <div class="a-act">
            <button class="btn sm primary" type="button" :disabled="busy" @click="resume">继续上传</button>
            <button class="btn sm danger" type="button" @click="cancel">取消上传</button>
          </div>
        </div>
      </div>

      <div v-else class="stack">
      <div class="form-row">
        <div class="field"><label for="mt">标题（可选）</label><input id="mt" v-model="metaTitle" class="input" maxlength="200" placeholder="留空用文件名" /></div>
        <div class="field"><label for="mg">标签（可选）</label><input id="mg" v-model="metaTags" class="input" placeholder="用逗号或空格分隔，例如：短剧 第一季" /></div>
      </div>
      <div class="upload">
        <div class="u-text">
          <b>选择一个视频文件</b>
          <div class="small muted">小文件一次传完；大文件自动分片，中断后可以继续上传。</div>
        </div>
        <label class="check"><input v-model="encrypted" type="checkbox" /> 加密（AES-128 HLS）</label>
        <label class="btn primary" :class="{ 'is-disabled': busy }"><Icon name="upload" />选择文件
          <input type="file" accept="video/*" :disabled="busy || !!pending" @change="onUpload" />
        </label>
      </div>
      </div>
    </div>
  </section>

  <section class="card">
    <div class="card-head"><h3>资产列表</h3></div>
    <form class="card-body form-row" style="border-bottom: 1px solid var(--border)" @submit.prevent="applyFilter">
      <div class="field"><label for="fq">关键字</label><input id="fq" v-model="q" class="input" maxlength="200" placeholder="标题里的字，或资产号" /></div>
      <div class="field" style="flex: 0 1 180px"><label for="fs">状态</label>
        <select id="fs" v-model="status" class="input">
          <option value="">全部（不含已删除）</option>
          <option v-for="s in STATUSES" :key="s" :value="s">{{ s }}</option>
        </select>
      </div>
      <div class="field" style="flex: 0 1 180px"><label for="ft">标签</label><input id="ft" v-model="tag" class="input" maxlength="32" /></div>
      <div class="btn-row">
        <button class="btn" type="submit" :disabled="busy">筛选</button>
        <button v-if="filtering" class="btn text" type="button" :disabled="busy" @click="clearFilter">清除</button>
      </div>
    </form>
    <div v-if="assets.length" class="table-wrap">
      <table class="tbl">
        <thead><tr><th style="width: 96px">封面</th><th>资产</th><th>状态</th><th class="r">源文件大小</th><th>加密</th><th>创建时间</th><th class="ops">操作</th></tr></thead>
        <tbody>
          <tr v-for="a in assets" :key="a.id">
            <td>
              <img v-if="a.poster_url" class="thumb" :src="a.poster_url" :alt="`资产 #${a.id} 封面`" loading="lazy" />
              <span v-else class="thumb none">无封面</span>
            </td>
            <td>
              <RouterLink :to="`/assets/${a.id}`"><b v-if="a.title">{{ a.title }}</b><span v-else class="faint">（无标题）</span></RouterLink>
              <div class="small"><span class="mono muted">#{{ a.id }}</span>
                <span v-for="t in a.tags || []" :key="t" class="tag">{{ t }}</span></div>
            </td>
            <td><StatusBadge :value="a.status" /></td>
            <td class="r num">{{ bytes(a.source_size) }}</td><td>{{ a.encrypted ? "是" : "否" }}</td>
            <td class="small muted">{{ a.created_at ? new Date(a.created_at).toLocaleString() : "—" }}</td>
            <td class="ops"><RouterLink class="btn sm text" :to="`/assets/${a.id}`">{{ a.status === "READY" ? "详情与试播" : "详情" }}</RouterLink></td>
          </tr>
        </tbody>
      </table>
    </div>
    <ListState v-if="filtering" :loaded="loaded" :count="assets.length" :failed="error" title="没有符合条件的资产"
      hint="换个关键字或标签，或点「清除」看全部资产。" />
    <ListState v-else :loaded="loaded" :count="assets.length" :failed="error" title="这个租户还没有资产"
      hint="用上面的「选择文件」上传第一个视频。上传完成后会自动提交转码，并出现在这里。" />
    <div v-if="assets.length" class="card-foot">
      <span>已显示 {{ assets.length }} 条</span>
      <button v-if="next" class="btn sm" type="button" :disabled="busy" @click="run(() => load(next!))">加载下一页</button>
    </div>
  </section>
</template>
