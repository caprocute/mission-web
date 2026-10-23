import { Environment, TypeError as CelTypeError } from '@marcbachmann/cel-js';
import { CelFunctions } from './cel-rule-evaluator';

/** Mã lỗi chuẩn của động cơ CEL theo đặc tả NEN-041 / TL-01 §4.3. */
export type MissionCelErrorCode =
  | 'POLICY-422-CEL-TYPE'
  | 'POLICY-422-CEL-MISSING-VAR'
  | 'POLICY-422-CEL-SANDBOX-LIMIT'
  | 'POLICY-422-CEL-TIMEOUT'
  | 'POLICY-422-CEL-EVAL-ERROR';

/** Chế độ làm tròn điểm ở lớp sau biểu thức CEL (TL-02 BR-022). */
export type CelRoundingMode = 'HALF_UP' | 'NUA_LEN' | 'DOWN' | 'CAT_BO';

/** Kiểu dữ liệu biến trong hợp đồng biểu thức CEL dùng chung giữa cel-java và cel-js. */
export type CelVariableType =
  | 'DOUBLE'
  | 'DECIMAL'
  | 'INT'
  | 'BOOL'
  | 'STRING'
  | 'NULLABLE_STRING'
  | 'LIST_DOUBLE'
  | 'LIST_INT'
  | 'LIST_STRING'
  | 'LIST_DYN'
  | 'MAP_STRING_DYN'
  | 'DYN';

/** Cấu hình lịch ngày làm việc cho hàm `ngay_lam_viec` / `workdays`. */
export interface WorkingCalendarConfig {
  /** Tập ngày nghỉ lễ định dạng ISO `YYYY-MM-DD`. */
  readonly holidays?: ReadonlySet<string>;
  /** Tập ngày thứ Bảy/Chủ nhật đi làm bù định dạng ISO `YYYY-MM-DD`. */
  readonly compensatoryWorkdays?: ReadonlySet<string>;
}

/** Lỗi biên dịch hoặc thực thi biểu thức CEL với mã lỗi chuẩn đối chiếu máy chủ. */
export class MissionCelError extends Error {
  constructor(
    public readonly errorCode: MissionCelErrorCode,
    public readonly expression: string,
    message: string,
  ) {
    super(message);
    this.name = 'MissionCelError';
  }
}

/** Lịch kiểm thử mặc định năm 2026 đồng bộ 100% với `StaticWorkingCalendar.defaultTestCalendar()` bên Java. */
export const DEFAULT_TEST_WORKING_CALENDAR: WorkingCalendarConfig = {
  holidays: new Set([
    '2026-01-01',
    '2026-02-16',
    '2026-02-17',
    '2026-02-18',
    '2026-02-19',
    '2026-02-20',
    '2026-04-27',
    '2026-04-30',
    '2026-05-01',
    '2026-09-02',
    '2026-09-03',
  ]),
  compensatoryWorkdays: new Set(['2026-02-14', '2026-04-25', '2026-08-29']),
};

const RATING_RANKS: Readonly<Record<string, { rank: number; shortCode: string }>> = {
  KHTNV: { rank: 1, shortCode: 'KHTNV' },
  KHONG_HOAN_THANH: { rank: 1, shortCode: 'KHTNV' },
  HTNV: { rank: 2, shortCode: 'HTNV' },
  HOAN_THANH: { rank: 2, shortCode: 'HTNV' },
  HTTNV: { rank: 3, shortCode: 'HTTNV' },
  HOAN_THANH_TOT: { rank: 3, shortCode: 'HTTNV' },
  HTXSNV: { rank: 4, shortCode: 'HTXSNV' },
  HOAN_THANH_XUAT_SAC: { rank: 4, shortCode: 'HTXSNV' },
};

function parseDecimalToBigInt(value: number): { unscaled: bigint; scale: number } {
  if (!Number.isFinite(value)) {
    throw new Error(`Non-finite numeric value is not allowed: ${value}`);
  }
  if (Object.is(value, -0) || value === 0) {
    return { unscaled: 0n, scale: 0 };
  }
  const str = value.toString();
  const [mantissa, expPart] = str.split(/[eE]/);
  const sign = mantissa.startsWith('-') ? -1n : 1n;
  const unsigned = mantissa.replace(/^[+-]/, '');
  const [intPart, fracPart = ''] = unsigned.split('.');
  const digits = `${intPart}${fracPart}`;
  let unscaled = BigInt(digits) * sign;
  let scale = fracPart.length;

  if (expPart !== undefined) {
    const exp = Number.parseInt(expPart, 10);
    if (exp >= 0) {
      if (exp >= scale) {
        unscaled *= 10n ** BigInt(exp - scale);
        scale = 0;
      } else {
        scale -= exp;
      }
    } else {
      scale += -exp;
    }
  }
  return { unscaled, scale };
}

