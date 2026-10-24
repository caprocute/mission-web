import { DestroyRef, Signal, computed, inject, signal } from '@angular/core';
import { Observable, finalize } from 'rxjs';
import {
  PrefKey,
  SavedFilter,
  SavedFilterCriteria,
  TableScreenPreference,
  UserPreferenceItem,
} from '@vt/api-client';
import { PreferenceApi } from '@vt/api-client';
import { toProblem } from '@vt/api-client';

/**
 * Cột tuỳ chọn và bộ lọc đã lưu dùng chung cho mọi danh sách (CNH-04, quy ước danh sách TL-02).
 * Màn hình khai danh mục cột và bộ chuyển đổi trạng thái lọc ↔ tiêu chí; phần lưu đi qua API tuỳ
 * chọn cá nhân của CNH-02 (`ui.table.SCR-xxxx`, `/config/me/saved-filters`) kèm `If-Match`.
 */
export interface ListColumnDef {
  /** Mã cột (`ColumnKey`: chữ, số, gạch dưới). */
  key: string;
  label: string;
  /** Cột bắt buộc: luôn hiện, không có ô bỏ chọn. */
  required?: boolean;
  /** Cột phụ: mặc định ẩn, người dùng tự bật trong "Chọn cột" khi cần. */
  hiddenByDefault?: boolean;
}

/** Bộ chuyển đổi giữa trạng thái lọc của màn hình và tiêu chí lưu ở máy chủ. */
export interface FilterCodec<S> {
  toCriteria(state: S): SavedFilterCriteria;
  /** `dropped`: số điều kiện không còn hợp lệ (bị bỏ qua, màn hình báo nhẹ). */
  fromCriteria(criteria: SavedFilterCriteria): { state: S; dropped: number };
}

/** Giới hạn của máy chủ (`SavedFilter.MAX_PER_SCREEN`). */
export const SAVED_FILTER_LIMIT = 20;

const COLUMN_KEY = /^[a-zA-Z][a-zA-Z0-9_]{0,63}$/;
const STATUS_CODE = /^[A-Z][A-Z0-9_]{1,63}$/;
const UUID =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const RATINGS = new Set([
  'KHTNV',
  'HTNV',
  'HTTNV',
  'HTXSNV',
  'KHONG_HOAN_THANH',
  'HOAN_THANH',
  'HOAN_THANH_TOT',
  'HOAN_THANH_XUAT_SAC',
]);
const SCOPES = new Set(['ME', 'MY_UNIT', 'SUBORDINATE_UNITS', 'ALL_ALLOWED']);
const PRESETS = new Set([
  'TODAY',
  'THIS_WEEK',
  'THIS_MONTH',
  'THIS_QUARTER',
  'THIS_YEAR',
  'OVERDUE',
]);

const isObj = (v: unknown): v is Record<string, unknown> =>
  !!v && typeof v === 'object' && !Array.isArray(v);

function codes(v: unknown, ok: (s: string) => boolean, max: number): string[] | undefined {
  if (!Array.isArray(v)) return undefined;
  const out = [...new Set(v.filter((x): x is string => typeof x === 'string' && ok(x)))].slice(
    0,
    max,
  );
  return out.length ? out : undefined;
}

function keyed<T>(
  v: unknown,
  max: number,
  pick: (x: unknown) => T | undefined,
): Record<string, T> | undefined {
  if (!isObj(v)) return undefined;
  const out: Record<string, T> = {};
  for (const [k, x] of Object.entries(v).slice(0, max)) {
    const val = COLUMN_KEY.test(k) ? pick(x) : undefined;
    if (val !== undefined) out[k] = val;
  }
  return Object.keys(out).length ? out : undefined;
}

/**
 * Chỉ giữ những gì lược đồ `SavedFilterCriteria` cho phép: mã, mã định danh, khoảng ngày, khoảng số,
 * cờ, sắp xếp. Mọi khoá khác (ví dụ văn bản tìm kiếm có thể chứa dữ liệu C2) bị bỏ trước khi gửi.
 */
