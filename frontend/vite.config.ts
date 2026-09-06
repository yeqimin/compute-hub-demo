import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({ resolvers: [ElementPlusResolver({ importStyle: 'css' })], dts: 'src/auto-imports.d.ts' }),
    Components({ resolvers: [ElementPlusResolver({ importStyle: 'css' })], dts: 'src/components.d.ts' }),
  ],
  server: { port: 5173, proxy: { '/api': 'http://localhost:8080' } },
  build: {
    chunkSizeWarningLimit: 600,
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [
            { name: 'echarts', test: /node_modules[\\/]echarts[\\/]/ },
          ],
        },
      },
    },
  },
  test: { environment: 'jsdom', server: { deps: { inline: ['element-plus'] } } },
})
