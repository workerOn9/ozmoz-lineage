<script setup lang="ts">
// 兼容性矩阵（解析 / 血缘语义模型）：`ozml matrix` 的实测快照，逐格可溯源到 commit。
// 数据来自 `.data/dataset.json`（由 site/scripts/normalize.mjs 从 CI 产物规整而来）。
import { computed, ref } from 'vue'
import { withBase } from 'vitepress'
import dataset from '../../../.data/dataset.json'

const matrix = dataset.matrix
const MAX_ROWS = 200

const dialect = ref('全部')
const engine = ref('全部')
const status = ref('全部')

const filtered = computed(() => {
  if (!matrix.available) return []
  return (matrix.cells as any[]).filter(
    (c) =>
      (dialect.value === '全部' || c.dialect === dialect.value) &&
      (engine.value === '全部' || c.engine === engine.value) &&
      (status.value === '全部' || (status.value === '失败') === !c.ok),
  )
})

const shown = computed(() => filtered.value.slice(0, MAX_ROWS))
</script>

<template>
  <DataMissing v-if="!matrix.available" what="兼容性矩阵" cmd="just docs-data" />
  <div v-else>
    <p class="lead">
      语料 <strong>{{ matrix.caseCount }}</strong> 条 × 引擎 <strong>{{ matrix.engineCount }}</strong> 个
      = <strong>{{ matrix.cells.length }}</strong> 格，其中
      <strong>{{ matrix.cells.length - matrix.failures.length }}</strong> ok、
      <strong class="fail-count">{{ matrix.failures.length }}</strong> 失败。
      失败格是<strong>产物</strong>（不是 bug 列表）：矩阵问的就是「哪个引擎吃得下哪条 SQL」。
    </p>
    <p class="meta">
      commit <code :title="matrix.commit ?? ''">{{ (matrix.commit ?? '(本地)').slice(0, 7) }}</code>
      <span v-if="matrix.generatedAt">· 生成于 {{ matrix.generatedAt }}</span>
      · <a :href="withBase(matrix.rawUrl)">全量 JSON（可溯源）</a>
    </p>

    <h2 id="coverage">方言 × 引擎覆盖率</h2>
    <table class="grid">
      <thead>
        <tr>
          <th>方言</th>
          <th v-for="e in matrix.engines" :key="e">{{ e }}</th>
          <th>合计</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in matrix.coverage" :key="row.dialect">
          <td><code>{{ row.dialect }}</code></td>
          <td v-for="cell in row.engines" :key="cell.engine" :class="{ bad: cell.ok < cell.total }">
            {{ cell.ok }}/{{ cell.total }}
          </td>
          <td>{{ row.ok }}/{{ row.total }}</td>
        </tr>
      </tbody>
    </table>

    <h2 id="cells">逐格浏览</h2>
    <div class="filters">
      <label>方言
        <select v-model="dialect">
          <option>全部</option>
          <option v-for="row in matrix.coverage" :key="row.dialect">{{ row.dialect }}</option>
        </select>
      </label>
      <label>引擎
        <select v-model="engine">
          <option>全部</option>
          <option v-for="e in matrix.engines" :key="e">{{ e }}</option>
        </select>
      </label>
      <label>状态
        <select v-model="status">
          <option>全部</option>
          <option>失败</option>
          <option>ok</option>
        </select>
      </label>
      <span class="count">命中 {{ filtered.length }} 格</span>
    </div>

    <table class="grid">
      <thead>
        <tr><th>case</th><th>方言</th><th>引擎</th><th>语句类型</th><th>特性</th><th>结果</th><th>原因</th></tr>
      </thead>
      <tbody>
        <tr v-for="(c, i) in shown" :key="i" :class="{ bad: !c.ok }">
          <td><code>{{ c.caseId }}</code></td>
          <td>{{ c.dialect }}</td>
          <td>{{ c.engine }}</td>
          <td>{{ c.kind }}</td>
          <td class="features">{{ c.features.join(', ') }}</td>
          <td>{{ c.ok ? 'ok' : '失败' }}</td>
          <td class="reason">{{ c.ok ? '' : c.reason }}</td>
        </tr>
      </tbody>
    </table>
    <p v-if="filtered.length > shown.length" class="note">
      仅显示前 {{ shown.length }} 格；其余 {{ filtered.length - shown.length }} 格见
      <a :href="withBase(matrix.rawUrl)">全量 JSON</a>。
    </p>
  </div>
</template>

<style scoped>
.lead {
  margin-bottom: 4px;
}
.fail-count {
  color: var(--vp-c-danger-1);
}
.meta {
  color: var(--vp-c-text-2);
  font-size: 13px;
  margin-bottom: 16px;
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
.features {
  color: var(--vp-c-text-2);
}
.filters {
  display: flex;
  gap: 16px;
  align-items: center;
  flex-wrap: wrap;
  margin: 8px 0;
}
.filters select {
  margin-left: 4px;
  padding: 2px 6px;
  border: 1px solid var(--vp-c-divider);
  border-radius: 6px;
  background: var(--vp-c-bg);
  color: var(--vp-c-text-1);
}
.count {
  color: var(--vp-c-text-2);
  font-size: 13px;
}
.note {
  color: var(--vp-c-text-2);
  font-size: 13px;
}
</style>
