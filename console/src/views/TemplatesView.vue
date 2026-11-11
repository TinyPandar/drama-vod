<script setup lang="ts">
import { onMounted, ref } from "vue";
import { adminApi, tenantApi, type Template } from "../api";
import { session } from "../session";
import Notice from "../components/Notice.vue";
import { useAction } from "../useAction";

const templates = ref<Template[]>([]);
const source = ref("");
const { busy, message, error, run } = useAction();

async function load() {
  if (session.adminToken) {
    templates.value = (await adminApi<{ templates: Template[] }>("/templates")).templates;
    source.value = "管理端：全部模板";
  } else if (session.tenantKey) {
    templates.value = (await tenantApi<{ templates: Template[] }>("/templates")).templates;
    source.value = "当前租户可用的模板";
  } else {
    source.value = "请先管理登录或选用租户 Key";
  }
}

onMounted(() => run(load));
</script>

<template>
  <h1>转码模板</h1>
  <p class="muted small">{{ source }}。系统模板 ladder_v1 与旧梯队一致，默认只读；租户模板在「租户与 Key」的租户详情里创建。Worker 按任务读取模板，消息体里没有梯队。</p>
  <Notice :text="message" :error="error" />
  <div class="row"><button class="ghost" :disabled="busy" @click="run(load)">刷新</button></div>
  <table>
    <thead><tr><th>ID</th><th>名称</th><th>归属</th><th>编码 / 封装</th><th>梯队（档名 短边 码率）</th><th>音频</th><th>状态</th></tr></thead>
    <tbody>
      <tr v-for="t in templates" :key="t.id">
        <td>{{ t.id }}</td><td>{{ t.name }}</td><td>{{ t.system ? "系统" : `租户 #${t.tenant_id}` }}</td>
        <td>{{ t.video_codec }} / {{ t.container }}</td>
        <td class="mono">{{ t.ladder.map((r) => `${r[0]} ${r[1]} ${r[2]}k`).join(" · ") }}</td>
        <td>{{ t.audio_kbps }}k</td><td>{{ t.status }}</td>
      </tr>
    </tbody>
  </table>
</template>
