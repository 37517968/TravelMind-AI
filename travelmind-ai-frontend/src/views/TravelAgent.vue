<template>
  <div class="travel-agent-container">
    <div class="header">
      <div class="back-button" @click="goBack">返回</div>
      <h1 class="title">🌍 AI旅行管家</h1>
      <div class="placeholder"></div>
    </div>
    
    <div class="content-wrapper">
      <div class="chat-area">
        <ChatRoom 
          :messages="messages" 
          :connection-status="connectionStatus"
          ai-type="travel"
          @send-message="sendMessage"
        />
      </div>
      
      <!-- 快捷操作面板 -->
      <div class="quick-actions" v-if="messages.length <= 1">
        <h3>🎯 快速开始</h3>
        <div class="action-grid">
          <div class="action-card" @click="quickStart('我想去上海玩3天，帮我规划一下行程')">
            <span class="action-icon">🏙️</span>
            <span class="action-text">上海3日游</span>
          </div>
          <div class="action-card" @click="quickStart('推荐一些适合周末短途旅行的目的地')">
            <span class="action-icon">🚗</span>
            <span class="action-text">周末短途</span>
          </div>
          <div class="action-card" @click="quickStart('查询北京最近一周的天气')">
            <span class="action-icon">🌤️</span>
            <span class="action-text">天气查询</span>
          </div>
          <div class="action-card" @click="quickStart('帮我找一些杭州的网红打卡地')">
            <span class="action-icon">📸</span>
            <span class="action-text">网红打卡</span>
          </div>
          <div class="action-card" @click="quickStart('我预算3000元，想带家人出去玩，有什么推荐？')">
            <span class="action-icon">👨‍👩‍👧</span>
            <span class="action-text">家庭出游</span>
          </div>
          <div class="action-card" @click="quickStart('帮我规划一次云南7天深度游')">
            <span class="action-icon">🏔️</span>
            <span class="action-text">云南深度游</span>
          </div>
        </div>
      </div>
    </div>
    
    <div class="footer-container">
      <AppFooter />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import ChatRoom from '../components/ChatRoom.vue'
import AppFooter from '../components/AppFooter.vue'
import { createAgentTask, getAgentTask, resumeAgentTask, connectAgentTaskEvents } from '../api'

// 设置页面标题和元数据
useHead({
  title: 'AI旅行管家 - 智能旅游规划助手',
  meta: [
    {
      name: 'description',
      content: 'AI旅行管家是您的智能旅游规划助手，提供目的地推荐、天气查询、行程规划、酒店餐厅推荐等全方位旅游服务'
    },
    {
      name: 'keywords',
      content: 'AI旅行,智能旅游,行程规划,旅游助手,天气查询,景点推荐,酒店推荐'
    }
  ]
})

const router = useRouter()
const messages = ref([])
const connectionStatus = ref('disconnected')
const conversationId = localStorage.getItem('travel-conversation-id')
  || globalThis.crypto?.randomUUID?.()
  || `travel-${Date.now()}`
localStorage.setItem('travel-conversation-id', conversationId)

let eventSource = null
let activeTaskId = null
let waitingForUser = false
let answerMessageIndex = -1

// 添加消息到列表
const addMessage = (content, isUser, type = '') => {
  messages.value.push({
    content,
    isUser,
    type,
    time: new Date().getTime()
  })
}

// 同一条助手消息同时承载“过程小字”和正式回复，避免聊天区出现空气泡。
const assistantMessage = () => {
  if (answerMessageIndex < 0) {
    messages.value.push({ content: '', isUser: false, type: 'ai-answer', time: Date.now(), steps: [] })
    answerMessageIndex = messages.value.length - 1
  }
  const message = messages.value[answerMessageIndex]
  if (!message.steps) message.steps = []
  return message
}

const appendToken = (content) => {
  if (!content) return
  assistantMessage().content += content
}

// 节点与工具进度写入小字步骤区，相同 key 覆盖更新，避免刷屏。
const appendStep = (event, state) => {
  const text = event?.message
  if (!text) return
  const key = event?.details?.key || `${event?.type}:${event?.nodeId || ''}`
  const steps = assistantMessage().steps
  const existing = steps.find(step => step.key === key)
  if (existing) {
    existing.text = text
    existing.state = state
  } else {
    steps.push({ key, text, state })
  }
}

