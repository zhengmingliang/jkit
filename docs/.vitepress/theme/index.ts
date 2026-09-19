import { h } from 'vue'
import DefaultTheme from 'vitepress/theme'
import VersionSwitcher from './components/VersionSwitcher.vue'
import MavenBadge from './components/MavenBadge.vue'
import DocBadges from './components/DocBadges.vue'
import './custom.css'

export default {
  extends: DefaultTheme,
  Layout() {
    return h(DefaultTheme.Layout, null, {
      // title-after 在 Logo 的 <a href="/"> 内部，点击会冒泡回首页
      'nav-bar-content-before': () => h(VersionSwitcher),
    })
  },
  enhanceApp({ app }) {
    app.component('MavenBadge', MavenBadge)
    app.component('DocBadges', DocBadges)
  },
}
