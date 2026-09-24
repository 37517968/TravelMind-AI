<template>
  <section class="route-options" aria-label="推荐景点路线">
    <article v-for="route in routes" :key="route.id" class="route-option">
      <div class="route-cover" v-if="cover(route)">
        <img :src="cover(route)" :alt="`${route.title}景点图片`" loading="lazy" referrerpolicy="no-referrer" />
      </div>
      <div class="route-content">
        <h4>{{ route.emoji || '🗺️' }} {{ route.title }} <small v-if="route.destination">· {{ route.destination }}</small></h4>
        <p>{{ route.summary }}</p>
        <div class="route-stops">
          <span v-for="(stop, index) in route.attractions" :key="stop.id || stop.name">
            {{ index + 1 }}. {{ stop.name }}
          </span>
        </div>
        <button type="button" @click="$emit('select', route)">选择这条路线</button>
      </div>
    </article>
  </section>
</template>

<script setup>
defineProps({ routes: { type: Array, default: () => [] } })
defineEmits(['select'])
const cover = route => route?.attractions?.find(item => item.photoUrl)?.photoUrl || ''
</script>

<style scoped>
.route-options { display: grid; gap: 12px; margin-top: 12px; }
.route-option { display: grid; grid-template-columns: 118px 1fr; overflow: hidden; background: #fff; border: 1px solid #dce6f3; border-radius: 14px; box-shadow: 0 5px 18px rgba(43, 73, 117, .08); }
.route-cover { min-height: 142px; background: linear-gradient(135deg, #dbeafe, #dcfce7); }
.route-cover img { width: 100%; height: 100%; object-fit: cover; display: block; }
.route-content { padding: 12px 14px; }
h4 { margin: 0 0 5px; color: #19324d; font-size: 16px; }
h4 small { color: #64748b; font-weight: 500; }
p { margin: 0 0 8px; color: #667085; font-size: 13px; line-height: 1.5; }
.route-stops { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 10px; }
.route-stops span { padding: 4px 8px; border-radius: 999px; background: #eef5ff; color: #31577f; font-size: 12px; }
button { border: 0; border-radius: 9px; padding: 8px 13px; color: #fff; background: linear-gradient(135deg, #3b82f6, #6366f1); cursor: pointer; font-weight: 600; }
button:hover { filter: brightness(1.06); transform: translateY(-1px); }
@media (max-width: 620px) { .route-option { grid-template-columns: 1fr; } .route-cover { height: 130px; min-height: 0; } }
</style>
