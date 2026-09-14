#!/usr/bin/env node
/**
 * 构建当前文档，再从 git tag 构建历史版本，嵌到 dist/vX.Y.Z/。
 * 仓库里不拷贝多份 markdown：历史文档的源是对应 tag。
 *
 * 用法：在仓库根目录 `npm run docs:build:all`
 */
import { spawnSync } from 'node:child_process'
import {
  cpSync,
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { finalizeStaticDist } from './docs-clean-urls.mjs'

const root = dirname(fileURLToPath(new URL('../package.json', import.meta.url)))
const dist = join(root, 'docs/.vitepress/dist')
const worktrees = join(root, '.docs-worktrees')

function run(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, { stdio: 'inherit', cwd: root, ...opts })
  if (r.status !== 0) {
    throw new Error(`${cmd} ${args.join(' ')} 失败（exit ${r.status}）`)
  }
  return r
}

function runSoft(cmd, args, opts = {}) {
  return spawnSync(cmd, args, { stdio: 'pipe', cwd: root, ...opts })
}

function loadArchived() {
  const src = readFileSync(join(root, 'docs/.vitepress/data/versions.ts'), 'utf8')
  const current = /export const currentVersion = '([^']+)'/.exec(src)
  if (!current) {
    throw new Error('versions.ts 缺少 currentVersion')
  }
  const all = []
  const objRe = /\{[^{}]+\}/g
  let m
  while ((m = objRe.exec(src))) {
    const block = m[0]
    const version = /version:\s*'([^']+)'/.exec(block)
    if (!version) {
      continue
    }
    const path = /path:\s*'([^']+)'/.exec(block)
    const tagMatch = /tag:\s*'([^']+)'/.exec(block)
    const isCurrent = /current:\s*true/.test(block)
    all.push({
      version: version[1],
      path: path ? path[1] : '',
      current: isCurrent,
      tag: tagMatch ? tagMatch[1] : `v${version[1]}`,
    })
  }
  return {
    current: current[1],
    all,
    archived: all.filter((v) => !v.current && v.path),
  }
}

function dropDuplicateChangelogs(docsDir) {
  for (const name of ['CHANGELOG.md', 'CHANGELOG-en.md']) {
    const p = join(docsDir, name)
    if (existsSync(p)) {
      rmSync(p)
      console.log(`  去掉与小写 changelog 撞名的 ${name}`)
    }
  }
}

function patchIgnoreDeadLinks(configPath) {
  if (!existsSync(configPath)) {
    return
  }
  let src = readFileSync(configPath, 'utf8')
  if (src.includes('ignoreDeadLinks')) {
    return
  }
  if (!src.includes('defineConfig({')) {
    return
  }
  writeFileSync(
    configPath,
    src.replace('defineConfig({', 'defineConfig({\n  ignoreDeadLinks: true,'),
  )
}

function tagExists(tag) {
  const r = spawnSync('git', ['rev-parse', '--verify', '--quiet', `refs/tags/${tag}`], {
    cwd: root,
  })
  return r.status === 0
}

function walkHtml(dir, visit) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    const st = statSync(p)
    if (st.isDirectory()) {
      walkHtml(p, visit)
    } else if (name.endsWith('.html')) {
      visit(p)
    }
  }
}

