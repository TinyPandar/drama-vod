<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { login } from "../api";
import { setAdmin } from "../session";
import Notice from "../components/Notice.vue";
import { useAction } from "../useAction";

// 不在源码里写任何默认账号或口令；开发环境的管理用户来自 VOD_ADMIN_USERNAME / VOD_ADMIN_PASSWORD。
const username = ref("");
const password = ref("");
const router = useRouter();
const route = useRoute();
const { busy, message, error, run } = useAction();

function submit() {
  run(async () => {
    const out = await login(username.value, password.value);
    password.value = "";
    setAdmin(out.token, out.username, out.expires_at);
    await router.push(typeof route.query.next === "string" ? route.query.next : "/tenants");
  });
}
</script>

<template>
  <h1>管理登录</h1>
  <p class="muted small">管理端用 JWT 鉴权（只能调 /api/v1/admin/**）；租户接口要用租户 API Key，在「租户与 Key」里创建或在「选用租户 Key」里粘贴。</p>
  <form @submit.prevent="submit">
    <label for="u">用户名</label>
    <input id="u" v-model="username" autocomplete="username" required />
    <label for="p">口令</label>
    <input id="p" v-model="password" type="password" autocomplete="current-password" required />
    <div class="row"><button type="submit" :disabled="busy">登录</button></div>
  </form>
  <Notice :text="message" :error="error" />
</template>
