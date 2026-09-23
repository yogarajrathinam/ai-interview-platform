import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { DEV_CANDIDATE_ID, TEMPLATE_KEY } from './config'
import './styles.css'

const root = document.getElementById('root')
if (!root) {
  throw new Error('Root element is missing from index.html')
}

createRoot(root).render(
  <StrictMode>
    <App candidateUserId={DEV_CANDIDATE_ID} templateKey={TEMPLATE_KEY} />
  </StrictMode>,
)