function bigIntToNumber(unscaled: bigint, scale: number): number {
  if (unscaled === 0n) {
    return 0;
  }
  if (scale <= 0) {
    return Number(unscaled * 10n ** BigInt(-scale));
  }
  const negative = unscaled < 0n;
  const absStr = (negative ? -unscaled : unscaled).toString().padStart(scale + 1, '0');
  const intPart = absStr.slice(0, absStr.length - scale);
  const fracPart = absStr.slice(absStr.length - scale);
  return Number(`${negative ? '-' : ''}${intPart}.${fracPart}`);
}

/**
 * Cộng danh sách số thực bằng số học thập phân chính xác (tương đương `BigDecimal.valueOf(d)`
 * trong `MissionCelFunctions.sum` của `vt-cel`) để tránh sai số dấu phẩy động IEEE-754.
 */
export function sumCelValues(values: readonly unknown[]): number {
  if (!Array.isArray(values)) {
    throw new Error('sum(list) requires an array argument');
  }
  if (values.length === 0) {
    return 0;
  }
  const parsed = values.map((item, idx) => {
    if (typeof item !== 'number' || !Number.isFinite(item)) {
      throw new Error(`sum(list) element at index ${idx} is not a finite number`);
    }
    return parseDecimalToBigInt(item);
  });
  const maxScale = parsed.reduce((max, cur) => (cur.scale > max ? cur.scale : max), 0);
  let totalUnscaled = 0n;
  for (const item of parsed) {
    const factor = 10n ** BigInt(maxScale - item.scale);
    totalUnscaled += item.unscaled * factor;
  }
  return bigIntToNumber(totalUnscaled, maxScale);
}

/**
 * So sánh và trả về mã xếp loại thấp hơn giữa hai mức xếp loại cán bộ:
 * `KHTNV (1) < HTNV (2) < HTTNV (3) < HTXSNV (4)`.
 */
export function minMucXepLoai(levelA: string, levelB: string): string {
  if (typeof levelA !== 'string' || typeof levelB !== 'string') {
    throw new Error('min_muc(a, b) requires string arguments');
  }
  const normA = RATING_RANKS[levelA.trim().toUpperCase()];
  const normB = RATING_RANKS[levelB.trim().toUpperCase()];
  if (!normA || !normB) {
    throw new Error(`Unsupported rating level in min_muc('${levelA}', '${levelB}')`);
  }
  // Trả lại đúng mã của mức thấp hơn theo cách người gọi truyền vào (mã viết tắt hay mã đầy đủ),
  // khớp bộ ca vàng dùng chung với vt-cel phía máy chủ
  return normA.rank <= normB.rank ? levelA.trim().toUpperCase() : levelB.trim().toUpperCase();
}

function parseStrictIsoDate(iso: string): Date {
  if (typeof iso !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(iso)) {
    throw new Error(`Invalid ISO-8601 date format (expected YYYY-MM-DD): '${iso}'`);
  }
  const [y, m, d] = iso.split('-').map((p) => Number.parseInt(p, 10));
  const dt = new Date(Date.UTC(y, m - 1, d));
  if (dt.getUTCFullYear() !== y || dt.getUTCMonth() + 1 !== m || dt.getUTCDate() !== d) {
    throw new Error(`Invalid calendar date: '${iso}'`);
  }
  return dt;
}

