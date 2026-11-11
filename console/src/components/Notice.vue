<script setup lang="ts">
defineProps<{ text: string; error?: boolean }>();
</script>

<template>
  <p v-if="text" class="notice" :class="{ error }">{{ text }}</p>
</template>
