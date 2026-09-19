# Versioned Docs

<DocBadges />

This documentation site always tracks the **current release** (see the version switcher next to the title). The Maven Central badge is the published latest. For the history of changes, see the [Changelog](/en/changelog).

## Releases

| Version | Date | Docs |
| --- | --- | --- |
| 2.0.2 | 2026-09-14 | [This site](/en/) |
| 2.0.1 | 2026-09-13 | [v2.0.1 docs](/v2.0.1/en/) |
| 2.0.0 | — | [Source archive on GitHub](https://github.com/zhengmingliang/jkit/releases) (no standalone docs site yet) |

## How versioning works (no copied trees)

VitePress has **no** built-in Docusaurus-style versioning. One markdown tree builds one site. Copying `docs/` into `docs-2.0.1/` would mean every nav/theme/typo change has to be repeated N times.

jkit keeps **one copy of the docs in git history**: tags are the archive.

1. Each release is tagged `vX.Y.Z`. The `docs/` tree at that tag is frozen with the code — nothing is duplicated on the current branch.
2. CI runs `npm run docs:build:all`:
   - current branch → site root `/`
   - each archived version with `path: '/vX.Y.Z/'` → `git worktree` of that tag, `vitepress build --base /vX.Y.Z/`, copied into `dist/vX.Y.Z/`
3. The title version menu uses a full-page navigation (`/v2.0.1/` is a separate static site and must not go through this site's Vue Router). Switching from `/sql` to 2.0.1 opens `/v2.0.1/sql` when that page exists; the archive banner back to latest keeps the same page too.
4. Archived sites are frozen at that git tag, so their own version menu does not list newer releases. Use the orange top banner to jump back to latest (or another archive); it keeps the current page when possible.

Current version only, locally: `npm run docs:dev`.

Preview current + archives:

```bash
npm run docs:build:all
npm run docs:preview          # recommended: VitePress handles clean URLs
# or: npm run docs:serve      # npx serve dist (serve.json maps /sql → sql.html, no directory listing)
```

Do not run `npx serve .` from the repo root. VitePress emits `sql.html` while links are `/sql`. Without rewrites, `/v2.0.1/json` hits the root `404.html` (the latest site’s 404) and `/v2.0.1/` may show a directory index. The build also writes `json/index.html` plus `serve.json` so GitHub Pages and `npm run docs:serve` resolve clean URLs.

To archive another version:

1. Tag the previous release (`v2.0.2`).
2. In [versions.ts](https://github.com/zhengmingliang/jkit/blob/develop/docs/.vitepress/data/versions.ts) mark the new version `current` and give the previous one `path: '/v2.0.2/'` plus `tag`.
3. Update the table on this page. Do not copy markdown.

## Versioning rules

- The version number lives in the `<version>` of the root `pom.xml` (`jkit-parent`); all submodules inherit it.
- User-visible changes of every release are recorded in the [Changelog](/en/changelog), grouped by Added / Changed / Fixed / Build.
