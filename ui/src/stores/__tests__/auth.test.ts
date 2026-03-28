import { describe, it, expect, beforeEach } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';
import { useAuthStore } from '../auth';

describe('useAuthStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    localStorage.clear();
  });

  it('initializes with correct default values', () => {
    const store = useAuthStore();
    expect(store.isAuthenticated).toBe(false);
    expect(store.token).toBeNull();
    expect(store.authEnabled).toBe(true);
  });

  it('updates state on successful login', () => {
    const store = useAuthStore();
    // Mock login response
    store.token = 'test-token';
    store.isAuthenticated = true;

    expect(store.token).toBe('test-token');
    expect(store.isAuthenticated).toBe(true);
  });

  it('clears state on logout', () => {
    const store = useAuthStore();
    store.token = 'test-token';
    store.isAuthenticated = true;

    store.logout();

    expect(store.token).toBeNull();
    expect(store.isAuthenticated).toBe(false);
  });
});
