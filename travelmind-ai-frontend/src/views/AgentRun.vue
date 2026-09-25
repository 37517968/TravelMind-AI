<template>
  <main class="run-page">
    <header class="topbar">
      <router-link to="/travel-agent">← 返回 Agent</router-link>
      <div><h1>Agent Run Explorer</h1><p>按 taskId 回放执行段、工作流节点和工具调用</p></div>
    </header>

    <form class="search" @submit.prevent="load(inputTaskId)">
      <input v-model="inputTaskId" inputmode="numeric" placeholder="输入 taskId，例如 1001" />
      <button :disabled="loading || !inputTaskId">{{ loading ? '查询中…' : '查看路径' }}</button>
    </form>
    <p v-if="error" class="error">{{ error }}</p>

    <template v-if="run">
      <section class="summary-grid">
        <article><small>任务</small><strong>#{{ run.task.id }}</strong></article>
        <article><small>状态</small><strong :class="statusClass(run.task.status)">{{ run.task.status }}</strong></article>
        <article><small>Workflow</small><strong>{{ run.task.workflowVersion }}</strong></article>
        <article><small>模型调用 / Token</small><strong>{{ run.task.modelCalls }} / {{ run.task.tokens }}</strong></article>
        <article><small>总耗时</small><strong>{{ taskDuration }}</strong></article>
      </section>

      <section class="panel">
        <div class="panel-title"><h2>执行段</h2><p>首次执行、恢复和重试分别保留 Trace</p></div>
        <div class="execution-list">
          <article v-for="execution in run.executions" :key="execution.id" class="execution-card">
            <div class="execution-head"><b>#{{ execution.id }} · {{ execution.commandType }}</b><span :class="statusClass(execution.status)">{{ execution.status }}</span></div>
            <small>{{ formatTime(execution.startedAt) }} · {{ duration(execution.durationMs) }} · {{ execution.workerInstance }}</small>
            <div v-if="execution.traceId" class="trace-row">
              <code>{{ execution.traceId }}</code>
              <button type="button" @click="copy(execution.traceId)">复制 Trace ID</button>
              <a :href="run.grafanaUrl" target="_blank" rel="noreferrer">打开 Grafana</a>
            </div>
          </article>
          <p v-if="!run.executions.length" class="empty">旧任务没有 execution 记录；新任务会自动记录。</p>
        </div>
      </section>

      <section class="panel">
        <div class="panel-title"><h2>实际编排路径</h2><p>按 checkpoint 顺序展示等待、恢复和节点重试</p></div>
        <div class="workflow-path">
          <template v-for="(node, index) in run.nodes" :key="node.checkpointId">
            <article class="node-card" :class="statusClass(node.status)">
              <div class="node-head"><span>{{ nodeIcon(node.status) }}</span><div><b>{{ node.label }}</b><code>{{ node.nodeId }}</code></div></div>
              <dl>
                <div><dt>状态</dt><dd>{{ node.status }}</dd></div>
                <div><dt>耗时</dt><dd>{{ duration(node.durationMs) }}</dd></div>
                <div><dt>尝试</dt><dd>#{{ node.attempt }}</dd></div>
                <div v-if="node.route"><dt>路由</dt><dd>{{ node.route }}</dd></div>
              </dl>
              <div v-if="toolsFor(node).length" class="node-tools">
                <span v-for="tool in toolsFor(node)" :key="tool.id" :class="{ failed: !tool.success }">🔧 {{ tool.toolName }} · {{ duration(tool.durationMs) }}</span>
              </div>
              <p v-for="warning in node.warnings" :key="warning" class="warning">⚠ {{ warning }}</p>
              <p v-if="node.errorMessage" class="node-error">{{ node.errorType }}：{{ node.errorMessage }}</p>
              <small v-if="node.outputKeys?.length">输出：{{ node.outputKeys.join('、') }}</small>
            </article>
            <div v-if="index < run.nodes.length - 1" class="path-arrow"><span>{{ node.route || 'NEXT' }}</span>↓</div>
          </template>
          <p v-if="!run.nodes.length" class="empty">任务尚未产生节点 checkpoint。</p>
        </div>
      </section>

      <section class="panel">
        <div class="panel-title"><h2>工具调用</h2><p>不展示 Prompt、工具参数和密钥</p></div>
        <div class="table-wrap"><table><thead><tr><th>节点</th><th>工具</th><th>来源</th><th>状态</th><th>缓存/降级</th><th>耗时</th><th>时间</th></tr></thead>
          <tbody><tr v-for="tool in run.tools" :key="tool.id"><td>{{ tool.workflowNode }}</td><td>{{ tool.toolName }}</td><td>{{ tool.source }}</td>
            <td :class="tool.success ? 'success' : 'failed'">{{ tool.success ? 'SUCCESS' : tool.errorCode }}</td>
            <td>{{ tool.cacheHit ? '缓存' : '实时' }} / {{ tool.degraded ? '降级' : '正常' }}</td><td>{{ duration(tool.durationMs) }}</td><td>{{ formatTime(tool.createdAt) }}</td></tr>
          <tr v-if="!run.tools.length"><td colspan="7" class="empty">本次任务没有工具审计记录</td></tr></tbody></table></div>
      </section>
    </template>
  </main>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getAgentRun } from '../api'

const route = useRoute()
const router = useRouter()
const inputTaskId = ref(route.params.taskId || '')
const run = ref(null)
const loading = ref(false)
const error = ref('')

