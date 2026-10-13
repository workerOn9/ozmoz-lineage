<script setup lang="ts">
// 方言转换矩阵：`ozml matrix --convert` 的实测快照（方言对 × 语料 × 渲染引擎）。
// 每方言对给覆盖率 + 一条**成品 SQL 样本**（渲染门禁通过才算 ok）；失败格带原因。
import { computed, ref } from 'vue'
import { withBase } from 'vitepress'
import dataset from '../../../.data/dataset.json'

const convert = dataset.convert
const MAX_FAILURES = 100

const from = ref('全部')
const onlyFailed = ref(false)

const pairs = computed(() => {
  if (!convert.available) return []
  return (convert.pairs as any[]).filter((p) => from.value === '全部' || p.from === from.value)
})

const failures = computed(() => {
  if (!convert.available) return []
  return (convert.failures as any[]).filter((f) => from.value === '全部' || f.from === from.value)
})
const shownFailures = computed(() => failures.value.slice(0, MAX_FAILURES))
</script>

<template>
  <DataMissing v-if="!convert.available" what="方言转换矩阵" cmd="just docs-data" />
  <div v-else>
    <p class="lead">
      语料 <strong>{{ convert.caseCount }}</strong> 条 · 引擎 <strong>{{ convert.engineCount }}</strong> 个 ·
      方言对 <strong>{{ convert.pairs.length }}</strong> 组（声明 {{ convert.declaredPairCount }} 组，
      其余 from 方言在语料里没有 case，故不出格）：
      <strong>{{ convert.pairs.reduce((s: number, p: any) => s + p.ok, 0) }}</strong> /
      <strong>{{ convert.pairs.reduce((s: number, p: any) => s + p.total, 0) }}</strong> ok。
      ok 格都过了 <code>parse → render → re-parse</code> 等价门禁。
    </p>
    <p class="meta">
      commit <code :title="convert.commit ?? ''">{{ (convert.commit ?? '(本地)').slice(0, 7) }}</code>
      <span v-if="convert.generatedAt">· 生成于 {{ convert.generatedAt }}</span>
      · <a :href="withBase(convert.rawUrl)">全量 JSON（可溯源）</a>
    </p>

    <div class="filters">
      <label>from 方言
        <select v-model="from">
          <option>全部</option>
          <option v-for="p in convert.pairs" :key="p.from">{{ p.from }}</option>
        </select>
      </label>
      <label class="inline"><input v-model="onlyFailed" type="checkbox" /> 只看失败</label>
    </div>

    <template v-if="!onlyFailed">
      <h2 id="pairs">方言对覆盖率与成品样本</h2>
      <section v-for="p in pairs" :key="`${p.from}->${p.to}`" class="pair">
        <h3 :id="`${p.from}-to-${p.to}`">
          <code>{{ p.from }}</code> → <code>{{ p.to }}</code>
        </h3>
        <p class="pair-ok">{{ p.ok }}/{{ p.total }}</p>
        <table class="grid">
          <thead><tr><th>引擎</th><th>ok</th></tr></thead>
          <tbody>
            <tr v-for="e in p.engines" :key="e.engine">
              <td>{{ e.engine }}</td>
              <td :class="{ bad: e.total > 0 && e.ok < e.total }">{{ e.total === 0 ? '—' : `${e.ok}/${e.total}` }}</td>
            </tr>
          </tbody>
        </table>
        <details v-for="s in p.samples" :key="`${s.engine}-${s.caseId}`">
          <summary>成品样本 · {{ s.engine }} · <code>{{ s.caseId }}</code></summary>
          <pre><code>{{ s.sql }}</code></pre>
        </details>
      </section>
    </template>

    <h2 id="failures">失败格</h2>
    <p class="meta">命中 {{ failures.length }} 格（渲染门禁不等价时不输出，如实入格）。</p>
    <table class="grid">
      <thead><tr><th>from</th><th>to</th><th>引擎</th><th>case</th><th>原因</th></tr></thead>
      <tbody>
        <tr v-for="(f, i) in shownFailures" :key="i" class="bad">
          <td>{{ f.from }}</td>
          <td>{{ f.to }}</td>
          <td>{{ f.engine }}</td>
          <td><code>{{ f.caseId }}</code></td>
          <td class="reason">{{ f.reason }}</td>
        </tr>
      </tbody>
    </table>
    <p v-if="failures.length > shownFailures.length" class="note">
      仅显示前 {{ shownFailures.length }} 格；其余见 <a :href="withBase(convert.rawUrl)">全量 JSON</a>。
    </p>
  </div>
</template>

<style scoped>
.lead {
  margin-bottom: 4px;
}
.meta {
  color: var(--vp-c-text-2);
  font-size: 13px;
}
.filters {
  display: flex;
  gap: 16px;
  align-items: center;
  flex-wrap: wrap;
  margin: 8px 0 16px;
}
.filters select {
  margin-left: 4px;
  padding: 2px 6px;
  border: 1px solid var(--vp-c-divider);
  border-radius: 6px;
  background: var(--vp-c-bg);
  color: var(--vp-c-text-1);
}
.pair h3 {
  margin-bottom: 0;
}
.pair-ok {
  margin: 0 0 6px;
  color: var(--vp-c-text-2);
  font-size: 13px;
}
.grid {
  width: 100%;
  border-collapse: collapse;
  font-size: 13.5px;
  display: block;
  overflow-x: auto;
}
.grid th,
.grid td {
  border-bottom: 1px solid var(--vp-c-divider);
  padding: 4px 10px;
  text-align: left;
  white-space: nowrap;
}
.grid thead th {
  color: var(--vp-c-text-2);
  font-weight: 600;
}
.grid tr.bad td {
  background: var(--vp-c-danger-soft);
}
.grid td.reason {
  white-space: normal;
  color: var(--vp-c-text-2);
}
details {
  margin: 6px 0;
}
pre {
  overflow-x: auto;
  padding: 8px 12px;
  border-radius: 6px;
  background: var(--vp-c-bg-soft);
}
.note {
  color: var(--vp-c-text-2);
  font-size: 13px;
}
</style>
