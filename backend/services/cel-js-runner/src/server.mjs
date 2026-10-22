// Bộ chạy cel-js của CORE-05-07: nhận lô biểu thức CEL mà cel-java đã chạy khi kiểm thử Policy Pack,
// chạy lại trên cel-js và trả kết quả cùng thứ tự để máy chủ so từng biểu thức (BR-15-07, MSG-15-07).
// Hàm Mission (sum chính xác thập phân, min_muc, ngay_lam_viec) lấy từ @vt/form-engine để trình
// duyệt và bộ chạy dùng cùng một mã; lõi CEL là @marcbachmann/cel-js vì cel-js 0.8.2 của form-engine
// chưa có macro (map, filter, exists), hàm double() và phương thức size() mà gói dùng (wiki 14).
import http from 'node:http';
import { Environment, ParseError, TypeError as CelTypeError } from '@marcbachmann/cel-js';
import {
  countWorkingDays,
  minMucXepLoai,
  sumCelValues,
} from '../../../../frontend/projects/form-engine/src/lib/mission-cel.ts';

const MAX_BODY = 32 * 1024 * 1024;

/** Không gian tên `math` của cel-java (math.least, math.greatest). */
class MathNamespace {}

function toNumber(v) {
  return typeof v === 'bigint' ? Number(v) : v;
}

/** Môi trường CEL dùng chung; lịch mặc định chỉ nghỉ thứ Bảy, Chủ nhật như StaticWorkingCalendar.weekendsOnly(). */
export function createEnvironment(calendar = {}) {
  const cal = {
    holidays: new Set(calendar.holidays ?? []),
    compensatoryWorkdays: new Set(calendar.compensatoryWorkdays ?? []),
  };
  const workdays = (from, to) => BigInt(countWorkingDays(from, to, cal));
  return new Environment({ unlistedVariablesAreDyn: true, homogeneousAggregateLiterals: false })
    .registerType('MathNamespace', MathNamespace)
    .registerConstant('math', 'MathNamespace', new MathNamespace())
    .registerFunction('MathNamespace.least(dyn, dyn): dyn', (_m, a, b) =>
      toNumber(a) <= toNumber(b) ? a : b,
    )
    .registerFunction('MathNamespace.greatest(dyn, dyn): dyn', (_m, a, b) =>
      toNumber(a) >= toNumber(b) ? a : b,
    )
    .registerFunction('sum(list): double', (list) => sumCelValues(list.map(toNumber)))
    .registerFunction('min_muc(string, string): string', (a, b) => minMucXepLoai(a, b))
    .registerFunction('ngay_lam_viec(string, string): int', workdays)
    .registerFunction('workdays(string, string): int', workdays);
}

/** Chuẩn hoá kết quả về JSON: BigInt thành số, Map thành đối tượng. */
export function normalize(value) {
  if (typeof value === 'bigint') {
    return Number(value);
  }
  if (Array.isArray(value)) {
    return value.map(normalize);
  }
  if (value instanceof Map) {
    return Object.fromEntries([...value.entries()].map(([k, v]) => [String(k), normalize(v)]));
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, normalize(v)]));
  }
  return value;
}

function matchesReturnType(value, returnType) {
  switch (returnType) {
    case 'BOOL':
      return typeof value === 'boolean';
    case 'STRING':
      return typeof value === 'string';
    case 'DOUBLE':
    case 'DECIMAL':
    case 'INT':
      return typeof value === 'number' || typeof value === 'bigint';
    default:
      return true;
  }
}

/** Chạy một biểu thức; lỗi trả mã chuẩn POLICY-422-CEL-* như MissionCelErrorCode của form-engine. */
export function evaluateItem(env, item) {
  try {
    const value = env.evaluate(item.expression, item.inputs ?? {});
    if (!matchesReturnType(value, item.returnType)) {
      return {
        ok: false,
        value: null,
        errorCode: 'POLICY-422-CEL-TYPE',
        message: `Kết quả ${typeof value} khác kiểu khai báo ${item.returnType}`,
      };
    }
    if (typeof value === 'number' && !Number.isFinite(value)) {
      return { ok: false, value: null, errorCode: 'POLICY-422-CEL-EVAL-ERROR', message: 'Kết quả không hữu hạn' };
    }
    return { ok: true, value: normalize(value), errorCode: null, message: null };
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    let errorCode = 'POLICY-422-CEL-EVAL-ERROR';
    if (err instanceof ParseError || err instanceof CelTypeError) {
      errorCode = 'POLICY-422-CEL-TYPE';
    } else if (/not found|Unknown variable|No such key/i.test(message)) {
      errorCode = 'POLICY-422-CEL-MISSING-VAR';
    }
    return { ok: false, value: null, errorCode, message: message.split('\n')[0] };
  }
}

/** Đọc JSON giữ kiểu int của CEL: số viết không có phần thập phân thành BigInt. */
export function parseBody(text) {
  return JSON.parse(text, (_key, value, context) => {
    if (typeof value === 'number' && context && /^-?\d+$/.test(context.source)) {
      return BigInt(context.source);
    }
    return value;
  });
}

export function evaluateBatch(body) {
  const env = createEnvironment(body.calendar);
  const items = Array.isArray(body.items) ? body.items : [];
  return { results: items.map((item) => evaluateItem(env, item)) };
}

export function createServer() {
  return http.createServer((req, res) => {
    if (req.method === 'GET' && req.url === '/healthz') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end('{"status":"UP"}');
      return;
    }
    if (req.method !== 'POST' || req.url !== '/v1/cel/evaluate') {
      res.writeHead(404, { 'content-type': 'application/problem+json' });
      res.end('{"status":404,"title":"Không tìm thấy"}');
      return;
    }
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY) {
        res.writeHead(413, { 'content-type': 'application/problem+json' });
        res.end('{"status":413,"title":"Yêu cầu quá lớn"}');
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (res.writableEnded) {
        return;
      }
      let body;
      try {
        body = parseBody(Buffer.concat(chunks).toString('utf8'));
      } catch {
        res.writeHead(400, { 'content-type': 'application/problem+json' });
        res.end('{"status":400,"title":"Thân yêu cầu không phải JSON"}');
        return;
      }
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify(evaluateBatch(body)));
    });
  });
}

if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href) {
  const port = Number(process.env.PORT ?? 8080);
  createServer().listen(port, () => {
    console.log(`cel-js-runner lắng nghe cổng ${port}`);
  });
}
