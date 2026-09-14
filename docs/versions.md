# 多版本说明

<DocBadges />

本文档站点始终对应**当前发布版本**（见导航栏标题旁的版本切换）。Maven Central 上的最新坐标以徽章为准。版本历史与变更内容见 [更新日志](/changelog)。

## 版本记录

| 版本 | 发布日期 | 文档 |
| --- | --- | --- |
| 2.0.2 | 2026-09-14 | [当前站点](/) |
| 2.0.1 | 2026-09-13 | [v2.0.1 文档](/v2.0.1/) |
| 2.0.0 | — | [GitHub 源码归档](https://github.com/zhengmingliang/jkit/releases)（当时还没有独立文档站） |

## 多版本是怎么做的（不拷贝文档）

VitePress **没有** Docusaurus 那种内置 versioning。一份 markdown 只能构建成一个站点。如果在仓库里再拷一份 `docs-2.0.1/`，以后改导航、主题、错字都要改 N 份，很快会散。

jkit 的做法是：**git tag 就是历史文档的源**，构建时再编进来。

1. 发版打 `vX.Y.Z` tag。那时的 `docs/` 随代码冻结，不必再复制。
2. CI 跑 `npm run docs:build:all`：
   - 当前分支 → 站点根路径 `/`
   - 每个带 `path: '/vX.Y.Z/'` 的历史版本 → `git worktree` 检出对应 tag，`vitepress build --base /vX.Y.Z/`，产物嵌进 `dist/vX.Y.Z/`
3. 导航栏版本菜单用整页跳转（`/v2.0.1/` 是另一份静态站，不能走当前站的 Vue Router）。在 `/sql` 切到 2.0.1 会尽量打开 `/v2.0.1/sql`；历史页横幅回到最新版时同样保留当前篇。
4. 历史站点是旧 tag 打出来的，里面的版本菜单不会出现新版本。顶部橙色横幅才是切回最新版 / 其它归档的入口（会整页跳转并尽量留在同一篇）。

本地只看当前版：`npm run docs:dev`。

预览「当前 + 历史」：

```bash
npm run docs:build:all
npm run docs:preview          # 推荐：VitePress 自带干净 URL
# 或：npm run docs:serve      # npx serve dist（已写 serve.json：/sql → sql.html，且不列目录）
```

不要对仓库根目录 `npx serve .`。VitePress 产出的是 `sql.html`，页面链接却是 `/sql`；没有 rewrite 时，`/v2.0.1/json` 会落到根目录的 `404.html`（看起来像最新版 404），`/v2.0.1/` 则可能被当成目录列表。打包后会再生成 `json/index.html` 并写入 `serve.json`，GitHub Pages / `npm run docs:serve` 都能打开干净 URL。

新增归档版本时：

1. 给旧版打好 tag（如 `v2.0.2`）。
2. 在 [versions.ts](https://github.com/zhengmingliang/jkit/blob/develop/docs/.vitepress/data/versions.ts) 把新版本标为 `current`，给上一版补 `path: '/v2.0.2/'` 和 `tag`。
3. 更新本页表格。不必复制 markdown。

## 版本号规则

- 版本号记录在根 `pom.xml`（`jkit-parent`）的 `<version>` 中，各子模块继承同一版本号。
- 每次发布的用户可见变更按「新增 / 变更 / 修复 / 构建」分类记录在 [更新日志](/changelog) 中。
