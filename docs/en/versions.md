# Versioned Docs

This documentation site always tracks the **current release** (see the version dropdown in the navigation bar). For the history of changes, see the [Changelog](/en/changelog).

## Releases

| Version | Date | Docs |
| --- | --- | --- |
| 2.0.1 | 2026-09-09 | This site |
| 2.0.0 | — | [Source archive on GitHub](https://github.com/zhengmingliang/jkit/releases) |

## How versioning works

VitePress builds one site from one set of sources, so multiple versions are served by **deploying multiple static sites**:

1. The docs for the current version (the `develop` branch) are always deployed at the site root `/`.
2. To keep docs for an older release, run `npm run docs:build` on that release tag and deploy the output under a `/v<version>/` subpath (for example `/v2.0.0/`).
3. The version dropdown in the navigation bar is driven by [versions.ts](https://github.com/zhengmingliang/jkit/blob/develop/docs/.vitepress/data/versions.ts). When you archive a new version, append one entry there with a link to its `/v<version>/` deployment.

## Versioning rules

- The version number lives in the `<version>` of the root `pom.xml` (`jkit-parent`); all submodules inherit it.
- User-visible changes of every release are recorded in the [Changelog](/en/changelog), grouped by Added / Changed / Fixed / Build.
