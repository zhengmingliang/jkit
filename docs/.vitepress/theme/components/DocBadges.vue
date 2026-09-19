<script setup lang="ts">
import MavenBadge from './MavenBadge.vue'

withDefaults(
  defineProps<{
    artifact?: string
  }>(),
  { artifact: 'jkit' },
)
</script>

<template>
  <p class="doc-badges">
    <MavenBadge :artifact="artifact" />
    <a
      class="doc-badges__item"
      href="https://jkit.alianga.com/guide/getting-started"
      title="JDK 8+"
    >
      <img
        src="https://img.shields.io/badge/JDK-8%2B-orange?style=flat-square"
        alt="JDK 8+"
      />
    </a>
    <a
      class="doc-badges__item"
      href="https://www.apache.org/licenses/LICENSE-2.0"
      title="Apache License 2.0"
    >
      <img
        src="https://img.shields.io/badge/License-Apache_2.0-blue?style=flat-square"
        alt="Apache License 2.0"
      />
    </a>
  </p>
</template>

<style scoped>
.doc-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin: 8px 0 20px;
}

.doc-badges__item {
  display: inline-flex;
  line-height: 0;
  text-decoration: none !important;
  border: 0;
}

.doc-badges__item img {
  height: 20px;
}
</style>