const readFinalResult = async () => {
  if (!activeTaskId) return
  const { data } = await getAgentTask(activeTaskId)
  const resultJson = data?.task?.resultJson
  if (!resultJson) return
  const message = answerMessageIndex >= 0 ? messages.value[answerMessageIndex] : null
  if (message && message.content.trim()) return
  let text = resultJson
  try {
    const result = JSON.parse(resultJson)
    text = result.itinerary || ''
  } catch {
    // 非 JSON 结果按纯文本展示
  }
  if (!text) return
  if (message) message.content = text
  else addMessage(text, false, 'ai-final')
}

const subscribeTask = (taskId) => {
  eventSource?.close()
  eventSource = connectAgentTaskEvents(taskId, {
    onToken(event) {
      connectionStatus.value = 'connected'
      appendToken(event?.details?.content || '')
    },
    onProgress(event) {
      const status = event?.status
      // 节点与工具调用只进小字区，不打断正式回复的流式输出
      if (event?.type === 'NODE' || event?.type === 'TOOL') {
        appendStep(event, status === 'SUCCEEDED' ? 'ok' : status === 'RUNNING' ? 'running' : 'warn')
        const warnings = Array.isArray(event?.details?.warnings) ? event.details.warnings : []
        warnings.forEach(warning => appendStep({
          message: warning,
          details: { key: `${event?.details?.key || event?.nodeId}:warning` }
        }, 'warn'))
        return
      }
      if (status === 'WAITING_USER') {
        waitingForUser = true
        connectionStatus.value = 'waiting'
        addMessage(event.message || '请补充完成规划所需的信息。', false, 'ai-question')
      } else if (status === 'FAILED') {
        addMessage(event.message || '任务执行失败，请稍后重试。', false, 'ai-error')
      } else if (status === 'RUNNING') {
        connectionStatus.value = 'connected'
      }
    },
    async onTerminal(status) {
      connectionStatus.value = status === 'SUCCEEDED' ? 'disconnected' : 'error'
      if (answerMessageIndex >= 0) {
        messages.value[answerMessageIndex].steps.forEach(step => {
          if (step.state === 'running') step.state = status === 'FAILED' ? 'error' : 'ok'
        })
      }
      if (status === 'SUCCEEDED') await readFinalResult()
      if (status !== 'SUCCEEDED') addMessage(`任务已结束：${status}`, false, 'ai-error')
      activeTaskId = null
      waitingForUser = false
      answerMessageIndex = -1
      eventSource = null
    },
    onError(error) {
      console.warn('任务事件连接暂时中断，浏览器将按 Last-Event-ID 自动重连', error)
      connectionStatus.value = 'connecting'
    }
  })
}

// 所有用户输入统一进入 /agent/tasks；WAITING_USER 状态下作为 supplemental 恢复同一任务。
const sendMessage = async (message) => {
  if (activeTaskId && !waitingForUser) {
    addMessage('当前规划任务仍在执行，请等待完成后再发起新任务。', false, 'ai-question')
    return
  }
  addMessage(message, true, 'user-question')
  connectionStatus.value = 'connecting'
  try {
    if (activeTaskId && waitingForUser) {
      await resumeAgentTask(activeTaskId, { userClarification: message })
      waitingForUser = false
      answerMessageIndex = -1
      if (!eventSource) subscribeTask(activeTaskId)
      return
    }

    const { data } = await createAgentTask({
      conversationId,
      taskType: 'PLAN',
      prompt: message,
      constraints: {}
    })
    activeTaskId = data.taskId
    answerMessageIndex = -1
    subscribeTask(activeTaskId)
  } catch (error) {
    connectionStatus.value = 'error'
    activeTaskId = null
    waitingForUser = false
    addMessage(error?.response?.data?.message || '任务提交失败，请稍后重试。', false, 'ai-error')
  }
}

// 快速开始
const quickStart = (message) => {
  sendMessage(message)
}

