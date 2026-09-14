export interface DocVersion {
  /** 版本号，如 2.0.1 */
  version: string
  /** 发布日期，未知留空字符串 */
  date: string
  /** 是否为当前站点对应的版本 */
  current?: boolean
  /**
   * 该版本文档的站点路径。当前版本为 `/`；
   * 有独立文档的历史版本为 `/vX.Y.Z/`（CI 从 git tag 构建后嵌进来）。
   * 没有独立文档站时省略，下拉里链到 GitHub Releases。
   */
  path?: string
  /** 构建历史文档用的 git tag，默认 `v` + version */
  tag?: string
}

/** 当前站点对应的发布版本（与根 pom.xml 一致） */
export const currentVersion = '2.0.2'

/**
 * 已发布版本列表，新版本追加到最上方。
 * 历史版本文档站点的部署方式见 docs/versions.md。
 */
export const versions: DocVersion[] = [
  { version: '2.0.2', date: '2026-09-14', current: true, path: '/' },
  { version: '2.0.1', date: '2026-09-13', path: '/v2.0.1/', tag: 'v2.0.1' },
  { version: '2.0.0', date: '' },
]

export const repoUrl = 'https://github.com/zhengmingliang/jkit'
export const releasesUrl = `${repoUrl}/releases`

/** 需要从 git tag 再构建并嵌进 `/vX.Y.Z/` 的历史版本 */
export function archivedDocVersions(): DocVersion[] {
  return versions.filter((v) => !v.current && !!v.path)
}
