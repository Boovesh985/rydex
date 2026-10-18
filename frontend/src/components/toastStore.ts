import { create } from 'zustand';

export type Tone = 'info' | 'good' | 'bad';

export interface Toast {
  id: number;
  title: string;
  message?: string;
  tone: Tone;
}

interface ToastState {
  toasts: Toast[];
  push: (title: string, message?: string, tone?: Tone) => void;
  dismiss: (id: number) => void;
}

let next = 1;

export const useToasts = create<ToastState>((set) => ({
  toasts: [],
  push: (title, message, tone = 'info') => set((s) => ({ toasts: [...s.toasts.slice(-2), { id: next++, title, message, tone }] })),
  dismiss: (id) => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),
}));

export const toast = (title: string, message?: string, tone?: Tone) => useToasts.getState().push(title, message, tone);
