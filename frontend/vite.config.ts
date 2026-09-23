import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'
import { mockPlugin } from './mock/vite-plugin'

// VITE_USE_MOCK=true 时不代理后端，改用本地 mock 中间件（见 mock/vite-plugin.ts）
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const useMock = env.VITE_USE_MOCK === 'true'
  return {
    plugins: [vue(), ...(useMock ? [mockPlugin()] : [])],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: {
      port: 5173,
      proxy: useMock
        ? undefined
        : {
            '/api': { target: 'http://localhost:8083', changeOrigin: true },
            '/sse': { target: 'http://localhost:8083', changeOrigin: true },
          },
    },
    build: { chunkSizeWarningLimit: 2000 },
  }
})
