import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// web 设计稿 §4.4：dev 时 /api 代理到本地 Ktor（ozml serve）。
// 目标地址可用 VITE_API_TARGET 覆盖（如 orbstack 容器映射的端口）。
const apiTarget = process.env.VITE_API_TARGET ?? 'http://localhost:8765'

// monaco-editor 0.57 的 package exports 不允许 `esm/vs/...` 深层子路径；
// 精确别名到 worker 文件实体，?url / ?worker 后缀照常生效。
const monacoEditorWorker = fileURLToPath(
  new URL('./node_modules/monaco-editor/esm/vs/editor/editor.worker.js', import.meta.url),
)

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: [
      {
        find: /^monaco-editor\/editor\.worker\.js(\?.*)?$/,
        replacement: monacoEditorWorker,
      },
    ],
  },
  server: {
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
      },
    },
  },
  build: {
    // Monaco 全量进异步 chunk，主预算保持默认；给整体 warning 阈值放宽到 8MB（离线工具，体积不是首要约束）。
    chunkSizeWarningLimit: 8192,
  },
  test: {
    include: ['test/**/*.test.ts'],
  },
})
