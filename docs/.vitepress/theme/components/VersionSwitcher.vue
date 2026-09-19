<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useData, useRoute } from 'vitepress'
import { versions, currentVersion, releasesUrl } from '../../data/versions'
import type { DocVersion } from '../../data/versions'

const DOCS_ORIGIN = 'https://jkit.alianga.com'

const { lang } = useData()
const route = useRoute()
const open = ref(false)
const currentLabel = computed(() => (lang.value.startsWith('en') ? 'current' : '当前'))
const sourceLabel = computed(() => (lang.value.startsWith('en') ? 'source' : '源码'))

function close() {
  open.value = false
}

function toggle() {
  open.value = !open.value
}

/** 当前文档路径（不含 /vX.Y.Z 前缀），如 /sql、/en/sql */
function docPath(): string {
  let path = route.path || '/'
  path = path.replace(/^\/v\d+\.\d+\.\d+(?=\/|$)/, '')
  if (!path) {
    path = '/'
  }
  const hash = typeof window !== 'undefined' ? window.location.hash : ''
  return path + hash
}

function joinArchive(archivePath: string, page: string): string {
  const root = archivePath.replace(/\/$/, '')
  const hashIdx = page.indexOf('#')
  const pathname = hashIdx < 0 ? page : page.slice(0, hashIdx)
  const hash = hashIdx < 0 ? '' : page.slice(hashIdx)
  if (!pathname || pathname === '/') {
    return root + '/' + hash
  }
  return root + pathname + hash
}

/**
 * 历史版是另一份静态站，不能走当前站 Vue Router。
 * docs:dev 下没有 /v2.0.1/，改开已部署站点；预览/生产用相对路径。
 * 切换时尽量留在同一篇（/sql → /v2.0.1/sql）。
 */
function hrefFor(v: DocVersion): string {
  const page = docPath()
  if (v.current || v.path === '/') {
    return page
  }
  if (v.path) {
    const dest = joinArchive(v.path, page)
    return import.meta.env.DEV ? DOCS_ORIGIN + dest : dest
  }
  return releasesUrl
}

function isExternal(v: DocVersion): boolean {
  return hrefFor(v).startsWith('http')
}

function onItemClick(e: MouseEvent, v: DocVersion) {
  close()
  if (v.current || v.path === '/') {
    return
  }
  const href = hrefFor(v)
  if (href.startsWith('http')) {
    return
  }
  // 同站 /v2.0.1/ 必须整页跳转，不能让 VitePress Router 当成站内页
  e.preventDefault()
  window.location.assign(href)
}

function onDocClick(e: MouseEvent) {
  const el = e.target as HTMLElement | null
  if (el && !el.closest('.version-switcher')) {
    close()
  }
}

onMounted(() => document.addEventListener('click', onDocClick))
onUnmounted(() => document.removeEventListener('click', onDocClick))
</script>

<template>
  <div class="version-switcher" @click.stop>
    <button class="version-switcher__btn" type="button" @click.stop="toggle">
      v{{ currentVersion }}
      <span class="version-switcher__caret" aria-hidden="true">▾</span>
    </button>
    <ul v-if="open" class="version-switcher__menu">
      <li v-for="v in versions" :key="v.version">
        <a
          class="version-switcher__item"
          :class="{ 'is-current': v.current }"
          :href="hrefFor(v)"
          :target="isExternal(v) ? '_blank' : undefined"
          :rel="isExternal(v) ? 'noopener' : undefined"
          @click="onItemClick($event, v)"
        >
          <span>{{ v.version }}</span>
          <span v-if="v.current" class="version-switcher__tag">{{ currentLabel }}</span>
          <span v-else-if="!v.path" class="version-switcher__tag is-muted">{{ sourceLabel }}</span>
        </a>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.version-switcher {
  position: relative;
  margin-right: 12px;
}

.version-switcher__btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  line-height: 18px;
  color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  border: 1px solid transparent;
  cursor: pointer;
  white-space: nowrap;
  transition: border-color 0.25s, color 0.25s;
}

.version-switcher__btn:hover {
  border-color: var(--vp-c-brand-1);
}

.version-switcher__caret {
  font-size: 10px;
  opacity: 0.75;
}

.version-switcher__menu {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  z-index: 40;
  min-width: 148px;
  margin: 0;
  padding: 6px;
  list-style: none;
  background: var(--vp-c-bg);
  border: 1px solid var(--vp-c-divider);
  border-radius: 10px;
  box-shadow: 0 12px 32px rgba(20, 12, 4, 0.14);
}

.version-switcher__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 6px 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--vp-c-text-1);
  font-size: 13px;
  cursor: pointer;
  text-align: left;
  text-decoration: none !important;
  box-sizing: border-box;
}

.version-switcher__item:hover {
  background: var(--vp-c-bg-soft);
  color: var(--vp-c-brand-1);
}

.version-switcher__item.is-current {
  color: var(--vp-c-brand-1);
  font-weight: 650;
}

.version-switcher__tag {
  font-size: 11px;
  color: var(--vp-c-brand-1);
}

.version-switcher__tag.is-muted {
  color: var(--vp-c-text-3);
}
</style>
