import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { Analytics } from '@vercel/analytics/react'
import App from './App'
import { Landing } from './landing/Landing'
import { usePath } from './lib/route'
import './styles/index.css'

/** 两个路由：`/` 落地页，`/app` 工作台（SPA 兜底已把非 /api 路径指回 index.html）。 */
function Root() {
  const path = usePath()
  return (
    <>
      {/* 本项目是 Vite（不是 Next.js），所以用 @vercel/analytics/react 入口；dev 下只打日志，不上报。 */}
      <Analytics />
      {path === '/app' ? <App /> : <Landing />}
    </>
  )
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Root />
  </StrictMode>,
)
