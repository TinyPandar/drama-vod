<script setup lang="ts">
import { onMounted, ref } from "vue";
import { adminApi, bytes, post, type Tenant } from "../api";
import Notice from "../components/Notice.vue";
import { useAction } from "../useAction";

const tenants = ref<Tenant[]>([]);
const name = ref("");
const { busy, message, error, run } = useAction();

async function load() {
  tenants.value = (await adminApi<{ tenants: Tenant[] }>("/tenants")).tenants;
}

function create() {
  run(async () => {
    const t = await adminApi<Tenant>("/tenants", post({ name: name.value }));
    name.value = "";
    await load();
    return `已创建租户 #${t.id} ${t.name}`;
  });
}

function toggle(t: Tenant) {
  run(async () => {
    const status = t.status === "ACTIVE" ? "DISABLED" : "ACTIVE";
    await adminApi(`/tenants/${t.id}`, { method: "PATCH", body: JSON.stringify({ status }) });
    await load();
    return status === "DISABLED" ? `已停用 ${t.name}，它的所有 Key 立即失效` : `已启用 ${t.name}`;
  });
}

onMounted(() => run(load));
</script>

<template>
  <h1>租户</h1>
  <form class="row" @submit.prevent="create">
    <div class="field"><label for="n">新租户名称</label><input id="n" v-model="name" required maxlength="100" /></div>
    <button type="submit" :disabled="busy">创建</button>
  </form>
  <Notice :text="message" :error="error" />
  <table>
    <thead><tr><th>ID</th><th>名称</th><th>状态</th><th>存储（已用/配额）</th><th>并发上限</th><th>每日转码</th><th>有效 Key</th><th></th></tr></thead>
    <tbody>
      <tr v-for="t in tenants" :key="t.id">
        <td>{{ t.id }}</td>
        <td><RouterLink :to="`/tenants/${t.id}`">{{ t.name }}</RouterLink></td>
        <td>{{ t.status }}</td>
        <td>{{ bytes(t.storage_used_bytes) }} / {{ t.storage_quota_bytes ? bytes(t.storage_quota_bytes) : "默认" }}</td>
        <td>{{ t.max_concurrent_jobs ?? "默认" }}</td>
        <td>{{ t.max_daily_transcodes ?? "默认" }}</td>
        <td>{{ t.active_keys }}</td>
        <td><button class="ghost" :disabled="busy" @click="toggle(t)">{{ t.status === "ACTIVE" ? "停用" : "启用" }}</button></td>
      </tr>
    </tbody>
  </table>
  <p class="muted small">配额留空表示用服务端默认值，0 表示不限制。超出并发或每日上限时接口返回 429（code=quota_exceeded）。</p>
</template>
