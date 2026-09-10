import { defineConfig, type DefaultTheme } from 'vitepress'
import { currentVersion, repoUrl, releasesUrl } from './data/versions'

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
      { text: '消息通知', link: '/notify' },
    ],
  },
  { text: '更新日志', link: '/changelog' },
  {
    text: `v${currentVersion}`,
    items: [
      { text: `${currentVersion}（当前版本）`, link: '/changelog' },
      { text: '多版本说明', link: '/versions' },
      { text: 'GitHub Releases', link: releasesUrl },
    ],
  },
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
    ],
  },
  {
    text: '网络与数据',
    collapsed: false,
    items: [
      { text: 'HTTP 客户端', link: '/http' },
      { text: 'SQL 解析', link: '/sql' },
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
      { text: 'Notification', link: '/en/notify' },
    ],
  },
  { text: 'Changelog', link: '/en/changelog' },
  {
    text: `v${currentVersion}`,
    items: [
      { text: `${currentVersion} (current)`, link: '/en/changelog' },
      { text: 'Versioned docs', link: '/en/versions' },
      { text: 'GitHub Releases', link: releasesUrl },
    ],
  },
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
    ],
  },
  {
    text: 'Network & Data',
    collapsed: false,
    items: [
      { text: 'HTTP Client', link: '/en/http' },
      { text: 'SQL Parsing', link: '/en/sql' },
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

export default defineConfig({
  title: 'jkit',
  description: '纯 JDK、零第三方依赖的 Java 通用工具库',
  cleanUrls: true,
  lastUpdated: true,
  // next-plan 是内部开发计划；csv/expression 尚未成文，先不构建进站点
  srcExclude: ['**/next-plan.md', '**/csv.md', '**/expression.md'],

  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/favicon.svg' }],
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
