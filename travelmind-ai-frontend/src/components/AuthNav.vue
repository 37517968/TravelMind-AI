<template>
  <nav class="auth-nav" aria-label="用户导航">
    <template v-if="authState.user">
      <span class="avatar">{{ avatarText }}</span>
      <span class="identity">
        <strong>{{ authState.user.userName || authState.user.userAccount }}</strong>
        <small>{{ authState.user.userRole === 'admin' ? '管理员' : '旅行者' }}</small>
      </span>
      <router-link class="secondary preferences" to="/preferences">偏好</router-link>
      <button type="button" :disabled="authState.loading" @click="signOut">退出</button>
    </template>
    <template v-else>
      <router-link class="secondary" to="/register">注册</router-link>
      <router-link class="primary" :to="loginTarget">登录</router-link>
    </template>
  </nav>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authState, ensureAuth, logout } from '../auth'

const route = useRoute()
const router = useRouter()
const avatarText = computed(() => (authState.user?.userName || authState.user?.userAccount || '旅').slice(0, 1))
const loginTarget = computed(() => ({ path: '/login', query: route.path === '/' ? {} : { redirect: route.fullPath } }))

const signOut = async () => {
  try { await logout() } catch { /* Session 已失效时本地状态仍应退出。 */ }
  if (route.meta.requiresAuth) router.replace('/login')
}

onMounted(ensureAuth)
</script>

<style scoped>
.auth-nav { position: fixed; z-index: 1000; top: 18px; right: 22px; display: flex; align-items: center; gap: 10px; min-height: 44px; padding: 7px 9px; color: #243046; background: rgba(255,255,255,.94); border: 1px solid rgba(255,255,255,.75); border-radius: 15px; box-shadow: 0 12px 35px rgba(26,33,65,.16); backdrop-filter: blur(16px); }
.avatar { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 11px; color: white; font-weight: 800; background: linear-gradient(135deg,#667eea,#8b5cf6); }
.identity { display: flex; flex-direction: column; max-width: 130px; line-height: 1.15; }
.identity strong { overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.identity small { margin-top: 3px; color: #7a8497; font-size: 11px; }
a, button { border: 0; border-radius: 10px; padding: 8px 13px; font: inherit; font-size: 13px; cursor: pointer; }
.primary { color: white; background: linear-gradient(135deg,#667eea,#764ba2); }
.secondary, button { color: #515b70; background: #f0f2f8; }
button:disabled { cursor: wait; opacity: .6; }
@media (max-width: 560px) { .auth-nav { top: 10px; right: 10px; } .identity, .preferences { display: none; } }
</style>
