/**
 * Sinh ca kiểm thử "vai trò × tuyến" từ ma trận phân quyền (WEB-057).
 *
 * Kỳ vọng tính trực tiếp từ `docs/phan-quyen/ma-tran.json` theo cùng quy tắc với bảng quyền của
 * `@vt/auth` (`gen_matrix.py`, hàm `ts_source`): chỉ chức năng `web`, bỏ AC-11 và AC-12, ô khác `-`
 * là có quyền (kể cả ô suy luận `*`), ô có `vai_tro_con` thuộc về vai trò con. Nhóm khoá của menu
 * và tuyến đọc từ mã của WEB-055 (`layout/nav.ts`, `app.routes.ts`, `task.routes.ts`), nên sửa ma
 * trận mà không sửa giao diện (hoặc ngược lại) đều làm ca kiểm thử đỏ.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const REPO = join(__dirname, '..', '..');
const SHELL = join(REPO, 'frontend/projects/fe-shell/src/app');
const read = (p: string) => readFileSync(p, 'utf8');

interface MatrixRole {
  ac: string;
  role: string;
}
interface MatrixFunction {
  key: string;
  web: boolean;
  /** `false`: năng lực nền, không tính là vào được màn hình (giống `screenKeys` của `@vt/auth`). */
  mo_man_hinh?: boolean;
  quyen: Record<string, string>;
  vai_tro_con?: Record<string, string>;
}
interface Matrix {
  vai_tro: MatrixRole[];
  chuc_nang: MatrixFunction[];
}

const MATRIX: Matrix = JSON.parse(read(join(REPO, 'docs/phan-quyen/ma-tran.json')));
const NO_UI = new Set(['AC-11', 'AC-12']);

/** Khoá chức năng web mà từng realm role có quyền, theo đúng quy tắc của bảng quyền giao diện. */
export const KEYS_BY_ROLE: ReadonlyMap<string, ReadonlySet<string>> = (() => {
  const map = new Map<string, Set<string>>();
  for (const r of MATRIX.vai_tro) map.set(r.role, new Set());
  for (const f of MATRIX.chuc_nang.filter((x) => x.web)) {
    for (const r of MATRIX.vai_tro) {
      if (NO_UI.has(r.ac) || f.quyen[r.ac] === '-') continue;
      const role = f.vai_tro_con?.[r.ac] ?? r.role;
      if (!map.has(role)) map.set(role, new Set());
      map.get(role)!.add(f.key);
    }
  }
  return map;
})();

/** Vai trò cần thử ở chế độ giả lập: AC-01..AC-10, AC-12 và vai trò con khai báo trong ma trận. */
export const MOCK_ROLES: { ac: string; role: string }[] = [
  ...MATRIX.vai_tro.filter((r) => r.ac !== 'AC-11').map((r) => ({ ac: r.ac, role: r.role })),
  ...[...new Set(MATRIX.chuc_nang.flatMap((f) => Object.entries(f.vai_tro_con ?? {})))].map(
    ([ac, role]) => ({ ac: `${ac} (vai trò con)`, role }),
  ),
].filter((r, i, all) => all.findIndex((x) => x.role === r.role) === i);

const ALL_KEYS = MATRIX.chuc_nang.filter((f) => f.web && f.mo_man_hinh !== false).map((f) => f.key);
export const screenKeys = (screens: string[]) =>
  ALL_KEYS.filter((k) => screens.includes(k.slice(0, k.indexOf('.'))));
const screensIn = (args: string) => [...args.matchAll(/'(SCR-\d{4})'/g)].map((m) => m[1]);

/** `SECTION_KEYS` của `layout/nav.ts`: tên nhóm → khoá chức năng. */
function sections(): Map<string, string[]> {
  const src = read(join(SHELL, 'layout/nav.ts'));
  const body = src.slice(src.indexOf('SECTION_KEYS = {'), src.indexOf('} satisfies'));
  const out = new Map<string, string[]>();
  for (const m of body.matchAll(/(\w+):\s*screenKeys\(([^)]*)\)/g)) {
    out.set(m[1], screenKeys(screensIn(m[2])));
  }
  return out;
}

export interface ShellSection {
  label: string;
  path: string;
  /** Khoá làm mục menu hiện. */
  menuKeys: string[];
  /** Khoá làm tuyến cha cho qua (`canMatch` trong `app.routes.ts`). */
  routeKeys: string[];
}

/** Mục menu có khoá trong `NAV` và tuyến cha tương ứng trong `app.routes.ts`. */
export function shellSections(): ShellSection[] {
  const groups = sections();
  const nav = read(join(SHELL, 'layout/nav.ts'));
  const routes = read(join(SHELL, 'app.routes.ts'));
  const items = nav.slice(nav.indexOf('export const NAV'));
  const out: ShellSection[] = [];
  for (const m of items.matchAll(
    /\{[^{}]*?label:\s*'([^']+)'[^{}]*?link:\s*'([^']+)'[^{}]*?\}/gs,
  )) {
    const [block, label, link] = m;
    const perms = /perms:\s*(?:SECTION_KEYS\.(\w+)|screenKeys\(([^)]*)\))/.exec(block);
    if (!perms) continue;
    const menuKeys = perms[1] ? (groups.get(perms[1]) ?? []) : screenKeys(screensIn(perms[2]));
    const path = link.replace(/^\//, '');
    const route = new RegExp(
      `path: '${path}',\\s*canMatch: \\[permissionGuard\\(\\.\\.\\.K\\.(\\w+)\\)\\]`,
    ).exec(routes);
    if (!route) throw new Error(`app.routes.ts không có canMatch theo SECTION_KEYS cho /${path}`);
    out.push({ label, path: link, menuKeys, routeKeys: groups.get(route[1]) ?? [] });
  }
  return out;
}

