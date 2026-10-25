#!/usr/bin/env node
/**
 * Kiểm tra khung xương khi tải (YC-PT-28, wiki/16 mục "Trạng thái đang tải bằng khung xương").
 *
 * Mọi tài nguyên dữ liệu của component (`rxResource`, `resource`, `httpResource`, và `toSignal`
 * bọc một lời gọi dịch vụ) phải có khung xương trên giao diện: template phải có ít nhất một
 * `<vt-skeleton>` nằm trong nhánh `@if`/`@else`/`@switch`/`@empty` mà điều kiện (kể cả điều kiện
 * của các nhánh đứng trước, và các `computed`/phương thức mà điều kiện gọi tới) đọc tài nguyên đó.
 * Ví dụ hợp lệ: `@if (ds.isLoading()) { <vt-skeleton …/> }`, `@if (ds.value(); as v) {…} @else
 * { <vt-skeleton …/> }`, hay `@if (loading()) {…}` với `loading = computed(() => ds.isLoading())`.
 *
 * Template cũng không được dùng biểu tượng xoay đơn độc (`p-progressSpinner`, `pi-spinner` ngoài
 * nút) hay `[loading]` của `p-table` (lớp phủ biểu tượng xoay) cho vùng dữ liệu.
 *
 * Tài nguyên chỉ cấp danh sách lựa chọn cho ô chọn (`p-select`, `p-multiselect`, `p-autocomplete`,
 * `p-treeselect`) đạt khi ô chọn đó gắn `[loading]` đọc trạng thái tải của tài nguyên.
 *
 * Tài nguyên không có vùng dữ liệu riêng (kiểm tra hợp lệ chạy ngầm, dữ liệu chuyển cho component con có khung xương riêng, tải theo
 * thao tác của người dùng có trạng thái chờ trên nút) được miễn bằng chú thích
 * `// skeleton-ok: <lý do>` trong ba dòng ngay trên khai báo. Người duyệt PR kiểm lý do.
 *
 * Chạy: `npm run check:skeleton` (tự chạy trước `npm run build:all`, và trong Jenkinsfile);
 * mã thoát 1 khi có vi phạm. `--list` in danh mục component có tải dữ liệu, `--json` in JSON.
 */
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import {
  parseTemplate,
  TmplAstElement,
  TmplAstTemplate,
  TmplAstIfBlock,
  TmplAstSwitchBlock,
  TmplAstForLoopBlock,
  TmplAstDeferredBlock,
  RecursiveAstVisitor,
  ImplicitReceiver,
  ThisReceiver,
  ASTWithSource,
} from '@angular/compiler';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const PROJECTS = join(ROOT, 'projects');
const LIST = process.argv.includes('--list');
const JSON_OUT = process.argv.includes('--json');

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules' || name.startsWith('.')) continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (p.endsWith('.ts') && !/\.(spec|stories)\.ts$/.test(p)) out.push(p);
  }
  return out;
}

const RESOURCE_FNS = new Set(['rxResource', 'resource', 'httpResource']);
/** Cách đọc trạng thái "đang tải" của một tài nguyên. */
const LOADING_READ = new Set(['isLoading', 'status']);
/**
 * `toSignal` coi là dữ liệu máy chủ khi đối số gọi một dịch vụ (`this.api.list(…)`,
 * `inject(TaskApi).myTasks(…)`), trừ dịch vụ tuyến, router và điểm ngắt màn hình.
 */
