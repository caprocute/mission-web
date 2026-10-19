// WEB-007: sinh kiểu TypeScript từ hợp đồng OpenAPI 3.1 của mission-core lúc build.
// Dùng openapi-typescript (chỉ sinh kiểu, không sinh mã chạy, không eval) rồi định dạng bằng Prettier.
//   node tools/openapi/generate.mjs          ghi projects/api-client/src/lib/generated/mission-core.ts
//   node tools/openapi/generate.mjs --check  báo lỗi nếu tệp đã commit lệch với hợp đồng (dùng trong CI)
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import openapiTS, { astToString } from 'openapi-typescript';
import * as prettier from 'prettier';

const root = fileURLToPath(new URL('../../', import.meta.url));
const SPECS = [
  {
    spec: new URL('./mission-core.openapi.yaml', import.meta.url),
    out: `${root}projects/api-client/src/lib/generated/mission-core.ts`,
  },
];
const check = process.argv.includes('--check');
let stale = 0;

for (const { spec, out } of SPECS) {
  const ast = await openapiTS(spec, { alphabetize: false });
  const header =
    '/* eslint-disable */\n/**\n * Sinh tự động bởi openapi-typescript từ tools/openapi (WEB-007). Không sửa tay;\n' +
    ' * sửa hợp đồng rồi chạy `npm run api:gen`.\n */\n';
  const options = { ...(await prettier.resolveConfig(out)), filepath: out };
  const code = await prettier.format(header + astToString(ast), options);
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
process.exit(stale ? 1 : 0);
