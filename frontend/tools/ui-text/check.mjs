// Kiểm tra nội dung giao diện (AGENTS.md quy tắc 9, wiki/16): bắt mã thiết kế, mã trạng thái thô, tên
// kỹ thuật và ghi chú giải pháp lộ ra màn hình. Quét văn bản trong template Angular (nút văn bản,
// thuộc tính tĩnh, chuỗi trong biểu thức) và chuỗi dạng câu trong mã TypeScript, kể cả dữ liệu giả lập.
// Cách dùng: `npm run check:ui-text` (thêm `--json` để in kết quả dạng JSON).
// Bỏ qua có chủ đích: đặt chú thích `ui-text-ok: <lý do>` ngay trên dòng (TS) hoặc trong `<!-- -->` (HTML).
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import {
  parseTemplate,
  TmplAstElement,
  TmplAstTemplate,
  TmplAstText,
  TmplAstBoundText,
  TmplAstTextAttribute,
  TmplAstBoundAttribute,
  TmplAstBoundEvent,
  TmplAstIfBlock,
  TmplAstForLoopBlock,
  TmplAstSwitchBlock,
  TmplAstDeferredBlock,
  RecursiveAstVisitor,
  LiteralPrimitive,
  TemplateLiteral,
  ASTWithSource,
} from '@angular/compiler';
import { RULES } from './rules.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const PROJECTS = join(ROOT, 'projects');
const JSON_OUT = process.argv.includes('--json');

/** Thuộc tính tĩnh không phải văn bản hiển thị. */
const NON_TEXT_ATTRS =
  /^(class|style|id|name|for|type|role|icon|iconPos|severity|size|routerLink|routerLinkActive|href|src|target|rel|tabindex|inputId|formControlName|optionLabel|optionValue|dataKey|selectionMode|scrollHeight|appendTo|layout|shape|mode|styleClass|autocomplete|inputmode|pattern|step|min|max|minlength|maxlength|rows|cols|width|height|viewBox|d|fill|stroke|xmlns|position|orientation|key|lang|dir|accept|method|loading|decoding|ngSkipHydration|track|i18n.*|data-.*|aria-(controls|describedby|labelledby|hidden|live|current|expanded|selected|pressed|haspopup|modal|level|orientation|valuenow|valuemin|valuemax|atomic|busy|owns|posinset|setsize|sort)|vt[A-Z].*|p[A-Z].*Class|[a-z]+Id|field|sortField|sortMode|filterBy|filterMatchMode|breakpoint|[a-z]*Breakpoint|[a-z]*Key|[a-z]*Name|layout|dateFormat|hourFormat|currencyCode|locale|variant|color|textAlign|panelStyleClass|appearance|aria-label-skip)$/;
/** Thuộc tính ràng buộc mà chuỗi bên trong không hiện lên màn hình. */
const NON_TEXT_BOUND =
  /^(class\..*|style\..*|attr\.(data-|aria-(controls|describedby|labelledby|hidden|live|current|expanded|selected|pressed|level)).*|routerLink|ngClass|ngStyle|class|style|severity|icon|styleClass|id|attr\.id|attr\.for|dataKey|field|key|testId|attr\.data-.*|value)$/;

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (name === 'node_modules' || name === 'generated' || name.startsWith('.')) continue;
    const st = statSync(p);
    if (st.isDirectory()) walk(p, out);
    else if (
      /\.(ts|html)$/.test(name) &&
      !name.includes('.generated.') &&
      !/\.(spec|test)\.ts$/.test(name) &&
      !name.endsWith('.d.ts')
    )
      out.push(p);
  }
  return out;
}

const findings = [];
function check(file, line, text, where) {
  const t = text.replace(/\s+/g, ' ').trim();
  if (!t) return;
  // biểu thức CEL, điều kiện hay đoạn mã trong chuỗi không phải văn bản hiển thị
  if (where !== 'html' && /(&&|\|\||[!=]==?|=>|\bctx\.|\(\)\s*\?)/.test(t)) return;
  for (const r of RULES) {
    if (r.sentenceOnly && where !== 'html' && !/\s/.test(t)) continue;
    const m = r.re.exec(t);
    r.re.lastIndex = 0;
    if (!m) continue;
    if (r.allow?.some((a) => a.test(t))) continue;
    findings.push({
      file: relative(ROOT, file),
      line,
      rule: r.id,
      match: m[0],
      text: t.length > 160 ? t.slice(0, 157) + '…' : t,
      hint: r.hint,
    });
  }
}

