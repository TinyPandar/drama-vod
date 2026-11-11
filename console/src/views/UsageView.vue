<script setup lang="ts">
import { onMounted, ref } from "vue";
import { bytes, tenantApi, type Usage } from "../api";
import Notice from "../components/Notice.vue";
import { useAction } from "../useAction";

const usage = ref<Usage | null>(null);
const days = ref(14);
const { busy, message, error, run } = useAction();

async function load() {
  usage.value = await tenantApi<Usage>(`/usage?days=${days.value}`);
}

onMounted(() => run(load));
</script>

<template>
  <h1>用量与配额</h1>
  <Notice :text="message" :error="error" />
  <template v-if="usage">
    <table>
      <tbody>
        <tr><th>已用存储</th><td>{{ bytes(usage.storage_used_bytes) }} / {{ bytes(usage.quota.storage_bytes) }}</td></tr>
        <tr><th>进行中的任务</th><td>{{ usage.concurrency.active_jobs }}（其中运行 {{ usage.concurrency.running_jobs }}）/ {{ usage.quota.max_concurrent_jobs ?? "不限" }}</td></tr>
        <tr><th>今日转码次数</th><td>{{ usage.transcodes_today }} / {{ usage.quota.max_daily_transcodes ?? "不限" }}</td></tr>
        <tr><th>今日播放授权</th><td>{{ usage.play_requests }}</td></tr>
      </tbody>
    </table>
    <div class="row">
      <div class="field"><label>历史天数</label><input v-model.number="days" type="number" min="1" /></div>
      <button class="ghost" :disabled="busy" @click="run(load)">刷新</button>
    </div>
    <table>
      <thead><tr><th>日期</th><th>上传字节</th><th>播放</th><th>转码</th></tr></thead>
      <tbody>
        <tr v-for="d in usage.history" :key="d.day"><td>{{ d.day }}</td><td>{{ bytes(d.storage_bytes) }}</td><td>{{ d.play_requests }}</td><td>{{ d.transcode_jobs }}</td></tr>
      </tbody>
    </table>
  </template>
</template>