function formatUtcIsoDate(dt: Date): string {
  const y = dt.getUTCFullYear();
  const m = String(dt.getUTCMonth() + 1).padStart(2, '0');
  const d = String(dt.getUTCDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/**
 * Đếm số ngày làm việc trong nửa khoảng `(fromIso, toIso]` theo lịch ngày làm việc.
 */
export function countWorkingDays(
  fromIso: string,
  toIso: string,
  calendar: WorkingCalendarConfig = {},
): number {
  const fromDate = parseStrictIsoDate(fromIso);
  const toDate = parseStrictIsoDate(toIso);
  if (toDate.getTime() <= fromDate.getTime()) {
    return 0;
  }
  const holidays = calendar.holidays ?? new Set<string>();
  const compensatory = calendar.compensatoryWorkdays ?? new Set<string>();

  let count = 0;
  const cursor = new Date(fromDate.getTime());
  while (cursor.getTime() < toDate.getTime()) {
    cursor.setUTCDate(cursor.getUTCDate() + 1);
    const iso = formatUtcIsoDate(cursor);
    if (compensatory.has(iso)) {
      count++;
    } else if (!holidays.has(iso)) {
      const dow = cursor.getUTCDay();
      if (dow !== 0 && dow !== 6) {
        count++;
      }
    }
  }
  return count;
}

/**
 * Làm tròn điểm số ở lớp sau biểu thức CEL bằng số học thập phân chính xác,
 * đồng bộ 100% với `CelRounding.round(double, scale, mode)` trên `vt-cel` (Java `BigDecimal`).
 */
export function roundCelScore(
  value: number,
  scale = 2,
  roundingMode: CelRoundingMode = 'HALF_UP',
): number {
  if (scale < 0) {
    throw new Error(`scale must be >= 0, got: ${scale}`);
  }
  const normalizedMode = roundingMode.trim().toUpperCase();
  if (
    normalizedMode !== 'HALF_UP' &&
    normalizedMode !== 'NUA_LEN' &&
    normalizedMode !== 'DOWN' &&
    normalizedMode !== 'CAT_BO'
  ) {
    throw new Error(`Unsupported rounding mode: ${roundingMode}`);
  }

  const { unscaled, scale: currentScale } = parseDecimalToBigInt(value);
  if (currentScale <= scale) {
    return value;
  }

  const diff = currentScale - scale;
  const divisor = 10n ** BigInt(diff);
  const negative = unscaled < 0n;
  const absUnscaled = negative ? -unscaled : unscaled;
  let quotient = absUnscaled / divisor;
  const remainder = absUnscaled % divisor;

  if (normalizedMode === 'HALF_UP' || normalizedMode === 'NUA_LEN') {
    if (remainder * 2n >= divisor) {
      quotient += 1n;
    }
  }

  const signedQuotient = negative ? -quotient : quotient;
  return bigIntToNumber(signedQuotient, scale);
}

/**
 * Tạo bảng hàm mở rộng chuẩn của Mission (`sum`, `min_muc`, `ngay_lam_viec`, `workdays`) theo kiểu
 * hàm của `cel-js` dùng cho quy tắc biểu mẫu (`evaluateRules`).
 */
export function createMissionCelFunctions(
  calendar: WorkingCalendarConfig = DEFAULT_TEST_WORKING_CALENDAR,
): CelFunctions {
  const ngayLamViec = ((fromIso: string, toIso: string) =>
    countWorkingDays(fromIso, toIso, calendar)) as (...args: never[]) => unknown;
  return {
    sum: ((list: readonly unknown[]) => sumCelValues(list)) as (...args: never[]) => unknown,
    min_muc: ((a: string, b: string) => minMucXepLoai(a, b)) as (...args: never[]) => unknown,
    ngay_lam_viec: ngayLamViec,
    workdays: ngayLamViec,
  };
}

export interface MissionCelEvalOptions {
  readonly expression: string;
  readonly variableTypes: Readonly<Record<string, CelVariableType>>;
  readonly returnType: CelVariableType;
  readonly inputs?: Readonly<Record<string, unknown>>;
  readonly scale?: number;
  readonly roundingMode?: CelRoundingMode;
  readonly calendar?: WorkingCalendarConfig;
  readonly maxExpressionLength?: number;
}

/** Kiểu CEL của từng kiểu biến trong hợp đồng, như `CelValueType` của `vt-cel`. */
const CEL_TYPE: Readonly<Record<CelVariableType, string>> = {
  DOUBLE: 'double',
  DECIMAL: 'double',
  INT: 'int',
  BOOL: 'bool',
  STRING: 'string',
  NULLABLE_STRING: 'dyn',
  LIST_DOUBLE: 'list<double>',
  LIST_INT: 'list<int>',
  LIST_STRING: 'list<string>',
  LIST_DYN: 'list<dyn>',
  MAP_STRING_DYN: 'map<string, dyn>',
  DYN: 'dyn',
};

/** Không gian tên `math.*` (phần mở rộng math của cel-java: `math.least`, `math.greatest`). */
class MathNamespace {}

const toNum = (v: unknown) => (typeof v === 'bigint' ? Number(v) : (v as number));

/** Môi trường gốc: hàm mở rộng của Mission và `math.least/greatest`, chưa khai biến. */
function baseEnvironment(calendar: WorkingCalendarConfig): Environment {
  const env = new Environment({
    unlistedVariablesAreDyn: false,
    // cel-java cho phép literal danh sách khác kiểu phần tử (kiểu list(dyn))
    homogeneousAggregateLiterals: false,
  })
    .registerType('MathNamespace', MathNamespace)
    .registerConstant('math', 'MathNamespace', new MathNamespace())
    .registerFunction('sum(list<double>): double', (l: number[]) => sumCelValues(l))
    .registerFunction('sum(list<int>): double', (l: bigint[]) => sumCelValues(l.map(Number)))
    .registerFunction('min_muc(string, string): string', (a: string, b: string) =>
      minMucXepLoai(a, b),
    )
    .registerFunction('ngay_lam_viec(string, string): int', (a: string, b: string) =>
      BigInt(countWorkingDays(a, b, calendar)),
    )
    .registerFunction('workdays(string, string): int', (a: string, b: string) =>
      BigInt(countWorkingDays(a, b, calendar)),
    );
  const pick = {
    least: (a: unknown, b: unknown) => (toNum(b) < toNum(a) ? b : a),
    greatest: (a: unknown, b: unknown) => (toNum(b) > toNum(a) ? b : a),
  };
  for (const [name, choose] of Object.entries(pick)) {
    const both = (r: string, t1: string, t2: string, conv: (v: unknown) => unknown) =>
      env.registerFunction({
        name,
        receiverType: 'MathNamespace',
        returnType: r,
        handler: (_ns: MathNamespace, a: unknown, b: unknown) => conv(choose(a, b)),
        params: [
          { name: 'a', type: t1 },
          { name: 'b', type: t2 },
        ],
      });
    both('double', 'double', 'double', (v) => v);
    both('int', 'int', 'int', (v) => v);
    both('double', 'double', 'int', toNum);
    both('double', 'int', 'double', toNum);
    for (const [r, t] of [
      ['double', 'list<double>'],
      ['int', 'list<int>'],
    ])
      env.registerFunction({
        name,
        receiverType: 'MathNamespace',
        returnType: r,
        handler: (_ns: MathNamespace, list: unknown[]) => {
          // ui-text-ok: thông điệp lỗi kỹ thuật nội bộ
          if (!list.length) throw new Error(`math.${name}() requires a non-empty list`);
          return list.reduce(choose);
        },
        params: [{ name: 'list', type: t }],
      });
  }
  return env;
}

const baseEnvs = new WeakMap<WorkingCalendarConfig, Environment>();
/** Môi trường đã khai biến theo bộ kiểu biến, kèm biểu thức đã phân tích và kiểm kiểu. */
const typedEnvs = new WeakMap<
  WorkingCalendarConfig,
  Map<string, { env: Environment; parsed: Map<string, ReturnType<Environment['parse']>> }>
>();

function typedEnvironment(
  calendar: WorkingCalendarConfig,
  variableTypes: Readonly<Record<string, CelVariableType>>,
) {
  const key = JSON.stringify(Object.entries(variableTypes).sort(([a], [b]) => (a < b ? -1 : 1)));
  let byTypes = typedEnvs.get(calendar);
  if (!byTypes) typedEnvs.set(calendar, (byTypes = new Map()));
  let entry = byTypes.get(key);
  if (!entry) {
    let base = baseEnvs.get(calendar);
    if (!base) baseEnvs.set(calendar, (base = baseEnvironment(calendar)));
    const env = base.clone();
    for (const [name, type] of Object.entries(variableTypes)) {
      env.registerVariable(name, CEL_TYPE[type] ?? 'dyn');
    }
    entry = { env, parsed: new Map() };
    byTypes.set(key, entry);
  }
  return entry;
}

/** Đổi giá trị đầu vào sang kiểu của CEL: số nguyên khai `INT` thành `bigint`. */
function toCelValue(
  expression: string,
  name: string,
  type: CelVariableType | undefined,
  value: unknown,
): unknown {
  const asInt = (v: unknown) => {
    if (typeof v === 'bigint') return v;
    if (typeof v === 'number' && Number.isInteger(v)) return BigInt(v);
    throw new MissionCelError(
      'POLICY-422-CEL-TYPE',
      expression,
      // ui-text-ok: thông điệp lỗi kỹ thuật nội bộ
      `Variable '${name}' expects integer value for INT, got: ${String(v)}`,
    );
  };
  if (type === 'INT') return asInt(value);
  if (type === 'LIST_INT' && Array.isArray(value)) return value.map(asInt);
  return value;
}

/** Biến khai báo mà biểu thức tham chiếu tới (tên gốc, không tính thuộc tính hay hàm). */
function referencedVariables(expression: string, declared: readonly string[]): string[] {
  const code = expression.replace(/"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'/g, '""');
  return declared.filter((name) => new RegExp(`(?<![.\\w])${name}(?![\\w(])`).test(code));
}

function matchesReturnType(value: unknown, returnType: CelVariableType): boolean {
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

/**
 * Biên dịch, kiểm kiểu tĩnh và thực thi biểu thức CEL trên `@marcbachmann/cel-js` (cài đặt CEL đầy
 * đủ, phân biệt `int` và `double` như cel-java), rồi làm tròn điểm ở lớp sau theo đúng hợp đồng
 * đối chiếu với `CelEngine` (`vt-cel` trên máy chủ Java): cùng kiểu biến, phần mở rộng `math`, các
 * hàm `sum`, `min_muc`, `ngay_lam_viec`, `workdays`, so sánh số khác kiểu được phép.
 */
export function evaluateMissionCel(options: MissionCelEvalOptions): unknown {
  const {
    expression,
    variableTypes,
    inputs = {},
    scale,
    roundingMode,
    calendar = DEFAULT_TEST_WORKING_CALENDAR,
    maxExpressionLength = 2048,
  } = options;

  if (!expression || expression.trim().length === 0) {
    throw new MissionCelError('POLICY-422-CEL-TYPE', expression, 'Expression must not be blank');
  }
  if ([...expression].length > maxExpressionLength) {
    throw new MissionCelError(
      'POLICY-422-CEL-TYPE',
      expression,
      `Expression length exceeds maximum ${maxExpressionLength}`,
    );
  }

  const { env, parsed } = typedEnvironment(calendar, variableTypes);
  let program = parsed.get(expression);
  if (!program) {
    try {
      program = env.parse(expression);
    } catch (err) {
      throw new MissionCelError('POLICY-422-CEL-TYPE', expression, messageOf(err));
    }
    const checked = program.check();
    if (!checked.valid) {
      throw new MissionCelError(
        'POLICY-422-CEL-TYPE',
        expression,
        messageOf(checked.error ?? 'Type check failed'),
      );
    }
    parsed.set(expression, program);
  }

  const context: Record<string, unknown> = {};
  for (const name of referencedVariables(expression, Object.keys(variableTypes))) {
    const value = inputs[name];
    if (value === null || value === undefined) {
      if (variableTypes[name] === 'NULLABLE_STRING') {
        context[name] = null;
        continue;
      }
      throw new MissionCelError(
        'POLICY-422-CEL-MISSING-VAR',
        expression,
        // ui-text-ok: thông điệp lỗi kỹ thuật nội bộ
        `Missing or null required variable: '${name}'`,
      );
    }
    context[name] = toCelValue(expression, name, variableTypes[name], value);
  }

  let rawResult: unknown;
  try {
    rawResult = program(context);
  } catch (err) {
    if (err instanceof MissionCelError) throw err;
    const msg = messageOf(err);
    if (/Unknown variable|No such key/.test(msg)) {
      throw new MissionCelError('POLICY-422-CEL-MISSING-VAR', expression, msg);
    }
    if (err instanceof CelTypeError || /no such overload|no matching overload/.test(msg)) {
      throw new MissionCelError('POLICY-422-CEL-TYPE', expression, msg);
    }
    throw new MissionCelError('POLICY-422-CEL-EVAL-ERROR', expression, msg);
  }
  if (typeof rawResult === 'bigint') rawResult = Number(rawResult);

  if (!matchesReturnType(rawResult, options.returnType)) {
    throw new MissionCelError(
      'POLICY-422-CEL-TYPE',
      expression,
      // ui-text-ok: thông điệp lỗi kỹ thuật nội bộ
      `Result type ${typeof rawResult} does not match declared return type ${options.returnType}`,
    );
  }

  if (typeof rawResult === 'number') {
    if (!Number.isFinite(rawResult)) {
      throw new MissionCelError(
        'POLICY-422-CEL-EVAL-ERROR',
        expression,
        // ui-text-ok: thông điệp lỗi kỹ thuật nội bộ
        `CEL expression evaluated to non-finite number: ${rawResult}`,
      );
    }
    if (scale !== undefined && roundingMode !== undefined) {
      return roundCelScore(rawResult, scale, roundingMode);
    }
  }

  return rawResult;
}

function messageOf(err: unknown): string {
  const msg = err instanceof Error ? err.message : String(err);
  return msg.split('\n')[0];
}