const DATA_CALL =
  /inject\(\w*Api\)\.\w+\(|this\.(?!route\b|router\b|activatedRoute\b|breakpoints?\b)\w+\.\w+\(/;

/**
 * Các component trong một tệp (một tệp có thể khai nhiều component, ví dụ các hộp thoại):
 * tài nguyên dữ liệu, phụ thuộc giữa các thành viên, template.
 */
function analyzeTs(file, src) {
  const sf = ts.createSourceFile(file, src, ts.ScriptTarget.Latest, true);
  const lines = src.split('\n');
  const components = [];
  const thisRefs = (node) => {
    const out = new Set();
    const visit = (n) => {
      if (ts.isPropertyAccessExpression(n) && n.expression.kind === ts.SyntaxKind.ThisKeyword)
        out.add(n.name.text);
      // `this.x.isLoading()`/`this.x.status()`: ghi thêm dấu `x#loading` để xét tài nguyên phụ thuộc
      if (
        ts.isPropertyAccessExpression(n) &&
        LOADING_READ.has(n.name.text) &&
        ts.isPropertyAccessExpression(n.expression) &&
        n.expression.expression.kind === ts.SyntaxKind.ThisKeyword
      )
        out.add(n.expression.name.text + '#loading');
      ts.forEachChild(n, visit);
    };
    visit(node);
    return out;
  };
  const visit = (node) => {
    if (!ts.isClassDeclaration(node)) return ts.forEachChild(node, visit);
    const comp = (ts.getDecorators?.(node) ?? []).find(
      (d) => ts.isCallExpression(d.expression) && d.expression.expression.getText() === 'Component',
    );
    if (!comp) return;
    const c = { name: node.name?.text ?? '?', resources: [], deps: new Map(), template: null };
    const arg = comp.expression.arguments[0];
    if (arg && ts.isObjectLiteralExpression(arg)) {
      for (const p of arg.properties) {
        if (!ts.isPropertyAssignment(p)) continue;
        const key = p.name.getText();
        if (key === 'template' && ts.isStringLiteralLike(p.initializer))
          c.template = p.initializer.text;
        else if (key === 'templateUrl' && ts.isStringLiteralLike(p.initializer)) {
          const path = join(dirname(file), p.initializer.text);
          if (existsSync(path)) c.template = readFileSync(path, 'utf8');
        }
      }
    }
    for (const m of node.members) {
      const name = m.name && ts.isIdentifier(m.name) ? m.name.text : null;
      if (!name) continue;
      if (ts.isPropertyDeclaration(m) && m.initializer) {
        c.deps.set(name, thisRefs(m.initializer));
        const init = m.initializer;
        if (!ts.isCallExpression(init)) continue;
        const fn = init.expression.getText();
        const line = sf.getLineAndCharacterOfPosition(m.getStart()).line;
        const exempt = /skeleton-ok:\s*\S/.test(
          lines.slice(Math.max(0, line - 3), line + 1).join('\n'),
        );
        if (RESOURCE_FNS.has(fn)) c.resources.push({ name, line: line + 1, exempt, kind: fn });
        else if (
          fn === 'toSignal' &&
          init.arguments[0] &&
          DATA_CALL.test(init.arguments[0].getText().replace(/\s+/g, ''))
        )
          c.resources.push({ name, line: line + 1, exempt, kind: fn });
      } else if ((ts.isMethodDeclaration(m) || ts.isGetAccessorDeclaration(m)) && m.body) {
        c.deps.set(name, thisRefs(m.body));
      }
    }
    components.push(c);
  };
  visit(sf);
  return components;
}

/** Tên thành viên component mà một biểu thức template đọc (`a.isLoading()`, `b()`, `c.d`). */
function exprNames(ast) {
  const out = new Set();
  class V extends RecursiveAstVisitor {
    visitPropertyRead(a, ctx) {
      if (a.receiver instanceof ImplicitReceiver || a.receiver instanceof ThisReceiver)
        out.add(a.name);
      else if (
        LOADING_READ.has(a.name) &&
        (a.receiver.receiver instanceof ImplicitReceiver ||
          a.receiver.receiver instanceof ThisReceiver)
      )
        out.add(a.receiver.name + '#loading');
      return super.visitPropertyRead(a, ctx);
    }
    visitSafePropertyRead(a, ctx) {
      if (a.receiver instanceof ImplicitReceiver || a.receiver instanceof ThisReceiver)
        out.add(a.name);
      return super.visitSafePropertyRead(a, ctx);
    }
  }
  const root = ast instanceof ASTWithSource ? ast.ast : ast;
  root?.visit?.(new V());
  return out;
}

const SPINNER_EL = /^p-progress-?spinner$|^p-progressSpinner$/i;
/** Ô chọn có trạng thái chờ riêng cho danh sách lựa chọn (`[loading]`), thay cho khung xương. */
const PICKER_EL =
  /^p-(select|multiselect|multi-select|autocomplete|auto-complete|treeselect|tree-select|cascadeselect|cascade-select)$/i;

/** Duyệt template: điều kiện bao quanh từng `<vt-skeleton>` và các chỗ biểu tượng xoay đơn độc. */
function analyzeTemplate(file, template) {
  const skeletons = [];
  const spinners = [];
  const pickers = [];
  let parsed;
  try {
    parsed = parseTemplate(template, file, { preserveWhitespaces: false, leadingTriviaChars: [] });
  } catch {
    return { skeletons, spinners, pickers, ok: false };
  }
  const visit = (nodes, cond, aliases) => {
    for (const n of nodes ?? []) {
      if (n instanceof TmplAstElement) {
        if (n.name === 'vt-skeleton')
          skeletons.push({ cond: new Set(cond), line: n.sourceSpan.start.line + 1 });
        if (PICKER_EL.test(n.name))
          for (const i of n.inputs) if (i.name === 'loading') pickers.push(exprNames(i.value));
        if (SPINNER_EL.test(n.name))
          spinners.push({ line: n.sourceSpan.start.line + 1, what: n.name });
        if (n.name === 'p-table' && n.inputs.some((i) => i.name === 'loading'))
          spinners.push({ line: n.sourceSpan.start.line + 1, what: 'p-table [loading]' });
        const cls = n.attributes.find((a) => a.name === 'class')?.value ?? '';
        if (/\bpi-spinner\b/.test(cls) && !/^(button|p-button)$/.test(n.name))
          spinners.push({ line: n.sourceSpan.start.line + 1, what: 'pi-spinner' });
        visit(n.children, cond, aliases);
      } else if (n instanceof TmplAstTemplate) {
        visit(n.children, cond, aliases);
      } else if (n instanceof TmplAstIfBlock) {
        const acc = new Set(cond);
        for (const b of n.branches) {
          if (b.expression) for (const x of exprNames(b.expression)) acc.add(x);
          visit(b.children, acc, aliases);
        }
      } else if (n instanceof TmplAstSwitchBlock) {
        const acc = new Set([...cond, ...exprNames(n.expression)]);
        // Angular 21 gom các @case liền nhau thành nhóm (`groups`); bản cũ hơn dùng `cases`
        for (const g of n.groups ?? n.cases ?? []) {
          for (const c of g.cases ?? [g])
            if (c.expression) for (const x of exprNames(c.expression)) acc.add(x);
          visit(g.children, acc, aliases);
        }
      } else if (n instanceof TmplAstForLoopBlock) {
        visit(n.children, cond, aliases);
        if (n.empty)
          visit(n.empty.children, new Set([...cond, ...exprNames(n.expression)]), aliases);
      } else if (n instanceof TmplAstDeferredBlock) {
        visit(n.children, cond, aliases);
        for (const k of ['placeholder', 'loading', 'error'])
          if (n[k]) visit(n[k].children, cond, aliases);
      } else if (n.children) {
        visit(n.children, cond, aliases);
      }
    }
  };
  visit(parsed.nodes, new Set(), new Map());
  return { skeletons, spinners, pickers, ok: true };
}

/** Mở rộng tập tên theo phụ thuộc `computed`/phương thức (bắc cầu). */
function closure(names, deps, skip) {
  const out = new Set(names);
  const stack = [...names];
  while (stack.length) {
    const n = stack.pop();
    if (n === skip) continue;
    for (const d of deps.get(n) ?? []) if (!out.has(d)) (out.add(d), stack.push(d));
  }
  return out;
}

const files = walk(PROJECTS).filter((f) => {
  const rel = relative(PROJECTS, f);
  return !rel.startsWith('api-client/') && !rel.includes('/mock/');
});

const rows = [];
const problems = [];
for (const file of files) {
  const src = readFileSync(file, 'utf8');
  if (!/@Component\s*\(/.test(src)) continue;
  const rel = relative(ROOT, file);
  for (const { name: cls, resources, deps, template } of analyzeTs(file, src)) {
    const t = template
      ? analyzeTemplate(file, template)
      : { skeletons: [], spinners: [], pickers: [], ok: true };
    for (const s of t.spinners)
      problems.push(
        `${rel} ${cls} (template dòng ${s.line}): ${s.what} làm trạng thái tải, thay bằng <vt-skeleton>`,
      );
    if (!resources.length) continue;
    const covers = t.skeletons.map((s) => closure(s.cond, deps));
    const names = new Set(resources.map((r) => r.name));
    const pickers = t.pickers.map((p) => closure(p, deps));
    const result = resources.map((r) => {
      const covered = covers.some((c) => c.has(r.name)) || pickers.some((c) => c.has(r.name));
      // Tài nguyên phụ thuộc tài nguyên khác (tham số lấy từ kết quả của tài nguyên cha): khi cha
      // chưa về, con còn chờ tham số và `isLoading()` còn sai, nên nhánh khung xương dựa trên
      // `con.isLoading()` phải gộp cả trạng thái của cha (wiki/16)
      const upstream = [...closure(deps.get(r.name) ?? [], deps)].filter(
        (n) => n !== r.name && names.has(n),
      );
      // Không mở rộng qua chính tài nguyên con: phụ thuộc của nó luôn chứa tài nguyên cha
      const chainOk =
        !upstream.length ||
        t.skeletons.every((sk) => {
          const c = closure(sk.cond, deps, r.name);
          return !c.has(r.name + '#loading') || upstream.every((u) => c.has(u));
        });
      if (covered && !chainOk && !r.exempt)
        problems.push(
          `${rel}:${r.line} ${cls}."${r.name}" chờ tham số từ ${upstream.join(', ')}: điều kiện ` +
            `khung xương phải gộp cả trạng thái tải của ${upstream.join(', ')} ` +
            `(ví dụ ${upstream[0]}.isLoading() || ${r.name}.isLoading())`,
        );
      if (!covered && !r.exempt)
        problems.push(
          `${rel}:${r.line} ${cls}."${r.name}" (${r.kind}) chưa có khung xương: đặt <vt-skeleton> trong nhánh ` +
            (r.kind === 'toSignal'
              ? `@else của @if đọc ${r.name}()`
              : `@if đọc ${r.name}.isLoading() (hoặc @else của @if đọc ${r.name}.value())`) +
            `, gắn [loading] của ô chọn dùng nó, hoặc ghi "// skeleton-ok: <lý do>" nếu tài nguyên ` +
            `không có vùng dữ liệu riêng`,
        );
      return { name: r.name, covered, exempt: r.exempt };
    });
    rows.push({ file: `${rel} ${cls}`, skeletons: t.skeletons.length, resources: result });
  }
}

if (JSON_OUT) {
  process.stdout.write(JSON.stringify({ rows, problems }, null, 2) + '\n');
} else if (LIST) {
  for (const r of rows) {
    const s = r.resources.map(
      (x) => `${x.name}${x.covered ? '' : x.exempt ? '(miễn)' : '(THIẾU)'}`,
    );
    console.log(`${r.skeletons}\t${r.file}\t${s.join(', ')}`);
  }
}
if (problems.length) {
  console.error(`\nKhung xương khi tải: ${problems.length} vi phạm (YC-PT-28, wiki/16)`);
  for (const p of problems) console.error('  - ' + p);
  process.exit(1);
}
if (!JSON_OUT) {
  const n = rows.reduce((a, r) => a + r.resources.length, 0);
  console.log(
    `Khung xương khi tải: ${rows.length} component, ${n} tài nguyên dữ liệu, không có vi phạm.`,
  );
}
