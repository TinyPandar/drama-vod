<script setup lang="ts">
import { onMounted, ref } from "vue";
import { tenantApi, type Job } from "../api";
import Icon from "../components/Icon.vue";
import ListState from "../components/ListState.vue";
import Notice from "../components/Notice.vue";
import StatusBadge from "../components/StatusBadge.vue";
import { useAction } from "../useAction";

const jobs = ref<Job[]>([]);
const { busy, message, raw, error, loaded, run } = useAction();

async function load() {
  jobs.value = (await tenantApi<{ jobs: Job[] }>("/jobs")).jobs;
}

onMounted(() => run(load));
</script>

<template>
  <div class="page-head">
    <p>当前租户的转码任务。进入任务可以取消、重试或按同一模板重新转码。</p>
    <div class="btn-row"><button class="btn" type="button" :disabled="busy" @click="run(load)"><Icon name="refresh" />刷新</button></div>
  </div>

  <Notice :text="message" :error="error" :raw="raw" />

  <section class="card">
    <div class="card-head"><h3>任务列表<span v-if="jobs.length" class="sub">共 {{ jobs.length }} 个</span></h3></div>
    <div v-if="jobs.length" class="table-wrap">
      <table class="tbl">
        <thead><tr><th>任务</th><th>资产</th><th>模板</th><th>状态</th><th class="r">尝试</th><th class="r">版本</th><th class="ops">操作</th></tr></thead>
        <tbody>
          <tr v-for="j in jobs" :key="j.id">
            <td class="mono"><RouterLink :to="`/jobs/${j.id}`">#{{ j.id }}</RouterLink></td>
            <td class="mono"><RouterLink :to="`/assets/${j.asset_id}`">#{{ j.asset_id }}</RouterLink></td>
            <td class="mono">{{ j.profile }}</td><td><StatusBadge :value="j.status" /></td>
            <td class="r num">{{ j.attempts }}</td><td class="r num">{{ j.claim_version }}</td>
            <td class="ops"><RouterLink class="btn sm text" :to="`/jobs/${j.id}`">详情</RouterLink></td>
          </tr>
        </tbody>
      </table>
    </div>
    <ListState :loaded="loaded" :count="jobs.length" :failed="error" title="还没有转码任务" hint="在「资产」上传视频后会自动创建任务，也可以在资产详情里按模板发起。" />
  </section>
</template>
