import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// In development the API is proxied through Vite, so the browser sees one origin
// (localhost:5173) and the httpOnly refresh cookie works without CORS tricks.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_PROXY_TARGET ?? 'http://localhost:8080',
        changeOrigin: false,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.js',
    env: { VITE_API_URL: 'http://localhost/api' },
  },
})
