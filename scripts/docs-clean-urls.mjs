#!/usr/bin/env node
/**
 * 让静态服务器（npx serve / GitHub Pages）也能打开 VitePress 的干净 URL。
 * VitePress 产出的是 sql.html，链接却是 /sql；无 rewrite 时会 404 或列出目录。
 */
import { cpSync, existsSync, mkdirSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const SKIP_HTML = new Set(['index.html', '404.html'])
const SKIP_DIR = new Set(['assets', 'node_modules'])

export function materializeCleanUrls(rootDir) {
  const files = []
  function walk(dir) {
    for (const name of readdirSync(dir)) {
      const p = join(dir, name)
      const st = statSync(p)
      if (st.isDirectory()) {
        if (!SKIP_DIR.has(name)) {
          walk(p)
        }
        continue
      }
      if (name.endsWith('.html') && !SKIP_HTML.has(name)) {
        files.push(p)
      }
    }
  }
  walk(rootDir)
  let n = 0
  for (const file of files) {
    const destDir = file.slice(0, -'.html'.length)
    const dest = join(destDir, 'index.html')
    if (existsSync(dest)) {
      continue
    }
    mkdirSync(destDir, { recursive: true })
    cpSync(file, dest)
    n++
  }
  return n
}

export function writeServeConfig(rootDir) {
  writeFileSync(
    join(rootDir, 'serve.json'),
    `${JSON.stringify(
      {
        cleanUrls: true,
        directoryListing: false,
        trailingSlash: false,
      },
      null,
      2,
    )}\n`,
  )
}

export function finalizeStaticDist(rootDir) {
  const n = materializeCleanUrls(rootDir)
  writeServeConfig(rootDir)
  console.log(`✓ 静态干净 URL：复制了 ${n} 个 */index.html，并写入 serve.json`)
}

const thisFile = fileURLToPath(import.meta.url)
if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const dist = join(dirname(thisFile), '../docs/.vitepress/dist')
  if (!existsSync(dist)) {
    throw new Error(`没有 ${dist}，先跑 npm run docs:build`)
  }
  finalizeStaticDist(dist)
}
