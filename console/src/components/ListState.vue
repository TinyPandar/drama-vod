<script setup lang="ts">
import Icon from "./Icon.vue";

/**
 * 列表的加载 / 空 / 失败占位，放在卡片里表格的位置。
 * loaded=false 显示骨架条；加载完且 count=0 显示空状态；failed 时说明原因在页面上方的提示条里。
 */
defineProps<{ loaded: boolean; count: number; failed?: boolean; title: string; hint?: string }>();
</script>

<template>
  <div v-if="!loaded" class="card-body stack" aria-busy="true" aria-label="正在加载">
    <span class="skel" style="width: 62%"></span><span class="skel" style="width: 44%"></span><span class="skel" style="width: 55%"></span>
  </div>
  <div v-else-if="count === 0 && failed" class="state">
    <span class="ico err"><Icon name="warn" /></span>
    <h3>暂时无法显示</h3>
    <p>原因见页面上方的提示，处理后点「刷新」重试。</p>
  </div>
  <div v-else-if="count === 0" class="state">
    <span class="ico"><Icon name="inbox" /></span>
    <h3>{{ title }}</h3>
    <p v-if="hint">{{ hint }}</p>
  </div>
</template>
