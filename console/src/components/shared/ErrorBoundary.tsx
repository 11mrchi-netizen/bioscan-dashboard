import { Component, type ErrorInfo, type ReactNode } from 'react'

interface Props {
  children: ReactNode
  fallback?: ReactNode
  level?: 'app' | 'workspace' | 'chart'
}

interface State {
  error: Error | null
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error(`[BioScan] ${this.props.level ?? 'unknown'} error:`, error, info.componentStack)
  }

  reset = () => this.setState({ error: null })

  render() {
    if (!this.state.error) return this.props.children

    if (this.props.fallback) return this.props.fallback

    const level = this.props.level ?? 'app'
    return (
      <div className={`error-boundary error-boundary--${level}`}>
        <div className="error-boundary-content mono">
          <div className="error-boundary-label">
            {level === 'chart' ? 'CHART ERROR' : level === 'workspace' ? 'WORKSPACE ERROR' : 'APPLICATION ERROR'}
          </div>
          <div className="error-boundary-message">{this.state.error.message}</div>
          <button className="error-boundary-retry" onClick={this.reset}>RETRY</button>
        </div>
      </div>
    )
  }
}
