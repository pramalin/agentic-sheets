import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Proxies /internal/** to the real backend during `npm run dev`, so
    // the browser sees same-origin requests and there's no CORS
    // configuration to add on the backend side. AGENTIC_SHEETS_BACKEND_URL
    // matches this project's existing env-var naming convention
    // (AGENTIC_SHEETS_* throughout compose.yaml/.env.example); defaults
    // to the plain `docker compose up` backend port.
    proxy: {
      '/internal': {
        target: process.env.AGENTIC_SHEETS_BACKEND_URL ?? 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
})
