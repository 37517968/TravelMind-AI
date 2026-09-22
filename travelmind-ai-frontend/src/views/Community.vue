<template>
  <main class="page">
    <header class="toolbar">
      <div>
        <router-link to="/">← 返回首页</router-link>
        <h1>旅行社区</h1>
      </div>
      <form @submit.prevent="search">
        <input v-model.trim="keyword" placeholder="搜索目的地或旅行方案" />
        <button type="submit">搜索</button>
      </form>
    </header>

    <p v-if="loading" class="status">正在加载旅行方案…</p>
    <p v-else-if="error" class="status error">{{ error }}</p>
    <p v-else-if="plans.length === 0" class="status">暂时还没有旅行方案。</p>

    <section v-else class="grid">
      <router-link v-for="plan in plans" :key="plan.id" :to="`/plan/${plan.id}`" class="card">
        <img v-if="plan.coverImage" :src="plan.coverImage" :alt="plan.title" />
        <div class="content">
          <h2>{{ plan.title }}</h2>
          <p>{{ plan.summary || plan.content || '查看旅行方案详情' }}</p>
          <small>{{ plan.destination }} · {{ plan.days }} 天 · 👍 {{ plan.likeCount || 0 }}</small>
        </div>
      </router-link>
    </section>
  </main>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { listTravelPlans, searchTravelPlans } from '../api'

const plans = ref([])
const keyword = ref('')
const loading = ref(false)
const error = ref('')

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const response = keyword.value
      ? await searchTravelPlans(keyword.value)
      : await listTravelPlans({ page: 1, size: 20 })
    plans.value = response.data?.data || []
  } catch (e) {
    error.value = e.response?.data?.message || '旅行方案加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

const search = () => load()
onMounted(load)
</script>

<style scoped>
.page { max-width: 1100px; margin: 0 auto; padding: 32px 20px; color: #263238; }
.toolbar { display: flex; justify-content: space-between; gap: 24px; align-items: end; margin-bottom: 28px; }
h1 { margin: 10px 0 0; }
form { display: flex; gap: 8px; }
input { min-width: 260px; padding: 10px 12px; border: 1px solid #ccd7d9; border-radius: 8px; }
button { padding: 10px 18px; border: 0; border-radius: 8px; background: #15977e; color: white; cursor: pointer; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(270px, 1fr)); gap: 18px; }
.card { overflow: hidden; border: 1px solid #e4e9e9; border-radius: 14px; color: inherit; text-decoration: none; background: white; box-shadow: 0 5px 20px #173b3212; }
.card img { width: 100%; height: 160px; object-fit: cover; }
.content { padding: 18px; }
.content h2 { margin: 0 0 10px; font-size: 19px; }
.content p { height: 44px; overflow: hidden; color: #637172; }
.status { padding: 40px; text-align: center; color: #637172; }
.error { color: #b42318; }
@media (max-width: 680px) { .toolbar { align-items: stretch; flex-direction: column; } form, input { width: 100%; min-width: 0; } }
</style>