export function sanitizeCriteria(input: unknown): SavedFilterCriteria {
  if (!isObj(input)) return {};
  const c: SavedFilterCriteria = {
    statusCodes: codes(input['statusCodes'], (s) => STATUS_CODE.test(s), 20),
    ratingCodes: codes(input['ratingCodes'], (s) => RATINGS.has(s), 8),
    orgUnitIds: codes(input['orgUnitIds'], (s) => UUID.test(s), 50),
    periodIds: codes(input['periodIds'], (s) => UUID.test(s), 12),
    assigneeScope: SCOPES.has(input['assigneeScope'] as string)
      ? (input['assigneeScope'] as SavedFilterCriteria['assigneeScope'])
      : undefined,
    dateRanges: keyed(input['dateRanges'], 10, (x) => {
      if (!isObj(x)) return undefined;
      const r = {
        from: typeof x['from'] === 'string' && DATE.test(x['from']) ? x['from'] : undefined,
        to: typeof x['to'] === 'string' && DATE.test(x['to']) ? x['to'] : undefined,
        relativePreset: PRESETS.has(x['relativePreset'] as string)
          ? (x['relativePreset'] as never)
          : undefined,
      };
      return r.from || r.to || r.relativePreset ? dropUndefined(r) : undefined;
    }),
    numberRanges: keyed(input['numberRanges'], 10, (x) => {
      if (!isObj(x)) return undefined;
      const r = {
        min: typeof x['min'] === 'number' && Number.isFinite(x['min']) ? x['min'] : undefined,
        max: typeof x['max'] === 'number' && Number.isFinite(x['max']) ? x['max'] : undefined,
      };
      return r.min !== undefined || r.max !== undefined ? dropUndefined(r) : undefined;
    }),
    flags: keyed(input['flags'], 15, (x) => (x === true ? true : undefined)),
    sort:
      isObj(input['sort']) &&
      typeof input['sort']['field'] === 'string' &&
      COLUMN_KEY.test(input['sort']['field']) &&
      (input['sort']['direction'] === 'ASC' || input['sort']['direction'] === 'DESC')
        ? { field: input['sort']['field'], direction: input['sort']['direction'] }
        : undefined,
  };
  return dropUndefined(c);
}

function dropUndefined<T extends object>(o: T): T {
  return Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined)) as T;
}

/** Cột đang hiện: theo lựa chọn đã lưu (bỏ mã lạ), luôn gồm cột bắt buộc; chưa lưu thì hiện hết. */
export function mergeVisibleColumns(defs: ListColumnDef[], saved: string[] | undefined): string[] {
  const known = new Set(defs.map((d) => d.key));
  const picked = new Set((saved ?? []).filter((k) => known.has(k)));
  if (!picked.size) return defs.filter((d) => !d.hiddenByDefault).map((d) => d.key);
  return defs.filter((d) => d.required || picked.has(d.key)).map((d) => d.key);
}

/** Tham số URL mang trạng thái lọc để chia sẻ đường dẫn trong nội bộ (quyền vẫn do máy chủ). */
export const FILTER_QUERY_PARAM = 'loc';

export function encodeCriteria(c: SavedFilterCriteria): string | null {
  const clean = sanitizeCriteria(c);
  return Object.keys(clean).length ? JSON.stringify(clean) : null;
}

export function decodeCriteria(raw: string | null | undefined): SavedFilterCriteria | null {
  if (!raw) return null;
  try {
    return sanitizeCriteria(JSON.parse(raw));
  } catch {
    return null;
  }
}

/** Đếm điều kiện có trong tiêu chí (để biết bộ chuyển đổi đã bỏ bao nhiêu điều kiện). */
export function countConditions(c: SavedFilterCriteria): number {
  let n = 0;
  for (const v of Object.values(c)) {
    if (Array.isArray(v)) n += v.length;
    else if (isObj(v) && !('field' in v)) n += Object.keys(v).length;
    else if (v !== undefined) n += 1;
  }
  return n;
}

/**
 * Trạng thái cột và bộ lọc đã lưu của một màn hình. Tạo trong ngữ cảnh tiêm (constructor hoặc
 * trường của component) qua {@link createListPersonalization}.
 */
