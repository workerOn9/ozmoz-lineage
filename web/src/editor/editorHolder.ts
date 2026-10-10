import type * as monaco from 'monaco-editor'
import type { Span } from '../types'

/**
 * Monaco 编辑器实例的模块级持有（非序列化、不进 React 状态）。
 * span 坐标约定与 Monaco 一致：line/column 1 起始、offset 为 UTF-16 码元。
 */

export const editorHolder: { current: monaco.editor.IStandaloneCodeEditor | null } = {
  current: null,
}

/** 把诊断 / AST 节点 / 边的 span 定位到编辑器并选中。 */
export function revealSpan(span: Span): void {
  const editor = editorHolder.current
  if (editor == null) return
  editor.setSelection({
    startLineNumber: span.start.line,
    startColumn: span.start.column,
    endLineNumber: span.end.line,
    endColumn: span.end.column,
  })
  editor.revealRangeInCenterIfOutsideViewport(
    {
      startLineNumber: span.start.line,
      startColumn: span.start.column,
      endLineNumber: span.end.line,
      endColumn: span.end.column,
    },
    0,
  )
  editor.focus()
}
