<template>
  <section class="map-card">
    <header class="map-toolbar">
      <div>
        <strong>行程地图</strong>
        <span>{{ mapPlan.destination }}</span>
      </div>
      <div class="mode-control">
        <label for="travel-mode">交通方式</label>
        <select id="travel-mode" v-model="selectedMode" @change="requestModeChange">
          <option value="TRANSIT">公交</option>
          <option value="DRIVING">驾车</option>
          <option value="WALKING">步行</option>
          <option value="BICYCLING">骑行</option>
        </select>
      </div>
    </header>

    <div class="day-tabs">
      <button :class="{ active: activeDay === 0 }" @click="activeDay = 0">全部</button>
      <button v-for="day in mapPlan.days" :key="day.day"
              :class="{ active: activeDay === day.day }" @click="activeDay = day.day">
        第{{ day.day }}天
      </button>
    </div>

    <div v-if="mapError" class="map-error">{{ mapError }}</div>
    <div ref="mapContainer" class="map-canvas" :class="{ hidden: mapError }"></div>

    <div class="poi-list">
      <article v-for="item in visibleStops" :key="`${item.day}-${item.stop.poiId}-${item.stop.order}`" class="poi-card">
        <img v-if="item.stop.photos?.[0]?.url" :src="item.stop.photos[0].url"
             :alt="item.stop.photos[0].title || item.stop.name" loading="lazy" referrerpolicy="no-referrer" />
        <div class="poi-copy">
          <div><b>{{ item.day }}-{{ item.stop.order }}</b> {{ item.stop.name }}</div>
          <small>{{ item.stop.address || item.stop.category }}</small>
          <a :href="amapMarkerUrl(item.stop)" target="_blank" rel="noopener noreferrer">在高德中打开</a>
        </div>
      </article>
    </div>
    <p v-for="warning in mapPlan.warnings || []" :key="warning" class="map-warning">{{ warning }}</p>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { loadAmap } from '../utils/amap'

const props = defineProps({ mapPlan: { type: Object, required: true } })
const emit = defineEmits(['change-mode'])
const mapContainer = ref(null)
const mapError = ref('')
const activeDay = ref(0)
const initialMode = props.mapPlan.days?.flatMap(day => day.legs || [])[0]?.mode || 'TRANSIT'
const selectedMode = ref(initialMode)
let map
let AMapApi
let overlays = []

const visibleDays = computed(() => activeDay.value === 0
  ? props.mapPlan.days || []
  : (props.mapPlan.days || []).filter(day => day.day === activeDay.value))

const visibleStops = computed(() => visibleDays.value.flatMap(day =>
  (day.stops || []).map(stop => ({ day: day.day, stop }))))

const colorForDay = day => ['#1677ff', '#12a182', '#fa8c16', '#722ed1', '#eb2f96'][(day - 1) % 5]

const renderMap = async () => {
  if (!mapContainer.value) return
  try {
    AMapApi ||= await loadAmap()
    map ||= new AMapApi.Map(mapContainer.value, { zoom: 12, viewMode: '2D' })
    map.remove(overlays)
    overlays = []
    visibleDays.value.forEach(day => {
      const color = colorForDay(day.day)
      ;(day.stops || []).forEach(stop => {
        if (!stop.location) return
        const marker = new AMapApi.Marker({
          position: [stop.location.lng, stop.location.lat],
          title: stop.name,
          content: `<div class="travelmind-marker" style="background:${color}">${day.day}-${stop.order}</div>`,
          anchor: 'center'
        })
        marker.on('click', () => {
          const photo = stop.photos?.[0]?.url
          const html = `<div class="travelmind-info">${photo ? `<img src="${escapeHtml(photo)}" alt="">` : ''}<b>${escapeHtml(stop.name)}</b><p>${escapeHtml(stop.address || '')}</p></div>`
          new AMapApi.InfoWindow({ content: html, offset: new AMapApi.Pixel(0, -18) })
            .open(map, [stop.location.lng, stop.location.lat])
        })
        overlays.push(marker)
      })
      ;(day.legs || []).forEach(leg => {
        const path = (leg.polyline || []).map(point => [point.lng, point.lat])
        if (path.length < 2) return
        overlays.push(new AMapApi.Polyline({ path, strokeColor: color, strokeWeight: 6,
          strokeOpacity: 0.85, showDir: true, lineJoin: 'round' }))
      })
    })
    map.add(overlays)
    if (overlays.length) map.setFitView(overlays, false, [45, 45, 45, 45], 16)
    mapError.value = ''
  } catch (error) {
    mapError.value = `${error.message}；下方仍可查看景点并跳转高德。`
  }
}

