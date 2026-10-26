/** 登录态存储与带鉴权头的请求封装 */

const AUTH_KEY = 'mathbuddy.auth.v1'

export function loadAuth() {
  try {
    const raw = localStorage.getItem(AUTH_KEY)
    const data = raw ? JSON.parse(raw) : null
    return data && data.token && data.user && data.user.id ? data : null
  } catch {
    return null
  }
}

export function saveAuth(auth) {
  localStorage.setItem(AUTH_KEY, JSON.stringify(auth))
}

export function clearAuth() {
  localStorage.removeItem(AUTH_KEY)
}

/** 给普通请求和 SSE 请求统一补 Authorization */
export function authHeaders(extra = {}) {
  const auth = loadAuth()
  return auth ? { ...extra, Authorization: `Bearer ${auth.token}` } : { ...extra }
}

/** 任何接口返回 401 时通知 App 回到登录页 */
export function notifyUnauthorized() {
  window.dispatchEvent(new CustomEvent('mathbuddy:unauthorized'))
}

export async function apiFetch(url, options = {}) {
  const res = await fetch(url, {
    ...options,
    headers: authHeaders(options.headers || {})
  })
  if (res.status === 401) notifyUnauthorized()
  return res
}

export async function updateProfile(nickname, avatar) {
  const res = await apiFetch('/api/auth/profile', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ nickname, avatar })
  })
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    throw new Error(data.error || `保存失败（HTTP ${res.status}）`)
  }
  const data = await res.json()
  const user = data.user
  if (user) {
    const auth = loadAuth()
    if (auth) saveAuth({ ...auth, user })
  }
  return user
}

export async function uploadAvatar(file) {
  const form = new FormData()
  form.append('file', file)
  const res = await apiFetch('/api/auth/avatar', {
    method: 'POST',
    body: form
  })
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    throw new Error(data.error || `上传失败（HTTP ${res.status}）`)
  }
  return await res.json()
}