class ExprStrings extends RecursiveAstVisitor {
  constructor(cb) {
    super();
    this.cb = cb;
  }
  visitBinary(ast, ctx) {
    // chuỗi so sánh với trạng thái (`status() === 'DA_GIAO'`) không hiện lên màn hình
    if (/^(===|!==|==|!=)$/.test(ast.operation)) return;
    return super.visitBinary(ast, ctx);
  }
  visitCall(ast, ctx) {
    // đối số của hàm kiểm quyền, tra nhãn… là khoá, không phải văn bản; nhãn trả về kiểm ở mã TS
    ast.receiver.visit(this, ctx);
    for (const a of ast.args)
      if (!(a instanceof LiteralPrimitive) || /\s/.test(String(a.value))) a.visit(this, ctx);
  }
  visitLiteralPrimitive(ast) {
    if (typeof ast.value === 'string') this.cb(ast.value);
  }
  visitTemplateLiteral(ast, ctx) {
    for (const e of ast.elements) this.cb(e.text);
    super.visitTemplateLiteral?.(ast, ctx);
  }
}

function exprStrings(ast, cb) {
  if (!ast) return;
  const node = ast instanceof ASTWithSource ? ast.ast : ast;
  node.visit(new ExprStrings(cb));
}

function ignoredLines(src) {
  const set = new Set();
  src.split('\n').forEach((l, i) => {
    if (/ui-text-ok/.test(l)) {
      set.add(i + 1);
      set.add(i + 2);
    }
  });
  return set;
}

function scanTemplate(file, template, baseLine, ignored) {
  let parsed;
  try {
    parsed = parseTemplate(template, file, { preserveWhitespaces: false, leadingTriviaChars: [] });
  } catch {
    return;
  }
  const lineOf = (span) => baseLine + (span?.start?.line ?? 0);
  const emit = (span, text, kind = 'html') => {
    const l = lineOf(span);
    if (!ignored.has(l)) check(file, l, text, kind);
  };
  const visit = (nodes) => {
    for (const n of nodes ?? []) {
      if (n instanceof TmplAstText) emit(n.sourceSpan, n.value);
      else if (n instanceof TmplAstBoundText) {
        const ast = n.value instanceof ASTWithSource ? n.value.ast : n.value;
        if (ast?.strings) emit(n.sourceSpan, ast.strings.join(' '));
        for (const e of ast?.expressions ?? [])
          exprStrings(e, (s) => emit(n.sourceSpan, s, 'expr'));
      } else if (n instanceof TmplAstElement || n instanceof TmplAstTemplate) {
        for (const a of n.attributes ?? [])
          if (a instanceof TmplAstTextAttribute && !NON_TEXT_ATTRS.test(a.name))
            emit(a.sourceSpan, a.value);
        for (const a of n.templateAttrs ?? [])
          if (a instanceof TmplAstBoundAttribute && !NON_TEXT_BOUND.test(a.name))
            exprStrings(a.value, (s) => emit(a.sourceSpan, s, 'expr'));
        for (const a of n.inputs ?? [])
          if (!NON_TEXT_BOUND.test(a.name))
            exprStrings(a.value, (s) => emit(a.sourceSpan, s, 'expr'));
        visit(n.children);
      } else {
        // khối @if, @for, @switch, @defer và nhánh con của chúng
        if (n.branches)
          for (const b of n.branches) {
            if (b.expression) exprStrings(b.expression, (s) => emit(b.sourceSpan, s, 'expr'));
            visit(b.children);
          }
        if (n.cases) for (const c of n.cases) visit(c.children);
        if (n.children) visit(n.children);
        if (n.empty) visit(n.empty.children);
        for (const k of ['placeholder', 'loading', 'error']) if (n[k]) visit(n[k].children);
      }
    }
  };
  visit(parsed.nodes);
}

