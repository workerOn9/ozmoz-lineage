<script setup lang="ts">
// 数据未生成时的降级占位（本地 dev 常见；CI 里数据一定在）。
defineProps<{ what: string; cmd: string }>()
</script>

<template>
  <div class="data-missing">
    <p><strong>{{ what }}暂无数据。</strong></p>
    <p>矩阵数据由 <code>ozml</code> 现生成、<strong>不入库</strong>（避免腐化）。本地先跑：</p>
    <pre><code>{{ cmd }}</code></pre>
    <p class="hint">CI 上由 <code>docs</code> workflow 生成后再构建，所以线上永远是最新快照。</p>
  </div>
</template>

<style scoped>
.data-missing {
  border: 1px solid var(--vp-c-divider);
  border-left: 4px solid var(--vp-c-warning-1);
  border-radius: 8px;
  padding: 12px 16px;
  background: var(--vp-c-bg-soft);
}
.data-missing pre {
  margin: 8px 0;
  padding: 8px 12px;
  background: var(--vp-c-bg);
  border-radius: 6px;
  overflow-x: auto;
}
.hint {
  color: var(--vp-c-text-2);
  font-size: 13px;
}
</style>
