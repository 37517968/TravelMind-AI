<template>
  <main class="page">
    <router-link to="/travel-agent">← 返回旅行 Agent</router-link>
    <section class="card">
      <p class="eyebrow">CROSS-CONVERSATION MEMORY</p>
      <h1>旅行偏好</h1>
      <p class="hint">这里只保存你明确填写的长期偏好，并在你的不同会话间共享；具体行程、聊天内容和工具结果不会跨会话共享。</p>
      <form @submit.prevent="save">
        <label>旅行风格<input v-model.trim="form.travelStyle" placeholder="例如：人文、自然、亲子、摄影" /></label>
        <label>行程节奏<select v-model="form.pace"><option value="">未设置</option><option>轻松</option><option>适中</option><option>紧凑</option></select></label>
        <label>常用交通<input v-model.trim="form.transport" placeholder="例如：公共交通优先" /></label>
        <label>饮食偏好<input v-model.trim="form.dietary" placeholder="例如：清淡、不吃辣、素食" /></label>
        <label>住宿档次<input v-model.trim="form.hotelLevel" placeholder="例如：舒适型、四星" /></label>
        <label>无障碍需求<input v-model.trim="form.accessibility" placeholder="没有可留空" /></label>
        <label>其他长期偏好<textarea v-model.trim="form.notes" rows="4" maxlength="500" placeholder="只填写希望长期记住的信息"></textarea></label>
        <p v-if="message" :class="['message', error && 'error']">{{ message }}</p>
        <button :disabled="saving">{{ saving ? '保存中…' : '保存偏好' }}</button>
      </form>
    </section>
  </main>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { getTravelPreferences, saveTravelPreferences } from '../api'
const form = reactive({ travelStyle: '', pace: '', transport: '', dietary: '', hotelLevel: '', accessibility: '', notes: '' })
const saving = ref(false), message = ref(''), error = ref(false)
onMounted(async () => { const { data } = await getTravelPreferences(); Object.assign(form, data || {}) })
const save = async () => {
  saving.value = true; message.value = ''; error.value = false
  try { await saveTravelPreferences({ ...form }); message.value = '偏好已保存，将用于你之后创建的旅行任务。' }
  catch (e) { error.value = true; message.value = e?.response?.data?.message || '保存失败' }
  finally { saving.value = false }
}
</script>

<style scoped>
.page { min-height:100vh; padding:90px 20px 40px; color:#26324a; background:linear-gradient(145deg,#eef2ff,#ecfdf8); }
.page>a { display:block; max-width:680px; margin:0 auto 18px; color:#6153b6; }
.card { max-width:680px; margin:auto; padding:34px; border-radius:24px; background:rgba(255,255,255,.92); box-shadow:0 24px 70px #43447b1f; }
.eyebrow { color:#7561cc; font-size:11px; font-weight:800; letter-spacing:1.5px; } h1{margin:8px 0}.hint{color:#727d90;line-height:1.7}
form{display:grid;grid-template-columns:1fr 1fr;gap:17px;margin-top:25px} label{display:grid;gap:7px;font-size:13px;font-weight:700} label:last-of-type{grid-column:1/-1}
input,select,textarea{width:100%;padding:12px;border:1px solid #dbe0eb;border-radius:11px;background:white;color:#26324a;font:inherit} textarea{resize:vertical}
button{grid-column:1/-1;padding:13px;border:0;border-radius:12px;color:white;background:linear-gradient(135deg,#667eea,#7654c6);font-weight:750}.message{grid-column:1/-1;color:#16765e}.error{color:#b33}
@media(max-width:600px){.card{padding:25px 20px}form{grid-template-columns:1fr}label:last-of-type{grid-column:auto}}
</style>
