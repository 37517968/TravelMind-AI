<template>
  <main class="page">
    <router-link to="/community">← 返回旅行社区</router-link>
    <p v-if="loading" class="status">正在加载方案…</p>
    <p v-else-if="error" class="status error">{{ error }}</p>
    <article v-else-if="plan">
      <img v-if="plan.coverImage" class="cover" :src="plan.coverImage" :alt="plan.title" />
      <h1>{{ plan.title }}</h1>
      <div class="meta">
        <span>📍 {{ plan.destination }}</span>
        <span>🗓️ {{ plan.days }} 天</span>
        <span v-if="plan.budget">💰 ¥{{ plan.budget }}</span>
        <span>👍 {{ plan.likeCount || 0 }}</span>
      </div>
      <p v-if="plan.summary" class="summary">{{ plan.summary }}</p>
      <div class="body">{{ plan.content }}</div>
    </article>
  </main>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getTravelPlan } from '../api'

const route = useRoute()
const plan = ref(null)
const loading = ref(true)
const error = ref('')

onMounted(async () => {
  try {
    const response = await getTravelPlan(route.params.id)
    plan.value = response.data?.data
    if (!plan.value) error.value = '没有找到该旅行方案。'
  } catch (e) {
    error.value = e.response?.data?.message || '方案加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.page { max-width: 860px; margin: 0 auto; padding: 32px 20px 70px; color: #263238; }
article { margin-top: 24px; }
.cover { width: 100%; max-height: 420px; object-fit: cover; border-radius: 16px; }
h1 { margin: 24px 0 12px; font-size: clamp(28px, 5vw, 42px); }
.meta { display: flex; flex-wrap: wrap; gap: 18px; color: #637172; }
.summary { margin: 26px 0; padding: 18px; border-left: 4px solid #15977e; background: #eef9f6; }
.body { white-space: pre-wrap; line-height: 1.8; font-size: 16px; }
.status { padding: 60px 0; text-align: center; color: #637172; }
.error { color: #b42318; }
</style>
