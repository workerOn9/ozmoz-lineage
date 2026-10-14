import { EXAMPLES } from '../lib/examples'
import { nav } from '../lib/route'
import { DOCS_URL } from '../lib/links'

const GITHUB_URL = 'https://github.com/workerOn9/ozmoz-lineage'

const FEATURES = [
  {
    title: '列级血缘图',
    desc: '列与列之间的来龙去脉：值血缘 / 过滤 / 连接键 / 分组 / 排序六类边，支持上溯、下溯与短路径高亮。',
  },
  {
    title: '双引擎 AST 对比',
    desc: '同一段 SQL 交给两个引擎解析，归一化树并排 diff，分歧一眼可见。',
  },
  {
    title: '语义校验',
    desc: '结合 CREATE TABLE 元数据，未知表、未知列、列歧义即刻报进诊断面板。',
  },
  {
    title: '工具百科',
    desc: '引擎能力、兼容性矩阵、方言转换矩阵，全部由 CI 现跑生成，每格可溯源到 commit。',
  },
]

/** 演示落地页：品牌 hero + 能力卡片 + 示例画廊，深链进工作台自动跑。 */
export function Landing() {
  return (
    <div className="min-h-screen bg-white text-neutral-800">
      <header className="mx-auto flex max-w-5xl items-center gap-2 px-6 py-4">
        <img src="/favicon.svg" alt="" className="h-6 w-6" />
        <span className="text-[15px] font-semibold">ozmoz-lineage</span>
        <nav className="ml-auto flex items-center gap-4 text-[13px]">
          <a className="text-neutral-500 hover:text-neutral-800" href={GITHUB_URL} target="_blank" rel="noreferrer">
            GitHub ↗
          </a>
          <a className="text-neutral-500 hover:text-neutral-800" href={DOCS_URL} target="_blank" rel="noreferrer">
            文档 ↗
          </a>
          <button
            className="rounded-lg bg-brand px-3 py-1.5 text-white hover:bg-brand-dark"
            onClick={() => nav('/app')}
          >
            打开工作台 →
          </button>
        </nav>
      </header>

      <main className="mx-auto max-w-5xl px-6">
        <section className="py-16 text-center">
          <img src="/favicon.svg" alt="" className="mx-auto h-24 w-24" />
          <h1 className="mt-6 text-4xl font-bold tracking-tight">把 SQL 的血缘，啄开给你看</h1>
          <p className="mx-auto mt-4 max-w-2xl leading-7 text-neutral-500">
            面向 JVM 生态的离线 SQL 解析 / 列级血缘 / 方言对照工具链。粘贴一段 SQL，
            立刻看到列与列之间的来龙去脉——纯字面解析，不连数据库。
          </p>
          <div className="mt-8 flex items-center justify-center gap-3">
            <button
              className="rounded-lg bg-brand px-5 py-2.5 text-[15px] text-white hover:bg-brand-dark"
              onClick={() => nav('/app')}
            >
              打开工作台 →
            </button>
            <a
              className="rounded-lg border border-neutral-300 px-5 py-2.5 text-[15px] text-neutral-600 hover:bg-neutral-50"
              href={DOCS_URL}
              target="_blank"
              rel="noreferrer"
            >
              查看文档
            </a>
          </div>
        </section>

        <section className="grid gap-4 py-8 sm:grid-cols-2">
          {FEATURES.map((f) => (
            <div key={f.title} className="rounded-xl border border-neutral-200 bg-white p-5 shadow-sm">
              <div className="mb-2 h-1 w-8 rounded bg-brand" />
              <h2 className="text-[15px] font-semibold">{f.title}</h2>
              <p className="mt-1.5 text-[13px] leading-6 text-neutral-500">{f.desc}</p>
            </div>
          ))}
        </section>

        <section className="py-8">
          <h2 className="text-xl font-bold">挑个例子，一键演示</h2>
          <p className="mt-1 text-[13px] text-neutral-500">
            点卡片直接进工作台并自动运行；也可以在工作台里慢慢调 SQL。
          </p>
          <div className="mt-5 grid gap-4 sm:grid-cols-2">
            {EXAMPLES.map((ex) => (
              <button
                key={ex.id}
                className="rounded-xl border border-neutral-200 bg-white p-5 text-left shadow-sm hover:border-brand/50 hover:shadow"
                onClick={() => nav(`/app?example=${ex.id}`)}
              >
                <div className="flex items-center justify-between">
                  <h3 className="text-[15px] font-semibold">{ex.title}</h3>
                  <span className="text-[13px] text-brand">运行 →</span>
                </div>
                <p className="mt-1.5 text-[13px] leading-6 text-neutral-500">{ex.blurb}</p>
              </button>
            ))}
          </div>
        </section>
      </main>

      <footer className="mx-auto max-w-5xl px-6 py-10 text-[12px] leading-6 text-neutral-500">
        <p>
          公网实例仅作演示：无鉴权，请勿粘贴真实业务 SQL；解析只读你输入的文本，不存任何数据。
        </p>
        <p className="mt-1">
          <a className="hover:text-neutral-600" href={GITHUB_URL} target="_blank" rel="noreferrer">
            GitHub
          </a>
          <span className="mx-2">·</span>
          <a className="hover:text-neutral-600" href={DOCS_URL} target="_blank" rel="noreferrer">
            工具百科
          </a>
          <span className="mx-2">·</span>
          <button className="hover:text-neutral-600" onClick={() => nav('/app')}>
            工作台
          </button>
        </p>
      </footer>
    </div>
  )
}
