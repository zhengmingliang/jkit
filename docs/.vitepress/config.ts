import { existsSync } from 'node:fs'
import { join, normalize } from 'node:path'
import { defineConfig, type DefaultTheme } from 'vitepress'
import { repoUrl } from './data/versions'

// dev 模式下 Vite 的 public 中间件只认确切文件路径，/tools/excel2json/ 这类
// 目录式 URL 会落到 SPA 回退页、前端再渲染成 404；生产环境由静态服务器的目录
// index 与 cleanUrls 处理，没有这个问题。这里加一个仅 dev 生效的中间件，把
// public/ 下实际存在的目录 URL 重写到 /index.html，两端行为对齐。
function publicDirIndexPlugin() {
  return {
    name: 'jkit-public-dir-index',
    configureServer(server: {
      config: { publicDir: string }
      middlewares: { use: (fn: (req: { method?: string; url?: string }, res: unknown, next: () => void) => void) => void }
    }) {
      server.middlewares.use((req, _res, next) => {
        const raw = req.url
        if (raw && (!req.method || req.method === 'GET' || req.method === 'HEAD')) {
          const qIdx = raw.indexOf('?')
          const pathname = qIdx >= 0 ? raw.slice(0, qIdx) : raw
          // 跳过带扩展名的资源请求和根路径
          if (pathname.startsWith('/') && !pathname.endsWith('/index.html') && !pathname.slice(1).includes('.')) {
            const indexRel = normalize(join(decodeURIComponent(pathname.slice(1)), 'index.html'))
            if (!indexRel.startsWith('..') && existsSync(join(server.config.publicDir, indexRel))) {
              req.url = (pathname.endsWith('/') ? pathname : pathname + '/') + 'index.html' + (qIdx >= 0 ? raw.slice(qIdx) : '')
            }
          }
        }
        next()
      })
    },
  }
}

// 中文没有空格分词，用 Intl.Segmenter 按词切分（对英文同样有效），
// 让 minisearch 的本地索引能命中中文词。
// 注意：该函数会被序列化到浏览器端执行，不能引用任何闭包变量，
// segmenter 必须挂在 globalThis 上缓存。
function tokenize(text: string): string[] {
  const g = globalThis as { __jkitSegmenter?: Intl.Segmenter }
  g.__jkitSegmenter ??= new Intl.Segmenter('zh', { granularity: 'word' })
  const tokens: string[] = []
  for (const seg of g.__jkitSegmenter.segment(text)) {
    if (seg.isWordLike) tokens.push(seg.segment)
  }
  return tokens
}

const zhSearchTranslations = {
  button: { buttonText: '搜索', buttonAriaLabel: '搜索' },
  modal: {
    displayDetails: '显示详细列表',
    resetButtonTitle: '重置搜索',
    backButtonTitle: '关闭搜索',
    noResultsText: '未找到相关结果',
    footer: {
      selectText: '选择',
      selectKeyAriaLabel: '回车',
      navigateText: '切换',
      navigateUpKeyAriaLabel: '上箭头',
      navigateDownKeyAriaLabel: '下箭头',
      closeText: '关闭',
      closeKeyAriaLabel: 'Esc',
    },
  },
}

// public/tools/ 下是独立静态工具页，不经过 VitePress 客户端路由；
// 链接必须带 target 才能走整页跳转，否则 SPA 接管后会落到 404。
const toolLink = { target: '_blank', rel: 'noopener' } as const

const zhNav: DefaultTheme.NavItem[] = [
  { text: '首页', link: '/' },
  { text: '快速开始', link: '/guide/getting-started' },
  {
    text: '模块文档',
    items: [
      { text: '其它工具模块', link: '/toolkit' },
      { text: 'IO 与资源', link: '/io' },
      { text: 'JSON 模块', link: '/json' },
      { text: 'YAML 模块', link: '/yaml' },
      { text: '配置读取', link: '/config' },
      { text: 'HTTP 客户端', link: '/http' },
      { text: 'SQL 解析', link: '/sql' },
      { text: '自动建表', link: '/sql-auto' },
      { text: '消息通知', link: '/notify' },
    ],
  },
  {
    text: '在线工具',
    items: [
      { text: 'Excel/CSV 转 JSON', link: '/tools/excel2json/', ...toolLink },
    ],
  },
  { text: '更新日志', link: '/changelog' },
]

const zhSidebar: DefaultTheme.Sidebar = [
  {
    text: '开始使用',
    items: [{ text: '快速开始', link: '/guide/getting-started' }],
  },
  {
    text: '核心模块',
    collapsed: false,
    items: [
      { text: '其它工具模块', link: '/toolkit' },
      { text: 'IO 与资源', link: '/io' },
      { text: 'JSON 模块', link: '/json' },
      { text: 'YAML 模块', link: '/yaml' },
      { text: '配置读取', link: '/config' },
      { text: 'CSV 模块', link: '/csv' },
      { text: '表达式引擎', link: '/expression' },
      { text: '韧性抽包', link: '/resilience' },
      { text: 'HTML 解析', link: '/html' },
    ],
  },
  {
    text: '网络与数据',
    collapsed: false,
    items: [
      { text: 'HTTP 客户端', link: '/http' },
      { text: 'SQL 解析', link: '/sql' },
      { text: '自动建表', link: '/sql-auto' },
      { text: '消息通知', link: '/notify' },
    ],
  },
  {
    text: '项目信息',
    items: [
      { text: '更新日志', link: '/changelog' },
      { text: '多版本说明', link: '/versions' },
    ],
  },
]

