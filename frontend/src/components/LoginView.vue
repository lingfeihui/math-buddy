<script setup>
import { computed, ref } from 'vue'

const emit = defineEmits(['success'])

const mode = ref('login')
const username = ref('')
const password = ref('')
const confirm = ref('')
const error = ref('')
const submitting = ref(false)

const isRegister = computed(() => mode.value === 'register')

function switchMode(next) {
  if (submitting.value || mode.value === next) return
  mode.value = next
  error.value = ''
  password.value = ''
  confirm.value = ''
}

async function submit() {
  if (submitting.value) return
  error.value = ''

  const name = username.value.trim()
  if (!name || !password.value) {
    error.value = '请填写用户名和密码。'
    return
  }
  if (isRegister.value) {
    if (name.length < 2 || name.length > 20) {
      error.value = '用户名请输入 2-20 个字符。'
      return
    }
    if (password.value.length < 5 || password.value.length > 32) {
      error.value = '密码请输入 5-32 位。'
      return
    }
    if (password.value !== confirm.value) {
      error.value = '两次输入的密码不一致。'
      return
    }
  }

  submitting.value = true
  try {
    const res = await fetch(isRegister.value ? '/api/auth/register' : '/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: name, password: password.value })
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error(data.error || `操作失败（HTTP ${res.status}）`)
    emit('success', data)
  } catch (err) {
    error.value = err?.message || '请求失败，请确认后端已启动（8080 端口）'
  } finally {
    submitting.value = false
  }
}

function onKeydown(event) {
  if (event.key === 'Enter') submit()
}
</script>

<template>
  <div class="relative flex min-h-screen items-center justify-center overflow-hidden px-4 py-10">
    <!-- 背景图 -->
    <div
      class="absolute inset-0 scale-105 bg-cover bg-center"
      style="background-image: url('/login-bg.png')"
    ></div>
    <!-- 柔光遮罩：压住背景的亮度，保证卡片和文字可读 -->
    <div class="absolute inset-0 bg-gradient-to-b from-white/55 via-sky-50/25 to-slate-300/35"></div>
    <!-- 右上暖光光晕，呼应背景的斜射光；左下冷光呼应玻璃球 -->
    <div class="absolute -top-40 right-[-120px] h-80 w-[520px] rounded-full bg-amber-50/50 blur-3xl"></div>
    <div class="absolute -bottom-32 left-[-100px] h-72 w-[420px] rounded-full bg-sky-200/35 blur-3xl"></div>

    <div class="relative w-full max-w-[480px]">
      <div class="mb-5 flex items-center gap-3">
        <div
          class="flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl border border-white/70 bg-white/70 text-blue-600 shadow-sm backdrop-blur-md"
        >
          <svg viewBox="0 0 24 24" class="h-6 w-6" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <rect x="6" y="8" width="12" height="9" rx="3" />
            <circle cx="9.5" cy="12.5" r="1" fill="currentColor" stroke="none" />
            <circle cx="14.5" cy="12.5" r="1" fill="currentColor" stroke="none" />
            <path d="M10 15.5h4" />
            <path d="M12 8V6" />
            <circle cx="12" cy="5" r="1.2" fill="currentColor" stroke="none" />
          </svg>
        </div>
        <div>
          <h1 class="text-[17px] font-semibold text-slate-900">高中数学 · 个性化答疑智能学伴</h1>
          <p class="mt-0.5 text-[12.5px] text-slate-600">登录后，错题、学情和对话都按账号保存</p>
        </div>
      </div>

      <div
        class="rounded-3xl border border-white/70 bg-white/75 p-8 shadow-[0_24px_60px_-24px_rgba(30,64,175,0.35)] backdrop-blur-xl"
      >
        <div class="mb-6 flex rounded-xl bg-slate-900/5 p-1">
          <button
            type="button"
            class="flex-1 rounded-lg py-2 text-[13.5px] transition"
            :class="!isRegister ? 'bg-white font-medium text-slate-900 shadow-sm' : 'text-slate-500'"
            @click="switchMode('login')"
          >
            登录
          </button>
          <button
            type="button"
            class="flex-1 rounded-lg py-2 text-[13.5px] transition"
            :class="isRegister ? 'bg-white font-medium text-slate-900 shadow-sm' : 'text-slate-500'"
            @click="switchMode('register')"
          >
            注册新账号
          </button>
        </div>

        <div class="space-y-3.5" @keydown="onKeydown">
          <label class="block">
            <span class="mb-1.5 block text-[12.5px] text-slate-500">用户名</span>
            <input
              v-model="username"
              type="text"
              autocomplete="username"
              maxlength="20"
              placeholder="2-20 个字符，不能含空格"
              class="w-full rounded-xl border border-white/80 bg-white/85 px-3.5 py-2.5 text-[14px] text-slate-900 placeholder:text-slate-400 focus:border-blue-400 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-100"
            />
          </label>

          <label class="block">
            <span class="mb-1.5 block text-[12.5px] text-slate-500">密码</span>
            <input
              v-model="password"
              type="password"
              :autocomplete="isRegister ? 'new-password' : 'current-password'"
              maxlength="32"
              placeholder="5-32 位"
              class="w-full rounded-xl border border-white/80 bg-white/85 px-3.5 py-2.5 text-[14px] text-slate-900 placeholder:text-slate-400 focus:border-blue-400 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-100"
            />
          </label>

          <label v-if="isRegister" class="block">
            <span class="mb-1.5 block text-[12.5px] text-slate-500">确认密码</span>
            <input
              v-model="confirm"
              type="password"
              autocomplete="new-password"
              maxlength="32"
              placeholder="再输一次"
              class="w-full rounded-xl border border-white/80 bg-white/85 px-3.5 py-2.5 text-[14px] text-slate-900 placeholder:text-slate-400 focus:border-blue-400 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-100"
            />
          </label>
        </div>

        <p v-if="error" class="mt-3 rounded-lg border border-rose-200 bg-rose-50/90 px-3 py-2 text-[12.5px] text-rose-600">
          {{ error }}
        </p>

        <button
          type="button"
          class="mt-5 w-full rounded-xl bg-blue-600 py-3 text-[14px] font-medium text-white shadow-sm transition hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-45"
          :disabled="submitting"
          @click="submit"
        >
          {{ submitting ? '请稍候…' : isRegister ? '注册并进入' : '登录' }}
        </button>

        <p class="mt-5 text-center text-[11.5px] leading-relaxed text-slate-500">
          演示账号：<b class="font-medium text-slate-700">text</b> / <b class="font-medium text-slate-700">12345</b>
          <br />
          每个账号的错题、学情和对话相互独立
        </p>
      </div>
    </div>
  </div>
</template>
