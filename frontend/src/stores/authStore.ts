import { create } from 'zustand';
import { authApi } from '@/api/client';
import type { User } from '@/types';

interface AuthState {
  user: User | null;
  token: string | null;
  loading: boolean;

  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string, displayName: string) => Promise<void>;
  googleLogin: (credential: string) => Promise<void>;
  logout: () => void;
  loadUser: () => Promise<void>;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  token: localStorage.getItem('token'),
  loading: false,

  login: async (email, password) => {
    const res = await authApi.login(email, password);
    const token = res.data.access_token;
    localStorage.setItem('token', token);
    set({ token });
    const me = await authApi.me();
    set({ user: me.data });
  },

  register: async (email, password, displayName) => {
    const res = await authApi.register(email, password, displayName);
    const token = res.data.access_token;
    localStorage.setItem('token', token);
    set({ token });
    const me = await authApi.me();
    set({ user: me.data });
  },

  googleLogin: async (credential) => {
    const res = await authApi.googleLogin(credential);
    const token = res.data.access_token;
    localStorage.setItem('token', token);
    set({ token });
    const me = await authApi.me();
    set({ user: me.data });
  },

  logout: () => {
    localStorage.removeItem('token');
    set({ user: null, token: null });
  },

  loadUser: async () => {
    const token = localStorage.getItem('token');
    if (!token) return;
    set({ loading: true });
    try {
      const me = await authApi.me();
      set({ user: me.data, token });
    } catch {
      localStorage.removeItem('token');
      set({ user: null, token: null });
    } finally {
      set({ loading: false });
    }
  },
}));
