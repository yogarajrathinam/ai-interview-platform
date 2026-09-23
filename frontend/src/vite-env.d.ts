/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_CANDIDATE_ID?: string
  readonly VITE_TEMPLATE_KEY?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
