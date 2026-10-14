import { useAppStore } from '../state/store'

/**
 * Schema（内联 DDL）输入面板（web 设计稿 §4.1 `/api/validate` / `/api/lineage` 的 `schema` 字段）。
 * 折叠在 SQL 输入栏下方；缺省带一份与示例 SQL 对齐的 DDL。
 *
 * 用原生 `textarea` 而非 Monaco：DDL 量小、无需高亮，避免再挂一个 Monaco 实例
 * （web 设计稿 §9 R1 的按需加载原则）。空内容 = 未提供，后端改从 SQL 文本收 `CREATE TABLE`。
 */

export function SchemaPanel() {
  const schema = useAppStore((s) => s.schema)
  const setSchema = useAppStore((s) => s.setSchema)

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-2 border-b border-neutral-200 px-3 py-1">
        <span className="text-[11px] text-neutral-400">
          CREATE TABLE 文本（可选）；留空则由后端从 SQL 文本自动收集
        </span>
        <button
          className="ml-auto shrink-0 rounded border border-neutral-300 px-1.5 py-0.5 text-[11px] text-neutral-500 hover:bg-neutral-200"
          onClick={() => setSchema('')}
          disabled={schema === ''}
        >
          清空
        </button>
      </div>
      <textarea
        className="min-h-0 flex-1 resize-none bg-white px-3 py-2 font-mono text-[12px] leading-5 text-neutral-200 outline-none placeholder:text-neutral-400"
        placeholder={'CREATE TABLE orders (\n  order_id INT,\n  amount DECIMAL(10,2)\n);'}
        value={schema}
        spellCheck={false}
        onChange={(e) => setSchema(e.target.value)}
      />
    </div>
  )
}
