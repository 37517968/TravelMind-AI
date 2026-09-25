<template>
  <div class="chat-container">
    <!-- 聊天记录区域 -->
    <div class="chat-messages" ref="messagesContainer">
      <div v-for="(msg, index) in messages" :key="index" class="message-wrapper">
        <!-- AI消息 -->
        <div v-if="!msg.isUser" 
             class="message ai-message" 
             :class="[msg.type]">
          <div class="avatar ai-avatar">
            <AiAvatarFallback :type="aiType" />
          </div>
          <div class="message-bubble">
            <div v-if="msg.generating" class="generating-status" role="status" aria-live="polite">
              <span class="generating-orbit" aria-hidden="true"></span>
              <span>Generating</span>
              <span class="generating-dots" aria-hidden="true"><i></i><i></i><i></i></span>
            </div>
            <!-- 模型思考与工具调用过程用小字灰度展示，不与正式回复混在一起 -->
            <div v-if="msg.steps && msg.steps.length" class="message-steps">
              <div class="step-summary-row">
                <div class="step-line step-summary" :class="latestStep(msg).state">
                  <span class="step-icon">{{ stepIcon(latestStep(msg).state) }}</span>
                  <span class="step-text">{{ latestStep(msg).text }}</span>
                </div>
                <button v-if="msg.steps.length > 1" class="steps-toggle" type="button"
                        @click="toggleSteps(index)">
                  {{ stepsExpanded(index) ? '收起' : `展开 ${msg.steps.length - 1} 条` }}
                </button>
              </div>
              <div v-if="stepsExpanded(index)" class="steps-history">
                <div v-for="step in previousSteps(msg)" :key="step.key"
                     class="step-line" :class="step.state">
                  <span class="step-icon">{{ stepIcon(step.state) }}</span>
                  <span class="step-text">{{ step.text }}</span>
                </div>
              </div>
            </div>
            <RouteOptionsCard v-if="msg.routeOptions?.length" :routes="msg.routeOptions"
                              @select="route => emit('select-route', route)" />
            <ItineraryPlanCard v-if="msg.planResult && ['PLAN', 'MODIFY'].includes(msg.planResult.responseType)"
                               :result="msg.planResult" />
            <div v-if="msg.content" class="message-content markdown-body" v-html="renderMarkdown(msg.content)"></div>
            <TravelMapCard v-if="msg.mapPlan?.available" :map-plan="msg.mapPlan"
                           @change-mode="mode => emit('change-route-mode', mode)" />
            <router-link v-if="msg.taskId" class="run-link" :to="`/observability/${msg.taskId}`">
              🔎 查看本次 Agent 编排路径 · Task #{{ msg.taskId }}
            </router-link>
            <span v-if="showTyping(index)" class="typing-indicator">▋</span>
            <div class="message-time">{{ formatTime(msg.time) }}</div>
          </div>
        </div>
        
        <!-- 用户消息 -->
        <div v-else class="message user-message" :class="[msg.type]">
          <div class="message-bubble">
            <div class="message-content">{{ msg.content }}</div>
            <div class="message-time">{{ formatTime(msg.time) }}</div>
          </div>
          <div class="avatar user-avatar">
            <div class="avatar-placeholder">我</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 输入区域 -->
    <div class="chat-input-container">
      <div class="chat-input">
        <textarea 
          v-model="inputMessage" 
          @keydown.enter.prevent="sendMessage"
          placeholder="请输入消息..." 
          class="input-box"
          :disabled="connectionStatus === 'connecting'"
        ></textarea>
        <button 
          @click="sendMessage" 
          class="send-button"
          :disabled="connectionStatus === 'connecting' || !inputMessage.trim()"
        >发送</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick, watch } from 'vue'
import AiAvatarFallback from './AiAvatarFallback.vue'
import TravelMapCard from './TravelMapCard.vue'
import RouteOptionsCard from './RouteOptionsCard.vue'
import ItineraryPlanCard from './ItineraryPlanCard.vue'
import { renderMarkdown } from '../utils/markdown'