// 返回主页
const goBack = () => {
  router.push('/')
}

// 页面加载时添加欢迎消息
onMounted(() => {
  addMessage(`你好！我是AI旅行管家 🌍

我可以帮你：
• 🗺️ 规划旅行行程
• 🌤️ 查询目的地天气
• 🏨 推荐酒店住宿
• 🍜 发现当地美食
• 📍 搜索热门景点
• 💰 估算旅行预算
• 📄 生成行程PDF

告诉我你想去哪里玩，或者有什么旅行想法？`, false)
})

// 组件销毁前关闭SSE连接
onBeforeUnmount(() => {
  if (eventSource) {
    eventSource.close()
  }
})
</script>

<style scoped>
.travel-agent-container {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  background-attachment: fixed;
}

.header {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  padding: 16px 24px;
  background: rgba(255, 255, 255, 0.15);
  backdrop-filter: blur(10px);
  color: white;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.1);
  position: sticky;
  top: 0;
  z-index: 10;
}

.back-button {
  font-size: 16px;
  cursor: pointer;
  display: flex;
  align-items: center;
  transition: all 0.3s;
  justify-self: start;
  padding: 8px 16px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.2);
}

.back-button:hover {
  background: rgba(255, 255, 255, 0.3);
  transform: translateX(-3px);
}

.back-button:before {
  content: '←';
  margin-right: 8px;
}

.title {
  font-size: 22px;
  font-weight: bold;
  margin: 0;
  text-align: center;
  justify-self: center;
  text-shadow: 0 2px 10px rgba(0, 0, 0, 0.2);
}

.placeholder {
  width: 1px;
  justify-self: end;
}

.content-wrapper {
  display: flex;
  flex-direction: column;
  flex: 1;
  padding: 16px;
}

.chat-area {
  flex: 1;
  background: rgba(255, 255, 255, 0.95);
  border-radius: 20px;
  overflow: hidden;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.15);
  min-height: calc(100vh - 200px);
  margin-bottom: 16px;
}

/* 快捷操作面板 */
.quick-actions {
  background: rgba(255, 255, 255, 0.95);
  border-radius: 16px;
  padding: 20px;
  margin-bottom: 16px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.1);
}

.quick-actions h3 {
  margin: 0 0 16px 0;
  color: #333;
  font-size: 16px;
}

.action-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.action-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 16px 12px;
  background: linear-gradient(135deg, #f5f7fa 0%, #e4e8ec 100%);
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.3s;
  border: 2px solid transparent;
}

.action-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 6px 20px rgba(102, 126, 234, 0.3);
  border-color: #667eea;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.action-card:hover .action-text {
  color: white;
}

.action-icon {
  font-size: 28px;
  margin-bottom: 8px;
}

.action-text {
  font-size: 13px;
  color: #555;
  text-align: center;
  transition: color 0.3s;
}

.footer-container {
  margin-top: auto;
}

/* 响应式样式 */
@media (max-width: 768px) {
  .header {
    padding: 12px 16px;
  }
  
  .title {
    font-size: 18px;
  }
  
  .content-wrapper {
    padding: 12px;
  }
  
  .chat-area {
    min-height: calc(100vh - 180px);
    border-radius: 16px;
  }
  
  .action-grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 10px;
  }
  
  .action-card {
    padding: 14px 10px;
  }
  
  .action-icon {
    font-size: 24px;
  }
  
  .action-text {
    font-size: 12px;
  }
}

@media (max-width: 480px) {
  .header {
    padding: 10px 12px;
  }
  
  .back-button {
    font-size: 14px;
    padding: 6px 12px;
  }
  
  .title {
    font-size: 16px;
  }
  
  .content-wrapper {
    padding: 8px;
  }
  
  .chat-area {
    min-height: calc(100vh - 160px);
    border-radius: 12px;
  }
  
  .quick-actions {
    padding: 16px;
  }
  
  .action-grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 8px;
  }
  
  .action-card {
    padding: 12px 8px;
  }
  
  .action-icon {
    font-size: 22px;
    margin-bottom: 6px;
  }
  
  .action-text {
    font-size: 11px;
  }
}
</style>