export interface ChildRoute {
  /** Đường dẫn trong tệp tuyến của remote, ví dụ `assign/:id`. */
  path: string;
  /** Đường dẫn đầy đủ để mở thử (thay tham số `:id` bằng mã giả). */
  url: string;
  /** Mẫu khớp đường dẫn đầy đủ của tuyến (tham số khớp một đoạn bất kỳ). */
  pattern: RegExp;
  keys: string[];
}

/**
 * Tuyến con có `canActivate: [permissionGuard(...)]` trong một tệp tuyến của remote (quy ước wiki/10).
 * Đối số của guard là khoá viết thẳng (`'SCR-0101.xem'`), `screenKeys(...)` hoặc một hằng khai báo
 * bằng `const X = screenKeys(...)` trong cùng tệp.
 */
/** Đường dẫn các tuyến cha lồng (`children`) bao quanh vị trí `at`, suy theo thụt lề của `path:`. */
function parentPaths(src: string, at: number): string[] {
  const lines = src.slice(0, at).split('\n');
  let indent = lines.at(-1)!.search(/\S|$/);
  const out: string[] = [];
  for (let i = lines.length - 2; i >= 0; i--) {
    const m = /^(\s*)path: '([^']*)'/.exec(lines[i]);
    if (m && m[1].length < indent) {
      out.unshift(m[2]);
      indent = m[1].length;
    }
  }
  return out;
}

/** Hằng nhóm khoá trong tệp tuyến và các tệp nó import tương đối (`./studio-layout`...). */
function keyConsts(file: string, src: string): Map<string, string[]> {
  const dir = join(REPO, 'frontend/projects', file, '..');
  const sources = [src];
  for (const m of src.matchAll(/from '(\.{1,2}\/[^']+)'/g)) {
    try {
      sources.push(read(join(dir, `${m[1]}.ts`)));
    } catch {
      // import không phải tệp .ts cùng cây (thư mục có index): không có hằng khoá cần đọc
    }
  }
  const consts = new Map<string, string[]>();
  for (const text of sources) {
    for (const m of text.matchAll(/const (\w+) = screenKeys\(([^)]*)\)/g))
      consts.set(m[1], screenKeys(screensIn(m[2])));
    for (const m of text.matchAll(/const (\w+)(?:: \w+\[\])? = \[([^\]]*)\]/g)) {
      const keys = [...m[2].matchAll(/'(SCR-\d{4}\.[a-z0-9-]+)'/g)].map((k) => k[1]);
      if (keys.length) consts.set(m[1], keys);
    }
  }
  return consts;
}

export function childRoutes(file: string, base: string): ChildRoute[] {
  const src = read(join(REPO, 'frontend/projects', file));
  const consts = keyConsts(file, src);
  const out: ChildRoute[] = [];
  const add = (path: string, at: number, args: string) => {
    args = args.trim();
    const keys = args.startsWith('...screenKeys(')
      ? screenKeys(screensIn(args))
      : args.startsWith('...')
        ? (consts.get(args.slice(3)) ?? [])
        : [...args.matchAll(/'(SCR-\d{4}\.[a-z0-9-]+)'/g)].map((k) => k[1]);
    const segs = [...parentPaths(src, at), path].filter(Boolean);
    const full = segs.length ? `${base}/${segs.join('/')}` : base;
    out.push({
      path,
      url: full.replace(/:\w+/g, 'E2E-1'),
      pattern: new RegExp(`^${full.replace(/:\w+/g, '[^/]+')}$`),
      keys,
    });
  };
  for (const m of src.matchAll(
    /path: '([^']*)',[^{}]*?canActivate: \[permissionGuard\(([^\]]*)\)\]/g,
  ))
    add(m[1], m.index, m[2]);
  // Nhiều tuyến chung guard: `...['a', 'a/:id'].map((path) => ({ path, canActivate: [...] }))`
  for (const m of src.matchAll(
    /\.\.\.\[([^\]]*)\]\.map\(\(path\) => \(\{\s*path,[^{}]*?canActivate: \[permissionGuard\(([^\]]*)\)\]/g,
  ))
    for (const p of m[1].matchAll(/'([^']*)'/g)) add(p[1], m.index, m[2]);
  return out;
}

/**
 * Tuyến con của mọi remote mà shell gắn: đọc `remoteRoutes('<khoá>', '<expose>')` trong
 * `app.routes.ts`, tra dự án ở `tools/remotes/remotes.json` và tệp tuyến ở `federation.config.js`.
 */
export function remoteChildRoutes(): ChildRoute[] {
  const routes = read(join(SHELL, 'app.routes.ts'));
  const registry: { remotes: { key: string; project: string }[] } = JSON.parse(
    read(join(REPO, 'frontend/tools/remotes/remotes.json')),
  );
  const out: ChildRoute[] = [];
  for (const m of routes.matchAll(
    /path: '([^']+)'[^}]*?remoteRoutes\('(\w+)'(?:, '([^']+)')?\)/g,
  )) {
    const project = registry.remotes.find((r) => r.key === m[2])?.project;
    if (!project) throw new Error(`Không thấy remote ${m[2]} trong remotes.json`);
    const fed = read(join(REPO, 'frontend/projects', project, 'federation.config.js'));
    const expose = m[3] ?? './routes';
    const file = new RegExp(
      `'${expose.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}': '\\./projects/([^']+)'`,
    ).exec(fed)?.[1];
    if (!file) throw new Error(`${project} không công bố ${expose}`);
    out.push(...childRoutes(file, `/${m[1]}`));
  }
  return out;
}

export const canAny = (roles: string[], keys: string[]) =>
  roles.some((r) => keys.some((k) => KEYS_BY_ROLE.get(r)?.has(k)));
