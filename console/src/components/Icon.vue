<script setup lang="ts">
/** 线性小图标，路径与已批准的设计稿一致。只做装饰，文字含义都另有文本。 */
const PATHS: Record<string, string> = {
  tenants: '<path d="M3 13.5V6l5-3 5 3v7.5H3z"/><path d="M6.5 13.5v-3h3v3"/>',
  templates: '<rect x="2.5" y="2.5" width="11" height="11" rx="1.5"/><path d="M2.5 6.5h11M6.5 6.5v7"/>',
  assets: '<rect x="2" y="3.5" width="12" height="9" rx="1.5"/><path d="M6.8 6.2v3.6L10 8z"/>',
  jobs: '<path d="M3 4h10M3 8h10M3 12h6"/>',
  webhooks: '<circle cx="4" cy="11.5" r="1.8"/><circle cx="12" cy="11.5" r="1.8"/><circle cx="8" cy="4" r="1.8"/><path d="M7.2 5.6L4.8 9.9M8.8 5.6l2.4 4.3M5.8 11.5h4.4"/>',
  usage: '<path d="M3 13V8M8 13V3M13 13V6"/>',
  play: '<circle cx="8" cy="8" r="5.5"/><path d="M7 5.8v4.4L10.2 8z"/>',
  ext: '<path d="M6.5 3.5h-3v9h9v-3M9 3h4v4M13 3L7.5 8.5"/>',
  lock: '<rect x="3.5" y="7" width="9" height="6" rx="1.2"/><path d="M5.5 7V5.2a2.5 2.5 0 015 0V7"/>',
  user: '<circle cx="8" cy="5.5" r="2.5"/><path d="M3 13.5c.6-2.4 2.6-3.6 5-3.6s4.4 1.2 5 3.6"/>',
  key: '<circle cx="5.2" cy="10.8" r="2.4"/><path d="M7 9l5.5-5.5M10.5 5.5l1.6 1.6M9 7l1.2 1.2"/>',
  info: '<circle cx="8" cy="8" r="5.8"/><path d="M8 7.2v3.6M8 5.2v.2"/>',
  warn: '<path d="M8 2.5l6 10.5H2z"/><path d="M8 6.8v2.8M8 11.4v.2"/>',
  check: '<circle cx="8" cy="8" r="5.8"/><path d="M5.4 8.2l1.8 1.8 3.4-3.6"/>',
  upload: '<path d="M8 10.5v-7M5 6l3-3 3 3M3 10.5v2.5h10v-2.5"/>',
  refresh: '<path d="M13 8a5 5 0 11-1.6-3.7M13 3v2.6h-2.6"/>',
  plus: '<path d="M8 3.5v9M3.5 8h9"/>',
  inbox: '<path d="M2.5 9.5l2-6h7l2 6v3.5h-11z"/><path d="M2.5 9.5h3.2l.8 1.5h3l.8-1.5h3.2"/>',
  menu: '<path d="M2.5 4.5h11M2.5 8h11M2.5 11.5h11"/>',
  close: '<path d="M4 4l8 8M12 4l-8 8"/>'
};
defineProps<{ name: string }>();
</script>

<template>
  <svg class="i" viewBox="0 0 16 16" aria-hidden="true" v-html="PATHS[name] ?? ''"></svg>
</template>