const escapeHtml = value => String(value || '').replace(/[&<>'"]/g, char => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;'
}[char]))

const amapMarkerUrl = stop => {
  const point = stop.location || {}
  return `https://uri.amap.com/marker?position=${point.lng},${point.lat}&name=${encodeURIComponent(stop.name)}&src=travelmind&coordinate=gaode&callnative=1`
}

const requestModeChange = () => {
  if (selectedMode.value !== initialMode) emit('change-mode', selectedMode.value)
}

watch(activeDay, async () => { await nextTick(); renderMap() })
watch(() => props.mapPlan, async () => { await nextTick(); renderMap() }, { deep: true })
onMounted(renderMap)
onBeforeUnmount(() => map?.destroy())
</script>

<style scoped>
.map-card { margin-top: 14px; padding: 12px; background: #fff; border-radius: 14px; border: 1px solid #dfe7e4; min-width: min(680px, 72vw); }
.map-toolbar { display: flex; justify-content: space-between; gap: 12px; align-items: center; margin-bottom: 10px; }
.map-toolbar strong { display: block; font-size: 16px; }.map-toolbar span,.mode-control label { color: #697875; font-size: 12px; }
.mode-control { display: flex; gap: 6px; align-items: center; }.mode-control select { border: 1px solid #cbd8d4; border-radius: 8px; padding: 5px 8px; background: white; }
.day-tabs { display: flex; gap: 6px; overflow-x: auto; margin-bottom: 10px; }.day-tabs button { border: 0; border-radius: 16px; padding: 5px 11px; cursor: pointer; white-space: nowrap; }.day-tabs button.active { background: #1677ff; color: #fff; }
.map-canvas { width: 100%; height: 380px; border-radius: 10px; overflow: hidden; }.map-canvas.hidden { display: none; }.map-error,.map-warning { color: #9a5b00; background: #fff7e6; padding: 8px; border-radius: 8px; font-size: 12px; }
.poi-list { display: grid; grid-template-columns: repeat(auto-fit,minmax(180px,1fr)); gap: 8px; margin-top: 10px; }.poi-card { display: flex; gap: 8px; min-width: 0; background: #f5f8f7; border-radius: 9px; overflow: hidden; }.poi-card img { width: 72px; height: 72px; object-fit: cover; }.poi-copy { padding: 8px; min-width: 0; font-size: 13px; }.poi-copy small { display: block; color: #73807d; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.poi-copy a { display: inline-block; margin-top: 4px; color: #1677ff; text-decoration: none; font-size: 12px; }
:global(.travelmind-marker) { color: white; width: 34px; height: 34px; display: grid; place-items: center; border: 3px solid white; border-radius: 50%; box-shadow: 0 2px 8px #0005; font-weight: 700; }
:global(.travelmind-info) { width: 190px; }.travelmind-info img,:global(.travelmind-info img) { width: 100%; height: 90px; object-fit: cover; border-radius: 6px; }.travelmind-info p,:global(.travelmind-info p) { margin: 4px 0 0; color: #68736f; }
@media (max-width: 768px) { .map-card { min-width: 0; width: calc(100vw - 82px); }.map-canvas { height: 300px; }.map-toolbar { align-items: flex-start; flex-direction: column; } }
</style>
