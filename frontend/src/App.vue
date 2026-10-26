<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { fetchEventSource } from '@microsoft/fetch-event-source'
import ChatBubble from './components/ChatBubble.vue'
import StatsChart from './components/StatsChart.vue'
import MindMapPanel from './components/MindMapPanel.vue'
import ConversationPanel from './components/ConversationPanel.vue'
import MistakeBookPanel from './components/MistakeBookPanel.vue'
import QuizPanel from './components/QuizPanel.vue'
import ReportPanel from './components/ReportPanel.vue'
import LoginView from './components/LoginView.vue'
import {
  apiFetch,
  authHeaders,
  clearAuth,
  loadAuth,
  notifyUnauthorized,
  saveAuth,
  updateProfile,
  uploadAvatar
} from './api'

const GREETING =
  '同学你好，我是你的数思引航。**我不会直接把答案给你**，但会陪你把思路一步步想清楚。\n\n先说说：这道题你卡在哪一步了？是读不懂条件，还是不知道用哪个公式？'

function greetingMessage() {
  return {
    role: 'assistant',
    content: GREETING,
    streaming: false,
    variant: false,
    greeting: true
  }
}

const messages = ref([greetingMessage()])

const input = ref('')
const sending = ref(false)
const recommending = ref(false)
const stats = ref({})
const historyOpen = ref(false)
const mistakeOpen = ref(false)
const quizOpen = ref(false)
const reportOpen = ref(false)
const sessions = ref([])
const sessionId = ref('')
const mindmapOpen = ref(false)
const mindmapPoint = ref('')
const mindmapText = ref('')
const mindmapLoading = ref(false)
const mindmapError = ref('')
let mindmapController = null
let mindmapSeq = 0
const lastQuestion = ref('')
const scrollRef = ref(null)
const imageInputRef = ref(null)
const docInputRef = ref(null)
const pendingImage = ref('')
const pendingDoc = ref(null)
const docLoading = ref(false)
const attachError = ref('')
const lastAttachment = ref({ image: '', doc: null })

/* ---------------- 登录态：未登录先显示登录/注册页 ---------------- */
const authState = ref('checking')
const currentUser = ref(null)

const AVATAR_OPTIONS = [
  { key: 'preset-bear', image: '/presets/bear.jpg', label: '小胖熊', style: '软萌' },
  { key: 'preset-panther', image: '/presets/panther.jpg', label: '孤狼', style: '帅气' },
  { key: 'preset-cactus', image: '/presets/cactus.jpg', label: '仙人掌', style: '简约' },
  { key: 'preset-dino', image: '/presets/dino.jpg', label: '小恐龙', style: '治愈' },
  { key: 'preset-gameboy', image: '/presets/gameboy.jpg', label: '掌机', style: '极客' },
  { key: 'preset-ghost', image: '/presets/ghost.jpg', label: '小恶魔', style: '甜酷' },
  { key: 'preset-swan', image: '/presets/swan.jpg', label: '天鹅', style: '优雅' },
  { key: 'preset-planet', image: '/presets/planet.jpg', label: '星球眼', style: '奇幻' }
]

const profileOpen = ref(false)
const editNickname = ref('')
const editAvatar = ref('preset-bear')
const customAvatarPreview = ref('')
const uploadingAvatar = ref(false)
const savingProfile = ref(false)
const profileError = ref('')
const avatarInputRef = ref(null)

function isPresetAvatar(avatar) {
  return typeof avatar === 'string' && avatar.startsWith('preset-')
}

function isCustomAvatar(avatar) {
  return typeof avatar === 'string' && avatar.startsWith('avatar-') && avatar.includes('.')
}

function avatarUrl(avatar) {
  if (isPresetAvatar(avatar)) {
    const name = avatar.replace('preset-', '')
    return `/presets/${name}.jpg`
  }
  if (isCustomAvatar(avatar)) {
    return `/avatars/${avatar}`
  }
  return AVATAR_OPTIONS[0].image
}

function avatarMeta(key) {
  return AVATAR_OPTIONS.find((o) => o.key === key) || AVATAR_OPTIONS[0]
}

function displayName(user) {
  return user?.nickname || user?.username || ''
}

function startEditProfile() {
  editNickname.value = displayName(currentUser.value)
  const avatar = currentUser.value?.avatar || 'preset-bear'
  editAvatar.value = isPresetAvatar(avatar) || isCustomAvatar(avatar) ? avatar : 'preset-bear'
  customAvatarPreview.value = isCustomAvatar(avatar) ? avatarUrl(avatar) : ''
  profileError.value = ''
  profileOpen.value = true
}

function cancelEditProfile() {
  profileOpen.value = false
  profileError.value = ''
  customAvatarPreview.value = ''
}

async function onPickAvatarFile(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!file.type.startsWith('image/')) {
    profileError.value = '请选择图片文件（png、jpg、webp、gif）'
    return
  }
  if (file.size > 2 * 1024 * 1024) {
    profileError.value = '图片不能超过 2MB'
    return
  }
  uploadingAvatar.value = true
  profileError.value = ''
  try {
    const data = await uploadAvatar(file)
    editAvatar.value = data.avatar
    customAvatarPreview.value = URL.createObjectURL(file)
  } catch (err) {
    profileError.value = err?.message || '头像上传失败'
  } finally {
    uploadingAvatar.value = false
  }
}