const props = defineProps({
  messages: {
    type: Array,
    default: () => []
  },
  connectionStatus: {
    type: String,
    default: 'disconnected'
  },
  aiType: {
    type: String,
    default: 'default'  // 'travel' 等
  }
})

const emit = defineEmits(['send-message', 'change-route-mode', 'select-route'])

const inputMessage = ref('')
const messagesContainer = ref(null)
const expandedStepGroups = ref(new Set())

// 发送消息
const sendMessage = () => {
  if (!inputMessage.value.trim()) return
  
  emit('send-message', inputMessage.value)
  inputMessage.value = ''
}

// 步骤状态图标：running 进行中、ok 完成、warn 降级、error 失败
const stepIcon = (state) => ({ running: '◌', ok: '✓', warn: '!', error: '×' }[state] || '·')
const latestStep = msg => msg.steps[msg.steps.length - 1]
const previousSteps = msg => msg.steps.slice(0, -1)
const stepsExpanded = index => expandedStepGroups.value.has(index)
const toggleSteps = index => {
  const next = new Set(expandedStepGroups.value)
  if (next.has(index)) next.delete(index)
  else next.add(index)
  expandedStepGroups.value = next
}

const showTyping = (index) => (
  props.connectionStatus === 'connecting' && index === props.messages.length - 1
)

// 格式化时间
const formatTime = (timestamp) => {
  const date = new Date(timestamp)
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

// 自动滚动到底部
const scrollToBottom = async () => {
  await nextTick()
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
  }
}

// 监听消息变化与内容变化，自动滚动
watch(() => props.messages.length, () => {
  scrollToBottom()
})

watch(() => props.messages.map(m => m.content).join(''), () => {
  // 一旦正式内容开始输出，过程轨迹自动全部折叠；用户仍可手动再次展开。
  const next = new Set(expandedStepGroups.value)
  props.messages.forEach((message, index) => {
    if (message.content) next.delete(index)
  })
  expandedStepGroups.value = next
  scrollToBottom()
})

onMounted(() => {
  scrollToBottom()
})
</script>

<style scoped>
.chat-container {
  display: flex;
  flex-direction: column;
  height: 70vh;
  min-height: 600px;
  background-color: #f5f5f5;
  border-radius: 8px;
  overflow: hidden;
  position: relative;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  padding-bottom: 80px; /* 为输入框留出空间 */
  display: flex;
  flex-direction: column;
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 72px; /* 与输入框高度相匹配 */
}

.message-wrapper {
  margin-bottom: 16px;
  display: flex;
  flex-direction: column;
  width: 100%;
}

.message {
  display: flex;
  align-items: flex-start;
  max-width: 85%;
  margin-bottom: 8px;
}

.user-message {
  margin-left: auto; /* 用户消息靠右 */
  flex-direction: row; /* 正常顺序，先气泡后头像 */
}

.ai-message {
  margin-right: auto; /* AI消息靠左 */
  max-width: 96%;
}

.ai-message .message-bubble { width: 100%; box-sizing: border-box; }

.avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  overflow: hidden;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.user-avatar {
  margin-left: 8px; /* 用户头像在右侧，左边距 */
}

.ai-avatar {
  margin-right: 8px; /* AI头像在左侧，右边距 */
}

.avatar-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #007bff;
  color: white;
  font-weight: bold;
}

.message-bubble {
  padding: 12px;
  border-radius: 18px;
  position: relative;
  word-wrap: break-word;
  min-width: 100px; /* 最小宽度 */
}

.user-message .message-bubble {
  background-color: #007bff;
  color: white;
  border-bottom-right-radius: 4px;
  text-align: left;
}

.ai-message .message-bubble {
  background-color: #e9e9eb;
  color: #333;
  border-bottom-left-radius: 4px;
  text-align: left;
}

