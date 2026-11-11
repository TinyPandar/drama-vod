<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import { post, tenantApi, when, type Delivery, type Job } from "../api";
import Notice from "../components/Notice.vue";
import { useAction } from "../useAction";

const route = useRoute();
const id = computed(() => Number(route.params.id));
const job = ref<Job | null>(null);
const deliveries = ref<Delivery[]>([]);
const { busy, message, error, run } = useAction();
const active = computed(() => !!job.value && ["PENDING", "RETRYING", "RUNNING"].includes(job.value.status));
const retryable = computed(() => !!job.value && ["FAILED", "CANCELLED"].includes(job.value.status));

async function load() {
  job.value = await tenantApi<Job>(`/jobs/${id.value}`);
  deliveries.value = (await tenantApi<{ deliveries: Delivery[] }>(`/webhook-deliveries?job_id=${id.value}`)).deliveries;
}

function cancel() {
  run(async () => {
    job.value = await tenantApi<Job>(`/jobs/${id.value}/cancel`, post());
    return "已取消（在跑的 Worker 下次续租时发现并停止）";
  });
}

function retry() {
  run(async () => {
    job.value = await tenantApi<Job>(`/jobs/${id.value}/retry`, {
      ...post(), headers: { "Idempotency-Key": `console-retry-${id.value}-${job.value?.claim_version}` }
    });
    return `已重新排队，claim_version ${job.value.claim_version}`;
  });
}

function retranscode() {
  run(async () => {
    if (!job.value) return;
    job.value = await tenantApi<Job>(`/assets/${job.value.asset_id}/transcodes`, {
      ...post({ template: job.value.profile }),
      headers: { "Idempotency-Key": `console-re-${id.value}-${job.value.claim_version}` }
    });
    return `已按模板 ${job.value.profile} 重新转码，claim_version ${job.value.claim_version}`;
  });
}

onMounted(() => run(load));
</script>

<template>
  <p><RouterLink to="/jobs">← 任务列表</RouterLink></p>
  <h1>任务 #{{ id }}</h1>
  <Notice :text="message" :error="error" />
  <div v-if="job" class="card">
    <table>
      <tbody>
        <tr><th>资产</th><td><RouterLink :to="`/assets/${job.asset_id}`">#{{ job.asset_id }}</RouterLink></td></tr>
        <tr><th>模板</th><td>{{ job.profile }}</td></tr>
        <tr><th>状态</th><td><strong>{{ job.status }}</strong></td></tr>
        <tr><th>尝试</th><td>{{ job.attempts }} / {{ job.max_attempts }}</td></tr>
        <tr><th>claim_version</th><td>{{ job.claim_version }}</td></tr>
        <tr><th>错误</th><td>{{ job.error_code ?? "—" }} <span class="small muted">{{ job.last_error ?? "" }}</span></td></tr>
        <tr><th>下次重试</th><td>{{ when(job.next_attempt_at) }}</td></tr>
        <tr><th>更新</th><td>{{ when(job.updated_at) }}</td></tr>
      </tbody>
    </table>
    <div class="row">
      <button class="danger" :disabled="busy || !active" @click="cancel">取消</button>
      <button :disabled="busy || !retryable" @click="retry">重试</button>
      <button class="ghost" :disabled="busy || active" @click="retranscode">按同一模板重新转码</button>
      <button class="ghost" :disabled="busy" @click="run(load)">刷新</button>
    </div>
  </div>
  <h2>这个任务的 Webhook 投递</h2>
  <table>
    <thead><tr><th>ID</th><th>事件</th><th>状态</th><th>次数</th><th>响应</th><th>下次</th><th>错误</th></tr></thead>
    <tbody>
      <tr v-for="d in deliveries" :key="d.id">
        <td>{{ d.id }}</td><td>{{ d.event_type }}</td><td>{{ d.status }}</td><td>{{ d.attempts }}</td>
        <td>{{ d.response_code ?? "—" }}</td><td>{{ when(d.next_attempt_at) }}</td><td class="small">{{ d.last_error ?? "" }}</td>
      </tr>
    </tbody>
  </table>
</template>
