<script setup lang="ts">
import { onMounted, ref } from "vue";
import { bytes, tenantApi, type Usage } from "../api";
import Icon from "../components/Icon.vue";
import ListState from "../components/ListState.vue";
import Notice from "../components/Notice.vue";
import StatusBadge from "../components/StatusBadge.vue";
import { useAction } from "../useAction";

const usage = ref<Usage | null>(null);
const days = ref(14);
const { busy, message, raw, error, loaded, run } = useAction();

async function load() {
  usage.value = await tenantApi<Usage>(`/usage?days=${days.value}`);
}

onMounted(() => run(load));
</script>

<template>
  <div class="page-head">
    <p>当前租户的用量和配额。配额由管理员在「租户与 Key」里调整。</p>
    <div class="btn-row"><button class="btn" type="button" :disabled="busy" @click="run(load)"><Icon name="refresh" />刷新</button></div>
  </div>

  <Notice :text="message" :error="error" :raw="raw" />

  <div v-if="usage" class="stats">
    <div class="stat"><span class="s-label">已用存储</span><span class="s-value">{{ bytes(usage.storage_used_bytes) }}</span><span class="s-sub">配额 {{ bytes(usage.quota.storage_bytes) }}</span></div>
    <div class="stat"><span class="s-label">进行中的任务</span><span class="s-value">{{ usage.concurrency.active_jobs }}</span><span class="s-sub">其中运行 {{ usage.concurrency.running_jobs }} · 上限 {{ usage.quota.max_concurrent_jobs ?? "不限" }}</span></div>
    <div class="stat"><span class="s-label">今日转码次数</span><span class="s-value">{{ usage.transcodes_today }}</span><span class="s-sub">上限 {{ usage.quota.max_daily_transcodes ?? "不限" }}</span></div>
    <div class="stat"><span class="s-label">今日播放授权</span><span class="s-value">{{ usage.play_requests }}</span><span class="s-sub">申请播放 token 的次数</span></div>
  </div>

  <section class="card" aria-labelledby="hist-title">
    <div class="card-head">
      <h3 id="hist-title">按天历史</h3>
      <form class="btn-row" @submit.prevent="run(load)">
        <label for="days" class="small muted">历史天数</label>
        <input id="days" v-model.number="days" class="input num" type="number" min="1" style="width: 88px" />
        <button class="btn sm" type="submit" :disabled="busy">查看</button>
      </form>
    </div>
    <div v-if="usage?.history?.length" class="table-wrap">
      <table class="tbl">
        <thead><tr><th>日期</th><th class="r">上传字节</th><th class="r">播放</th><th class="r">转码</th></tr></thead>
        <tbody>
          <tr v-for="d in usage.history" :key="d.day"><td class="num">{{ d.day }}</td><td class="r num">{{ bytes(d.storage_bytes) }}</td><td class="r num">{{ d.play_requests }}</td><td class="r num">{{ d.transcode_jobs }}</td></tr>
        </tbody>
      </table>
    </div>
    <ListState :loaded="loaded" :count="usage?.history?.length ?? 0" :failed="error" title="这段时间没有用量记录" />
  </section>
</template>
