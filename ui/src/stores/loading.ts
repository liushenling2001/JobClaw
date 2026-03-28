import { defineStore } from 'pinia';

export const useLoadingStore = defineStore('loading', {
  state: () => ({
    global: false,
    counters: {} as Record<string, number>
  }),

  actions: {
    start(key: string = 'global') {
      if (!this.counters[key]) {
        this.counters[key] = 0;
      }
      this.counters[key]++;
      this.updateGlobal();
    },

    end(key: string = 'global') {
      if (this.counters[key]) {
        this.counters[key]--;
      }
      this.updateGlobal();
    },

    updateGlobal() {
      this.global = Object.values(this.counters).some(c => c > 0);
    }
  }
});
