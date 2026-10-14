import { useSyncExternalStore } from 'react'

/**
 * 极简客户端路由：落地页 `/` 与工作台 `/app` 两个路由，外加 `?example=` 深链参数。
 * 有意不引 react-router——全站只有两个路由，订阅 popstate + pushState 就够。
 */

const listeners = new Set<() => void>()

function emit() {
  listeners.forEach((l) => l())
}

if (typeof window !== 'undefined') {
  window.addEventListener('popstate', emit)
}

/** 客户端跳转：pushState 并通知订阅者。 */
export function nav(to: string): void {
  window.history.pushState(null, '', to)
  emit()
}

export function usePath(): string {
  return useSyncExternalStore(
    (onStoreChange) => {
      listeners.add(onStoreChange)
      return () => listeners.delete(onStoreChange)
    },
    () => window.location.pathname,
  )
}
