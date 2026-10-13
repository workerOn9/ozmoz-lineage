import type { Theme } from 'vitepress'
import DefaultTheme from 'vitepress/theme'
import CompatMatrix from './components/CompatMatrix.vue'
import ConvertMatrix from './components/ConvertMatrix.vue'
import DataMissing from './components/DataMissing.vue'
import EngineCatalog from './components/EngineCatalog.vue'

// 工具百科的表格全部由组件从 `.data/dataset.json` 渲染（无手写表格）。
export default {
  extends: DefaultTheme,
  enhanceApp({ app }) {
    app.component('EngineCatalog', EngineCatalog)
    app.component('CompatMatrix', CompatMatrix)
    app.component('ConvertMatrix', ConvertMatrix)
    app.component('DataMissing', DataMissing)
  },
} satisfies Theme