.message-content {
  font-size: 16px;
  line-height: 1.5;
  white-space: pre-wrap;
}

.generating-status {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 24px;
  color: #53657d;
  font-size: 13px;
  font-weight: 600;
}

.generating-orbit {
  width: 13px;
  height: 13px;
  border: 2px solid #c8d3e3;
  border-top-color: #4f78d1;
  border-radius: 50%;
  animation: generating-spin .8s linear infinite;
}

.generating-dots {
  display: inline-flex;
  gap: 3px;
  align-items: center;
}

.generating-dots i {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: currentColor;
  animation: generating-pulse 1.2s infinite ease-in-out;
}

.generating-dots i:nth-child(2) { animation-delay: .16s; }
.generating-dots i:nth-child(3) { animation-delay: .32s; }

@keyframes generating-spin { to { transform: rotate(360deg); } }
@keyframes generating-pulse {
  0%, 70%, 100% { opacity: .25; transform: translateY(0); }
  35% { opacity: 1; transform: translateY(-2px); }
}

/* 思考/工具调用过程：小字灰度，避免与正式回复抢视觉重点 */
.message-steps {
  font-size: 12px;
  line-height: 1.6;
  color: #8a8f99;
  background-color: rgba(255, 255, 255, 0.55);
  border-left: 2px solid #d3d7de;
  border-radius: 4px;
  padding: 6px 8px;
  margin-bottom: 8px;
}

.step-summary-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.step-summary {
  flex: 1;
  min-width: 0;
}

.step-summary .step-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.steps-toggle {
  flex-shrink: 0;
  border: 0;
  background: transparent;
  color: #687386;
  cursor: pointer;
  font-size: 11px;
  padding: 1px 2px;
}

.steps-toggle:hover {
  color: #315fba;
}

.steps-history {
  border-top: 1px dashed #d8dce3;
  margin-top: 5px;
  padding-top: 5px;
}

.step-line {
  display: flex;
  align-items: baseline;
  gap: 6px;
  word-break: break-all;
}

.step-line + .step-line {
  margin-top: 2px;
}

.step-icon {
  width: 12px;
  text-align: center;
  flex-shrink: 0;
}

.step-line.warn {
  color: #b26a00;
}

.step-line.error {
  color: #c0392b;
}

.step-line.running .step-icon {
  animation: blink 1s infinite;
}

/* 正式回复按 Markdown 结构化渲染，覆盖上面的 pre-wrap；v-html 内容需要 :deep() 才能命中 */
.markdown-body {
  white-space: normal;
}

.markdown-body :deep(p) {
  margin: 0 0 8px;
}

.markdown-body :deep(h3),
.markdown-body :deep(h4),
.markdown-body :deep(h5),
.markdown-body :deep(h6) {
  margin: 12px 0 6px;
  font-size: 15px;
  line-height: 1.4;
}

.markdown-body :deep(h3) {
  font-size: 17px;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 0 0 8px;
  padding-left: 22px;
}

.markdown-body :deep(li) {
  margin-bottom: 2px;
}

.markdown-body :deep(code) {
  background-color: rgba(0, 0, 0, 0.06);
  border-radius: 4px;
  padding: 1px 5px;
  font-family: Consolas, Monaco, monospace;
  font-size: 13px;
}

.markdown-body :deep(pre) {
  background-color: #282c34;
  color: #e6e6e6;
  border-radius: 6px;
  padding: 10px 12px;
  overflow-x: auto;
  margin: 0 0 8px;
}

.markdown-body :deep(pre code) {
  background: none;
  color: inherit;
  padding: 0;
}

.markdown-body :deep(blockquote) {
  margin: 0 0 8px;
  padding: 4px 10px;
  border-left: 3px solid #b9c2cf;
  color: #5b6472;
  background-color: rgba(255, 255, 255, 0.5);
}

.markdown-body :deep(a) {
  color: #0071e3;
  text-decoration: none;
}