export class ListPersonalization<S> {
  private readonly api = inject(PreferenceApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly table = signal<UserPreferenceItem | null>(null);
  private readonly saved = signal<SavedFilter[]>([]);

  readonly filters: Signal<SavedFilter[]> = this.saved.asReadonly();
  readonly busy = signal(false);
  /** Thông báo nhẹ cho người dùng (lưu xong, điều kiện bị bỏ, lỗi). */
  readonly message = signal<{ ok: boolean; text: string } | null>(null);
  readonly activeId = signal<string | null>(null);
  readonly visible = computed(() =>
    mergeVisibleColumns(
      this.columns,
      (this.table()?.value as TableScreenPreference | undefined)?.visibleColumns,
    ),
  );
  readonly defaultFilter = computed(() => this.saved().find((f) => f.isDefault) ?? null);

  constructor(
    readonly screenCode: string,
    readonly columns: ListColumnDef[],
    private readonly codec: FilterCodec<S>,
  ) {}

  /** Cột có đang hiện không (dùng trong template). */
  shows(key: string): boolean {
    return this.visible().includes(key);
  }

  /** Tải tuỳ chọn cột và bộ lọc; `onDefault` nhận trạng thái của bộ lọc mặc định (nếu có). */
  load(onDefault?: (state: S) => void): void {
    this.api.all().subscribe({
      next: (all) => this.table.set(all.items[`ui.table.${this.screenCode}`] ?? null),
      error: () => undefined,
    });
    this.api.filters(this.screenCode).subscribe({
      next: (items) => {
        this.saved.set(sortFilters(items));
        const d = this.defaultFilter();
        if (d && onDefault) onDefault(this.apply(d.id)!);
      },
      error: () => undefined,
    });
  }

  /** Trạng thái lọc của một bộ lọc đã lưu; báo nhẹ khi có điều kiện không còn hợp lệ. */
  apply(id: string | null): S | null {
    this.activeId.set(id);
    if (!id) return null;
    const f = this.saved().find((x) => x.id === id);
    if (!f) return null;
    const { state, dropped } = this.codec.fromCriteria(sanitizeCriteria(f.criteria));
    this.message.set(
      dropped
        ? {
            ok: true,
            text: `Đã áp dụng bộ lọc "${f.name}"; ${dropped} điều kiện không còn phù hợp nên được bỏ qua.`,
          }
        : null,
    );
    return state;
  }

  criteriaOf(state: S): SavedFilterCriteria {
    return sanitizeCriteria(this.codec.toCriteria(state));
  }

  setVisible(keys: string[]): void {
    const current = this.table();
    const value: TableScreenPreference = {
      ...((current?.value as TableScreenPreference | undefined) ?? {}),
      visibleColumns: mergeVisibleColumns(this.columns, keys),
    };
    this.run(
      this.api.put(`ui.table.${this.screenCode}` as PrefKey, value, current?.rowVersion ?? null),
      (saved) => this.table.set(saved),
      null,
    );
  }

  saveFilter(name: string, state: S, isDefault: boolean): void {
    const trimmed = name.trim();
    if (!trimmed) return;
    if (this.saved().length >= SAVED_FILTER_LIMIT) {
      this.message.set({
        ok: false,
        text: `Mỗi danh sách lưu được tối đa ${SAVED_FILTER_LIMIT} bộ lọc.`,
      });
      return;
    }
    this.run(
      this.api.createFilter({
        screenCode: this.screenCode,
        name: trimmed,
        criteria: this.criteriaOf(state) as Record<string, unknown>,
        isDefault,
        displayOrder: this.saved().length,
      }),
      (f) => {
        this.reload();
        this.activeId.set(f.id);
      },
      `Đã lưu bộ lọc "${trimmed}".`,
    );
  }

  rename(id: string, name: string): void {
    const f = this.saved().find((x) => x.id === id);
    if (!f || !name.trim()) return;
    this.run(
      this.api.updateFilter(f.id, { ...inputOf(f), name: name.trim() }, f.rowVersion),
      () => this.reload(),
      'Đã đổi tên bộ lọc.',
    );
  }

  toggleDefault(id: string): void {
    const f = this.saved().find((x) => x.id === id);
    if (!f) return;
    this.run(
      this.api.updateFilter(f.id, { ...inputOf(f), isDefault: !f.isDefault }, f.rowVersion),
      () => this.reload(),
      f.isDefault
        ? 'Đã bỏ bộ lọc mặc định.'
        : `Bộ lọc "${f.name}" sẽ được áp dụng khi mở danh sách.`,
    );
  }

  remove(id: string): void {
    const f = this.saved().find((x) => x.id === id);
    if (!f) return;
    this.run(
      this.api.removeFilter(f.id, f.rowVersion),
      () => {
        if (this.activeId() === id) this.activeId.set(null);
        this.reload();
      },
      `Đã xoá bộ lọc "${f.name}".`,
    );
  }

  private reload(): void {
    this.api.filters(this.screenCode).subscribe({
      next: (items) => this.saved.set(sortFilters(items)),
      error: () => undefined,
    });
  }

  private run<T>(obs: Observable<T>, next: (v: T) => void, done: string | null): void {
    this.busy.set(true);
    const sub = obs.pipe(finalize(() => this.busy.set(false))).subscribe({
      next: (v) => {
        next(v);
        if (done) this.message.set({ ok: true, text: done });
      },
      error: (err) => {
        const p = toProblem(err);
        const conflict = p.status === 409 || p.status === 412 || p.status === 428;
        this.message.set({
          ok: false,
          text: conflict
            ? 'Bộ lọc vừa được thay đổi trên thiết bị khác; danh sách đã được tải lại, đồng chí vui lòng thử lại.'
            : (p.errors?.[0]?.message ?? p.detail ?? p.title ?? 'Chưa lưu được thay đổi.'),
        });
        if (conflict) this.load();
      },
    });
    this.destroyRef.onDestroy(() => sub.unsubscribe());
  }
}

function sortFilters(items: SavedFilter[]): SavedFilter[] {
  return [...items].sort(
    (a, b) => a.displayOrder - b.displayOrder || a.name.localeCompare(b.name, 'vi'),
  );
}

function inputOf(f: SavedFilter) {
  return {
    name: f.name,
    criteria: f.criteria,
    isDefault: f.isDefault,
    displayOrder: f.displayOrder,
  };
}

/** Tạo trạng thái cột và bộ lọc đã lưu cho màn hình `screenCode` (gọi trong ngữ cảnh tiêm). */
export function createListPersonalization<S>(
  screenCode: string,
  columns: ListColumnDef[],
  codec: FilterCodec<S>,
): ListPersonalization<S> {
  return new ListPersonalization(screenCode, columns, codec);
}
