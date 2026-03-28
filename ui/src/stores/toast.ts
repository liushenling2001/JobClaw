import { defineStore } from 'pinia';

interface Toast {
  id: string;
  type: 'success' | 'error' | 'warning' | 'info';
  message: string;
  duration?: number;
}

export const useToastStore = defineStore('toast', {
  state: () => ({
    toasts: [] as Toast[]
  }),

  actions: {
    add(toast: Omit<Toast, 'id'>) {
      const id = 'toast_' + Date.now();
      const newToast = { ...toast, id };
      this.toasts.push(newToast);

      setTimeout(() => {
        this.remove(id);
      }, toast.duration || 5000);

      return id;
    },

    remove(id: string) {
      this.toasts = this.toasts.filter(t => t.id !== id);
    },

    success(message: string) {
      return this.add({ type: 'success', message });
    },

    error(message: string) {
      return this.add({ type: 'error', message });
    },

    warning(message: string) {
      return this.add({ type: 'warning', message });
    },

    info(message: string) {
      return this.add({ type: 'info', message });
    }
  }
});
