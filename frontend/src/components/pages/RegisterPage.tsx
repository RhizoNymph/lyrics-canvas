import { useState, useEffect, useRef, useCallback } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAuthStore } from '@/stores/authStore';

declare global {
  interface Window {
    google?: {
      accounts: {
        id: {
          initialize: (config: Record<string, unknown>) => void;
          renderButton: (el: HTMLElement, config: Record<string, unknown>) => void;
        };
      };
    };
  }
}

const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID;

export default function RegisterPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [error, setError] = useState('');
  const { register, googleLogin } = useAuthStore();
  const navigate = useNavigate();
  const googleBtnRef = useRef<HTMLDivElement>(null);

  const handleGoogleCallback = useCallback(async (response: { credential: string }) => {
    setError('');
    try {
      await googleLogin(response.credential);
      navigate('/');
    } catch {
      setError('Google sign-in failed');
    }
  }, [googleLogin, navigate]);

  useEffect(() => {
    if (!GOOGLE_CLIENT_ID) return;
    const script = document.createElement('script');
    script.src = 'https://accounts.google.com/gsi/client';
    script.async = true;
    script.onload = () => {
      window.google?.accounts.id.initialize({
        client_id: GOOGLE_CLIENT_ID,
        callback: handleGoogleCallback,
      });
      if (googleBtnRef.current) {
        window.google?.accounts.id.renderButton(googleBtnRef.current, {
          theme: 'filled_black',
          size: 'large',
          width: 296,
          text: 'signup_with',
        });
      }
    };
    document.head.appendChild(script);
    return () => { script.remove(); };
  }, [handleGoogleCallback]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    try {
      await register(email, password, displayName);
      navigate('/');
    } catch {
      setError('Registration failed. Email may already be in use.');
    }
  };

  return (
    <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#12122a' }}>
      <form
        onSubmit={handleSubmit}
        style={{
          width: 360,
          background: '#1e1e3a',
          borderRadius: 12,
          padding: 32,
          border: '1px solid #2a2a4a',
        }}
      >
        <h1 style={{ fontSize: 24, marginBottom: 8, color: '#e0e0e0' }}>Create Account</h1>
        <p style={{ color: '#888', marginBottom: 24, fontSize: 14 }}>Start building your lyrics canvas</p>

        {error && <div style={{ color: '#ef4444', marginBottom: 12, fontSize: 13 }}>{error}</div>}

        <div style={{ marginBottom: 16 }}>
          <label style={{ display: 'block', color: '#aaa', fontSize: 12, marginBottom: 4 }}>Display Name</label>
          <input
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            required
            style={{
              width: '100%',
              background: '#2a2a4a',
              color: '#e0e0e0',
              border: '1px solid #3a3a5a',
              borderRadius: 6,
              padding: '10px 12px',
              fontSize: 14,
              boxSizing: 'border-box',
            }}
          />
        </div>

        <div style={{ marginBottom: 16 }}>
          <label style={{ display: 'block', color: '#aaa', fontSize: 12, marginBottom: 4 }}>Email</label>
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            style={{
              width: '100%',
              background: '#2a2a4a',
              color: '#e0e0e0',
              border: '1px solid #3a3a5a',
              borderRadius: 6,
              padding: '10px 12px',
              fontSize: 14,
              boxSizing: 'border-box',
            }}
          />
        </div>

        <div style={{ marginBottom: 24 }}>
          <label style={{ display: 'block', color: '#aaa', fontSize: 12, marginBottom: 4 }}>Password</label>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            minLength={6}
            style={{
              width: '100%',
              background: '#2a2a4a',
              color: '#e0e0e0',
              border: '1px solid #3a3a5a',
              borderRadius: 6,
              padding: '10px 12px',
              fontSize: 14,
              boxSizing: 'border-box',
            }}
          />
        </div>

        <button
          type="submit"
          style={{
            width: '100%',
            background: '#6366f1',
            color: '#fff',
            border: 'none',
            borderRadius: 6,
            padding: '12px',
            fontSize: 14,
            cursor: 'pointer',
            fontWeight: 'bold',
          }}
        >
          Create Account
        </button>

        {GOOGLE_CLIENT_ID && (
          <>
            <div style={{ display: 'flex', alignItems: 'center', margin: '20px 0', gap: 12 }}>
              <div style={{ flex: 1, height: 1, background: '#3a3a5a' }} />
              <span style={{ color: '#888', fontSize: 13 }}>or</span>
              <div style={{ flex: 1, height: 1, background: '#3a3a5a' }} />
            </div>
            <div ref={googleBtnRef} style={{ display: 'flex', justifyContent: 'center' }} />
          </>
        )}

        <p style={{ textAlign: 'center', marginTop: 16, fontSize: 13, color: '#888' }}>
          Already have an account? <Link to="/login" style={{ color: '#6366f1', textDecoration: 'none' }}>Sign In</Link>
        </p>
      </form>
    </div>
  );
}
