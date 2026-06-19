import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',       // 必须：允许局域网和外网映射访问
    port: 5173,
    allowedHosts: true,    // 必须：允许花生壳等第三方域名访问
    proxy: {
      // 路由 1：专门发给 Python 大模型微服务 (8000端口)
      '/api/v1/chat': {
        target: 'http://127.0.0.1:8000',
        changeOrigin: true
      },
      
      // 路由 2：兜底转发给 Java Spring Boot 后端 (8080端口) 或 远程服务器
      '/api': {
        target: 'http://13.114.177.156',
        changeOrigin: true,
      }
    }
  }
})
