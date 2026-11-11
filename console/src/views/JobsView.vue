<script setup lang="ts">
import { onMounted, ref } from "vue";
import { tenantApi, type Job } from "../api";
import Notice from "../components/Notice.vue";
import { useAction } from "../useAction";

const jobs = ref<Job[]>([]);
const { busy, message, error, run } = useAction();

async function load() {
  jobs.value = (await tenantApi<{ jobs: Job[] }>("/jobs")).jobs;
}

onMounted(() => run(load));
</script>

<template>
  <h1>转码任务</h1>
  <div class="row"><button class="ghost" :disabled="busy" @click="run(load)">刷新</button></div>
  <Notice :text="message" :error="error" />
  <table>
    <thead><tr><th>任务</th><th>资产</th><th>模板</th><th>状态</th><th>尝试</th><th>版本</th></tr></thead>
    <tbody>
      <tr v-for="j in jobs" :key="j.id">
        <td><RouterLink :to="`/jobs/${j.id}`">#{{ j.id }}</RouterLink></td>
        <td><RouterLink :to="`/assets/${j.asset_id}`">#{{ j.asset_id }}</RouterLink></td>
        <td>{{ j.profile }}</td><td>{{ j.status }}</td><td>{{ j.attempts }}</td><td>{{ j.claim_version }}</td>
      </tr>
    </tbody>
  </table>
</template>
