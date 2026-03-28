import { useToastStore } from '@/stores/toast';

export function useToast() {
  const store = useToastStore();

  return {
    success: (message: string) => store.success(message),
    error: (message: string) => store.error(message),
    warning: (message: string) => store.warning(message),
    info: (message: string) => store.info(message)
  };
}
