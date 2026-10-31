import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { router } from './router'
import { configureHttp } from './api/http'
import { useAuthStore } from './stores/auth'
import { useClockStore } from './stores/clock'
import './styles/fonts'
import './styles/tokens.css'
import './styles/base.css'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)

const auth = useAuthStore(pinia)
configureHttp({
  getToken: () => auth.token,
  getRefreshToken: () => auth.refreshToken,
  onRefreshed: (result) => auth.applyLogin(result),
  onUnauthorized: () => {
    // 刷新也失败（令牌过期、被登出或改密）：清空本地登录态并回到登录页，保留当前位置以便登录后返回
    if (!auth.token) return
    auth.clear()
    const current = router.currentRoute.value
    if (current.name !== 'login') {
      void router.replace({ name: 'login', query: { redirect: current.fullPath, expired: '1' } })
    }
  },
})

const clock = useClockStore(pinia)
clock.start()
void clock.sync()

app.use(router)
app.mount('#app')
