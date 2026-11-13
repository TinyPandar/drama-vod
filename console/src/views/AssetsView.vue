<script setup lang="ts">
import { onMounted, ref } from "vue";
import { bytes, tenantApi, type Asset } from "../api";
import Notice from "../components/Notice.vue";
import { abortUpload, openMultipart, putUpload, runMultipart, uploadConfig, type PendingUpload } from "../upload";
import { useAction } from "../useAction";

const assets = ref<Asset[]>([]);
const next = ref<number | null>(null);
const encrypted = ref(false);
/** 分片上传失败后保留会话，可以「继续上传」或「取消」。 */
const pending = ref<PendingUpload | null>(null);
let controller: AbortController | null = null;
const { busy, message, error, run } = useAction();

async function load(cursor?: number) {
  const page = await tenantApi<{ assets: Asset[]; next_cursor: number | null }>(`/assets${cursor ? `?cursor=${cursor}` : ""}`);
  assets.value = cursor ? assets.value.concat(page.assets) : page.assets;
  next.value = page.next_cursor;
}

function progress(text: string) {
  message.value = text;
}

/** 达到服务端下发的阈值（默认等于分片大小）走 multipart，否则仍是单次 PUT。 */
function onUpload(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  run(async () => {
    const cfg = await uploadConfig();
    controller = new AbortController();
    try {
      if (file.size < cfg.multipart_threshold_bytes) {
        const done = await putUpload(file, encrypted.value, progress, controller.signal);
        await load();
        return `资产 #${done.assetId} 已提交转码，任务 #${done.jobId}`;
      }
      pending.value = await openMultipart(file, encrypted.value);
      return await finishMultipart();
    } finally {
      input.value = "";
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
  run(finishMultipart);
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

onMounted(() => run(() => load()));
</script>

<template>
  <h1>资产</h1>
  <div class="row">
    <label class="small"><input v-model="encrypted" type="checkbox" style="width:auto" /> 加密（AES-128 HLS）</label>
    <label class="small" style="margin:0"><span class="muted">上传视频（大文件自动分片、可续传）</span>
      <input type="file" accept="video/*" :disabled="busy || !!pending" @change="onUpload" />
    </label>
    <button class="ghost" :disabled="busy" @click="run(() => load())">刷新</button>
  </div>
  <div v-if="pending" class="row">
    <span class="small muted">分片上传未完成：{{ pending.file.name }}（{{ bytes(pending.file.size) }}，会话 #{{ pending.sessionId }}）</span>
    <button :disabled="busy" @click="resume">继续上传</button>
    <button class="danger" @click="cancel">取消上传</button>
  </div>
  <Notice :text="message" :error="error" />
  <table>
    <thead><tr><th>封面</th><th>ID</th><th>状态</th><th>大小</th><th>加密</th></tr></thead>
    <tbody>
      <tr v-for="a in assets" :key="a.id">
        <td class="thumb">
          <img v-if="a.poster_url" :src="a.poster_url" :alt="`资产 #${a.id} 封面`" loading="lazy" />
          <span v-else class="muted">—</span>
        </td>
        <td><RouterLink :to="`/assets/${a.id}`">#{{ a.id }}</RouterLink></td><td>{{ a.status }}</td>
        <td>{{ bytes(a.source_size) }}</td><td>{{ a.encrypted ? "是" : "否" }}</td>
      </tr>
    </tbody>
  </table>
  <div class="row"><button v-if="next" class="ghost" :disabled="busy" @click="run(() => load(next!))">下一页</button></div>
</template>

<style scoped>
.thumb { width: 56px; }
.thumb img { display: block; width: 48px; height: 48px; object-fit: cover; border-radius: 6px; background: #171512; }
</style>
