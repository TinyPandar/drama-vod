import { createApp } from "vue";
import { createRouter, createWebHashHistory } from "vue-router";
import App from "./App.vue";
import { session } from "./session";
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
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: "/", redirect: () => (session.adminToken ? "/tenants" : session.tenantKey ? "/assets" : "/login") },
    { path: "/login", component: LoginView },
    { path: "/key", component: KeyView },
    { path: "/tenants", component: TenantsView, meta: { admin: true } },
    { path: "/tenants/:id", component: TenantDetailView, meta: { admin: true } },
    { path: "/assets", component: AssetsView, meta: { tenant: true } },
    { path: "/assets/:id", component: AssetDetailView, meta: { tenant: true } },
    { path: "/jobs", component: JobsView, meta: { tenant: true } },
    { path: "/jobs/:id", component: JobDetailView, meta: { tenant: true } },
    { path: "/templates", component: TemplatesView },
    { path: "/webhooks", component: WebhooksView, meta: { tenant: true } },
    { path: "/usage", component: UsageView, meta: { tenant: true } }
  ]
});

router.beforeEach((to) => {
  if (to.meta.admin && !session.adminToken) return { path: "/login", query: { next: to.fullPath } };
  if (to.meta.tenant && !session.tenantKey) return { path: "/key", query: { next: to.fullPath } };
  return true;
});

createApp(App).use(router).mount("#app");