function selectPresetAvatar(key) {
  editAvatar.value = key
  customAvatarPreview.value = ''
}

function removeCustomAvatar() {
  editAvatar.value = 'preset-bear'
  customAvatarPreview.value = ''
}

async function saveProfile() {
  if (savingProfile.value) return
  savingProfile.value = true
  profileError.value = ''
  try {
    const user = await updateProfile(editNickname.value, editAvatar.value)
    currentUser.value = user
    profileOpen.value = false
    customAvatarPreview.value = ''
  } catch (err) {
    profileError.value = err?.message || '保存失败，请重试'
  } finally {
    savingProfile.value = false
  }
}

const suggestions = [
  '这道导数题怎么求单调区间？',
  '圆锥曲线的离心率要怎么找？',
  '数列求和总是想不到方法'
]

const weakTags = computed(() => Object.entries(stats.value || {}).slice(0, 5))

const latestAssistantIndex = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    if (messages.value[i].role === 'assistant') return i
  }
  return -1
})

function scrollToBottom() {
  nextTick(() => {
    const el = scrollRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

async function loadStats() {
  try {
    const res = await apiFetch('/api/stats')
    if (!res.ok) return
    stats.value = await res.json()
  } catch {
    // 后端未启动时静默处理，不打断聊天
  }
}

/* ---------------- 账号与会话：token 存在 localStorage，打开页面自动校验 ---------------- */

function enterWorkspace() {
  authState.value = 'ready'
  loadStats()
  sessions.value = readSessions()
}

async function bootstrapAuth() {
  const auth = loadAuth()
  if (!auth) {
    authState.value = 'guest'
    return
  }

  try {
    const res = await fetch('/api/auth/me', { headers: authHeaders() })
    if (res.status === 401) {
      clearAuth()
      authState.value = 'guest'
      return
    }
    if (res.ok) {
      const data = await res.json()
      currentUser.value = data.user || auth.user
      enterWorkspace()
      return
    }
  } catch {
    // 后端暂时连不上时先用本地缓存进入，接口失败会再提示
  }

  currentUser.value = auth.user
  enterWorkspace()
}

function onAuthSuccess(data) {
  if (!data?.token || !data?.user) return
  saveAuth({ token: data.token, user: data.user })
  currentUser.value = data.user
  messages.value = [greetingMessage()]
  sessionId.value = ''
  lastQuestion.value = ''
  stats.value = {}
  migrateLegacySessions(data.user)
  enterWorkspace()
}

async function logout() {
  try {
    await fetch('/api/auth/logout', { method: 'POST', headers: authHeaders() })
  } catch {
    // token 已失效或后端未启动，本地照样清掉
  }
  clearAuth()
  currentUser.value = null
  authState.value = 'guest'
  messages.value = [greetingMessage()]
  sessions.value = []
  sessionId.value = ''
  lastQuestion.value = ''
  stats.value = {}
}

function onUnauthorized() {
  if (authState.value !== 'ready') return
  clearAuth()
  currentUser.value = null
  authState.value = 'guest'
  messages.value = [greetingMessage()]
  sessions.value = []
  sessionId.value = ''
  stats.value = {}
}

/* ---------------- 历史对话：存在 localStorage，刷新页面后还在 ---------------- */

const STORAGE_KEY = 'mathbuddy.sessions.v1'
const MAX_SESSIONS = 20

/** 历史对话按账号分开存，换号登录互不影响 */
function sessionStorageKey() {
  const id = currentUser.value?.id
  return id ? `${STORAGE_KEY}.${id}` : STORAGE_KEY
}

/** 演示账号 text 首次登录时，把改造前的旧全局记录迁到它名下 */
function migrateLegacySessions(user) {
  if (!user || (user.username || '').toLowerCase() !== 'text') return
  const key = sessionStorageKey()
  try {
    if (localStorage.getItem(key) != null) return
    const legacy = localStorage.getItem(STORAGE_KEY)
    if (legacy) localStorage.setItem(key, legacy)
  } catch {
    // 配额不足时忽略，不影响正常使用
  }
}

function readSessions() {
  try {
    const key = sessionStorageKey()
    const raw = localStorage.getItem(key)
    const list = raw ? JSON.parse(raw) : []
    return Array.isArray(list) ? list : []
  } catch {
    return []
  }
}

/** 图片 dataURL 可能很大，超出浏览器配额时逐条丢掉最旧的记录再重试 */
function writeSessions(list) {
  const key = sessionStorageKey()
  let data = list
  while (data.length) {
    try {
      localStorage.setItem(key, JSON.stringify(data))
      sessions.value = data
      return
    } catch {
      data = data.slice(0, data.length - 1)
    }
  }
  try {
    localStorage.removeItem(key)
  } catch {
    // 忽略
  }
  sessions.value = []
}

/** 把当前聊天存成一段历史；没有实际提问就不存 */
function saveCurrentSession() {
  if (authState.value !== 'ready') return
  const chat = messages.value.filter((m) => !m.greeting)
  if (!chat.length) return

  const firstUser = chat.find((m) => m.role === 'user')
  const title =
    (firstUser?.content || '图片/文档答疑').replace(/\s+/g, ' ').trim().slice(0, 20) || '新对话'
  const now = Date.now()

  if (!sessionId.value) {
    sessionId.value = `s-${now}-${Math.random().toString(36).slice(2, 7)}`
  }

  const existing = sessions.value.find((s) => s.id === sessionId.value)
  const record = {
    id: sessionId.value,
    title,
    createdAt: existing?.createdAt || now,
    updatedAt: now,
    messages: messages.value.map((m) => ({ ...m, streaming: false }))
  }

  const rest = sessions.value.filter((s) => s.id !== record.id)
  writeSessions([record, ...rest].slice(0, MAX_SESSIONS))
}

/** 开启新对话：先把当前这段存下来，再清空聊天区 */
function startNewChat() {
  if (sending.value || recommending.value) return
  saveCurrentSession()
  messages.value = [greetingMessage()]
  sessionId.value = ''
  lastQuestion.value = ''
  lastAttachment.value = { image: '', doc: null }
  pendingImage.value = ''
  pendingDoc.value = null
  attachError.value = ''
  historyOpen.value = false
  scrollToBottom()
}

/** 查看旧对话：载入选中的那段记录 */
function openSession(item) {
  if (sending.value || recommending.value) return
  if (!item || !Array.isArray(item.messages) || !item.messages.length) return

  messages.value = item.messages.map((m) => ({ ...m, streaming: false }))
  sessionId.value = item.id
  const lastUser = [...messages.value].reverse().find((m) => m.role === 'user')
  lastQuestion.value = lastUser?.content || ''
  lastAttachment.value = { image: lastUser?.image || '', doc: null }
  historyOpen.value = false
  scrollToBottom()
}

function buildHistory() {
  return messages.value
    .filter((m) => !m.greeting)
    .slice(-8)
    .map((m) => ({ role: m.role, content: m.content }))
    .filter((m) => m.content && m.content.trim())
}

function readAsDataURL(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result)
    reader.onerror = () => reject(new Error('图片读取失败'))
    reader.readAsDataURL(file)
  })
}

