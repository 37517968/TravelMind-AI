<template>
  <main class="auth-page">
    <div class="orb orb-one"></div>
    <div class="orb orb-two"></div>
    <router-link class="brand" to="/">
      <span>✦</span>
      <strong>TravelMind AI</strong>
    </router-link>

    <section class="auth-card">
      <div class="intro">
        <span class="eyebrow">AI TRAVEL OPERATING SYSTEM</span>
        <h1>{{ isRegister ? '创建旅行账户' : '欢迎回来' }}</h1>
        <p>{{ isRegister ? '保存规划、分享路线，并让每次旅行偏好持续沉淀。' : '登录后继续你的旅行规划与知识探索。' }}</p>
      </div>

      <form @submit.prevent="submit">
        <label v-if="isRegister">
          <span>昵称</span>
          <input v-model.trim="form.userName" autocomplete="name" maxlength="40" placeholder="怎么称呼你" />
        </label>
        <label>
          <span>账号</span>
          <input v-model.trim="form.userAccount" autocomplete="username" minlength="4" maxlength="32" required placeholder="至少 4 位字符" />
        </label>
        <label>
          <span>密码</span>
          <div class="password-field">
            <input v-model="form.userPassword" :type="showPassword ? 'text' : 'password'" :autocomplete="isRegister ? 'new-password' : 'current-password'" minlength="6" maxlength="72" required placeholder="至少 6 位字符" />
            <button type="button" class="reveal" @click="showPassword = !showPassword">{{ showPassword ? '隐藏' : '显示' }}</button>
          </div>
        </label>
        <label v-if="isRegister">
          <span>确认密码</span>
          <input v-model="form.checkPassword" :type="showPassword ? 'text' : 'password'" autocomplete="new-password" minlength="6" maxlength="72" required placeholder="再次输入密码" />
        </label>

        <p v-if="error" class="message error" role="alert">{{ error }}</p>
        <button class="submit" type="submit" :disabled="authState.loading">
          {{ authState.loading ? '正在处理…' : (isRegister ? '注册并登录' : '登录') }}
          <span>→</span>
        </button>
      </form>

      <p class="switch">
        {{ isRegister ? '已有账户？' : '还没有账户？' }}
        <router-link :to="isRegister ? '/login' : '/register'">{{ isRegister ? '直接登录' : '立即注册' }}</router-link>
      </p>
    </section>
  </main>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authState, login, registerAndLogin } from '../auth'

const route = useRoute()
const router = useRouter()
const isRegister = computed(() => route.name === 'Register')
const showPassword = ref(false)
const error = ref('')
const form = reactive({ userName: '', userAccount: '', userPassword: '', checkPassword: '' })

watch(isRegister, () => {
  error.value = ''
  form.userPassword = ''
  form.checkPassword = ''
})

const submit = async () => {
  error.value = ''
  if (isRegister.value && form.userPassword !== form.checkPassword) {
    error.value = '两次输入的密码不一致'
    return
  }
  try {
    if (isRegister.value) {
      await registerAndLogin({ ...form })
    } else {
      await login({ userAccount: form.userAccount, userPassword: form.userPassword })
    }
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/')
      ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch (failure) {
    error.value = failure?.message || '登录失败，请稍后重试'
  }
}
</script>

<style scoped>
.auth-page { position: relative; display: grid; min-height: 100vh; place-items: center; overflow: hidden; padding: 92px 20px 40px; color: #1d2840; background: linear-gradient(145deg,#eef2ff 0%,#f8f5ff 44%,#ecfdf8 100%); }
.brand { position: absolute; z-index: 2; top: 28px; left: 34px; display: flex; align-items: center; gap: 9px; color: #303b59; font-size: 17px; letter-spacing: .2px; }
.brand span { color: #7559d9; font-size: 23px; }
.orb { position: absolute; border-radius: 50%; filter: blur(2px); opacity: .55; }
.orb-one { top: -130px; right: -90px; width: 420px; height: 420px; background: radial-gradient(circle,#a78bfa 0%,transparent 70%); }
.orb-two { bottom: -170px; left: -110px; width: 470px; height: 470px; background: radial-gradient(circle,#5eead4 0%,transparent 70%); }
.auth-card { position: relative; z-index: 1; width: min(100%, 470px); padding: 38px; border: 1px solid rgba(255,255,255,.9); border-radius: 26px; background: rgba(255,255,255,.82); box-shadow: 0 30px 80px rgba(68,66,116,.16); backdrop-filter: blur(20px); }
.intro { margin-bottom: 27px; }
.eyebrow { color: #6c57c7; font-size: 11px; font-weight: 800; letter-spacing: 1.7px; }
h1 { margin: 10px 0 9px; font-size: 32px; letter-spacing: -.8px; }
.intro p { color: #727c90; line-height: 1.65; }
form { display: grid; gap: 17px; }
label > span { display: block; margin-bottom: 7px; color: #46516a; font-size: 13px; font-weight: 700; }
input { width: 100%; height: 48px; padding: 0 14px; color: #25304a; border: 1px solid #dce1ec; border-radius: 12px; outline: none; background: rgba(255,255,255,.9); font: inherit; transition: .2s; }
input:focus { border-color: #7864d8; box-shadow: 0 0 0 4px rgba(120,100,216,.12); }
.password-field { position: relative; }
.password-field input { padding-right: 68px; }
.reveal { position: absolute; top: 8px; right: 7px; height: 32px; padding: 0 9px; color: #6957be; border: 0; border-radius: 8px; background: #f0edff; }
.submit { display: flex; align-items: center; justify-content: space-between; height: 50px; margin-top: 5px; padding: 0 18px; color: white; border: 0; border-radius: 13px; background: linear-gradient(135deg,#667eea,#7654c6); box-shadow: 0 10px 24px rgba(102,126,234,.25); font: inherit; font-weight: 750; }
.submit:disabled { cursor: wait; opacity: .65; }
.message { padding: 11px 13px; border-radius: 10px; font-size: 13px; }
.error { color: #a23333; background: #fff0f0; }
.switch { margin-top: 23px; color: #788196; text-align: center; font-size: 14px; }
.switch a { color: #6652c0; font-weight: 750; }
@media (max-width: 520px) { .auth-card { padding: 29px 22px; border-radius: 21px; } .brand { left: 20px; } h1 { font-size: 28px; } }
</style>
