<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { setTenantKey, session } from "../session";
import Icon from "../components/Icon.vue";

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

/** 只是让本标签页忘掉这把 Key，不会撤销它；撤销要在「租户与 Key」里做。 */
function forget() {
  setTenantKey("", "");
}
</script>

<template>
  <div class="page-head">
    <p>租户端页面（资产、任务、Webhook、用量与配额）用这把 Key 调租户接口。Key 只保存在当前浏览器标签页，页面上之后只显示备注，不再显示明文。</p>
  </div>

  <div v-if="session.tenantKey && session.tenantKeyRejected" class="alert err" role="status">
    <Icon name="warn" />
    <div class="a-body">
      <div class="a-title">当前选用的 Key 已失效：{{ session.tenantLabel }}</div>
      <div class="a-text">它被服务端拒绝了（被撤销或轮换、已过期，或所属租户被停用）。请在下面换一把。</div>
      <div class="a-act"><button class="btn sm" type="button" @click="forget">在本标签页停用这把 Key</button></div>
    </div>
  </div>
  <div v-else-if="session.tenantKey" class="alert info" role="status">
    <Icon name="key" />
    <div class="a-body">
      <div class="a-title">当前已选用：{{ session.tenantLabel }}</div>
      <div class="a-text">在下面粘贴另一把即可更换。</div>
      <div class="a-act">
        <RouterLink class="btn sm" to="/assets">继续使用，去资产</RouterLink>
        <button class="btn sm" type="button" @click="forget">在本标签页停用这把 Key</button>
      </div>
    </div>
  </div>

  <section class="card narrow">
    <div class="card-head"><h3>粘贴已有的 Key</h3><span class="id-tag tenant">租户 Key</span></div>
    <form class="card-body stack" @submit.prevent="use">
      <div class="field">
        <label for="k">API Key</label>
        <input id="k" v-model="key" class="input" autocomplete="off" type="password" required />
        <span class="hint">输入时不显示明文。是否有效由服务端判断，无效的 Key 进入租户页时会提示已失效。</span>
      </div>
      <div class="field">
        <label for="l">备注</label>
        <input id="l" v-model="label" class="input" autocomplete="off" maxlength="60" placeholder="例如：某租户演示用" />
        <span class="hint">只在本标签页显示，用来辨认是哪一把。不填则显示「手动输入」。</span>
      </div>
      <div class="btn-row"><button class="btn primary" type="submit">使用这把 Key</button></div>
    </form>
  </section>

  <section class="card narrow">
    <div class="card-head"><h3>还没有 Key？</h3><span class="id-tag admin">管理登录</span></div>
    <div class="card-body stack">
      <p class="muted">Key 由管理员在「租户与 Key」里为某个租户新建。明文只在新建或轮换后显示一次，当时可以直接点「在本标签页使用」。</p>
      <div class="btn-row">
        <RouterLink v-if="session.adminToken" class="btn" to="/tenants">去「租户与 Key」</RouterLink>
        <RouterLink v-else class="btn" :to="{ path: '/login', query: { next: '/tenants' } }">去管理登录</RouterLink>
      </div>
    </div>
  </section>
</template>