const load = async taskId => {
  if (!taskId) return
  loading.value = true
  error.value = ''
  try {
    const { data } = await getAgentRun(taskId)
    run.value = data
    if (String(route.params.taskId || '') !== String(taskId)) router.replace(`/observability/${taskId}`)
  } catch (failure) {
    run.value = null
    error.value = failure?.response?.data?.message || failure?.response?.data?.error || '查询失败，请确认 taskId 和服务状态。'
  } finally { loading.value = false }
}

const duration = ms => ms == null ? '—' : ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(2)} s`
const formatTime = value => value ? new Date(value).toLocaleString('zh-CN') : '—'
const statusClass = value => String(value || '').toLowerCase().replace('_user', '')
const nodeIcon = status => ({ SUCCEEDED: '✓', SUCCESS: '✓', RUNNING: '◌', WAITING_USER: '⏸', FAILED: '×' }[status] || '·')
const toolsFor = node => (run.value?.tools || []).filter(tool => node.nodeId.startsWith(tool.workflowNode) || tool.workflowNode.startsWith(node.nodeId.split('_v')[0]))
const copy = value => navigator.clipboard?.writeText(value)
const taskDuration = computed(() => {
  const task = run.value?.task
  if (!task?.createdAt) return '—'
  return duration(new Date(task.finishedAt || Date.now()).getTime() - new Date(task.createdAt).getTime())
})

onMounted(() => { if (inputTaskId.value) load(inputTaskId.value) })
</script>

<style scoped>
.run-page{min-height:100vh;padding:28px;background:#f3f6fa;color:#203047}.topbar{display:flex;gap:24px;align-items:flex-start;max-width:1180px;margin:auto}.topbar a{color:#4769ad;text-decoration:none}.topbar h1{margin:0;font-size:28px}.topbar p,.panel-title p{margin:5px 0 0;color:#718096}.search{display:flex;gap:10px;max-width:560px;margin:24px auto}.search input{flex:1;padding:12px 16px;border:1px solid #cfd8e5;border-radius:12px;font-size:15px}.search button,.trace-row button{border:0;border-radius:10px;background:#426bd0;color:white;padding:10px 16px;cursor:pointer}.summary-grid,.panel{max-width:1180px;margin:0 auto 18px}.summary-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:12px}.summary-grid article,.panel{background:white;border:1px solid #dfe6ef;border-radius:16px;box-shadow:0 6px 20px rgba(31,47,70,.05)}.summary-grid article{padding:15px;display:flex;flex-direction:column;gap:7px}.summary-grid small{color:#718096}.summary-grid strong{font-size:15px;overflow-wrap:anywhere}.panel{padding:20px}.panel-title h2{margin:0;font-size:19px}.execution-list{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:12px;margin-top:16px}.execution-card{border:1px solid #e0e7f0;border-radius:12px;padding:13px}.execution-head{display:flex;justify-content:space-between;gap:8px}.execution-card small{display:block;color:#718096;margin-top:7px}.trace-row{display:flex;align-items:center;gap:8px;margin-top:10px}.trace-row code{overflow:hidden;text-overflow:ellipsis;font-size:11px}.trace-row button{padding:5px 8px;font-size:11px}.trace-row a{font-size:11px;color:#426bd0}.workflow-path{display:flex;flex-direction:column;max-width:820px;margin:18px auto 0}.node-card{border:1px solid #dbe4ef;border-left:5px solid #91a2b8;border-radius:13px;padding:14px;background:#fbfcfe}.node-card.succeeded,.node-card.success{border-left-color:#2f9e6f}.node-card.running{border-left-color:#4474db}.node-card.waiting{border-left-color:#dc9b24}.node-card.failed{border-left-color:#d34d58}.node-head{display:flex;gap:10px;align-items:center}.node-head>span{font-size:20px}.node-head div{display:flex;flex-direction:column}.node-head code{font-size:10px;color:#8290a4;margin-top:3px}.node-card dl{display:flex;flex-wrap:wrap;gap:8px 18px;margin:12px 0}.node-card dl div{display:flex;gap:5px}.node-card dt{color:#7a8799}.node-card dd{margin:0;font-weight:600}.node-tools{display:flex;flex-wrap:wrap;gap:7px}.node-tools span{font-size:11px;background:#eaf5ef;color:#267552;padding:4px 7px;border-radius:12px}.node-tools span.failed{background:#fdebec;color:#b63d48}.warning{color:#a86d13;font-size:12px}.node-error,.error{color:#bd3947}.path-arrow{display:flex;flex-direction:column;align-items:center;color:#718096;font-size:17px;padding:4px}.path-arrow span{font-size:10px;background:#edf1f7;padding:2px 7px;border-radius:8px}.table-wrap{overflow:auto;margin-top:15px}table{width:100%;border-collapse:collapse;font-size:12px}th,td{padding:9px;border-bottom:1px solid #e5eaf1;text-align:left;white-space:nowrap}th{background:#f5f8fc}.success{color:#24875e}.failed{color:#c33f4c}.waiting{color:#b47917}.running{color:#356bd4}.empty{text-align:center;color:#8793a4;padding:16px}@media(max-width:800px){.run-page{padding:15px}.summary-grid{grid-template-columns:repeat(2,1fr)}.topbar{gap:12px}.execution-list{grid-template-columns:1fr}}
</style>
