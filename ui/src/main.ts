import { createApp } from 'vue';
import App from './App.vue';
import router from './router';
import pinia from './stores';
import './styles/main.css';
import './styles/material-symbols.css';

const app = createApp(App);

// 全局错误处理
app.config.errorHandler = (err, _instance, info) => {
  console.error('Global error:', err, info);
};

// 未捕获的 Promise 错误
window.addEventListener('unhandledrejection', event => {
  console.error('Unhandled promise rejection:', event.reason);
});

app.use(pinia);
app.use(router);
app.mount('#app');
