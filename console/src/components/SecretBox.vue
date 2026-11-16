<script setup lang="ts">
/** 只显示一次的秘密（新 Key 明文、Webhook 签名密钥）。值只存在调用方的内存里，关闭即丢弃。 */
defineProps<{ title: string; value: string }>();
defineEmits<{ close: [] }>();
</script>

<template>
  <section class="secret-box" role="status">
    <div>
      <h3>{{ title }}</h3>
      <p class="small muted">关闭这块提示、刷新或离开页面后都无法再看到。需要留存请现在保存到安全的地方。</p>
    </div>
    <div class="secret-val">{{ value }}</div>
    <div class="btn-row">
      <slot />
      <button class="btn" type="button" @click="$emit('close')">我已保存，关闭</button>
    </div>
  </section>
</template>
