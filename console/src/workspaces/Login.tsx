import { useAuth } from '../context/AuthContext'
import './Login.css'

export function Login() {
  const { signInWithGoogle } = useAuth()
  return (
    <div className="login-page">
      <div className="login-card card">
        <div className="mono login-eyebrow">FIELD TERMINAL</div>
        <h1 className="login-title">Analysis Console</h1>
        <p className="login-body">Large-screen analytical workstation for longitudinal health and performance data.</p>
        <button className="btn-signin" onClick={signInWithGoogle}>
          Sign in with Google
        </button>
      </div>
    </div>
  )
}
