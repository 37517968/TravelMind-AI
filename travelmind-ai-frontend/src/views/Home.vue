<template>
  <div class="home-container">
    <div class="header">
      <div class="glitch-wrapper">
        <h1 class="glitch-title">AI旅行管家</h1>
      </div>
      <p class="subtitle">/ 智能规划，畅游天下 /</p>
      <div class="cyber-line"></div>
    </div>
    
    <div class="apps-container">
      <div class="app-card" @click="navigateTo('/travel-agent')">
        <div class="card-glow"></div>
        <div class="app-icon travel-icon">🌍</div>
        <div class="app-info">
          <div class="app-title">AI旅行规划</div>
          <div class="app-desc">智能行程规划，天气查询，景点美食推荐</div>
        </div>
        <div class="app-button">
          <span class="btn-text">开始规划</span>
          <span class="btn-icon">→</span>
        </div>
      </div>
      
      <div class="app-card" @click="navigateTo('/community')">
        <div class="card-glow"></div>
        <div class="app-icon community-icon">📝</div>
        <div class="app-info">
          <div class="app-title">旅行社区</div>
          <div class="app-desc">分享旅行方案，发现精彩旅程</div>
        </div>
        <div class="app-button">
          <span class="btn-text">进入社区</span>
          <span class="btn-icon">→</span>
        </div>
      </div>
    </div>
    
    <!-- 功能特点 -->
    <div class="features-section">
      <h2 class="section-title">✨ 核心功能</h2>
      <div class="features-grid">
        <div class="feature-card">
          <span class="feature-icon">🗺️</span>
          <span class="feature-text">智能行程规划</span>
        </div>
        <div class="feature-card">
          <span class="feature-icon">🌤️</span>
          <span class="feature-text">天气实时查询</span>
        </div>
        <div class="feature-card">
          <span class="feature-icon">🏨</span>
          <span class="feature-text">酒店智能推荐</span>
        </div>
        <div class="feature-card">
          <span class="feature-icon">🍜</span>
          <span class="feature-text">美食地图导航</span>
        </div>
        <div class="feature-card">
          <span class="feature-icon">🚗</span>
          <span class="feature-text">路线智能规划</span>
        </div>
        <div class="feature-card">
          <span class="feature-icon">📄</span>
          <span class="feature-text">行程PDF导出</span>
        </div>
      </div>
    </div>
    
    <div class="cyber-circles">
      <div class="circle circle-1"></div>
      <div class="circle circle-2"></div>
      <div class="circle circle-3"></div>
    </div>
    
    <AppFooter />
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import AppFooter from '../components/AppFooter.vue'

// 设置页面标题和元数据
useHead({
  title: 'AI旅行管家 - 智能旅游规划平台',
  meta: [
    {
      name: 'description',
      content: 'AI旅行管家提供智能旅游规划、景点推荐、天气查询、酒店餐厅推荐等全方位旅游服务'
    },
    {
      name: 'keywords',
      content: 'AI旅行,智能旅游,行程规划,旅游助手,天气查询,景点推荐,酒店推荐,美食推荐'
    }
  ]
})

const router = useRouter()

const navigateTo = (path) => {
  router.push(path)
}
</script>

<style scoped>
@import url('https://fonts.googleapis.com/css2?family=Noto+Sans+SC:wght@400;500;700&display=swap');

/* 全局样式变量 */
:root {
  --primary-blue: #667eea;
  --primary-purple: #764ba2;
  --accent-pink: #f093fb;
  --dark-bg: #1a1a2e;
  --darker-bg: #16213e;
  --light-text: #edf7ff;
}

.home-container {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background: linear-gradient(135deg, var(--primary-blue) 0%, var(--primary-purple) 50%, var(--accent-pink) 100%);
  background-attachment: fixed;
  position: relative;
  overflow: hidden;
  font-family: 'Noto Sans SC', sans-serif;
}

/* 标题区域 */
.header {
  padding: 60px 20px 40px;
  text-align: center;
  position: relative;
  z-index: 2;
}

.glitch-wrapper {
  position: relative;
  display: inline-block;
  margin-bottom: 16px;
}

.glitch-title {
  font-size: 3rem;
  font-weight: 700;
  color: white;
  text-shadow: 
    0 0 10px rgba(255, 255, 255, 0.5),
    0 0 20px rgba(102, 126, 234, 0.5),
    0 0 40px rgba(102, 126, 234, 0.3);
  letter-spacing: 4px;
}

.subtitle {
  font-size: 1.1rem;
  color: rgba(255, 255, 255, 0.85);
  max-width: 600px;
  margin: 0 auto 20px;
  letter-spacing: 3px;
}

.cyber-line {
  height: 2px;
  width: 60%;
  max-width: 400px;
  margin: 0 auto;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.8), transparent);
}

.apps-container {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 40px;
  max-width: 900px;
  margin: 40px auto;
  padding: 0 20px;
  position: relative;
  z-index: 2;
}

.app-card {
  width: 320px;
  background: rgba(255, 255, 255, 0.95);
  border-radius: 20px;
  box-shadow: 0 15px 50px rgba(0, 0, 0, 0.2);
  padding: 30px;
  cursor: pointer;
  transition: all 0.4s cubic-bezier(0.175, 0.885, 0.32, 1.275);
  display: flex;
  flex-direction: column;
  align-items: center;
  position: relative;
  overflow: hidden;
}

