// 引擎能力 / 方言目录 —— 数据来自 `ozml engines --format json`（与 /api/engines 同形）。
import dataset from '../.data/dataset.json'
import DataMissing from './DataMissing'

export default function EngineCatalog() {
  const engines = dataset.engines as {
    available: boolean
    items: Array<{ id: string; features: string[]; dialects: string[]; reasons: Record<string, string> }>
  }

  if (!engines.available) return <DataMissing what="引擎能力表" />

  return (
    <div>
      <p className="ozml-lead">
        注册引擎 <strong>{engines.items.length}</strong> 个。能力表是<strong>路由依据</strong>——
        「这个引擎能不能干这件事」看声明，不靠试错；不支持的能力一律带可解释原因（下表 <em>unsupported</em>）。
      </p>

      {engines.items.map((engine) => (
        <section key={engine.id}>
          <h3 id={engine.id}>{engine.id}</h3>
          <table>
            <tbody>
              <tr>
                <th>声明能力</th>
                <td>
                  {engine.features.length === 0 ? '—' : engine.features.map((f) => <code key={f}>{f}</code>)}
                </td>
              </tr>
              <tr>
                <th>方言</th>
                <td>
                  {engine.dialects.length === 0 ? '—' : engine.dialects.map((d) => <code key={d}>{d}</code>)}
                </td>
              </tr>
              {Object.keys(engine.reasons).length > 0 && (
                <tr>
                  <th>不支持</th>
                  <td>
                    <ul>
                      {Object.entries(engine.reasons).map(([feature, reason]) => (
                        <li key={feature}>
                          <code>{feature}</code> — {reason}
                        </li>
                      ))}
                    </ul>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </section>
      ))}

      <p className="ozml-note">
        数据源：<code>ozml engines --format json</code>（与 <code>GET /api/engines</code> 同一形状与同一构造器）。
      </p>
    </div>
  )
}
