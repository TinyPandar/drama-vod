import { createApp } from "vue";
import { createRouter, createWebHashHistory } from "vue-router";
import App from "./App.vue";
import { setAdminExpiredHandler } from "./api";
import { adminExpired, clearAdmin, session } from "./session";
import "./styles/console.css";
import LoginView from "./views/LoginView.vue";
import TenantsView from "./views/TenantsView.vue";
import TenantDetailView from "./views/TenantDetailView.vue";
import AssetsView from "./views/AssetsView.vue";
import AssetDetailView from "./views/AssetDetailView.vue";
import JobsView from "./views/JobsView.vue";
import JobDetailView from "./views/JobDetailView.vue";
import TemplatesView from "./views/TemplatesView.vue";
import WebhooksView from "./views/WebhooksView.vue";
import UsageView from "./views/UsageView.vue";
import KeyView from "./views/KeyView.vue";

// Hash 路由：静态文件仍由 Nginx 的 location / 直接提供，刷新深链不需要额外的回退规则。
// meta：admin = 需要管理登录；tenant = 需要租户 Key；either = 两种身份任一即可（模板）；
// nav = 侧栏高亮哪一项（详情页高亮所属列表）；group = 页头面包屑；bare = 不套后台外壳。
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: "/", redirect: () => (session.adminToken ? "/tenants" : session.tenantKey ? "/assets" : "/login") },
    { path: "/login", component: LoginView, meta: { bare: true, title: "管理登录" } },
    { path: "/key", component: KeyView, meta: { title: "选用租户 Key", group: "租户端" } },
    { path: "/tenants", component: TenantsView, meta: { admin: true, nav: "tenants", title: "租户与 Key", group: "管理端" } },
    { path: "/tenants/:id", component: TenantDetailView, meta: { admin: true, nav: "tenants", title: "租户", group: "管理端 / 租户与 Key" } },
    { path: "/assets", component: AssetsView, meta: { tenant: true, nav: "assets", title: "资产", group: "租户端" } },
    { path: "/assets/:id", component: AssetDetailView, meta: { tenant: true, nav: "assets", title: "资产", group: "租户端 / 资产" } },
    { path: "/jobs", component: JobsView, meta: { tenant: true, nav: "jobs", title: "任务", group: "租户端" } },
    { path: "/jobs/:id", component: JobDetailView, meta: { tenant: true, nav: "jobs", title: "任务", group: "租户端 / 任务" } },
    { path: "/templates", component: TemplatesView, meta: { either: true, nav: "templates", title: "模板", group: "管理端" } },
    { path: "/webhooks", component: WebhooksView, meta: { tenant: true, nav: "webhooks", title: "Webhook", group: "租户端" } },
    { path: "/usage", component: UsageView, meta: { tenant: true, nav: "usage", title: "用量与配额", group: "租户端" } }
  ]
});

// 管理页：没登录或登录已过期就去登录页（带原因和回跳地址）。
// 租户页不在这里跳转：没选 Key 或 Key 失效时，外壳在原页面位置显示引导（见 App.vue）。
router.beforeEach((to) => {
  if (session.adminToken && adminExpired() && to.path !== "/login") {
    clearAdmin();
    if (to.meta.admin) return { path: "/login", query: { next: to.fullPath, reason: "expired" } };
  }
  if (to.meta.admin && !session.adminToken) return { path: "/login", query: { next: to.fullPath } };
  return true;
});

// 服务端用 401 拒绝了管理 JWT：登录状态已被 adminApi 清掉，这里带着「已过期」回登录页。
setAdminExpiredHandler(() => {
  const at = router.currentRoute.value;
  if (at.path !== "/login") router.replace({ path: "/login", query: { next: at.fullPath, reason: "expired" } });
});

createApp(App).use(router).mount("#app");