.card-glow {
  position: absolute;
  top: -50%;
  left: -50%;
  width: 200%;
  height: 200%;
  background: radial-gradient(
    circle at center,
    rgba(102, 126, 234, 0.1) 0%,
    transparent 70%
  );
  opacity: 0;
  transition: opacity 0.5s;
  pointer-events: none;
}

.app-card:hover {
  transform: translateY(-10px) scale(1.02);
  box-shadow: 0 25px 60px rgba(102, 126, 234, 0.3);
}

.app-card:hover .card-glow {
  opacity: 1;
}

.app-icon {
  font-size: 4rem;
  margin-bottom: 20px;
  width: 100px;
  height: 100px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  position: relative;
  z-index: 1;
}

.travel-icon {
  background: linear-gradient(135deg, var(--primary-blue), var(--primary-purple));
  box-shadow: 0 10px 30px rgba(102, 126, 234, 0.4);
}

.community-icon {
  background: linear-gradient(135deg, #f093fb, #f5576c);
  box-shadow: 0 10px 30px rgba(240, 147, 251, 0.4);
}

.app-info {
  text-align: center;
  margin-bottom: 25px;
  width: 100%;
}

.app-title {
  font-size: 1.5rem;
  font-weight: bold;
  color: #333;
  margin-bottom: 10px;
}

.app-desc {
  font-size: 0.95rem;
  color: #666;
  line-height: 1.6;
}

.app-button {
  background: linear-gradient(135deg, var(--primary-blue), var(--primary-purple));
  color: white;
  padding: 12px 30px;
  border-radius: 25px;
  font-weight: 500;
  transition: all 0.3s;
  display: flex;
  align-items: center;
  gap: 8px;
}

.app-button:hover {
  transform: scale(1.05);
  box-shadow: 0 8px 25px rgba(102, 126, 234, 0.4);
}

.btn-icon {
  transition: transform 0.3s;
}

.app-button:hover .btn-icon {
  transform: translateX(5px);
}

/* 功能特点区域 */
.features-section {
  padding: 40px 20px;
  position: relative;
  z-index: 2;
}

.section-title {
  text-align: center;
  color: white;
  font-size: 1.5rem;
  margin-bottom: 30px;
  text-shadow: 0 2px 10px rgba(0, 0, 0, 0.2);
}

.features-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 15px;
  max-width: 800px;
  margin: 0 auto;
}

.feature-card {
  background: rgba(255, 255, 255, 0.15);
  backdrop-filter: blur(10px);
  border-radius: 12px;
  padding: 20px 15px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  transition: all 0.3s;
}

.feature-card:hover {
  background: rgba(255, 255, 255, 0.25);
  transform: translateY(-3px);
}

.feature-icon {
  font-size: 2rem;
}

.feature-text {
  color: white;
  font-size: 0.9rem;
  text-align: center;
}

/* 背景圆圈动画 */
.cyber-circles {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  overflow: hidden;
  z-index: 1;
}

.circle {
  position: absolute;
  border-radius: 50%;
  opacity: 0.1;
}

.circle-1 {
  width: 400px;
  height: 400px;
  top: -150px;
  right: -150px;
  background: linear-gradient(135deg, white, transparent);
  animation: float 20s infinite alternate;
}

.circle-2 {
  width: 600px;
  height: 600px;
  bottom: -250px;
  left: -250px;
  background: linear-gradient(135deg, white, transparent);
  animation: float 25s infinite alternate-reverse;
}

.circle-3 {
  width: 300px;
  height: 300px;
  top: 50%;
  right: 10%;
  background: linear-gradient(135deg, white, transparent);
  animation: float 15s infinite alternate;
}

@keyframes float {
  0% {
    transform: translate(0, 0) rotate(0deg);
  }
  100% {
    transform: translate(30px, 30px) rotate(5deg);
  }
}

/* 响应式设计 */
@media (max-width: 768px) {
  .glitch-title {
    font-size: 2.2rem;
  }
  
  .subtitle {
    font-size: 1rem;
  }
  
  .apps-container {
    gap: 25px;
    margin: 30px auto;
  }
  
  .app-card {
    width: 100%;
    max-width: 350px;
    padding: 25px;
  }
  
  .app-icon {
    font-size: 3rem;
    width: 80px;
    height: 80px;
  }
  
  .features-grid {
    grid-template-columns: repeat(3, 1fr);
    gap: 10px;
  }
  
  .feature-card {
    padding: 15px 10px;
  }
  
  .feature-icon {
    font-size: 1.5rem;
  }
  
  .feature-text {
    font-size: 0.8rem;
  }
}

@media (max-width: 480px) {
  .header {
    padding: 40px 15px 30px;
  }
  
  .glitch-title {
    font-size: 1.8rem;
    letter-spacing: 2px;
  }
  
  .subtitle {
    font-size: 0.9rem;
    letter-spacing: 2px;
  }
  
  .apps-container {
    padding: 0 15px;
  }
  
  .app-card {
    padding: 20px;
  }
  
  .app-icon {
    font-size: 2.5rem;
    width: 70px;
    height: 70px;
    margin-bottom: 15px;
  }
  
  .app-title {
    font-size: 1.3rem;
  }
  
  .app-desc {
    font-size: 0.9rem;
  }
  
  .features-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
