import { useAuth } from '../context/AuthContext'
import './Login.css'

export function Login({ title = 'Field Terminal', body = 'How you are today, the slow-moving picture, and the analysis, plans and log behind them.' }: { title?: string; body?: string }) {
  const { signInWithGoogle } = useAuth()
  return (
    <div className="login-page">
      <div className="login-card card">
        <div className="mono login-eyebrow">FIELD TERMINAL</div>
        <h1 className="login-title">{title}</h1>
        <p className="login-body">{body}</p>
        <button className="btn-signin" onClick={signInWithGoogle}>
          Sign in with Google
        </button>
      </div>
    </div>
  )
}