.markdown-body :deep(a:hover) {
  text-decoration: underline;
}

.markdown-body :deep(hr) {
  border: none;
  border-top: 1px solid #d3d7de;
  margin: 10px 0;
}

.markdown-body :deep(table) {
  border-collapse: collapse;
  font-size: 14px;
  width: 100%;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid #d3d7de;
  padding: 4px 8px;
  text-align: left;
}

.markdown-body :deep(th) {
  background-color: rgba(0, 0, 0, 0.06);
  font-weight: 600;
}

.markdown-body :deep(.md-table-wrap) {
  overflow-x: auto;
  margin-bottom: 8px;
}

.markdown-body :deep(*:last-child) {
  margin-bottom: 0;
}
.message-time {
  font-size: 12px;
  opacity: 0.7;
  margin-top: 4px;
  text-align: right;
}

.run-link {
  display: inline-flex;
  margin-top: 9px;
  color: #466ab3;
  font-size: 12px;
  text-decoration: none;
}

.run-link:hover { text-decoration: underline; }

.chat-input-container {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  background-color: white;
  border-top: 1px solid #e0e0e0;
  z-index: 100;
  height: 72px; /* 固定高度 */
  box-shadow: 0 -2px 10px rgba(0, 0, 0, 0.05);
}

.chat-input {
  display: flex;
  padding: 16px;
  height: 100%;
  box-sizing: border-box;
  align-items: center;
}

.input-box {
  flex-grow: 1;
  border: 1px solid #ddd;
  border-radius: 20px;
  padding: 10px 16px;
  font-size: 16px;
  resize: none;
  min-height: 20px;
  max-height: 40px; /* 限制高度 */
  outline: none;
  transition: border-color 0.3s;
  overflow-y: auto;
  scrollbar-width: none; /* Firefox */
  -ms-overflow-style: none; /* IE & Edge */
}

/* 隐藏Webkit浏览器的滚动条 */
.input-box::-webkit-scrollbar {
  display: none;
}

.input-box:focus {
  border-color: #007bff;
}

.send-button {
  margin-left: 12px;
  background-color: #007bff;
  color: white;
  border: none;
  border-radius: 20px;
  padding: 0 20px;
  font-size: 16px;
  cursor: pointer;
  transition: background-color 0.3s;
  height: 40px;
  align-self: center;
}

.send-button:hover:not(:disabled) {
  background-color: #0069d9;
}

.typing-indicator {
  display: inline-block;
  animation: blink 0.7s infinite;
  margin-left: 2px;
}

@keyframes blink {
  0% { opacity: 0; }
  50% { opacity: 1; }
  100% { opacity: 0; }
}

.input-box:disabled, .send-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .message {
    max-width: 95%;
  }
  
  .message-content {
    font-size: 15px;
  }
  
  .chat-input {
    padding: 12px;
  }
  
  .input-box {
    padding: 8px 12px;
  }
  
  .send-button {
    padding: 0 15px;
    font-size: 14px;
  }
}

@media (max-width: 480px) {
  .avatar {
    width: 32px;
    height: 32px;
  }
  
  .message-bubble {
    padding: 10px;
  }
  
  .message-content {
    font-size: 14px;
  }
  
  .chat-input-container {
    height: 64px;
  }
  
  .chat-messages {
    bottom: 64px;
  }
}

/* 新增：不同类型消息的样式 */
.ai-answer {
  animation: fadeIn 0.3s ease-in-out;
}

.ai-final {
  /* 最终回答，可以有不同的样式，例如边框高亮等 */
}

.ai-error {
  opacity: 0.7;
}

.user-question {
  /* 用户提问的特殊样式 */
}

/* 连续消息气泡样式 */
.ai-message + .ai-message {
  margin-top: 4px;
}

.ai-message + .ai-message .avatar {
  visibility: hidden;
}

.ai-message + .ai-message .message-bubble {
  border-top-left-radius: 10px;
}
</style>
