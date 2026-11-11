<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { setTenantKey, session } from "../session";

const key = ref("");
const label = ref("");
const router = useRouter();
const route = useRoute();

function use() {
  if (!key.value.trim()) return;
  setTenantKey(key.value.trim(), label.value.trim() || "手动输入");
  key.value = "";
  router.push(typeof route.query.next === "string" ? route.query.next : "/assets");
}
</script>

<template>
  <h1>选用租户 API Key</h1>
  <p class="muted small">
    租户页面（资产、任务、Webhook、用量）用这把 Key 调租户接口，只保存在本标签页。
    管理员可以在「租户与 Key」里新建 Key 时直接选用；Key 明文只在创建或轮换后显示一次。
  </p>
  <p v-if="session.tenantKey" class="notice">当前已选用：{{ session.tenantLabel }}</p>
  <form @submit.prevent="use">
    <label for="k">API Key</label>
    <input id="k" v-model="key" autocomplete="off" type="password" />
    <label for="l">备注（只在本页显示）</label>
    <input id="l" v-model="label" autocomplete="off" />
    <div class="row"><button type="submit">使用这把 Key</button></div>
  </form>
</template>
