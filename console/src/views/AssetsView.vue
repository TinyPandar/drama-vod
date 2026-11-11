<script setup lang="ts">
import { onMounted, ref } from "vue";
import { bytes, post, tenantApi, type Asset } from "../api";
import Notice from "../components/Notice.vue";
import { useAction } from "../useAction";

const assets = ref<Asset[]>([]);
const next = ref<number | null>(null);
const encrypted = ref(false);
const { busy, message, error, run } = useAction();

async function load(cursor?: number) {
  const page = await tenantApi<{ assets: Asset[]; next_cursor: number | null }>(`/assets${cursor ? `?cursor=${cursor}` : ""}`);
  assets.value = cursor ? assets.value.concat(page.assets) : page.assets;
  next.value = page.next_cursor;
}

function onUpload(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  run(async () => {
    message.value = "申请直传地址";
    const opened = await tenantApi<{ session_id: number; put_url: string; asset_id: number }>("/uploads", post({
      filename: file.name, encrypted: encrypted.value
    }));
    message.value = "直传对象存储中（不经过 API）";
    const put = await fetch(opened.put_url, { method: "PUT", body: file });
    if (!put.ok) throw new Error(`直传失败 ${put.status}`);
    const done = await tenantApi<{ job_id: number }>(`/uploads/${opened.session_id}/complete`, post());
    input.value = "";
    await load();
    return `资产 #${opened.asset_id} 已提交转码，任务 #${done.job_id}`;
  });
}

onMounted(() => run(() => load()));
</script>

<template>
  <h1>资产</h1>
  <div class="row">
    <label class="small"><input v-model="encrypted" type="checkbox" style="width:auto" /> 加密（AES-128 HLS）</label>
    <label class="small" style="margin:0"><span class="muted">上传视频</span>
      <input type="file" accept="video/*" :disabled="busy" @change="onUpload" />
    </label>
    <button class="ghost" :disabled="busy" @click="run(() => load())">刷新</button>
  </div>
  <Notice :text="message" :error="error" />
  <table>
    <thead><tr><th>ID</th><th>状态</th><th>大小</th><th>加密</th></tr></thead>
    <tbody>
      <tr v-for="a in assets" :key="a.id">
        <td><RouterLink :to="`/assets/${a.id}`">#{{ a.id }}</RouterLink></td><td>{{ a.status }}</td>
        <td>{{ bytes(a.source_size) }}</td><td>{{ a.encrypted ? "是" : "否" }}</td>
      </tr>
    </tbody>
  </table>
  <div class="row"><button v-if="next" class="ghost" :disabled="busy" @click="run(() => load(next!))">下一页</button></div>
</template>
