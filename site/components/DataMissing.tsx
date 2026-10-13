/** 数据未生成时的降级占位（本地 dev 常见；CI 里数据一定在）。 */
export default function DataMissing({ what, cmd = 'just docs-data' }: { what: string; cmd?: string }) {
  return (
    <div className="ozml-missing">
      <p>
        <strong>{what}暂无数据。</strong>
      </p>
      <p>
        矩阵数据由 <code>ozml</code> 现生成、<strong>不入库</strong>（避免腐化）。本地先跑：
      </p>
      <pre>
        <code>{cmd}</code>
      </pre>
      <p className="ozml-note">
        CI 上由 <code>docs</code> workflow 生成后再构建，所以线上永远是最新快照。
      </p>
    </div>
  )
}
