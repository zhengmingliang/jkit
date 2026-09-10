export interface DocVersion {
  /** 版本号，如 2.0.1 */
  version: string
  /** 发布日期，未知留空字符串 */
  date: string
  /** 是否为当前站点对应的版本 */
  current?: boolean
}

/** 当前站点对应的发布版本（与根 pom.xml 一致） */
export const currentVersion = '2.0.1'

/**
 * 已发布版本列表，新版本追加到最上方。
 * 历史版本文档站点的部署方式见 docs/versions.md。
 */
export const versions: DocVersion[] = [
  { version: '2.0.1', date: '2026-09-09', current: true },
  { version: '2.0.0', date: '' },
]

export const repoUrl = 'https://github.com/zhengmingliang/jkit'
export const releasesUrl = `${repoUrl}/releases`
