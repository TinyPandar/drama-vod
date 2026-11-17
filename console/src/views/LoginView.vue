<script setup lang="ts">
import { computed, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ApiError, explain, login } from "../api";
import { setAdmin } from "../session";
import Icon from "../components/Icon.vue";

// 不在源码里写任何默认账号或口令；开发环境的管理用户来自 VOD_ADMIN_USERNAME / VOD_ADMIN_PASSWORD。
const username = ref("");
const password = ref("");
const router = useRouter();
const route = useRoute();
const busy = ref(false);
const failure = ref<{ title: string; text: string; raw: string } | null>(null);
const expired = computed(() => route.query.reason === "expired");

async function submit() {
  busy.value = true;
  failure.value = null;
  try {
    const out = await login(username.value, password.value);
    password.value = "";
    setAdmin(out.token, out.username, out.expires_at);
    await router.push(typeof route.query.next === "string" ? route.query.next : "/tenants");
  } catch (e) {
    const said = explain(e);
    failure.value = e instanceof ApiError && e.status === 401
      ? { title: "用户名或口令不正确", text: "请检查后重新输入。", raw: said.raw }
      : { title: "没有登录成功", text: said.text, raw: said.raw };
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="login">
    <section class="login-info" aria-label="两种身份的说明">
      <div class="brand" style="height: auto; padding: 0; border: 0">
        <span class="brand-mark"><svg viewBox="0 0 16 16"><path d="M5 3.2v9.6L13 8z" /></svg></span>视频云控制台
      </div>
      <div>
        <h1>先登录管理端，再选一把租户 Key</h1>
        <p class="muted" style="margin-top: 8px; max-width: 52ch">控制台里有两种身份，各管一部分页面，互相不能代替。登录后页面右上角会一直显示这两项的当前状态。</p>
      </div>
      <div class="id-cards">
        <div class="id-card admin">
          <span class="ic"><Icon name="user" /></span>
          <div>
            <h3>管理登录 <span class="id-tag admin outline">本页登录的就是它</span></h3>
            <p>用管理员用户名和口令登录。可以进入「租户与 Key」「模板」：建租户、调配额、新建或撤销 Key。</p>
          </div>
        </div>
        <div class="id-card tenant">
          <span class="ic"><Icon name="key" /></span>
          <div>
            <h3>租户 Key <span class="id-tag tenant outline">登录后再选用</span></h3>
            <p>某个租户的 API Key。「资产」「任务」「Webhook」「用量与配额」只认它，管理登录进不去这些页面。Key 只保存在当前浏览器标签页。</p>
          </div>
        </div>
      </div>
      <div class="flow" aria-label="首次使用的顺序">
        <b>① 管理登录</b><span class="arrow">→</span>
        <b>② 建租户、新建 Key</b><span class="arrow">→</span>
        <b>③ 点「在本标签页使用」</b><span class="arrow">→</span>
        <span>进入资产等租户页</span>
      </div>
    </section>

    <section class="login-form">
      <div class="login-card">
        <div v-if="expired && !failure" class="alert warn" role="status">
          <Icon name="warn" />
          <div class="a-body">
            <div class="a-title">管理登录已过期</div>
            <div class="a-text">请重新登录，登录后会回到刚才的页面。已选用的租户 Key 不受影响。</div>
          </div>
        </div>

        <form class="card" aria-labelledby="login-title" @submit.prevent="submit">
          <div>
            <h2 id="login-title">管理登录</h2>
            <p class="muted small" style="margin-top: 2px">使用部署时配置的管理员账号。</p>
          </div>

          <div v-if="failure" class="alert err" role="alert">
            <Icon name="warn" />
            <div class="a-body">
              <div class="a-title">{{ failure.title }}</div>
              <div class="a-text">{{ failure.text }}</div>
              <div v-if="failure.raw" class="a-raw">服务端返回：<code>{{ failure.raw }}</code></div>
            </div>
          </div>

          <div class="field">
            <label for="u">用户名</label>
            <input id="u" v-model="username" class="input" autocomplete="username" required />
          </div>
          <div class="field">
            <label for="p">口令</label>
            <input id="p" v-model="password" class="input" :class="{ invalid: !!failure }" type="password" autocomplete="current-password" required />
          </div>

          <button class="btn primary lg" type="submit" :disabled="busy"><span v-if="busy" class="spin"></span>{{ busy ? "正在登录…" : "登录" }}</button>
        </form>

        <p class="small muted" style="text-align: center">
          没有管理账号，只有一把租户 Key？<RouterLink to="/key">直接选用租户 Key</RouterLink>
        </p>
      </div>
    </section>
  </div>
</template>
