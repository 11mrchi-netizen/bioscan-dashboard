// The Dashboard (Nov 2026 transition plan): the web's glance surface, Today for any day and Me. Same app as the Console
// (shared sign-in, data code and components), its own entry page; deployed at the site root.
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { HashRouter } from 'react-router-dom'
import { AuthProvider } from '../context/AuthContext'
import { ErrorBoundary } from '../components/shared/ErrorBoundary'
import DashboardApp from './DashboardApp'
import '../components/shared/ErrorBoundary.css'
import '../index.css'
import '../../../design/tokens/tokens.css'
import '../../../design/tokens/backgrounds.css'
import '../../../design/tokens/components.css'
import './Dashboard.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary level="app">
      <HashRouter>
        <AuthProvider>
          <DashboardApp />
        </AuthProvider>
      </HashRouter>
    </ErrorBoundary>
  </StrictMode>,
)