/** Chuỗi TS không hiện lên màn hình: import, khoá tuyến, selector, console, chuỗi kỹ thuật không dấu cách. */
function isNonUiContext(node) {
  const p = node.parent;
  if (!p) return true;
  if (ts.isImportDeclaration(p) || ts.isExportDeclaration(p) || ts.isExternalModuleReference(p))
    return true;
  if (ts.isLiteralTypeNode(p)) return true;
  if (ts.isPropertyAssignment(p) && p.initializer === node) {
    const k = p.name.getText().replace(/['"]/g, '');
    if (
      /^(selector|templateUrl|styleUrl|styleUrls|styles|path|redirectTo|loadComponent|icon|severity|styleClass|id|key|code|kind|type|status|state|route|link|href|url|testId|providedIn|field|dataKey)$/.test(
        k,
      )
    )
      return true;
  }
  if (ts.isElementAccessExpression(p) || ts.isCaseClause(p)) return true;
  if (ts.isBinaryExpression(p) && /^(===|!==|==|!=)$/.test(p.operatorToken.getText())) return true;
  let c = p;
  for (let i = 0; i < 4 && c; i++, c = c.parent) {
    if (ts.isCallExpression(c)) {
      const callee = c.expression.getText();
      if (
        /^console\.|\.(startsWith|endsWith|includes|indexOf|split|replace|match|test|get|set|has|delete|setItem|getItem|removeItem|querySelector|querySelectorAll|addEventListener|getAttribute|setAttribute)$|^(Symbol|InjectionToken|new InjectionToken|RegExp|require|import)$/.test(
          callee,
        )
      )
        return true;
    }
    if (
      ts.isNewExpression(c) &&
      /^(InjectionToken|RegExp|URL|Error|HttpParams)$/.test(c.expression.getText())
    )
      return true;
  }
  return false;
}

function scanTs(file, src) {
  const sf = ts.createSourceFile(file, src, ts.ScriptTarget.Latest, true);
  const ignored = ignoredLines(src);
  const visit = (node) => {
    // template nội tuyến của component
    if (
      ts.isPropertyAssignment(node) &&
      node.name.getText() === 'template' &&
      (ts.isNoSubstitutionTemplateLiteral(node.initializer) || ts.isStringLiteral(node.initializer))
    ) {
      const lit = node.initializer;
      const line = sf.getLineAndCharacterOfPosition(lit.getStart() + 1).line + 1;
      scanTemplate(file, lit.text, line, ignored);
      return;
    }
    if (ts.isPropertyAssignment(node) && node.name.getText() === 'styles') return;
    if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) {
      if (!isNonUiContext(node)) report(node, node.text);
    } else if (ts.isTemplateExpression(node)) {
      if (!isNonUiContext(node))
        report(
          node,
          [node.head.text, ...node.templateSpans.map((s) => s.literal.text)].join(' … '),
        );
    }
    ts.forEachChild(node, visit);
  };
  const report = (node, text) => {
    const line = sf.getLineAndCharacterOfPosition(node.getStart()).line + 1;
    if (!ignored.has(line)) check(file, line, text, 'ts');
  };
  visit(sf);
}

const files = walk(PROJECTS);
for (const f of files) {
  const src = readFileSync(f, 'utf8');
  if (f.endsWith('.html')) scanTemplate(f, src, 1, ignoredLines(src));
  else scanTs(f, src);
}

if (JSON_OUT) console.log(JSON.stringify(findings, null, 1));
else {
  for (const x of findings)
    console.log(`${x.file}:${x.line}  [${x.rule}] «${x.match}» — ${x.text}`);
  const byRule = findings.reduce((a, x) => ((a[x.rule] = (a[x.rule] ?? 0) + 1), a), {});
  console.log(`\n${findings.length} chỗ cần sửa nội dung giao diện`, byRule);
  if (findings.length) console.log('Quy định và bảng từ thay thế: wiki/16-noi-dung-giao-dien.md');
}
process.exit(findings.length && !JSON_OUT ? 1 : 0);
