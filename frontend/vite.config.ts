import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: { port: 5173, strictPort: true, proxy: { '/api': process.env.OPSFLOW_API_URL || 'http://127.0.0.1:8080' } },
  test: { environment: 'jsdom', clearMocks: true, include: ['src/**/*.test.ts'] },
})
