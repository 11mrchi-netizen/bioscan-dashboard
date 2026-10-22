import react from '@vitejs/plugin-react'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react()],
  base: './',
  // Analysis review phase G: the lab's event explorer runs the shared analysis core from ../supabase/functions/_shared;
  // the dev server only serves files from the allowed folders.
  server: {
    fs: {
      allow: [fileURLToPath(new URL('.', import.meta.url)), fileURLToPath(new URL('../supabase/functions/_shared', import.meta.url))],
    },
  },
  build: {
    outDir: 'dist',
    rolldownOptions: {
      // The source entry is app.html. console/index.html is the SERVED build output: the deploy workflow copies dist/app.html
      // over it, so building from index.html (the default) re-bundled the previous build instead of src/ from 2026-09-30 on.
      input: fileURLToPath(new URL('./app.html', import.meta.url)),
      output: {
        codeSplitting: true,
        manualChunks(id) {
          if (id.includes('echarts')) return 'echarts'
          if (id.includes('@supabase')) return 'supabase'
          if (id.includes('react-dom') || id.includes('react-router') || id.includes('/react/')) return 'react'
        },
      },
    },
  },
})
