import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

const r = (p: string) => fileURLToPath(new URL(p, import.meta.url));

export default defineConfig({
  // Bộ nhớ đệm nằm trong node_modules của workspace (đã bỏ qua trong git), không sinh pact/node_modules.
  cacheDir: r('../node_modules/.vite/pact'),
  resolve: {
    alias: {
      '@vt/api-client': r('../projects/api-client/src/public-api.ts'),
    },
  },
  test: {
    root: r('.'),
    include: ['kiem-thu/**/*.pact.spec.ts'],
    environment: 'jsdom',
    setupFiles: [r('./kiem-thu/thiet-lap.ts')],
  },
});
