// Test chạy trong Node (Vitest); lấy fs, path qua getBuiltinModule để không cần kiểu @types/node
const nodeProcess = (
  globalThis as unknown as { process: { cwd(): string; getBuiltinModule(id: string): unknown } }
).process;
const { readFileSync, readdirSync, statSync } = nodeProcess.getBuiltinModule('node:fs') as {
  readFileSync(p: string, enc: 'utf8'): string;
  readdirSync(p: string): string[];
  statSync(p: string): { isDirectory(): boolean };
};
const { join } = nodeProcess.getBuiltinModule('node:path') as { join(...p: string[]): string };

function files(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) files(p, out);
    else if (p.endsWith('.ts') && !p.endsWith('.spec.ts') && !p.endsWith('spec-util.ts'))
      out.push(p);
  }
  return out;
}

/**
 * WEB-074: remote nạp trong shell phải dùng HttpClient và chuỗi interceptor của shell (traceparent,
 * Idempotency-Key, cắt tải). `provideHttpClient` chỉ được đặt trong `app.config.ts` của remote, tệp
 * này chỉ dùng khi chạy riêng remote; đặt ở tuyến hay component sẽ tạo HttpClient riêng mất interceptor.
 */
describe('Remote dùng chung HttpClient của shell', () => {
  it('không remote nào tự provideHttpClient ngoài app.config.ts', () => {
    const root = join(nodeProcess.cwd(), 'projects');
    const offenders: string[] = [];
    for (const project of readdirSync(root).filter(
      (p) => p.startsWith('fe-') && p !== 'fe-shell',
    )) {
      for (const f of files(join(root, project, 'src/app'))) {
        if (f.endsWith('app.config.ts')) continue;
        if (/provideHttpClient\s*\(|HttpClientModule/.test(readFileSync(f, 'utf8')))
          offenders.push(f);
      }
    }
    expect(offenders).toEqual([]);
  });
});
