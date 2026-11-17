<script setup lang="ts">
import Icon from "./Icon.vue";

/** 操作结果提示条：先说人话（text），接口原文（raw）放最后一行小字。 */
defineProps<{ text: string; error?: boolean; raw?: string }>();
</script>

<template>
  <div v-if="text" class="alert" :class="error ? 'err' : 'ok'" :role="error ? 'alert' : 'status'">
    <Icon :name="error ? 'warn' : 'check'" />
    <div class="a-body">
      <div class="a-title">{{ error ? "这次操作没有完成" : text }}</div>
      <div v-if="error" class="a-text">{{ text }}</div>
      <div v-if="error && raw" class="a-raw">服务端返回：<code>{{ raw }}</code></div>
    </div>
  </div>
</template>