const enNav: DefaultTheme.NavItem[] = [
  { text: 'Home', link: '/en/' },
  { text: 'Getting Started', link: '/en/guide/getting-started' },
  {
    text: 'Modules',
    items: [
      { text: 'Misc Utilities', link: '/en/toolkit' },
      { text: 'IO & Resources', link: '/en/io' },
      { text: 'JSON', link: '/en/json' },
      { text: 'YAML', link: '/en/yaml' },
      { text: 'Configuration', link: '/en/config' },
      { text: 'HTTP Client', link: '/en/http' },
      { text: 'SQL Parsing', link: '/en/sql' },
      { text: 'Auto Schema', link: '/en/sql-auto' },
      { text: 'Notification', link: '/en/notify' },
    ],
  },
  {
    text: 'Tools',
    items: [
      { text: 'Excel/CSV to JSON', link: '/tools/excel2json/', ...toolLink },
    ],
  },
  { text: 'Changelog', link: '/en/changelog' },
]

const enSidebar: DefaultTheme.Sidebar = [
  {
    text: 'Start',
    items: [{ text: 'Getting Started', link: '/en/guide/getting-started' }],
  },
  {
    text: 'Core Modules',
    collapsed: false,
    items: [
      { text: 'Misc Utilities', link: '/en/toolkit' },
      { text: 'IO & Resources', link: '/en/io' },
      { text: 'JSON', link: '/en/json' },
      { text: 'YAML', link: '/en/yaml' },
      { text: 'Configuration', link: '/en/config' },
      { text: 'CSV', link: '/en/csv' },
      { text: 'Expression', link: '/en/expression' },
      { text: 'Resilience', link: '/en/resilience' },
      { text: 'HTML', link: '/en/html' },
    ],
  },
  {
    text: 'Network & Data',
    collapsed: false,
    items: [
      { text: 'HTTP Client', link: '/en/http' },
      { text: 'SQL Parsing', link: '/en/sql' },
      { text: 'Auto Schema', link: '/en/sql-auto' },
      { text: 'Notification', link: '/en/notify' },
    ],
  },
  {
    text: 'Project',
    items: [
      { text: 'Changelog', link: '/en/changelog' },
      { text: 'Versioned Docs', link: '/en/versions' },
    ],
  },
]

// 自定义域名 jkit.alianga.com 部署在根路径，base 直接设为 '/'
const base = '/'

export default defineConfig({
  title: 'jkit',
  description: '纯 JDK、零第三方依赖的 Java 通用工具库',
  base,
  cleanUrls: true,
  lastUpdated: true,
  // /v2.0.1/ 是 CI 从 tag 嵌进来的另一份站点，当前构建里还不存在
  ignoreDeadLinks: [
    (url) => /\/v\d+\.\d+\.\d+(\/|$)/.test(url),
    // public/ 下的独立静态工具页，不经过 VitePress 的 Markdown 路由
    (url) => url.startsWith('/tools/'),
  ],
  // next-plan 是内部开发计划，不构建进站点
  srcExclude: ['**/next-plan.md'],

  vite: {
    plugins: [publicDirIndexPlugin()],
  },

  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: `${base}favicon.svg` }],
    ['meta', { name: 'theme-color', content: '#ea580c' }],
    ['meta', { property: 'og:title', content: 'jkit' }],
    [
      'meta',
      {
        property: 'og:description',
        content: '纯 JDK、零第三方依赖的 Java 通用工具库',
      },
    ],
  ],

  themeConfig: {
    logo: '/logo.svg',
    socialLinks: [{ icon: 'github', link: repoUrl }],

    search: {
      provider: 'local',
      options: {
        miniSearch: {
          options: { tokenize },
          searchOptions: {
            fuzzy: 0.2,
            prefix: true,
            boost: { title: 4, text: 2, titles: 1 },
          },
        },
        locales: { root: { translations: zhSearchTranslations } },
      },
    },
  },

  locales: {
    root: {
      label: '简体中文',
      lang: 'zh-CN',
      themeConfig: {
        nav: zhNav,
        sidebar: zhSidebar,
        outline: { label: '本页目录', level: [2, 3] },
        docFooter: { prev: '上一篇', next: '下一篇' },
        lastUpdated: { text: '最后更新' },
        editLink: {
          pattern: `${repoUrl}/edit/develop/docs/:path`,
          text: '在 GitHub 上编辑此页',
        },
        returnToTopLabel: '回到顶部',
        sidebarMenuLabel: '菜单',
        darkModeSwitchLabel: '外观',
        lightModeSwitchTitle: '切换到浅色模式',
        darkModeSwitchTitle: '切换到深色模式',
        footer: {
          message: '基于 Apache License 2.0 发布',
          copyright: `Copyright © 2024-${new Date().getFullYear()} jkit`,
        },
      },
    },
    en: {
      label: 'English',
      lang: 'en-US',
      link: '/en/',
      description: 'A pure-JDK, zero-dependency Java utility library',
      themeConfig: {
        nav: enNav,
        sidebar: enSidebar,
        outline: { label: 'On this page', level: [2, 3] },
        editLink: {
          pattern: `${repoUrl}/edit/develop/docs/:path`,
          text: 'Edit this page on GitHub',
        },
        footer: {
          message: 'Released under the Apache License 2.0',
          copyright: `Copyright © 2024-${new Date().getFullYear()} jkit`,
        },
      },
    },
  },
})
