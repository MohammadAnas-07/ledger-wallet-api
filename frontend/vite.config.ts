import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  // The backend's host port is not fixed. docker-compose.yml publishes
  // "${SERVER_PORT:-8080}:8080", and a machine where something else already
  // holds 8080 sets SERVER_PORT to something else in the repository's root
  // .env — which is exactly the situation this was written on. Hardcoding 8080
  // here would send every call to whatever else is listening there, and the
  // symptom is a 404 that looks like a routing bug in this app.
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.VITE_API_TARGET ?? 'http://localhost:8080'

  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        // The backend has no CORS configuration — SecurityConfig never calls
        // .cors(...) — so a browser on :5173 cannot reach it directly.
        // Proxying makes every API call same-origin from the browser's point
        // of view, which keeps a complete and tested Stage 1 out of this
        // stage's diff.
        //
        // A development convenience, not the fix. Serving a production build
        // from a different origin still needs CORS on the backend; that work
        // is parked in phases.md, deliberately.
        '/api': {
          target,
          changeOrigin: true,
        },
      },
    },
  }
})
