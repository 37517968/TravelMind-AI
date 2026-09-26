import axios from 'axios'

// 根据环境变量设置 API 基础 URL
const API_BASE_URL = process.env.NODE_ENV === 'production' 
 ? '/api' // 生产环境使用相对路径，适用于前后端部署在同一域名下
 : 'http://localhost:8123/api' // 开发环境指向本地后端服务

// 创建axios实例
const request = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60000,
  withCredentials: true
})

const idempotencyKey = () => globalThis.crypto?.randomUUID?.()
  || `task-${Date.now()}-${Math.random().toString(16).slice(2)}`

// ==================== 用户与登录态 ====================

export const login = (data) => request.post('/user/login', data)

export const register = (data) => request.post('/user/register', data)

export const getLoginUser = () => request.get('/user/get/login')

export const logout = () => request.post('/user/logout')

export const createAgentTask = (payload) => request.post('/agent/tasks', payload, {
  headers: { 'Idempotency-Key': idempotencyKey() }
})

export const getAgentTask = (taskId) => request.get(`/agent/tasks/${taskId}`)

export const getAgentRun = (taskId) => {
  return request.get(`/agent/tasks/${taskId}/run`)
}

export const getAgentRunNode = (taskId, checkpointId) => {
  return request.get(`/agent/tasks/${taskId}/run/nodes/${checkpointId}`)
}

export const resumeAgentTask = (taskId, supplemental) => request.post(`/agent/tasks/${taskId}/resume`, {
  supplemental
})

export const cancelAgentTask = (taskId) => request.post(`/agent/tasks/${taskId}/cancel`)

export const clearAgentConversationMemory = (conversationId) => request.delete(
  `/agent/tasks/conversations/${encodeURIComponent(conversationId)}/memory`
)

export const listAgentConversations = () => request.get('/agent/conversations')
export const createAgentConversation = (title = '') => request.post('/agent/conversations', { title })
export const renameAgentConversation = (conversationId, title) => request.patch(`/agent/conversations/${conversationId}`, { title })
export const archiveAgentConversation = conversationId => request.delete(`/agent/conversations/${conversationId}`)
export const getAgentConversationMessages = conversationId => request.get(`/agent/conversations/${conversationId}/messages`)
export const getTravelPreferences = () => request.get('/agent/conversations/preferences')
export const saveTravelPreferences = preferences => request.put('/agent/conversations/preferences', preferences)

// 所有规划进度和 Token 都从任务 Redis Stream 经 SSE 返回；浏览器重连会携带 Last-Event-ID。
export const connectAgentTaskEvents = (taskId, handlers = {}) => {
  const eventSource = new EventSource(`${API_BASE_URL}/agent/tasks/${taskId}/events`)

  const parse = event => {
    try { return JSON.parse(event.data) } catch { return { message: event.data } }
  }
  eventSource.addEventListener('token', event => handlers.onToken?.(parse(event), event.lastEventId))
  eventSource.addEventListener('progress', event => handlers.onProgress?.(parse(event), event.lastEventId))
  eventSource.addEventListener('terminal', event => {
    handlers.onTerminal?.(event.data)
    eventSource.close()
  })
  eventSource.onerror = error => handlers.onError?.(error)
  return eventSource
}

// ==================== 旅行社区相关API ====================

// 创建旅行方案
export const createTravelPlan = (data) => {
  return request.post('/travel/community/plan/create', data)
}

// 获取方案详情
export const getTravelPlan = (id) => {
  return request.get(`/travel/community/plan/${id}`)
}

// 获取方案列表
export const listTravelPlans = (params) => {
  return request.get('/travel/community/plan/list', { params })
}

// 获取热门方案
export const getHotTravelPlans = (limit = 10) => {
  return request.get('/travel/community/plan/hot', { params: { limit } })
}

// 搜索方案
export const searchTravelPlans = (keyword, page = 1, size = 10) => {
  return request.get('/travel/community/plan/search', { params: { keyword, page, size } })
}

// 点赞方案
export const likeTravelPlan = (id) => {
  return request.post(`/travel/community/plan/${id}/like`)
}

// 收藏方案
export const favoriteTravelPlan = (id) => {
  return request.post(`/travel/community/plan/${id}/favorite`)
}

// 创建评论
export const createComment = (data) => {
  return request.post('/travel/community/comment/create', data)
}

// 获取方案评论列表
export const getComments = (planId, page = 1, size = 20) => {
  return request.get(`/travel/community/comment/list/${planId}`, { params: { page, size } })
}

// 点赞评论
export const likeComment = (id) => {
  return request.post(`/travel/community/comment/${id}/like`)
}

// ==================== 文件下载相关API ====================

// 获取文件列表
export const listFiles = (type = 'all') => {
  return request.get('/file/list', { params: { type } })
}

// 获取文件下载链接
export const getFileDownloadUrl = (path) => {
  return `${API_BASE_URL}/file/download?path=${encodeURIComponent(path)}`
}

export default {
  login,
  register,
  getLoginUser,
  logout,
  createAgentTask,
  getAgentTask,
  getAgentRun,
  getAgentRunNode,
  resumeAgentTask,
  cancelAgentTask,
  clearAgentConversationMemory,
  listAgentConversations,
  createAgentConversation,
  renameAgentConversation,
  archiveAgentConversation,
  getAgentConversationMessages,
  getTravelPreferences,
  saveTravelPreferences,
  connectAgentTaskEvents,
  createTravelPlan,
  getTravelPlan,
  listTravelPlans,
  getHotTravelPlans,
  searchTravelPlans,
  likeTravelPlan,
  favoriteTravelPlan,
  createComment,
  getComments,
  likeComment,
  listFiles,
  getFileDownloadUrl
}
