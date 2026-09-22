# TravelMind AI 前端

TravelMind AI 旅行智能规划平台的前端工程，基于 Vue 3 + Vite 构建。

## 功能模块

- 🧭 **AI 旅行管家**：以异步任务方式提交旅行需求，通过 SSE 实时接收规划进度、Token 流与追问，支持补充信息恢复任务、取消任务和清除会话记忆
- 👥 **旅行社区**：发布、浏览、搜索旅行方案，支持点赞、收藏和评论，并可查看方案详情
- 📁 **文件中心**：列出后端生成的行程、报告等文件并提供下载入口

## 技术栈

- Vue 3 + Vue Router
- @vueuse/head（页面标题与 SEO 元信息）
- Axios
- SSE（Server-Sent Events）
- Vite

## 开发说明

### 环境要求

- Node.js >= 16.0.0
- npm >= 7.0.0

### 常用命令

```bash
npm install     # 安装依赖
npm run dev     # 启动开发服务器
npm run build   # 构建生产产物
npm run preview # 本地预览构建产物
```

## 后端接口

接口前缀由 `src/api/index.js` 决定：开发环境指向 `http://localhost:8123/api`，生产环境使用同域相对路径 `/api`。

### Agent 任务

- `POST /api/agent/tasks` - 提交旅行规划任务（携带 Idempotency-Key）
- `GET /api/agent/tasks/{taskId}/events` - SSE 进度、追问和 Token 流（重连携带 Last-Event-ID）
- `POST /api/agent/tasks/{taskId}/resume` - 补充信息并恢复任务
- `POST /api/agent/tasks/{taskId}/cancel` - 取消任务
- `DELETE /api/agent/tasks/conversations/{conversationId}/memory` - 清除会话记忆
- `GET /api/agent/tasks/{taskId}` - 查询终态与完整结果

### 旅行社区

- `POST /api/travel/community/plan/create` - 创建旅行方案
- `GET /api/travel/community/plan/{id}` - 获取方案详情
- `GET /api/travel/community/plan/list` - 分页获取方案列表
- `GET /api/travel/community/plan/hot` - 获取热门方案
- `GET /api/travel/community/plan/search` - 按关键词搜索方案
- `POST /api/travel/community/plan/{id}/like` - 点赞方案
- `POST /api/travel/community/plan/{id}/favorite` - 收藏方案
- `POST /api/travel/community/comment/create` - 创建评论
- `GET /api/travel/community/comment/list/{planId}` - 分页获取方案评论
- `POST /api/travel/community/comment/{id}/like` - 点赞评论

### 文件

- `GET /api/file/list` - 获取文件列表
- `GET /api/file/download` - 下载指定文件
