/**
 * 对外链接常量。
 *
 * `DOCS_URL` 指向**工具百科**（M5 文档站）：由 `docs` workflow 现跑 `ozml` 生成矩阵数据后
 * 用 Rspress 构建、发布到 GitHub Pages 的**项目站点**。
 *
 * 与 `site/rspress.config.ts` 的 `base` 有耦合：站点换部署位置（例如将来挂到应用同域
 * `/docs`）时必须同步改这里——`web/test/links.test.ts` 会读站点配置做漂移检查。
 */
export const DOCS_URL = 'https://workeron9.github.io/ozmoz-lineage/'