function loadImage(src) {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('图片解析失败'))
    img.src = src
  })
}

/** 手机拍的照片往往几 MB，先缩到长边 1400px 再发，识别效果基本不受影响 */
async function toCompactDataUrl(file) {
  const raw = await readAsDataURL(file)
  const img = await loadImage(raw)
  const maxEdge = 1400
  const scale = Math.min(1, maxEdge / Math.max(img.width, img.height))
  if (scale === 1 && raw.length < 1500000) return raw

  const canvas = document.createElement('canvas')
  canvas.width = Math.max(1, Math.round(img.width * scale))
  canvas.height = Math.max(1, Math.round(img.height * scale))
  const ctx = canvas.getContext('2d')
  ctx.fillStyle = '#ffffff'
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
  return canvas.toDataURL('image/jpeg', 0.82)
}

async function attachImageFile(file) {
  attachError.value = ''
  if (!file.type.startsWith('image/')) {
    attachError.value = '请选择图片文件（png、jpg 等）。'
    return
  }
  if (file.size > 8 * 1024 * 1024) {
    attachError.value = '图片有点大，换一张小于 8MB 的吧。'
    return
  }
  try {
    pendingImage.value = await toCompactDataUrl(file)
  } catch (err) {
    attachError.value = err.message || '图片处理失败'
  }
}

async function attachDocFile(file) {
  attachError.value = ''
  if (file.size > 10 * 1024 * 1024) {
    attachError.value = '文件超过 10MB，换个小一点的吧。'
    return
  }

  docLoading.value = true
  try {
    const form = new FormData()
    form.append('file', file)
    const res = await apiFetch('/api/upload', { method: 'POST', body: form })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error(data.error || `上传失败（HTTP ${res.status}）`)
    pendingDoc.value = {
      name: data.filename || file.name,
      text: data.text || '',
      chars: data.chars || 0,
      truncated: !!data.truncated
    }
  } catch (err) {
    attachError.value = err.message || '文档解析失败'
  } finally {
    docLoading.value = false
  }
}

async function onPickImage(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (file) await attachImageFile(file)
}

async function onPickDoc(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (file) await attachDocFile(file)
}

function isDocFile(file) {
  const name = (file.name || '').toLowerCase()
  return /\.(pdf|docx|txt|md|markdown|csv)$/.test(name)
}

/** Ctrl+V：从剪贴板里取截图或复制的文件 */
function onPaste(event) {
  if (authState.value !== 'ready') return

  const items = Array.from(event.clipboardData?.items || [])
  const files = items
    .filter((item) => item.kind === 'file')
    .map((item) => item.getAsFile())
    .filter(Boolean)

  if (!files.length) return

  event.preventDefault()
  for (const file of files) {
    if (file.type.startsWith('image/')) {
      attachImageFile(file)
    } else if (isDocFile(file)) {
      attachDocFile(file)
    } else {
      attachError.value = '这种格式暂时支持不了，图片或 PDF / Word / TXT / Markdown 可以。'
    }
  }
}

async function streamFrom(url, payload, index) {
  await fetchEventSource(url, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(payload),
    openWhenHidden: true,
    async onopen(res) {
      if (res.status === 401) notifyUnauthorized()
      if (!res.ok) {
        const detail = await res.text().catch(() => '')
        throw new Error(detail || `请求失败（HTTP ${res.status}）`)
      }
    },
    onmessage(ev) {
      if (!ev.data) return
      const target = messages.value[index]
      target.content += ev.data
      scrollToBottom()
    },
    onclose() {
      const target = messages.value[index]
      if (target) target.streaming = false
    },
    onerror(err) {
      throw err // 直接抛出让 fetchEventSource 停止自动重试
    }
  })
}

