# 多版本说明

本文档站点始终对应**当前发布版本**（见导航栏右侧的版本下拉框）。版本历史与变更内容见 [更新日志](/changelog)。

## 版本记录

| 版本 | 发布日期 | 文档 |
| --- | --- | --- |
| 2.0.1 | 2026-09-09 | 当前站点 |
| 2.0.0 | — | [GitHub 源码归档](https://github.com/zhengmingliang/jkit/releases) |

## 版本切换是如何工作的

VitePress 本身按「一个站点对应一份文档」工作，多版本通过**部署多个静态站点**实现：

1. 当前版本（`develop` 分支）的文档始终部署在站点根路径 `/`。
2. 需要保留某个历史版本的文档时，在该版本的 tag 上执行 `npm run docs:build`，把产物部署到 `/v<版本号>/` 子路径（例如 `/v2.0.0/`）。
3. 导航栏的版本下拉框由 [versions.ts](https://github.com/zhengmingliang/jkit/blob/develop/docs/.vitepress/data/versions.ts) 驱动，新增归档版本时在其中追加一行，并给该版本补上指向 `/v<版本号>/` 的链接即可。

## 版本号规则

- 版本号记录在根 `pom.xml`（`jkit-parent`）的 `<version>` 中，各子模块继承同一版本号。
- 每次发布的用户可见变更按「新增 / 变更 / 修复 / 构建」分类记录在 [更新日志](/changelog) 中。
