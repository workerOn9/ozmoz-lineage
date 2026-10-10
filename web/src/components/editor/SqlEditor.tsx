import { useEffect, useRef } from 'react'
import * as monaco from 'monaco-editor'
import { editorHolder } from '../../editor/editorHolder'


// 离线原则：Monaco 走本地打包，只挂 editor worker（SQL 高亮无需语言服务 worker）。
// worker 用官方 new URL(import.meta.url) 模式（vite 打包成 worker chunk）；
// monaco-editor 0.57 的 package exports 拦深层子路径，vite.config.ts 的精确别名接住它。
;(globalThis as { MonacoEnvironment?: monaco.Environment }).MonacoEnvironment = {
  getWorker: () =>
    new Worker(new URL("monaco-editor/editor.worker.js", import.meta.url), { type: "module" }),
}


interface SqlEditorProps {
  value: string
  onChange: (value: string) => void
}

export function SqlEditor({ value, onChange }: SqlEditorProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange

  useEffect(() => {
    const container = containerRef.current
    if (container == null) return
    const editor = monaco.editor.create(container, {
      value,
      language: 'sql',
      theme: 'vs-dark',
      automaticLayout: true,
      minimap: { enabled: false },
      fontSize: 13,
      scrollBeyondLastLine: false,
      padding: { top: 8 },
      fixedOverflowWidgets: true,
      // Monaco 0.57 默认启用 Chromium EditContext 输入通道，实测真实键盘输入会丢空格
      // （keydown → EditContext textupdate 在部分 Chrome 版本对 Space 不生效；
      // CDP 注入 text 绕过该通道所以测不出来）。关掉回落到经典 textarea 输入管线，
      // 这条路对 IME（中文输入法）与空格最稳。
      editContext: false,
    })
    editorHolder.current = editor
    const sub = editor.onDidChangeModelContent(() => onChangeRef.current(editor.getValue()))
    return () => {
      sub.dispose()
      if (editorHolder.current === editor) editorHolder.current = null
      editor.dispose()
    }
    // 只在挂载时创建一次；外部值同步走下面的 effect。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 外部赋值（示例 SQL / 其他面板写回）时同步进编辑器，不打断正在输入的用户。
  useEffect(() => {
    const editor = editorHolder.current
    if (editor != null && editor.getValue() !== value) {
      editor.setValue(value)
    }
  }, [value])

  return <div ref={containerRef} className="h-full w-full" />
}
