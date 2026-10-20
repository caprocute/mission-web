import { AURA_COMPONENTS } from './aura-core';

// Test chạy trong Node (Vitest); lấy fs, path qua getBuiltinModule để không cần kiểu @types/node
interface NodeFs {
  readFileSync(p: string, enc: 'utf8'): string;
  readdirSync(p: string): string[];
  statSync(p: string): { isDirectory(): boolean };
}
const nodeProcess = (
  globalThis as unknown as {
    process: { cwd(): string; getBuiltinModule(id: string): unknown };
  }
).process;
const { readFileSync, readdirSync, statSync } = nodeProcess.getBuiltinModule('node:fs') as NodeFs;
const { join } = nodeProcess.getBuiltinModule('node:path') as { join(...p: string[]): string };

/**
 * WEB-052 pha 2: preset Aura rút gọn phải có token của mọi component PrimeNG mà mã nguồn dùng,
 * kể cả component con PrimeNG tự dựng bên trong (tra theo import trong gói primeng).
 */
const ROOT = nodeProcess.cwd();
const AURA_DIR = join(ROOT, 'node_modules/@primeuix/themes/dist/aura');
const PRIMENG_DIR = join(ROOT, 'node_modules/primeng/fesm2022');
const IMPORT = /from ['"]primeng\/([a-z]+)['"]/g;

function sourceFiles(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) sourceFiles(p, out);
    else if (p.endsWith('.ts') && !p.endsWith('.spec.ts')) out.push(p);
  }
  return out;
}

function primengDeps(mod: string): string[] {
  try {
    const src = readFileSync(join(PRIMENG_DIR, `primeng-${mod}.mjs`), 'utf8');
    return [...src.matchAll(IMPORT)].map((m) => m[1]);
  } catch {
    return [];
  }
}

describe('Preset Aura rút gọn', () => {
  it('có token cho mọi component PrimeNG được dùng trong shell, remote và thư viện', () => {
    const used = new Set<string>();
    for (const dir of [join(ROOT, 'projects'), join(ROOT, 'tools/remotes/template')]) {
      for (const f of sourceFiles(dir)) {
        for (const m of readFileSync(f, 'utf8').matchAll(IMPORT)) used.add(m[1]);
      }
    }
    expect(used.has('button')).toBe(true);
    const stack = [...used];
    while (stack.length) {
      for (const d of primengDeps(stack.pop()!)) if (!used.has(d)) (used.add(d), stack.push(d));
    }
    const themed = new Set(readdirSync(AURA_DIR).filter((d) => d !== 'base' && d !== 'css'));
    const needed = [...used].filter((m) => themed.has(m)).sort();
    const missing = needed.filter((m) => !(m in AURA_COMPONENTS));
    expect(missing).toEqual([]);
  });
});
