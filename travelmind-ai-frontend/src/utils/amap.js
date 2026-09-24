let loadingPromise

export const loadAmap = () => {
  const key = import.meta.env.VITE_AMAP_JS_API_KEY
  if (!key) return Promise.reject(new Error('未配置 VITE_AMAP_JS_API_KEY'))
  if (globalThis.AMap) return Promise.resolve(globalThis.AMap)
  if (loadingPromise) return loadingPromise

  const securityJsCode = import.meta.env.VITE_AMAP_JS_SECURITY_CODE
  if (securityJsCode) globalThis._AMapSecurityConfig = { securityJsCode }
  loadingPromise = new Promise((resolve, reject) => {
    const callback = `travelmindAmapLoaded${Date.now()}`
    globalThis[callback] = () => {
      delete globalThis[callback]
      resolve(globalThis.AMap)
    }
    const script = document.createElement('script')
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}&callback=${callback}`
    script.async = true
    script.onerror = () => {
      delete globalThis[callback]
      loadingPromise = null
      reject(new Error('高德地图脚本加载失败'))
    }
    document.head.appendChild(script)
  })
  return loadingPromise
}
