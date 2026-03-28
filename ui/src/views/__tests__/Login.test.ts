import { describe, it, expect, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createRouter, createWebHistory } from 'vue-router';
import Login from '../Login.vue';

describe('Login', () => {
  let router: ReturnType<typeof createRouter>;

  beforeEach(() => {
    setActivePinia(createPinia());
    router = createRouter({
      history: createWebHistory(),
      routes: [{ path: '/login', component: Login }]
    });
  });

  it('renders login form', () => {
    const wrapper = mount(Login, { global: { plugins: [router] } });
    expect(wrapper.find('input[type="text"]').exists()).toBe(true);
    expect(wrapper.find('input[type="password"]').exists()).toBe(true);
  });

  it('shows error when submitting empty form', async () => {
    const wrapper = mount(Login, { global: { plugins: [router] } });
    await wrapper.find('form').trigger('submit.prevent');
    expect(wrapper.text()).toContain('请输入用户名和密码');
  });
});
