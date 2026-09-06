import { createApp } from 'vue'
import { createPinia } from 'pinia'
import './styles.css'
import App from './App.vue'
import router from './router'
import { useUiStore } from './stores/ui'

const pinia = createPinia()
useUiStore(pinia).applyTheme()

createApp(App).use(pinia).use(router).mount('#app')
