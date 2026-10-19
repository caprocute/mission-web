// WEB-015: lệnh workspace đọc danh mục remote ở tools/remotes/remotes.json.
//   node tools/remotes/workspace.mjs build                build mọi remote rồi fe-shell
//   node tools/remotes/workspace.mjs test                 Vitest cho thư viện, remote, shell có spec
//   node tools/remotes/workspace.mjs assemble <dir> [tsv] ghép shell + remotes/<project> như nginx
//   node tools/remotes/workspace.mjs new <project> <port> <route> "<tiêu đề>" "<QT>" "<SCR>"
import { execFileSync } from 'node:child_process';
import {
  cpSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const REGISTRY = join(ROOT, 'tools/remotes/remotes.json');
const LIBS = ['design-system', 'auth', 'sdui-renderer', 'form-engine', 'telemetry', 'api-client'];

const readJson = (p) => JSON.parse(readFileSync(p, 'utf8'));
const registry = () => readJson(REGISTRY);
const ng = (...args) => execFileSync('npx', ['ng', ...args], { cwd: ROOT, stdio: 'inherit' });

function hasSpec(dir) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory() ? hasSpec(p) : e.name.endsWith('.spec.ts')) return true;
  }
  return false;
}

const commands = {
  build() {
    for (const r of registry().remotes) ng('build', r.project);
    ng('build', 'fe-shell');
  },

  test() {
    const projects = [...LIBS, ...registry().remotes.map((r) => r.project), 'fe-shell'];
    for (const p of projects) {
      if (!hasSpec(join(ROOT, 'projects', p))) {
        console.log(`[test:all] ${p}: chưa có tệp *.spec.ts, bỏ qua`);
        continue;
      }
      ng('test', p, '--watch=false');
    }
  },

  /** Bố cục giống deploy/web: shell ở gốc, remote ở remotes/<project>, manifest trỏ cùng nguồn. */
  assemble(outDir, tsvPath) {
    if (!outDir) throw new Error('Thiếu thư mục đích');
    const out = join(process.cwd(), outDir);
    rmSync(out, { recursive: true, force: true });
    cpSync(join(ROOT, 'dist/fe-shell/browser'), out, { recursive: true });
    const manifest = {};
    const tsv = [];
    for (const r of registry().remotes) {
      const src = join(ROOT, 'dist', r.project, 'browser');
      if (!existsSync(src)) throw new Error(`Chưa build ${r.project}`);
      cpSync(src, join(out, 'remotes', r.project), { recursive: true });
      manifest[r.key] = `/remotes/${r.project}/remoteEntry.json`;
      tsv.push(`${r.key}\t${envVar(r.project)}\t${r.project}`);
    }
    writeFileSync(join(out, 'federation.manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
    if (tsvPath) {
      mkdirSync(dirname(join(process.cwd(), tsvPath)), { recursive: true });
      writeFileSync(join(process.cwd(), tsvPath), tsv.join('\n') + '\n');
    }
    console.log(
      `[assemble] ${relative(process.cwd(), out) || out}: fe-shell + ${tsv.length} remote`,
    );
  },

  new(project, port, route, title, qt, screens) {
    if (!/^fe-[a-z][a-z-]*$/.test(project ?? '')) throw new Error('Tên project dạng fe-<ten>');
    if (!Number.isInteger(Number(port)) || !route || !title) {
      throw new Error('Cách dùng: new <project> <port> <route> "<tiêu đề>" "<QT>" "<SCR>"');
    }
    const reg = registry();
    if (reg.remotes.some((r) => r.project === project)) throw new Error(`${project} đã có`);
    if (reg.remotes.some((r) => r.port === Number(port))) throw new Error(`Cổng ${port} đã dùng`);
    const short = project.slice(3);
    const vars = {
      __PROJECT__: project,
      __SHORT__: short,
      __CLASS__: short.replace(/(^|-)([a-z])/g, (_, __, c) => c.toUpperCase()),
      __PORT__: String(port),
      __ROUTE__: route,
      __TITLE__: title,
      __QT__: qt || 'QT chưa ghi',
      __SCREENS__: screens || 'chưa ghi',
    };
    const fill = (s) => Object.entries(vars).reduce((a, [k, v]) => a.split(k).join(v), s);
    const projDir = join(ROOT, 'projects', project);
    if (existsSync(projDir)) throw new Error(`Đã có thư mục ${projDir}`);

    // 1. Mã nguồn từ mẫu
    const tplDir = join(ROOT, 'tools/remotes/template');
    const walk = (dir) => {
      for (const e of readdirSync(dir)) {
        const p = join(dir, e);
        if (statSync(p).isDirectory()) walk(p);
        else {
          const target = join(projDir, fill(relative(tplDir, p)).replace(/\.tpl$/, ''));
          mkdirSync(dirname(target), { recursive: true });
          writeFileSync(target, fill(readFileSync(p, 'utf8')));
        }
      }
    };
    walk(tplDir);
    mkdirSync(join(projDir, 'public'), { recursive: true });
    cpSync(join(ROOT, 'projects/fe-task/public/favicon.ico'), join(projDir, 'public/favicon.ico'));

    // 2. angular.json: nhân bản cấu hình fe-task (build native-federation, esbuild, serve, test)
    const angularPath = join(ROOT, 'angular.json');
    const angular = readJson(angularPath);
    const tpl = JSON.stringify(angular.projects['fe-task']).split('fe-task').join(project);
    const conf = JSON.parse(tpl);
    conf.architect['serve-original'].options.port = Number(port);
    angular.projects[project] = conf;
    writeFileSync(angularPath, JSON.stringify(angular, null, 2) + '\n');

    // 3. tsconfig.json: thêm tham chiếu (giữ nguyên định dạng tệp)
    const tsPath = join(ROOT, 'tsconfig.json');
    const ts = readFileSync(tsPath, 'utf8');
    const anchor = '    {\n      "path": "./projects/design-system/tsconfig.lib.json"\n    },';
    if (!ts.includes(anchor))
      throw new Error('Không tìm thấy chỗ chèn tham chiếu trong tsconfig.json');
    const refs = ['app', 'spec']
      .map((k) => `    {\n      "path": "./projects/${project}/tsconfig.${k}.json"\n    },\n`)
      .join('');
    writeFileSync(tsPath, ts.replace(anchor, refs + anchor));

    // 4. Danh mục remote và manifest dev của shell
    const key = 'fe' + vars.__CLASS__;
    reg.remotes.push({ project, key, port: Number(port), route });
    writeFileSync(REGISTRY, JSON.stringify(reg, null, 2) + '\n');
    const manPath = join(ROOT, 'projects/fe-shell/public/federation.manifest.json');
    const man = readJson(manPath);
    man[key] = `http://localhost:${port}/remoteEntry.json`;
    writeFileSync(manPath, JSON.stringify(man, null, 2) + '\n');

    execFileSync('npx', ['prettier', '--write', angularPath, REGISTRY, manPath, projDir], {
      cwd: ROOT,
      stdio: 'ignore',
    });
    console.log(`[new-remote] ${project}: cổng ${port}, khoá manifest ${key}, tuyến /${route}`);
    console.log(`  Việc còn lại: trong fe-shell/src/app/app.routes.ts đổi tuyến '${route}' sang`);
    console.log(`  loadChildren: remoteRoutes('${key}'), giữ roleGuard và chú thích QT, SCR.`);
  },
};

/** Biến môi trường URL của remote lúc chạy container, ví dụ fe-config-studio → FE_CONFIG_STUDIO_URL. */
function envVar(project) {
  return project.toUpperCase().replace(/-/g, '_') + '_URL';
}

const [cmd, ...args] = process.argv.slice(2);
if (!commands[cmd]) {
  console.error(`Lệnh không hợp lệ: ${cmd ?? '(trống)'}; dùng build | test | assemble | new`);
  process.exit(2);
}
try {
  commands[cmd](...args);
} catch (e) {
  console.error(`[workspace] ${e.message}`);
  process.exit(1);
}
