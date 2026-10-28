<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import UiButton from '@/components/ui/UiButton.vue'
import UiField from '@/components/ui/UiField.vue'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/api/http'
import { errorMessage } from '@/lib/errors'
import { safeRedirect } from '@/router'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const submitting = ref(false)
const formError = ref<string | null>(null)
const fieldErrors = ref<{ username?: string; password?: string }>({})
const traceId = ref<string | null>(null)
const showExpired = ref(route.query.expired === '1')

// 429：按 Retry-After 倒计时，期间禁用提交
const waitSeconds = ref(0)
let waitTimer: number | null = null
function startWait(seconds: number) {
  waitSeconds.value = Math.ceil(seconds)
  if (waitTimer) window.clearInterval(waitTimer)
  waitTimer = window.setInterval(() => {
    waitSeconds.value -= 1
    if (waitSeconds.value <= 0 && waitTimer) {
      window.clearInterval(waitTimer)
      waitTimer = null
    }
  }, 1000)
}
onBeforeUnmount(() => {
  if (waitTimer) window.clearInterval(waitTimer)
})

const canSubmit = computed(() => !submitting.value && waitSeconds.value <= 0)

async function submit() {
  formError.value = null
  traceId.value = null
  fieldErrors.value = {}
  showExpired.value = false
  if (!username.value.trim()) fieldErrors.value.username = '请输入学号或工号'
  if (!password.value) fieldErrors.value.password = '请输入密码'
  if (fieldErrors.value.username || fieldErrors.value.password) return

  submitting.value = true
  try {
    await auth.login(username.value.trim(), password.value)
    password.value = ''
    await router.replace(safeRedirect(route.query.redirect))
  } catch (e) {
    password.value = ''
    if (e instanceof ApiError) {
      traceId.value = e.status >= 500 ? e.traceId : null
      if (e.code === 'VALIDATION_FAILED') {
        fieldErrors.value = { username: e.fieldError('username'), password: e.fieldError('password') }
        formError.value = e.errors.length ? null : e.message
      } else if (e.code === 'AUTH_BAD_CREDENTIALS') {
        formError.value = '账号或密码不正确。'
      } else if (e.code === 'AUTH_ACCOUNT_LOCKED') {
        formError.value = e.message || '账号已锁定，请稍后再试或联系管理员。'
      } else if (e.code === 'AUTH_ACCOUNT_DISABLED') {
        formError.value = e.message || '账号已停用，请联系管理员。'
      } else if (e.code === 'TOO_MANY_REQUESTS') {
        startWait(e.retryAfter ?? 60)
        formError.value = '尝试次数过多。'
      } else {
        formError.value = errorMessage(e)
      }
    } else {
      formError.value = errorMessage(e)
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login">
    <!-- 左：扉页 -->
    <section class="frontis" aria-hidden="true">
      <div class="frontis__top">
        <!-- [logo] 校徽待提供 -->
        <span class="frontis__mark">校园 AI 服务平台</span>
        <span class="latin-italic">Campus Press</span>
      </div>

      <h1 class="frontis__title">
        <span>问一句，</span>
        <span>读一页，</span>
        <span>办一事。</span>
      </h1>

      <ol class="frontis__toc">
        <li><span class="latin-italic">i.</span> 问答 <em>依据知识库作答，标注出处</em></li>
        <li><span class="latin-italic">ii.</span> 知识库 <em>校内文档，按空间成册</em></li>
        <li><span class="latin-italic">iii.</span> 论坛 <em>读者来信与回复</em></li>
        <li><span class="latin-italic">iv.</span> 课程 <em>选课、课表、请假</em></li>
      </ol>
    </section>

    <!-- 右：登录 -->
    <section class="sheet">
      <form class="form fade-in" novalidate @submit.prevent="submit">
        <p class="eyebrow">登录</p>
        <h2 class="form__title">欢迎回来</h2>

        <p v-if="showExpired" class="notice" role="status">登录已失效，请重新登录。</p>

        <UiField
          v-model="username"
          label="学号 / 工号"
          autocomplete="username"
          :error="fieldErrors.username"
          :maxlength="64"
          required
        />
        <UiField
          v-model="password"
          label="密码"
          type="password"
          autocomplete="current-password"
          :error="fieldErrors.password"
          :maxlength="128"
          required
        />

        <div v-if="formError" class="form__error" role="alert">
          <p>
            {{ formError }}
            <template v-if="waitSeconds > 0">请在 <strong class="mono">{{ waitSeconds }}</strong> 秒后重试。</template>
          </p>
          <p v-if="traceId" class="form__trace mono">traceId {{ traceId }}</p>
        </div>

        <UiButton type="submit" variant="primary" block :loading="submitting" :disabled="!canSubmit">
          {{ waitSeconds > 0 ? `请稍候 ${waitSeconds}s` : '登录' }}
        </UiButton>

        <p class="form__foot">忘记密码请联系管理员重置。本平台不保存登录状态，关闭标签页后需重新登录。</p>
      </form>
    </section>
  </div>
</template>

<style scoped>
.login {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(360px, 1fr);
  min-height: 100vh;
}

.frontis {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: var(--s-6) clamp(var(--s-5), 6vw, var(--s-7));
  background: var(--paper-deep);
  border-right: 1px solid var(--ink);
  overflow: hidden;
}

/* 内框：书页上的双线版框 */
.frontis::before {
  content: '';
  position: absolute;
  inset: var(--s-4);
  border: 1px solid var(--rule-strong);
  outline: 1px solid var(--rule);
  outline-offset: 4px;
  pointer-events: none;
}

.frontis__top {
  position: relative;
  display: flex;
  align-items: baseline;
  gap: var(--s-2);
  color: var(--ink-2);
}

.frontis__mark {
  font-family: var(--font-serif-cjk);
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--ink);
}

.frontis__title {
  position: relative;
  display: grid;
  margin: var(--s-6) 0;
  font-family: var(--font-serif-cjk);
  font-size: var(--fs-h1);
  font-weight: 600;
  line-height: 1.12;
  letter-spacing: 0.02em;
}

.frontis__title span:nth-child(2) {
  padding-left: 1em;
}

.frontis__title span:nth-child(3) {
  padding-left: 2em;
  color: var(--teal);
}

.frontis__toc {
  position: relative;
  display: grid;
  gap: var(--s-2);
  max-width: 30em;
  margin: 0;
  padding: var(--s-3) 0 0;
  border-top: 1px solid var(--ink);
  list-style: none;
  font-family: var(--font-serif-cjk);
  font-size: var(--fs-ui-lg);
}

.frontis__toc li {
  display: grid;
  grid-template-columns: 2.4em 4.5em 1fr;
  align-items: baseline;
}

.frontis__toc .latin-italic {
  color: var(--ink-2);
}

.frontis__toc em {
  font-style: normal;
  font-family: var(--font-sans);
  font-size: var(--fs-ui);
  color: var(--ink-2);
}

.sheet {
  display: grid;
  place-items: center;
  padding: var(--s-6) var(--s-5);
}

.form {
  display: grid;
  gap: var(--s-4);
  width: min(100%, 360px);
}

.form__title {
  margin: calc(-1 * var(--s-3)) 0 var(--s-2);
  font-size: var(--fs-h2);
}

.notice {
  margin: 0;
  padding: var(--s-2) var(--s-3);
  border-left: 2px solid var(--teal);
  background: var(--teal-tint);
  color: var(--ink);
}

.form__error {
  padding: var(--s-2) 0;
  border-top: 1px solid var(--ochre);
  border-bottom: 1px solid var(--ochre);
  color: var(--ochre);
}

.form__error p {
  margin: 0;
}

.form__trace {
  margin-top: var(--s-1) !important;
  color: var(--ink-2);
  font-size: 11px;
  user-select: all;
}

.form__foot {
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.7;
}

@media (max-width: 900px) {
  .login {
    grid-template-columns: 1fr;
  }

  .frontis {
    padding: var(--s-5) var(--s-5) var(--s-4);
    border-right: 0;
    border-bottom: 1px solid var(--ink);
  }

  .frontis::before {
    inset: var(--s-2);
  }

  .frontis__title {
    margin: var(--s-4) 0 var(--s-2);
    font-size: 40px;
  }

  .frontis__toc {
    display: none;
  }

  .sheet {
    place-items: start center;
    padding: var(--s-5) var(--s-4) var(--s-6);
  }
}
</style>
