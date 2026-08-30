import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

// Self-hosted rather than loaded from Google Fonts: a wallet screen should not
// announce itself to a third party on every page load, and the app keeps
// working when that third party does not.
import '@fontsource-variable/inter'

import './styles/tokens.css'
import './styles/base.css'
import App from './App.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
