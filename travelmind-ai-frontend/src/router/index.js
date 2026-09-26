import { createRouter, createWebHistory } from 'vue-router'
import { ensureAuth } from '../auth'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/AuthView.vue'),
    meta: {
      guestOnly: true,
      title: '登录 - TravelMind AI'
    }
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('../views/AuthView.vue'),
    meta: {
      guestOnly: true,
      title: '注册 - TravelMind AI'
    }
  },
  {
    path: '/',
    name: 'Home',
    component: () => import('../views/Home.vue'),
    meta: {
      title: '首页 - AI旅行管家',
      description: 'AI旅行管家提供智能旅游规划、景点推荐、天气查询、酒店餐厅推荐等全方位旅游服务'
    }
  },
  {
    path: '/travel-agent',
    name: 'TravelAgent',
    component: () => import('../views/TravelAgent.vue'),
    meta: {
      requiresAuth: true,
      title: 'AI旅行管家 - 智能旅游规划助手',
      description: 'AI旅行管家是您的智能旅游规划助手，提供目的地推荐、天气查询、行程规划、酒店餐厅推荐等全方位旅游服务'
    }
  },
  {
    path: '/preferences',
    name: 'Preferences',
    component: () => import('../views/PreferencesView.vue'),
    meta: { requiresAuth: true, title: '旅行偏好 - TravelMind AI' }
  },
  {
    path: '/community',
    name: 'Community',
    component: () => import('../views/Community.vue'),
    meta: {
      title: '旅行社区 - AI旅行管家',
      description: '分享您的旅行方案，发现更多精彩旅程'
    }
  },
  {
    path: '/plan/:id',
    name: 'PlanDetail',
    component: () => import('../views/PlanDetail.vue'),
    meta: {
      title: '方案详情 - AI旅行管家',
      description: '查看旅行方案详情'
    }
  },
  {
    path: '/observability/:taskId?',
    name: 'AgentRun',
    component: () => import('../views/AgentRun.vue'),
    meta: {
      title: 'Agent运行路径 - AI旅行管家',
      description: '查看单次Agent任务的工作流节点、执行段、工具调用与Trace'
    }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局导航守卫，设置文档标题
router.beforeEach(async (to, from, next) => {
  // 设置页面标题
  if (to.meta.title) {
    document.title = to.meta.title
  }
  const user = await ensureAuth()
  if (to.meta.requiresAuth && !user) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }
  if (to.meta.guestOnly && user) {
    next('/')
    return
  }
  next()
})

export default router
