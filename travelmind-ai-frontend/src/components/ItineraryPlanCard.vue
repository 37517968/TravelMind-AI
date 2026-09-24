<template>
  <section v-if="days.length" class="plan-card">
    <header><span>🗺️</span><div><strong>路线速览</strong><small>{{ result?.mapPlan?.destination }} · {{ stopCount }} 个地点</small></div></header>
    <div v-if="photos.length" class="photo-grid">
      <figure v-for="photo in photos" :key="photo.id">
        <img :src="photo.url" :alt="photo.name" loading="lazy" referrerpolicy="no-referrer" />
        <figcaption>📍 {{ photo.name }}</figcaption>
      </figure>
    </div>
    <div v-for="day in days" :key="day.day" class="day-block">
      <h4>☀️ 第 {{ day.day }} 天</h4>
      <div class="table-wrap"><table><thead><tr><th>顺序</th><th>景点</th><th>地址</th><th>下一程</th></tr></thead>
        <tbody><tr v-for="(stop, index) in day.stops" :key="stop.poiId || stop.name">
          <td>{{ stop.order || index + 1 }}</td><td>📍 {{ stop.name }}</td><td>{{ stop.address || '以地图定位为准' }}</td>
          <td>{{ legText(day.legs?.[index]) }}</td>
        </tr></tbody></table></div>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'
const props = defineProps({ result: { type: Object, default: () => ({}) } })
const days = computed(() => props.result?.mapPlan?.days || [])
const stopCount = computed(() => days.value.reduce((sum, day) => sum + (day.stops?.length || 0), 0))
const photos = computed(() => days.value.flatMap(day => day.stops || []).map(stop => ({
  id: stop.poiId || stop.name, name: stop.name, url: stop.photos?.[0]?.url
})).filter(item => item.url).slice(0, 6))
const legText = leg => {
  if (!leg) return '—'
  const labels = { TRANSIT: '🚌 公交', DRIVING: '🚗 驾车', WALKING: '🚶 步行', BICYCLING: '🚲 骑行' }
  const minutes = leg.durationSeconds ? ` · ${Math.max(1, Math.round(leg.durationSeconds / 60))} 分钟` : ''
  return `${labels[leg.mode] || '➡️ 出行'}${minutes}`
}
</script>

<style scoped>
.plan-card { margin-top: 14px; padding: 14px; background: linear-gradient(180deg, #fff, #f8fbff); border: 1px solid #dce8f5; border-radius: 16px; }
header { display: flex; gap: 9px; align-items: center; margin-bottom: 12px; color: #17324d; }
header > span { font-size: 25px; } header div { display: flex; flex-direction: column; } header small { color: #718096; margin-top: 2px; }
.photo-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-bottom: 14px; }
figure { margin: 0; position: relative; height: 110px; overflow: hidden; border-radius: 11px; background: #eaf0f6; }
figure img { width: 100%; height: 100%; object-fit: cover; }
figcaption { position: absolute; inset: auto 0 0; padding: 18px 7px 6px; color: #fff; font-size: 12px; background: linear-gradient(transparent, rgba(0,0,0,.72)); }
.day-block + .day-block { margin-top: 12px; } h4 { margin: 0 0 7px; color: #274c72; }
.table-wrap { overflow-x: auto; } table { width: 100%; border-collapse: collapse; background: #fff; font-size: 13px; }
th, td { padding: 8px; text-align: left; border: 1px solid #e3eaf2; } th { color: #36536f; background: #edf5ff; white-space: nowrap; }
@media (max-width: 620px) { .photo-grid { grid-template-columns: repeat(2, 1fr); } figure { height: 95px; } }
</style>
