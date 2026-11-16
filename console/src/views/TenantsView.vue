<script setup lang="ts">
import { onMounted, ref } from "vue";
import { adminApi, bytes, post, type Tenant } from "../api";
import { session } from "../session";
import Icon from "../components/Icon.vue";
import ListState from "../components/ListState.vue";
import Notice from "../components/Notice.vue";
import StatusBadge from "../components/StatusBadge.vue";
import { useAction } from "../useAction";

const tenants = ref<Tenant[]>([]);
const name = ref("");
const { busy, message, raw, error, loaded, run } = useAction();

/** 存储用量占配额的百分比；没有配额（默认或不限）时不画进度。 */
function pct(t: Tenant): number {
  return t.storage_quota_bytes ? Math.min(100, Math.round((t.storage_used_bytes / t.storage_quota_bytes) * 100)) : 0;
}

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
  <div class="page-head">
    <p>这里用<b>管理登录</b>操作。每个租户有自己的配额和 API Key；要查看某个租户的资产、任务等，进入该租户新建一把 Key 并「在本标签页使用」。</p>
    <div class="btn-row"><button class="btn" type="button" :disabled="busy" @click="run(load)"><Icon name="refresh" />刷新</button></div>
  </div>

  <div v-if="!session.tenantKey && tenants.length" class="alert info">
    <Icon name="info" />
    <div class="a-body">
      <div class="a-title">还没有选用租户 Key</div>
      <div class="a-text">左侧「租户端」的页面现在进不去。点某个租户的「管理 Key」，新建一把 Key 后选「在本标签页使用」即可。</div>
    </div>
  </div>

  <Notice :text="message" :error="error" :raw="raw" />

  <section class="card">
    <div class="card-body">
      <form class="form-row" @submit.prevent="create">
        <div class="field" style="max-width: 420px">
          <label for="n">新租户名称</label>
          <input id="n" v-model="name" class="input" required maxlength="100" />
        </div>
        <button class="btn primary" type="submit" :disabled="busy"><Icon name="plus" />创建租户</button>
      </form>
    </div>
  </section>

  <section class="card">
    <div class="card-head"><h3>全部租户<span v-if="tenants.length" class="sub">共 {{ tenants.length }} 个</span></h3></div>
    <div v-if="tenants.length" class="table-wrap">
      <table class="tbl">
        <thead><tr><th>ID</th><th>名称</th><th>状态</th><th>存储（已用 / 配额）</th><th class="r">并发上限</th><th class="r">每日转码</th><th class="r">有效 Key</th><th class="ops">操作</th></tr></thead>
        <tbody>
          <tr v-for="t in tenants" :key="t.id">
            <td class="mono">{{ t.id }}</td>
            <td><RouterLink :to="`/tenants/${t.id}`">{{ t.name }}</RouterLink></td>
            <td><StatusBadge :value="t.status" /></td>
            <td>
              <div class="meter" :class="{ hot: pct(t) >= 90 }">
                <span class="num">{{ bytes(t.storage_used_bytes) }} / <template v-if="t.storage_quota_bytes">{{ bytes(t.storage_quota_bytes) }}</template><span v-else class="faint">默认</span></span>
                <i><b :style="{ width: pct(t) + '%' }"></b></i>
              </div>
            </td>
            <td class="r num"><template v-if="t.max_concurrent_jobs !== null">{{ t.max_concurrent_jobs }}</template><span v-else class="faint">默认</span></td>
            <td class="r num"><template v-if="t.max_daily_transcodes !== null">{{ t.max_daily_transcodes }}</template><span v-else class="faint">默认</span></td>
            <td class="r num">{{ t.active_keys }}</td>
            <td class="ops">
              <RouterLink class="btn sm" :to="`/tenants/${t.id}`">管理 Key</RouterLink>
              <button class="btn sm" :class="{ danger: t.status === 'ACTIVE' }" type="button" :disabled="busy" @click="toggle(t)">{{ t.status === "ACTIVE" ? "停用" : "启用" }}</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <ListState :loaded="loaded" :count="tenants.length" :failed="error" title="还没有租户"
      hint="在上面输入名称创建第一个租户。有了租户才能新建 API Key，进而使用资产、任务等租户端页面。" />
    <div v-if="tenants.length" class="card-foot">
      <span>配额显示「默认」表示用服务端默认值，0 表示不限制。停用租户后它的所有 Key 立即失效。超出并发或每日上限时，对应操作会被拒绝并提示配额已用尽。</span>
    </div>
  </section>
</template>
