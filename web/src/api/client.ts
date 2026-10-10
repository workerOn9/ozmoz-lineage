import type { ErrorResponse } from '../types'

/**
 * 唯一的网络出口（web 设计稿 §3 约定：组件里禁止直接 fetch）。
 * 同源 `/api`：dev 走 vite proxy，生产由 `ozml serve` 同端口托管。
 */

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: ErrorResponse | null,
  ) {
    super(body?.reason ?? `HTTP ${status}`)
    this.name = 'ApiError'
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response
  try {
    res = await fetch(path, init)
  } catch (cause) {
    throw new ApiError(0, { error: 'backend_unreachable', reason: '无法连接本地后端（ozml serve）' })
  }
  if (!res.ok) {
    let body: ErrorResponse | null = null
    try {
      body = (await res.json()) as ErrorResponse
    } catch {
      // 非 JSON 错误体（不应发生）：保留 null，走通用文案。
    }
    throw new ApiError(res.status, body)
  }
  return (await res.json()) as T
}

export function getJson<T>(path: string): Promise<T> {
  return request<T>(path)
}

export function postJson<T>(path: string, body: unknown): Promise<T> {
  return request<T>(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}
