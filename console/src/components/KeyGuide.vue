<script setup lang="ts">
import { useRoute } from "vue-router";
import { session } from "../session";
import Icon from "./Icon.vue";

/**
 * 租户页的身份引导：没选用 Key，或当前 Key 被服务端拒绝时，代替页面内容出现。
 * 这只是界面引导；能不能调租户接口始终由服务端按 Key 判断。
 */
defineProps<{ page: string; rejected: boolean; either?: boolean }>();
defineEmits<{ retry: [] }>();
const route = useRoute();
</script>

<template>
  <section v-if="rejected" class="card">
    <div class="state">
      <span class="ico err"><Icon name="key" /></span>
      <h2>当前选用的租户 Key 已失效</h2>
      <p>「{{ session.tenantLabel || "已选用的 Key" }}」被服务端拒绝了。常见原因：这把 Key 被撤销或轮换、已过期，或所属租户被停用。
        <template v-if="session.adminToken">你的<b>管理登录仍然有效</b>，不需要重新登录。</template></p>
      <div class="btn-row">
        <RouterLink v-if="session.adminToken" class="btn primary" to="/tenants">去「租户与 Key」换一把</RouterLink>
        <RouterLink class="btn" :class="{ primary: !session.adminToken }" :to="{ path: '/key', query: { next: route.fullPath } }">粘贴另一把 Key</RouterLink>
        <button class="btn text" type="button" @click="$emit('retry')">重试</button>
      </div>
      <p class="raw">服务端返回：<code>unauthorized（401）</code></p>
    </div>
  </section>

  <section v-else class="card">
    <div class="state">
      <span class="ico tenant"><Icon name="key" /></span>
      <h2>{{ either ? `先登录管理端或选用租户 Key，才能查看${page}` : `先选用一把租户 Key，才能查看${page}` }}</h2>
      <p v-if="either">「{{ page }}」用管理登录或租户 Key 都能看：管理登录看全部模板，租户 Key 看该租户可用的模板。现在两种身份都没有。</p>
      <p v-else-if="session.adminToken">你已经用管理员身份登录，但「{{ page }}」属于租户端页面，只认租户 Key。管理登录不能代替它。</p>
      <p v-else>「{{ page }}」属于租户端页面，只认租户 Key。有 Key 可以直接粘贴；没有的话，请先用管理登录去新建一把。</p>
      <div class="steps">
        <div class="step" :class="session.adminToken ? 'done' : ''"><span class="n">{{ session.adminToken ? "✓" : "1" }}</span>
          <div><b>管理登录</b><span>{{ session.adminToken ? "已完成" : "没有 Key 时需要" }}</span></div></div>
        <div class="step now"><span class="n">2</span><div><b>新建或选用 Key</b><span>在「租户与 Key」进入一个租户，新建 Key</span></div></div>
        <div class="step"><span class="n">3</span><div><b>在本标签页使用</b><span>选用后进入租户端页面</span></div></div>
      </div>
      <div class="btn-row">
        <RouterLink v-if="session.adminToken" class="btn primary" to="/tenants">去「租户与 Key」新建 Key</RouterLink>
        <RouterLink v-else class="btn primary" :to="{ path: '/login', query: { next: either ? route.fullPath : '/tenants' } }">去管理登录</RouterLink>
        <RouterLink class="btn" :to="{ path: '/key', query: { next: route.fullPath } }">我已有 Key，直接粘贴</RouterLink>
      </div>
      <p class="tiny faint">Key 只保存在当前浏览器标签页，关闭标签页后需要重新选用。</p>
    </div>
  </section>
</template>
