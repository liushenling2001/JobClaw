import { createRouter, createWebHistory } from 'vue-router';

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { requiresAuth: false }
  },
  {
    path: '/',
    component: () => import('@/components/Layout/AppLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'Dashboard', component: () => import('@/views/Dashboard.vue') },
      { path: 'chat', name: 'Chat', component: () => import('@/views/Chat.vue') },
      { path: 'sessions', name: 'Sessions', component: () => import('@/views/Sessions.vue') },
      { path: 'sessions/:id', name: 'SessionDetail', component: () => import('@/views/SessionDetail.vue'), props: true },
      { path: 'channels', name: 'Channels', component: () => import('@/views/Channels.vue') },
      { path: 'providers', name: 'Providers', component: () => import('@/views/Providers.vue') },
      { path: 'models', name: 'Models', component: () => import('@/views/Models.vue') },
      { path: 'agent', name: 'AgentConfig', component: () => import('@/views/AgentConfig.vue') },
      { path: 'cron', name: 'CronJobs', component: () => import('@/views/CronJobs.vue') },
      { path: 'skills', name: 'Skills', component: () => import('@/views/Skills.vue') },
      { path: 'mcp', name: 'MCPServers', component: () => import('@/views/MCPServers.vue') },
      { path: 'files', name: 'WorkspaceFiles', component: () => import('@/views/WorkspaceFiles.vue') },
      { path: 'stats', name: 'TokenStats', component: () => import('@/views/TokenStats.vue') },
      { path: 'settings', name: 'Settings', component: () => import('@/views/Settings.vue') }
    ]
  }
];

const router = createRouter({
  history: createWebHistory(),
  routes
});

// 路由守卫
router.beforeEach((to, _from, next) => {
  const isAuthenticated = localStorage.getItem('auth_token') !== null;

  if (to.meta.requiresAuth !== false && !isAuthenticated) {
    next('/login');
  } else if (to.path === '/login' && isAuthenticated) {
    next('/dashboard');
  } else {
    next();
  }
});

export default router;
