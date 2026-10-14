import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { Landing } from './landing/Landing'
import { usePath } from './lib/route'
import './styles/index.css'

/** 两个路由：`/` 落地页，`/app` 工作台（SPA 兜底已把非 /api 路径指回 index.html）。 */
function Root() {
  const path = usePath()
  return path === '/app' ? <App /> : <Landing />
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Root />
  </StrictMode>,
)