async function send(text) {
  const typed = (text ?? input.value).trim()
  const image = pendingImage.value
  const doc = pendingDoc.value
  if ((!typed && !image && !doc) || sending.value) return

  const question =
    typed || (image ? '这道题我看不太懂，你先帮我看看题目条件。' : '请帮我理解这份资料里的题目。')

  const history = buildHistory()
  input.value = ''
  lastQuestion.value = question
  lastAttachment.value = { image, doc }
  sending.value = true

  messages.value.push({
    role: 'user',
    content: question,
    image,
    docName: doc?.name || '',
    streaming: false,
    variant: false
  })
  messages.value.push({ role: 'assistant', content: '', streaming: true, variant: false })
  const index = messages.value.length - 1
  scrollToBottom()

  pendingImage.value = ''
  pendingDoc.value = null
  attachError.value = ''

  try {
    await streamFrom(
      '/api/chat',
      {
        message: question,
        history,
        image: image || null,
        docName: doc?.name || null,
        docText: doc?.text || null
      },
      index
    )
  } catch (err) {
    const target = messages.value[index]
    target.streaming = false
    target.content += `${target.content ? '\n\n' : ''}【连接失败】${err?.message || '请确认后端已启动（8080 端口）'}`
  } finally {
    messages.value[index].streaming = false
    sending.value = false
    if (authState.value === 'ready') {
      loadStats()
      saveCurrentSession()
    }
  }
}

async function sendVariant() {
  const question = lastQuestion.value
  if (!question || sending.value) return

  sending.value = true
  messages.value.push({
    role: 'assistant',
    content: '',
    streaming: true,
    variant: true
  })
  const index = messages.value.length - 1
  scrollToBottom()

  try {
    await streamFrom(
      '/api/variant',
      {
        message: question,
        image: lastAttachment.value.image || null,
        docName: lastAttachment.value.doc?.name || null,
        docText: lastAttachment.value.doc?.text || null
      },
      index
    )
  } catch (err) {
    const target = messages.value[index]
    target.streaming = false
    target.content += `${target.content ? '\n\n' : ''}【连接失败】${err?.message || '变式题生成失败'}`
  } finally {
    messages.value[index].streaming = false
    sending.value = false
    if (authState.value === 'ready') saveCurrentSession()
  }
}

/** 针对最薄弱的知识点，让 AI 推一道针对性训练题 */
async function sendRecommend() {
  const top = weakTags.value[0]
  if (!top || sending.value || recommending.value) return

  recommending.value = true
  messages.value.push({
    role: 'assistant',
    content: '',
    streaming: true,
    variant: false,
    recommend: true
  })
  const index = messages.value.length - 1
  scrollToBottom()

  try {
    await streamFrom('/api/recommend', {}, index)
  } catch (err) {
    const target = messages.value[index]
    target.streaming = false
    target.content += `${target.content ? '\n\n' : ''}【连接失败】${err?.message || '推荐例题生成失败'}`
  } finally {
    messages.value[index].streaming = false
    recommending.value = false
    if (authState.value === 'ready') saveCurrentSession()
  }
}

/** 打开知识点思维导图弹层，streaming 拿到 AI 给的缩进大纲 */
async function openMindmap(point) {
  const target = (point || weakTags.value[0]?.[0] || '函数与导数').trim()
  const seq = ++mindmapSeq

  mindmapPoint.value = target
  mindmapText.value = ''
  mindmapError.value = ''
  mindmapLoading.value = true
  mindmapOpen.value = true

  mindmapController?.abort()
  mindmapController = new AbortController()

  try {
    await fetchEventSource('/api/mindmap', {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ point: target }),
      signal: mindmapController.signal,
      openWhenHidden: true,
      async onopen(res) {
        if (res.status === 401) notifyUnauthorized()
        if (!res.ok) {
          const detail = await res.text().catch(() => '')
          throw new Error(detail || `请求失败（HTTP ${res.status}）`)
        }
      },
      onmessage(ev) {
        if (seq !== mindmapSeq || !ev.data) return
        mindmapText.value += ev.data
      },
      onclose() {
        // 流结束，由 finally 收尾
      },
      onerror(err) {
        throw err
      }
    })
  } catch (err) {
    if (seq === mindmapSeq && mindmapOpen.value) {
      mindmapError.value = err?.message || '思维导图生成失败，请确认后端已启动'
    }
  } finally {
    if (seq === mindmapSeq) mindmapLoading.value = false
  }
}

function closeMindmap() {
  mindmapOpen.value = false
  mindmapController?.abort()
  mindmapController = null
  mindmapLoading.value = false
}

/** 把错题带进对话区，让 AI 用反问一步步引导分析 */
function guideMistake(text) {
  const prefix = '这道错题我还没弄懂，带我一步步分析：'
  let question = (text || '').trim()
  while (question.startsWith(prefix)) {
    question = question.slice(prefix.length).trim()
  }
  if (!question || sending.value || recommending.value) return
  mistakeOpen.value = false
  send(question)
}

function onKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    send()
  }
}

