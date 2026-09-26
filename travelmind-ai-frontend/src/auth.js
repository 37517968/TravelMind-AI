import { readonly, reactive } from 'vue'
import { getLoginUser, login as loginRequest, logout as logoutRequest, register as registerRequest } from './api'

const state = reactive({
  user: null,
  initialized: false,
  loading: false
})

let initialization

const responseData = response => {
  const payload = response?.data
  if (!payload || payload.code !== 0) {
    throw new Error(payload?.message || '请求失败，请稍后重试')
  }
  return payload.data
}

export const ensureAuth = async () => {
  if (state.initialized) return state.user
  if (initialization) return initialization
  initialization = (async () => {
    try {
      state.user = responseData(await getLoginUser())
    } catch {
      state.user = null
    } finally {
      state.initialized = true
      initialization = null
    }
    return state.user
  })()
  return initialization
}

export const login = async credentials => {
  state.loading = true
  try {
    state.user = responseData(await loginRequest(credentials))
    state.initialized = true
    return state.user
  } finally {
    state.loading = false
  }
}

export const registerAndLogin = async registration => {
  state.loading = true
  try {
    responseData(await registerRequest(registration))
    state.user = responseData(await loginRequest({
      userAccount: registration.userAccount,
      userPassword: registration.userPassword
    }))
    state.initialized = true
    return state.user
  } finally {
    state.loading = false
  }
}

export const logout = async () => {
  state.loading = true
  try {
    responseData(await logoutRequest())
  } finally {
    state.user = null
    state.initialized = true
    state.loading = false
  }
}

export const authState = readonly(state)