function injectBanner(outDir, version, all) {
  const chips = all
    .filter((v) => v.current || v.path)
    .map((v) => {
      const dest = v.current ? '/' : v.path
      const here = v.version === version
      const label = v.current ? `${v.version} 最新` : v.version
      const color = here ? '#fff' : '#fdba74'
      const weight = here ? '700' : '600'
      const deco = here ? 'underline' : 'none'
      return (
        `<a href="${dest}" data-jkit-ver="${dest}" ` +
        `style="color:${color};font-weight:${weight};text-decoration:${deco};margin:0 .45em;">` +
        `${label}</a>`
      )
    })
    .join('')
  const banner =
    `<div id="jkit-archive-banner" style="position:sticky;top:0;z-index:10000;background:#9a3412;` +
    `color:#fff;font-size:13px;line-height:1.5;text-align:center;padding:7px 16px;` +
    `font-family:system-ui,sans-serif;">` +
    `正在浏览 <strong>${version}</strong> · Viewing <strong>${version}</strong>` +
    `<span style="opacity:.55;margin:0 .35em;">|</span>${chips}</div>` +
    `<script>(function(){function page(){var p=location.pathname.replace(/^\\/v\\d+\\.\\d+\\.\\d+/, '');` +
    `if(!p)p='/';return p+location.search+location.hash}` +
    `function go(dest){var p=page();var path=p.split('#')[0].split('?')[0];var rest=p.slice(path.length);` +
    `var here=(location.pathname.match(/^\\/v\\d+\\.\\d+\\.\\d+/)||[''])[0];` +
    `var d=(dest||'/').replace(/\\/$/, '');` +
    `if(d&&d===here)return;` +
    `if(!dest||dest==='/'){location.assign(path+rest);return}` +
    `var root=dest.replace(/\\/$/, '');location.assign((path==='/'?root+'/':root+path)+rest)}` +
    `document.addEventListener('click',function(e){var t=e.target;if(!t||!t.closest)return;` +
    `var a=t.closest('#jkit-archive-banner [data-jkit-ver]');if(!a)return;` +
    `e.preventDefault();e.stopPropagation();if(e.stopImmediatePropagation)e.stopImmediatePropagation();` +
    `go(a.getAttribute('data-jkit-ver'))},true)})();<\/script>`
  walkHtml(outDir, (file) => {
    let html = readFileSync(file, 'utf8')
    if (html.includes('id="jkit-archive-banner"')) {
      return
    }
    html = html.replace(/<body([^>]*)>/i, `<body$1>${banner}`)
    writeFileSync(file, html)
  })
}

function buildArchive(item) {
  const destRel = item.path.replace(/^\/+|\/+$/g, '')
  const dest = join(dist, destRel)
  const wt = join(worktrees, item.version)
  console.log(`\n▶ 构建历史文档 ${item.version}（tag ${item.tag}）→ /${destRel}/`)
  if (!tagExists(item.tag)) {
    throw new Error(`找不到 git tag ${item.tag}，无法构建 ${item.version} 文档`)
  }
  runSoft('git', ['worktree', 'remove', '--force', wt])
  rmSync(wt, { recursive: true, force: true })
  run('git', ['worktree', 'add', '--detach', wt, item.tag])
  try {
    // 历史 tag 里的死链不应阻断归档构建；只改 worktree，不改 tag
    patchIgnoreDeadLinks(join(wt, 'docs/.vitepress/config.ts'))
    // v2.0.1 同时有 CHANGELOG.md 与 changelog.md，Linux 构建会撞名
    dropDuplicateChangelogs(join(wt, 'docs'))
    run('npm', ['ci'], { cwd: wt })
    run('npx', ['vitepress', 'build', 'docs', '--base', `/${destRel}/`], { cwd: wt })
    const srcDist = join(wt, 'docs/.vitepress/dist')
    if (!existsSync(srcDist)) {
      throw new Error(`${item.tag} 构建后没有 docs/.vitepress/dist`)
    }
    rmSync(dest, { recursive: true, force: true })
    mkdirSync(dirname(dest), { recursive: true })
    cpSync(srcDist, dest, { recursive: true })
    const cname = join(dest, 'CNAME')
    if (existsSync(cname)) {
      rmSync(cname)
    }
    injectBanner(dest, item.version, all)
  } finally {
    runSoft('git', ['worktree', 'remove', '--force', wt])
    rmSync(wt, { recursive: true, force: true })
  }
}

const { current, archived, all } = loadArchived()
console.log(`▶ 构建当前文档 ${current} → /`)
run('npx', ['vitepress', 'build', 'docs'])
if (!existsSync(dist)) {
  throw new Error('当前文档构建失败：没有 docs/.vitepress/dist')
}
mkdirSync(worktrees, { recursive: true })
for (const item of archived) {
  buildArchive(item)
}
finalizeStaticDist(dist)
console.log(`\n✓ 文档已输出到 ${dist}`)
for (const item of archived) {
  console.log(`  /${item.path.replace(/^\/+|\/+$/g, '')}/  ← ${item.version}`)
}
