<script setup lang="ts">
import { useData, withBase } from 'vitepress'
import { currentVersion } from '../../data/versions'

const { lang } = useData()
</script>

<template>
  <a
    class="version-badge"
    :href="withBase(lang.startsWith('en') ? '/en/changelog' : '/changelog')"
    :title="lang.startsWith('en') ? 'Changelog' : '更新日志'"
  >
    v{{ currentVersion }}
  </a>
</template>

<style scoped>
.version-badge {
  margin-left: 10px;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  line-height: 18px;
  color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  border: 1px solid transparent;
  transition: border-color 0.25s, color 0.25s;
  white-space: nowrap;
}

.version-badge:hover {
  border-color: var(--vp-c-brand-1);
}

@media (max-width: 768px) {
  .version-badge {
    display: none;
  }
}
</style>
