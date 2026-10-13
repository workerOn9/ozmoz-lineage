<script setup lang="ts">
// 引擎能力 / 方言目录 —— 数据来自 `ozml engines --format json`（与 /api/engines 同形）。
import dataset from '../../../.data/dataset.json'

const engines = dataset.engines
</script>

<template>
  <DataMissing v-if="!engines.available" what="引擎能力表" cmd="just docs-data" />
  <div v-else>
    <p class="lead">
      注册引擎 <strong>{{ engines.items.length }}</strong> 个。能力表是
      <strong>路由依据</strong>——「这个引擎能不能干这件事」看声明，不靠试错；
      不支持的能力一律带可解释原因（下表 <em>unsupported</em>）。
    </p>

    <section v-for="engine in engines.items" :key="engine.id" class="engine">
      <h3 :id="engine.id">{{ engine.id }}</h3>
      <table>
        <tbody>
          <tr>
            <th>声明能力</th>
            <td>
              <code v-for="f in engine.features" :key="f" class="tag">{{ f }}</code>
              <span v-if="engine.features.length === 0">—</span>
            </td>
          </tr>
          <tr>
            <th>方言</th>
            <td>
              <code v-for="d in engine.dialects" :key="d" class="tag tag-dialect">{{ d }}</code>
              <span v-if="engine.dialects.length === 0">—</span>
            </td>
          </tr>
          <tr v-if="Object.keys(engine.reasons).length > 0">
            <th>不支持</th>
            <td>
              <ul class="reasons">
                <li v-for="(reason, feature) in engine.reasons" :key="feature">
                  <code>{{ feature }}</code> — {{ reason }}
                </li>
              </ul>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <p class="note">
      数据源：<code>ozml engines --format json</code>（与 <code>GET /api/engines</code> 同一形状与同一构造器）。
    </p>
  </div>
</template>

<style scoped>
.lead {
  margin-bottom: 16px;
}
.engine table {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 8px;
}
.engine th {
  text-align: left;
  vertical-align: top;
  white-space: nowrap;
  width: 6.5em;
  padding: 6px 12px 6px 0;
  color: var(--vp-c-text-2);
  font-weight: 600;
}
.engine td {
  padding: 6px 0;
  border-bottom: 1px solid var(--vp-c-divider);
}
.tag {
  display: inline-block;
  margin: 0 6px 6px 0;
  padding: 1px 7px;
  border-radius: 5px;
  background: var(--vp-c-bg-soft);
  font-size: 12.5px;
}
.tag-dialect {
  background: var(--vp-c-brand-soft);
}
.reasons {
  margin: 0;
  padding-left: 18px;
}
.note {
  color: var(--vp-c-text-2);
  font-size: 13px;
}
</style>
