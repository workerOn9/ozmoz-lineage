import type { Span } from '../types'

/**
 * 两个半开区间 [start, end) 是否重叠。任一为 null / undefined → false（位置未知不作判断）。
 *
 * 归一化树与血缘图**没有天然一一对应**（前者按语法结构，后者按列引用），联动靠的是
 * 同一份 SQL 上的源文本位置：树节点、血缘列 / 边都带 span，重叠即视为「同一处」。
 * 注意这是**重叠**不是包含——点一个小的列节点时，覆盖它的表达式边也会一起高亮。
 */
export function spansOverlap(a: Span | null | undefined, b: Span | null | undefined): boolean {
  if (a == null || b == null) return false
  return a.start.offset < b.end.offset && b.start.offset < a.end.offset
}