onMounted(() => {
  bootstrapAuth()
  // 截图/文件可以直接 Ctrl+V 粘贴，监听整个页面，不要求光标在输入框里
  window.addEventListener('paste', onPaste)
  window.addEventListener('mathbuddy:unauthorized', onUnauthorized)
})

onUnmounted(() => {
  window.removeEventListener('paste', onPaste)
  window.removeEventListener('mathbuddy:unauthorized', onUnauthorized)
  mindmapController?.abort()
})
</script>

<template>
  <div class="app-shell overflow-x-hidden text-slate-900">
    <LoginView v-if="authState === 'guest'" @success="onAuthSuccess" />
    <div v-else-if="authState === 'checking'" class="flex min-h-screen items-center justify-center text-[12px] text-slate-400">
      正在检查登录状态…
    </div>
    <template v-else>
    <div class="relative z-10 mx-auto max-w-[1440px] px-4 py-5 sm:px-6 lg:px-8">
      <div class="grid gap-6 lg:grid-cols-[260px_minmax(0,1fr)_250px]">
        <!-- 左侧：身份 + 学情看板 -->
        <aside class="flex flex-col gap-5">
          <section class="rounded-2xl border border-slate-100 bg-white p-4 shadow-sm">
            <div class="flex items-center gap-3">
              <div
                class="flex h-10 w-10 shrink-0 items-center justify-center rounded-2xl bg-blue-100 text-blue-600"
              >
                <svg viewBox="0 0 24 24" class="h-5 w-5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <rect x="6" y="8" width="12" height="9" rx="3" />
                  <circle cx="9.5" cy="12.5" r="1" fill="currentColor" stroke="none" />
                  <circle cx="14.5" cy="12.5" r="1" fill="currentColor" stroke="none" />
                  <path d="M10 15.5h4" />
                  <path d="M12 8V6" />
                  <circle cx="12" cy="5" r="1.2" fill="currentColor" stroke="none" />
                </svg>
              </div>
              <div class="min-w-0">
                <h1 class="text-[14px] font-semibold leading-snug text-slate-900">
                  高中数学 · 个性化答疑智能学伴
                </h1>
                <p class="mt-0.5 text-[11px] text-slate-500">
                  苏格拉底式引导 · 只给提示，不给答案
                </p>
              </div>
            </div>

            <div
              class="mt-3 inline-flex items-center gap-1.5 rounded-full border border-slate-200 px-2.5 py-1 text-[10.5px] text-slate-500"
            >
              <span class="relative flex h-2 w-2">
                <span class="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-60"></span>
                <span class="relative inline-flex h-2 w-2 rounded-full bg-emerald-500"></span>
              </span>
              通义千问 · 流式在线
            </div>

            <div class="mt-3 rounded-xl bg-slate-50 p-2.5">
              <div class="flex items-center gap-2.5">
                <button
                  type="button"
                  class="flex h-10 w-10 shrink-0 items-center justify-center overflow-hidden rounded-2xl transition hover:opacity-90"
                  title="修改昵称和头像"
                  @click="startEditProfile"
                >
                  <img
                    :src="avatarUrl(currentUser?.avatar)"
                    class="h-full w-full object-cover"
                    alt="头像"
                  />
                </button>
                <div class="min-w-0 flex-1">
                  <p class="truncate text-[13px] font-semibold text-slate-800">{{ displayName(currentUser) }}</p>
                  <p class="truncate text-[10.5px] text-slate-400">{{ currentUser?.username }}</p>
                </div>
                <button
                  type="button"
                  class="shrink-0 rounded-lg border border-slate-200 bg-white px-2 py-1 text-[10.5px] text-slate-500 transition hover:border-rose-200 hover:text-rose-500"
                  title="退出当前账号"
                  @click="logout"
                >
                  退出
                </button>
              </div>

              <!-- 编辑个人资料 -->
              <div v-if="profileOpen" class="mt-2.5 border-t border-slate-200 pt-2.5">
                <label class="mb-1 block text-[10.5px] text-slate-500">昵称</label>
                <input
                  v-model="editNickname"
                  type="text"
                  maxlength="20"
                  class="w-full rounded-lg border border-slate-200 px-2.5 py-1.5 text-[12px] text-slate-800 focus:border-blue-400 focus:outline-none"
                  placeholder="2-20 个字符"
                />
                <label class="mb-1.5 mt-2.5 block text-[10.5px] text-slate-500">头像</label>
                <div class="grid grid-cols-4 gap-2">
                  <button
                    v-for="opt in AVATAR_OPTIONS"
                    :key="opt.key"
                    type="button"
                    class="flex flex-col items-center gap-1"
                    :title="`${opt.label} · ${opt.style}`"
                    @click="selectPresetAvatar(opt.key)"
                  >
                    <span
                      class="flex h-9 w-9 items-center justify-center overflow-hidden rounded-xl transition"
                      :class="editAvatar === opt.key && !customAvatarPreview ? 'ring-2 ring-blue-400 ring-offset-1' : 'hover:opacity-80'"
                    >
                      <img :src="opt.image" class="h-full w-full object-cover" :alt="opt.label" />
                    </span>
                    <span class="text-[9px] text-slate-400">{{ opt.label }}</span>
                  </button>

                  <button
                    type="button"
                    class="flex flex-col items-center gap-1"
                    title="上传自定义头像"
                    @click="avatarInputRef?.click()"
                  >
                    <span class="flex h-9 w-9 items-center justify-center rounded-xl border border-dashed border-slate-300 text-slate-400 transition hover:border-blue-400 hover:text-blue-500">
                      <svg viewBox="0 0 24 24" class="h-3.5 w-3.5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M12 5v14M5 12h14" />
                      </svg>
                    </span>
                    <span class="text-[9px] text-slate-400">自定义</span>
                  </button>
                  <input
                    ref="avatarInputRef"
                    type="file"
                    accept="image/png,image/jpeg,image/jpg,image/webp,image/gif"
                    class="hidden"
                    @change="onPickAvatarFile"
                  />
                </div>

                <div v-if="customAvatarPreview || uploadingAvatar" class="mt-3 flex items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-2 py-1.5">
                  <div class="relative h-10 w-10 overflow-hidden rounded-xl bg-slate-100">
                    <img
                      v-if="customAvatarPreview && !uploadingAvatar"
                      :src="customAvatarPreview"
                      class="h-full w-full object-cover"
                      alt="头像预览"
                    />
                    <div v-if="uploadingAvatar" class="flex h-full w-full items-center justify-center">
                      <span class="h-4 w-4 animate-spin rounded-full border border-slate-300 border-t-blue-500"></span>
                    </div>
                  </div>
                  <span class="flex-1 text-[10.5px] text-slate-500">{{ uploadingAvatar ? '上传中…' : '自定义头像' }}</span>
                  <button
                    v-if="!uploadingAvatar"
                    type="button"
                    class="rounded-md px-2 py-1 text-[10px] text-rose-500 transition hover:bg-rose-50"
                    title="删除自定义头像，恢复默认"
                    @click="removeCustomAvatar"
                  >
                    删除
                  </button>
                </div>
                <p v-if="profileError" class="mt-2 text-[10.5px] text-rose-500">{{ profileError }}</p>
                <div class="mt-2.5 flex justify-end gap-2">
                  <button
                    type="button"
                    class="rounded-lg px-2.5 py-1 text-[10.5px] text-slate-500 transition hover:bg-slate-100"
                    @click="cancelEditProfile"
                  >
                    取消
                  </button>
                  <button
                    type="button"
                    class="rounded-lg bg-blue-600 px-3 py-1 text-[10.5px] font-medium text-white transition hover:bg-blue-700 disabled:opacity-50"
                    :disabled="savingProfile"
                    @click="saveProfile"
                  >
                    {{ savingProfile ? '保存中' : '保存' }}
                  </button>
                </div>
              </div>
            </div>
          </section>

          <section class="rounded-2xl border border-slate-100 bg-white p-4 shadow-sm">
            <div class="mb-3 flex items-center justify-between">
              <h2 class="text-[14px] font-semibold text-slate-900">学情看板</h2>
              <span class="rounded-full bg-blue-50 px-2 py-[1px] text-[10px] text-blue-600">
                实时统计
              </span>
            </div>
            <p class="mb-2 text-[12px] text-slate-500">你的薄弱知识点（按提问次数）</p>

            <StatsChart :data="stats" />

            <div v-if="weakTags.length" class="mt-3 flex flex-wrap gap-1.5">
              <span
                v-for="[name, count] in weakTags"
                :key="name"
                class="inline-flex cursor-pointer items-center gap-1 rounded-md border border-blue-100 bg-blue-50 px-2 py-0.5 text-[10.5px] text-blue-700 transition hover:border-blue-200 hover:bg-blue-100"
                :title="`查看「${name}」的思维导图`"
                @click="openMindmap(name)"
              >
                {{ name }}
                <b class="font-semibold text-blue-700">{{ count }}</b>
              </span>
            </div>
            <p v-else class="mt-2 text-[11px] leading-relaxed text-slate-400">
              还没有记录，问一道题就会自动统计。
            </p>
          </section>
        </aside>

        <!-- 中间：核心对话区 -->
        <section class="flex h-[calc(100vh-2.5rem)] min-h-[560px] flex-col overflow-hidden rounded-2xl bg-white shadow-sm">
          <div class="flex items-center justify-between border-b border-slate-100 px-5 py-3.5">
            <div>
              <h2 class="text-[14px] font-semibold text-slate-900">对话区</h2>
              <p class="mt-0.5 text-[12px] text-slate-500">AI 导师只引导思路，不直接给答案</p>
            </div>
            <div class="flex items-center gap-2">
              <button
                type="button"
                class="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-2.5 py-1 text-[11.5px] text-slate-700 transition hover:border-blue-300 hover:bg-blue-50 hover:text-blue-600 disabled:cursor-not-allowed disabled:opacity-40"
                title="开启一段新对话"
                :disabled="sending || recommending"
                @click="startNewChat"
              >
                <svg viewBox="0 0 24 24" class="h-3.5 w-3.5" fill="none" stroke="currentColor" stroke-width="1.8">
                  <path d="M12 5v14M5 12h14" stroke-linecap="round" />
                </svg>
                新对话
              </button>
              <button
                type="button"
                class="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-2.5 py-1 text-[11.5px] text-slate-700 transition hover:border-blue-300 hover:bg-blue-50 hover:text-blue-600 disabled:cursor-not-allowed disabled:opacity-40"
                title="查看历史对话"
                :disabled="sending || recommending"
                @click="historyOpen = true"
              >
                <svg viewBox="0 0 24 24" class="h-3.5 w-3.5" fill="none" stroke="currentColor" stroke-width="1.8">
                  <path d="M12 7v5l3 2" stroke-linecap="round" stroke-linejoin="round" />
                  <path d="M4 5.5A8.5 8.5 0 1 1 3.5 12" stroke-linecap="round" />
                  <path d="M4 3v3.5H7.5" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
                历史对话
              </button>
              <div class="ml-1 flex items-center gap-1.5 text-[11px] text-slate-500">
                <svg viewBox="0 0 24 24" class="h-3.5 w-3.5 text-blue-500" fill="none" stroke="currentColor" stroke-width="1.7">
                  <path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M18.4 5.6 17 7M7 17l-1.4 1.4" stroke-linecap="round" />
                  <circle cx="12" cy="12" r="3.4" />
                </svg>
                启发式模式
              </div>
            </div>
          </div>

          <div ref="scrollRef" class="thin-scroll min-h-0 flex-1 space-y-5 overflow-y-auto bg-white px-5 py-5">
            <ChatBubble
              v-for="(msg, index) in messages"
              :key="index"
              :message="msg"
              :can-variant="index === latestAssistantIndex && msg.role === 'assistant' && !!lastQuestion"
              @variant="sendVariant"
            />

            <div v-if="messages.length === 1" class="flex flex-wrap gap-2 pl-12">
              <button
                v-for="item in suggestions"
                :key="item"
                type="button"
                class="rounded-full bg-slate-100 px-3.5 py-1.5 text-[12px] text-slate-700 transition hover:bg-blue-100 hover:text-blue-700"
                @click="send(item)"
              >
                {{ item }}
              </button>
            </div>
          </div>

          <div class="border-t border-slate-100 bg-white px-4 py-3.5 shadow-[0_-4px_20px_-6px_rgba(15,23,42,0.04)]">
            <!-- 待发送的附件预览 -->
            <div
              v-if="pendingImage || pendingDoc || docLoading"
              class="mb-2.5 flex flex-wrap items-center gap-2"
            >
              <div
                v-if="pendingImage"
                class="group relative overflow-hidden rounded-xl border border-slate-200 bg-slate-50"
              >
                <img :src="pendingImage" alt="题目图片预览" class="h-16 w-16 object-cover" />
                <button
                  type="button"
                  class="absolute right-0 top-0 flex h-5 w-5 items-center justify-center rounded-bl-lg bg-slate-900/70 text-[12px] leading-none text-white transition hover:text-rose-300"
                  title="移除图片"
                  @click="pendingImage = ''"
                >
                  ×
                </button>
              </div>

              <div
                v-if="docLoading"
                class="inline-flex items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-[11.5px] text-slate-600"
              >
                <span class="h-3 w-3 animate-spin rounded-full border border-slate-300 border-t-blue-500"></span>
                正在解析文档…
              </div>

              <div
                v-else-if="pendingDoc"
                class="inline-flex max-w-full items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2"
              >
                <svg viewBox="0 0 24 24" class="h-4 w-4 shrink-0 text-blue-500" fill="none" stroke="currentColor" stroke-width="1.7">
                  <path d="M6 3.5h8l4 4v13H6z" stroke-linejoin="round" />
                  <path d="M14 3.5v4h4" stroke-linejoin="round" />
                </svg>
                <div class="min-w-0">
                  <p class="truncate text-[12px] text-slate-900">{{ pendingDoc.name }}</p>
                  <p class="text-[10px] text-slate-500">
                    {{ pendingDoc.chars }} 字
                    <template v-if="pendingDoc.truncated"> · 已截取前 12000 字</template>
                  </p>
                </div>
                <button
                  type="button"
                  class="ml-1 text-[14px] leading-none text-slate-400 transition hover:text-rose-500"
                  title="移除文档"
                  @click="pendingDoc = null"
                >
                  ×
                </button>
              </div>
            </div>

            <div
              class="flex items-end gap-2 rounded-2xl border border-slate-200 bg-white px-3 py-2 shadow-sm transition focus-within:border-blue-400 focus-within:ring-2 focus-within:ring-blue-100"
            >
              <div class="mb-0.5 flex shrink-0 items-center gap-1">
                <button
                  type="button"
                  class="flex h-9 w-9 items-center justify-center rounded-xl border border-slate-200 bg-slate-50 text-slate-500 transition hover:border-blue-300 hover:text-blue-600"
                  title="上传题目图片（拍照识题）"
                  @click="imageInputRef?.click()"
                >
                  <svg viewBox="0 0 24 24" class="h-[17px] w-[17px]" fill="none" stroke="currentColor" stroke-width="1.7">
                    <rect x="3.5" y="5.5" width="17" height="13" rx="2.5" />
                    <circle cx="9" cy="10.5" r="1.6" />
                    <path d="m4.5 17 4.8-4.4 3.2 2.9 2.5-2.2 4.5 3.7" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                </button>
                <button
                  type="button"
                  class="flex h-9 w-9 items-center justify-center rounded-xl border border-slate-200 bg-slate-50 text-slate-500 transition hover:border-blue-300 hover:text-blue-600"
                  title="上传资料（PDF / Word / TXT / Markdown）"
                  @click="docInputRef?.click()"
                >
                  <svg viewBox="0 0 24 24" class="h-[17px] w-[17px]" fill="none" stroke="currentColor" stroke-width="1.7">
                    <path d="M6 3.5h8l4 4v13H6z" stroke-linejoin="round" />
                    <path d="M14 3.5v4h4" stroke-linejoin="round" />
                    <path d="M9 13h6M9 16.5h4" stroke-linecap="round" />
                  </svg>
                </button>
                <input
                  ref="imageInputRef"
                  type="file"
                  accept="image/*"
                  class="hidden"
                  @change="onPickImage"
                />
                <input
                  ref="docInputRef"
                  type="file"
                  accept=".pdf,.docx,.txt,.md,.markdown,.csv"
                  class="hidden"
                  @change="onPickDoc"
                />
              </div>

              <textarea
                v-model="input"
                rows="1"
                class="thin-scroll max-h-32 min-h-[38px] flex-1 resize-none bg-transparent py-2 text-[14px] leading-relaxed text-slate-900 placeholder:text-slate-400 focus:outline-none"
                placeholder="把题目、卡住的步骤发给我；截图可直接 Ctrl+V 粘贴，也可以传 PDF / Word 资料"
                @keydown="onKeydown"
              ></textarea>

              <button
                type="button"
                class="mb-0.5 inline-flex h-11 items-center gap-1.5 rounded-xl bg-blue-600 px-5 text-[14px] font-medium text-white shadow-md transition hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-45"
                :disabled="sending || (!input.trim() && !pendingImage && !pendingDoc)"
                @click="send()"
              >
                <svg viewBox="0 0 24 24" class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="1.8">
                  <path d="M4.5 12h13M12 5.5 18.5 12 12 18.5" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
                {{ sending ? '引导中' : '发送' }}
              </button>
            </div>
            <p v-if="attachError" class="mt-1.5 pl-1 text-[11px] text-rose-500">{{ attachError }}</p>
            <p class="mt-2 pl-1 text-[11px] text-slate-400">
              Enter 发送 · Shift + Enter 换行 · Ctrl+V 粘贴截图或文件 · AI 内容由通义千问生成
            </p>
          </div>
        </section>

        <!-- 右侧：学习工具 -->
        <aside class="flex flex-col gap-5">
          <section class="rounded-2xl border border-slate-100 bg-white p-4 shadow-sm">
            <h2 class="mb-3 text-[14px] font-semibold text-slate-900">学习工具</h2>
            <div class="flex flex-col gap-2">
              <button
                @click="mistakeOpen = true"
                class="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-[12px] text-slate-700 transition hover:border-blue-300 hover:bg-blue-50 hover:text-blue-600"
              >
                智能错题本
              </button>
              <button
                @click="quizOpen = true"
                class="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-[12px] text-slate-700 transition hover:border-blue-300 hover:bg-blue-50 hover:text-blue-600"
              >
                知识点自测
              </button>
              <button
                @click="openMindmap()"
                class="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-[12px] text-slate-700 transition hover:border-blue-300 hover:bg-blue-50 hover:text-blue-600"
              >
                知识点思维导图
              </button>
              <button
                @click="sendRecommend"
                :disabled="!weakTags.length || recommending"
                class="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-[12px] text-slate-700 transition hover:border-blue-300 hover:bg-blue-50 hover:text-blue-600 disabled:cursor-not-allowed disabled:opacity-40"
              >
                <span v-if="recommending">正在为薄弱点出题…</span>
                <span v-else>针对薄弱点推荐例题</span>
              </button>
              <button
                @click="reportOpen = true"
                class="w-full rounded-xl bg-blue-600 px-3 py-2 text-[12px] font-medium text-white shadow-sm transition hover:bg-blue-700"
              >
                查看学习报告
              </button>
            </div>
          </section>

          <section class="rounded-2xl border border-slate-100 bg-white p-4 shadow-sm">
            <h2 class="mb-2.5 text-[14px] font-semibold text-slate-900">使用小贴士</h2>
            <ul class="space-y-2 text-[11.5px] leading-relaxed text-slate-500">
              <li class="flex gap-2">
                <span class="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-blue-500"></span>
                说清楚你卡在哪一步，比只发题目更容易得到有效的提示。
              </li>
              <li class="flex gap-2">
                <span class="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-blue-500"></span>
                直接要答案会被反问回来——试着先回答老师的问题。
              </li>
              <li class="flex gap-2">
                <span class="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-blue-500"></span>
                弄懂之后点“举一反三”，用同类题检验自己是否真的会了。
              </li>
            </ul>
          </section>
        </aside>
      </div>
    </div>

    <MindMapPanel
      :open="mindmapOpen"
      :point="mindmapPoint"
      :text="mindmapText"
      :loading="mindmapLoading"
      :error="mindmapError"
      @close="closeMindmap"
    />

    <ConversationPanel
      :open="historyOpen"
      :sessions="sessions"
      :active-id="sessionId"
      @close="historyOpen = false"
      @select="openSession"
      @new="startNewChat"
    />

    <MistakeBookPanel
      :open="mistakeOpen"
      @close="mistakeOpen = false"
      @guide="guideMistake"
    />

    <QuizPanel :open="quizOpen" @close="quizOpen = false" @submitted="loadStats" />

    <ReportPanel :open="reportOpen" @close="reportOpen = false" />
    </template>
  </div>
</template>
