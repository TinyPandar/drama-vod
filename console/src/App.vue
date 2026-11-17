<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { clearAdmin, session } from "./session";
import Icon from "./components/Icon.vue";
import KeyGuide from "./components/KeyGuide.vue";

const route = useRoute();
const router = useRouter();

/** 侧栏分组与顺序：管理端用管理登录，租户端用租户 Key。 */
const NAV = [
  { head: "管理端", by: "用管理登录", tenant: false, items: [
    { id: "tenants", label: "租户与 Key", to: "/tenants" },
    { id: "templates", label: "模板", to: "/templates" }
  ] },
  { head: "租户端", by: "用租户 Key", tenant: true, items: [
    { id: "assets", label: "资产", to: "/assets" },
    { id: "jobs", label: "任务", to: "/jobs" },
    { id: "webhooks", label: "Webhook", to: "/webhooks" },
    { id: "usage", label: "用量与配额", to: "/usage" }
  ] }
];

const keyUsable = computed(() => !!session.tenantKey && !session.tenantKeyRejected);
/** 这个页面要靠租户 Key 才能显示（模板页在没有管理登录时也走租户 Key）。 */
const needsKey = computed(() => !!route.meta.tenant || (!!route.meta.either && !session.adminToken));
const gated = computed(() => needsKey.value && !keyUsable.value);
const title = computed(() => {
  const base = String(route.meta.title ?? "");
  return route.params.id ? `${base} #${route.params.id}` : base;
});

// 「重试」：先撤掉失效标记再重新挂载页面，由页面重新请求；服务端仍拒绝的话会再次标记。
const attempt = ref(0);
function retry() {
  session.tenantKeyRejected = false;
  attempt.value++;
}

function logout() {
  clearAdmin();
  router.push("/login");
}

// 窄屏抽屉：菜单按钮打开；遮罩、关闭按钮、Esc、切页都会关。
const navOpen = ref(false);
watch(navOpen, (open) => document.body.classList.toggle("nav-open", open));
watch(() => route.fullPath, () => { navOpen.value = false; });
function onKey(e: KeyboardEvent) {
  if (e.key === "Escape") navOpen.value = false;
}
onMounted(() => document.addEventListener("keydown", onKey));
onBeforeUnmount(() => {
  document.removeEventListener("keydown", onKey);
  document.body.classList.remove("nav-open");
});
</script>

<template>
  <RouterView v-if="route.meta.bare" />

  <div v-else class="shell">
    <aside id="side" class="side" aria-label="后台导航">
      <div class="brand">
        <span class="brand-mark"><svg viewBox="0 0 16 16"><path d="M5 3.2v9.6L13 8z" /></svg></span>视频云控制台
        <button class="btn icon sm text close-nav" type="button" aria-label="关闭导航" @click="navOpen = false"><Icon name="close" /></button>
      </div>
      <nav class="nav">
        <div v-for="g in NAV" :key="g.head" class="nav-group">
          <div class="nav-head"><span>{{ g.head }}</span><span class="id-tag" :class="g.tenant ? 'tenant' : 'admin'">{{ g.by }}</span></div>
          <RouterLink v-for="it in g.items" :key="it.id" class="item" :class="{ on: route.meta.nav === it.id }" :to="it.to"
            :aria-current="route.meta.nav === it.id ? 'page' : undefined">
            <Icon :name="it.id" /><span>{{ it.label }}</span>
            <span v-if="g.tenant ? !keyUsable : !session.adminToken && !(it.id === 'templates' && keyUsable)" class="lock"
              :title="g.tenant ? '需要先选用可用的租户 Key' : '需要先管理登录'"><Icon name="lock" /></span>
          </RouterLink>
        </div>
      </nav>
      <div class="side-foot">
        <a href="/" title="打开旧的短剧播放页"><Icon name="play" /><span>短剧场（旧播放页）</span><span class="ext"><Icon name="ext" /></span></a>
      </div>
    </aside>
    <button v-if="navOpen" class="scrim" type="button" aria-label="关闭导航" @click="navOpen = false"></button>

    <div class="main">
      <header class="top">
        <button class="btn icon menu-btn" type="button" aria-label="打开导航" aria-controls="side" :aria-expanded="navOpen" @click="navOpen = true"><Icon name="menu" /></button>
        <div class="top-title">
          <div v-if="route.meta.group" class="crumb">{{ route.meta.group }}</div>
          <h1>{{ title }}</h1>
        </div>
        <div class="ctx" aria-label="当前身份">
          <span v-if="session.adminUser && session.adminToken" class="who admin" title="管理登录：只用于「管理端」页面">
            <Icon name="user" /><span class="k">管理员</span><span class="v">{{ session.adminUser }}</span>
            <button class="linklike" type="button" @click="logout">退出</button>
          </span>
          <span v-else class="who none"><Icon name="user" /><span class="k">未登录管理端</span>
            <RouterLink :to="{ path: '/login', query: { next: route.fullPath } }">去登录</RouterLink></span>

          <span v-if="!session.tenantKey" class="who none" title="租户端页面需要先选用一把租户 Key">
            <Icon name="key" /><span class="k">未选用租户 Key</span><RouterLink to="/key">去选用</RouterLink></span>
          <span v-else-if="session.tenantKeyRejected" class="who bad" title="这把 Key 已被服务端拒绝">
            <Icon name="key" /><span class="k">租户 Key 已失效</span><span class="v">{{ session.tenantLabel || "已选用" }}</span>
            <RouterLink to="/key">换 Key</RouterLink></span>
          <span v-else class="who tenant" title="租户 Key：只用于「租户端」页面。这里只显示备注和前缀，不显示明文">
            <Icon name="key" /><span class="k">租户 Key</span><span class="v">{{ session.tenantLabel || "已选用" }}</span>
            <RouterLink to="/key">换 Key</RouterLink></span>
        </div>
      </header>
      <main class="content">
        <div class="content-inner">
          <KeyGuide v-if="gated" :page="String(route.meta.title ?? '')" :rejected="!!session.tenantKey && session.tenantKeyRejected"
            :either="!!route.meta.either" @retry="retry" />
          <RouterView v-else :key="`${route.fullPath}#${attempt}`" />
        </div>
      </main>
    </div>
  </div>
</template>
