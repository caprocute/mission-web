import { evaluate, parse } from 'cel-js';
import { CelFunctions } from './cel-rule-evaluator';

type Cst = Extract<ReturnType<typeof parse>, { isSuccess: true }>['cst'];

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

const RANK_TO_SHORT_CODE: Readonly<Record<number, string>> = {
  1: 'KHTNV',
  2: 'HTNV',
  3: 'HTTNV',
  4: 'HTXSNV',
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
  const minRank = Math.min(normA.rank, normB.rank);
  return RANK_TO_SHORT_CODE[minRank];
}

function parseStrictIsoDate(iso: string): Date {
  if (typeof iso !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(iso)) {
    throw new Error(`Invalid ISO-8601 date format (expected YYYY-MM-DD): '${iso}'`);
  }
  const [y, m, d] = iso.split('-').map((p) => Number.parseInt(p, 10));
  const dt = new Date(Date.UTC(y, m - 1, d));
  if (
    dt.getUTCFullYear() !== y ||
    dt.getUTCMonth() + 1 !== m ||
    dt.getUTCDate() !== d
  ) {
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
 * Tạo bảng hàm mở rộng chuẩn của Mission (`sum`, `min_muc`, `ngay_lam_viec`, `workdays`)
 * để truyền vào `cel-js`.
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

const RESERVED_IDENTIFIERS = new Set([
  'true',
  'false',
  'null',
  'size',
  'has',
  'sum',
  'min_muc',
  'ngay_lam_viec',
  'workdays',
  'all',
  'exists',
  'exists_one',
  'filter',
  'map',
]);

function extractMacroBoundVars(codeWithoutStrings: string): Set<string> {
  const bound = new Set<string>();
  const regex = /\.\s*(?:all|exists|exists_one|filter|map)\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*,/g;
  for (const match of codeWithoutStrings.matchAll(regex)) {
    bound.add(match[1]);
  }
  return bound;
}

function extractTopLevelIdentifiers(expression: string): string[] {
  const codeWithoutStrings = expression.replace(/"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'/g, '""');
  const macroVars = extractMacroBoundVars(codeWithoutStrings);
  const identifiers = new Set<string>();
  const tokenRegex = /(\.\s*)?([A-Za-z_][A-Za-z0-9_]*)(\s*\()?/g;

  for (const match of codeWithoutStrings.matchAll(tokenRegex)) {
    const isPropertyOrMethod = Boolean(match[1]);
    const name = match[2];
    const isFunctionCall = Boolean(match[3]);
    if (isPropertyOrMethod || isFunctionCall) {
      continue;
    }
    if (RESERVED_IDENTIFIERS.has(name) || macroVars.has(name)) {
      continue;
    }
    identifiers.add(name);
  }
  return [...identifiers];
}

function validateStaticTypes(
  expression: string,
  variableTypes: Readonly<Record<string, CelVariableType>>,
  topLevelVars: readonly string[],
): void {
  for (const varName of topLevelVars) {
    if (!(varName in variableTypes)) {
      throw new MissionCelError(
        'POLICY-422-CEL-TYPE',
        expression,
        `Undeclared reference to '${varName}'`,
      );
    }
  }

  // Kiểm kiểu tĩnh cho hàm sum(...) chỉ nhận LIST_DOUBLE hoặc LIST_INT
  const sumCallMatch = expression.match(/\bsum\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)/);
  if (sumCallMatch) {
    const argVar = sumCallMatch[1];
    const argType = variableTypes[argVar];
    if (argType && argType !== 'LIST_DOUBLE' && argType !== 'LIST_INT' && argType !== 'DYN') {
      throw new MissionCelError(
        'POLICY-422-CEL-TYPE',
        expression,
        // ui-text-ok: thông điệp lỗi kỹ thuật nội bộ
        `Function sum() expects LIST_DOUBLE or LIST_INT, got ${argType} for '${argVar}'`,
      );
    }
  }

  // Kiểm kiểu tĩnh cho phép cộng số với chuỗi ký tự
  const codeWithoutStrings = expression.replace(
    /"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'/g,
    '__STRING_LITERAL__',
  );
  for (const [varName, varType] of Object.entries(variableTypes)) {
    if (varType === 'DOUBLE' || varType === 'DECIMAL' || varType === 'INT') {
      const badAddLeft = new RegExp(`\\b${varName}\\b\\s*[+\\-*/]\\s*__STRING_LITERAL__`);
      const badAddRight = new RegExp(`__STRING_LITERAL__\\s*[+\\-*/]\\s*\\b${varName}\\b`);
      if (badAddLeft.test(codeWithoutStrings) || badAddRight.test(codeWithoutStrings)) {
        throw new MissionCelError(
          'POLICY-422-CEL-TYPE',
          expression,
          `Type mismatch: numeric variable '${varName}' (${varType}) cannot be combined with string`,
        );
      }
    }
  }
}

const cstCache = new Map<string, Cst>();

/**
 * Biên dịch, kiểm kiểu tĩnh, thực thi biểu thức CEL trên `cel-js` và làm tròn điểm ở lớp sau
 * theo đúng hợp đồng đối chiếu với `CelEngine` (`vt-cel` trên máy chủ Java).
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

  let cst = cstCache.get(expression);
  if (!cst) {
    const parsed = parse(expression);
    if (!parsed.isSuccess) {
      throw new MissionCelError('POLICY-422-CEL-TYPE', expression, parsed.errors.join('; '));
    }
    cst = parsed.cst;
    cstCache.set(expression, cst);
  }

  const referencedVars = extractTopLevelIdentifiers(expression);
  validateStaticTypes(expression, variableTypes, referencedVars);

  for (const requiredVar of referencedVars) {
    if (
      !(requiredVar in inputs) ||
      inputs[requiredVar] === null ||
      inputs[requiredVar] === undefined
    ) {
      throw new MissionCelError(
        'POLICY-422-CEL-MISSING-VAR',
        expression,
        // ui-text-ok: thông điệp lỗi kỹ thuật nội bộ
        `Missing or null required variable: '${requiredVar}'`,
      );
    }
  }

  const fns = createMissionCelFunctions(calendar);
  let rawResult: unknown;
  try {
    rawResult = evaluate(
      cst,
      inputs as Record<string, unknown>,
      fns as Record<string, CallableFunction>,
    );
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    if (/Identifier ".*" not found/.test(msg)) {
      throw new MissionCelError('POLICY-422-CEL-MISSING-VAR', expression, msg);
    }
    throw new MissionCelError('POLICY-422-CEL-EVAL-ERROR', expression, msg);
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
