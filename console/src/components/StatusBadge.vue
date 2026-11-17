<script setup lang="ts">
import { computed } from "vue";
import { statusInfo } from "../api";

/** 状态徽标：中文说法 + 颜色，旁边用小号等宽字保留接口原值。 */
const props = defineProps<{ value: string; kind?: "key" }>();
const info = computed(() => statusInfo(props.value, props.kind));
</script>

<template>
  <span class="badge" :class="info.tone">{{ info.label }}</span><span v-if="info.label !== value" class="enum">{{ value }}</span>
</template>
