<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import { post, tenantApi, when, type Delivery, type Job } from "../api";
import Icon from "../components/Icon.vue";
import ListState from "../components/ListState.vue";
import Notice from "../components/Notice.vue";
import StatusBadge from "../components/StatusBadge.vue";
import { useAction } from "../useAction";

const route = useRoute();
const id = computed(() => Number(route.params.id));
const job = ref<Job | null>(null);
const deliveries = ref<Delivery[]>([]);
const { busy, message, raw, error, loaded, run } = useAction();
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
  <div class="page-head">
    <div>
      <h2>任务 #{{ id }} <StatusBadge v-if="job" :value="job.status" /></h2>
      <p>排队或运行中的任务可以取消；失败或已取消的可以重试；已结束的可以按同一模板重新转码。</p>
    </div>
    <div class="btn-row">
      <RouterLink class="btn" to="/jobs">返回任务列表</RouterLink>
      <button class="btn" type="button" :disabled="busy" @click="run(load)"><Icon name="refresh" />刷新</button>
    </div>
  </div>

  <Notice :text="message" :error="error" :raw="raw" />

  <section class="card" aria-labelledby="job-title">
    <div class="card-head"><h3 id="job-title">任务信息</h3></div>
    <template v-if="job">
      <table class="kv">
        <tbody>
          <tr><th>资产</th><td class="mono"><RouterLink :to="`/assets/${job.asset_id}`">#{{ job.asset_id }}</RouterLink></td></tr>
          <tr><th>模板</th><td class="mono">{{ job.profile }}</td></tr>
          <tr><th>状态</th><td><StatusBadge :value="job.status" /></td></tr>
          <tr><th>尝试次数</th><td class="num">{{ job.attempts }} / {{ job.max_attempts }}</td></tr>
          <tr><th>版本 <span class="enum" style="margin: 0">claim_version</span></th><td class="num">{{ job.claim_version }}</td></tr>
          <tr><th>错误</th><td><span class="mono">{{ job.error_code ?? "—" }}</span> <span class="small muted break">{{ job.last_error ?? "" }}</span></td></tr>
          <tr><th>下次重试</th><td class="num">{{ when(job.next_attempt_at) }}</td></tr>
          <tr><th>更新时间</th><td class="num">{{ when(job.updated_at) }}</td></tr>
        </tbody>
      </table>
      <div class="card-foot">
        <div class="btn-row">
          <button class="btn danger" type="button" :disabled="busy || !active" @click="cancel">取消任务</button>
          <button class="btn primary" type="button" :disabled="busy || !retryable" @click="retry">重试</button>
          <button class="btn" type="button" :disabled="busy || active" @click="retranscode">按同一模板重新转码</button>
        </div>
      </div>
    </template>
    <ListState v-else :loaded="loaded" :count="0" :failed="error" title="没有找到这个任务" />
  </section>

  <section class="card" aria-labelledby="jd-title">
    <div class="card-head"><h3 id="jd-title">这个任务的 Webhook 投递</h3></div>
    <div v-if="deliveries.length" class="table-wrap">
      <table class="tbl">
        <thead><tr><th>ID</th><th>事件</th><th>状态</th><th class="r">次数</th><th class="r">响应</th><th>下次</th><th>错误</th></tr></thead>
        <tbody>
          <tr v-for="d in deliveries" :key="d.id">
            <td class="mono">{{ d.id }}</td><td class="mono">{{ d.event_type }}</td><td><StatusBadge :value="d.status" /></td>
            <td class="r num">{{ d.attempts }}</td><td class="r num">{{ d.response_code ?? "—" }}</td>
            <td class="num">{{ when(d.next_attempt_at) }}</td><td class="small wrap">{{ d.last_error ?? "" }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <ListState :loaded="loaded" :count="deliveries.length" :failed="error" title="这个任务还没有 Webhook 投递" />
  </section>
</template>
