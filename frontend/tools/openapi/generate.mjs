// WEB-064: sinh kiểu TypeScript từ bộ hợp đồng OpenAPI 3.1 ở `contracts/openapi/` (WEB-063, ADR-32).
// Dùng openapi-typescript (chỉ sinh kiểu, không sinh mã chạy, không eval) rồi định dạng bằng Prettier.
// Mỗi tệp hợp đồng sinh một tệp kiểu riêng, không gộp vào một không gian tên, vì tên cục bộ
// (ví dụ tham số `VersionNo`) có thể trùng giữa các module:
//   contracts/openapi/common/common.yaml    → projects/api-client/src/lib/generated/common.ts
//   contracts/openapi/modules/<module>.yaml → projects/api-client/src/lib/generated/<module>.ts
//   cộng tệp chỉ mục generated/index.ts xuất bí danh theo module (TasksPaths, OrgComponents...).
//   node tools/openapi/generate.mjs          ghi các tệp trên, xoá tệp sinh không còn hợp đồng
//   node tools/openapi/generate.mjs --check  báo lỗi nếu tệp đã commit lệch với hợp đồng (dùng trong CI)
import { readFile, readdir, unlink, writeFile } from 'node:fs/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import openapiTS, { astToString } from 'openapi-typescript';
import * as prettier from 'prettier';

const root = fileURLToPath(new URL('../../', import.meta.url));
const contracts = fileURLToPath(new URL('../../../contracts/openapi/', import.meta.url));
const outDir = `${root}projects/api-client/src/lib/generated/`;
const check = process.argv.includes('--check');

const HEADER =
  '/* eslint-disable */\n/**\n * Sinh tự động bởi openapi-typescript từ contracts/openapi/%SRC% (WEB-064).\n' +
  ' * Không sửa tay; sửa hợp đồng rồi chạy `npm run api:gen` trong frontend/.\n */\n';

/** `products` → `Products`, `org` → `Org` (tiền tố bí danh trong index.ts). */
const pascal = (name) => name.replace(/(^|[-_])(\w)/g, (_, __, c) => c.toUpperCase());

const modules = (await readdir(`${contracts}modules`))
  .filter((f) => f.endsWith('.yaml'))
  .map((f) => f.slice(0, -'.yaml'.length))
  .sort();
const specs = [
  { name: 'common', src: 'common/common.yaml' },
  ...modules.map((name) => ({ name, src: `modules/${name}.yaml` })),
];

async function format(code, out) {
  const options = { ...(await prettier.resolveConfig(out)), filepath: out };
  return prettier.format(code, options);
}

/** Danh sách tệp cần có: tên tệp → nội dung đã định dạng. */
const expected = new Map();
for (const { name, src } of specs) {
  const out = `${outDir}${name}.ts`;
  const ast = await openapiTS(pathToFileURL(`${contracts}${src}`), { alphabetize: false });
  expected.set(`${name}.ts`, await format(HEADER.replace('%SRC%', src) + astToString(ast), out));
}

const index = [
  HEADER.replace('/* eslint-disable */\n', '').replace('%SRC%', '*'),
  '// Bí danh theo module để dùng ngoài thư viện; trong thư viện import thẳng từ ./<module>.',
  "export type { components as CommonComponents } from './common';",
  ...modules.map(
    (m) =>
      `export type { paths as ${pascal(m)}Paths, components as ${pascal(m)}Components, ` +
      `operations as ${pascal(m)}Operations } from './${m}';`,
  ),
  '',
  '/** Mọi đường dẫn khai trong bộ hợp đồng (không kèm tiền tố `/api/v1`). */',
  `export type ApiPaths = ${modules.map((m) => `import('./${m}').paths`).join(' & ')};`,
  '',
].join('\n');
expected.set('index.ts', await format(index, `${outDir}index.ts`));

const present = (await readdir(outDir).catch(() => [])).filter((f) => f.endsWith('.ts'));
let stale = 0;
for (const [file, code] of expected) {
  const out = `${outDir}${file}`;
  if (check) {
    const current = await readFile(out, 'utf8').catch(() => '');
    if (current !== code) {
      console.error(`[api:check] ${out} lệch với hợp đồng, chạy npm run api:gen`);
      stale++;
    }
  } else {
    await writeFile(out, code);
    console.log(`[api:gen] ${out}`);
  }
}
for (const file of present.filter((f) => !expected.has(f))) {
  const out = `${outDir}${file}`;
  if (check) {
    console.error(`[api:check] ${out} không còn hợp đồng tương ứng, chạy npm run api:gen`);
    stale++;
  } else {
    await unlink(out);
    console.log(`[api:gen] xoá ${out}`);
  }
}
process.exit(stale ? 1 : 0);
