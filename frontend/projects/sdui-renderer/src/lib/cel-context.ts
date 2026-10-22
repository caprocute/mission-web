import { Injectable, computed, inject } from '@angular/core';
import { SessionService } from '@vt/auth';
import { SduiTelemetry } from './sdui-telemetry';
import { SduiNode } from './ui-pack';

type CelModule = typeof import('cel-js');
type Cst = Extract<ReturnType<CelModule['parse']>, { isSuccess: true }>['cst'];

/** Giới hạn chi phí đánh giá CEL ở trình duyệt (WEB-009): độ dài, độ lồng ngoặc, số macro lặp. */
export const CEL_LIMITS = { maxLength: 512, maxDepth: 12, maxComprehensions: 2 } as const;

const COMPREHENSION = /^\.\s*(all|exists_one|exists|map|filter)\s*\(/;

/**
 * Lý do biểu thức vượt giới hạn chi phí, hoặc null. Quét ký tự (bỏ qua chuỗi trong nháy) trước khi
 * phân tích, để biểu thức lồng sâu không làm tràn ngăn xếp của bộ phân tích.
 */
export function celLimitViolation(expr: string): string | null {
  if (expr.length > CEL_LIMITS.maxLength) return 'length';
  let depth = 0;
  let comprehensions = 0;
  for (let i = 0; i < expr.length; i++) {
    const c = expr[i];
    if (c === '"' || c === "'") {
      // bỏ qua chuỗi; chuỗi không đóng để bộ phân tích báo lỗi
      for (i++; i < expr.length && expr[i] !== c; i++) if (expr[i] === '\\') i++;
    } else if (c === '(' || c === '[' || c === '{') {
      if (++depth > CEL_LIMITS.maxDepth) return 'depth';
    } else if (c === ')' || c === ']' || c === '}') {
      depth--;
    } else if (c === '.' && COMPREHENSION.test(expr.slice(i, i + 16))) {
      if (++comprehensions > CEL_LIMITS.maxComprehensions) return 'comprehensions';
    }
  }
  return null;
}

/** Bố cục có nút nào cần đánh giá `visibleWhen` không (để nạp sẵn cel-js song song). */
export function layoutNeedsCel(node: SduiNode | null | undefined): boolean {
  if (!node) return false;
  return !!node.visibleWhen || (node.children ?? []).some((c) => layoutNeedsCel(c));
}

/**
 * Đánh giá biểu thức CEL `visibleWhen` trên ngữ cảnh người dùng.
 * cel-js thông dịch bằng bộ phân tích riêng, không dùng `eval`, nên chạy được dưới CSP chặt.
 * Máy chủ (cel-java) vẫn là nguồn sự thật cho mọi quy tắc nghiệp vụ.
 *
 * cel-js (khoảng 47 KB gzip) được nạp lười ở lần đánh giá đầu tiên (WEB-052), nên không nằm trên
 * đường găng khởi động của shell và không tải ở trang không có `visibleWhen`.
 */
@Injectable({ providedIn: 'root' })
export class CelContext {
  private readonly session = inject(SessionService);
  private readonly telemetry = inject(SduiTelemetry);
  private readonly cache = new Map<string, Cst | null>();
  private cel: Promise<CelModule> | null = null;

  private readonly ctx = computed(() => {
    const u = this.session.user();
    return {
      user: {
        id: u?.id ?? '',
        roles: u?.roles ?? [],
        tenant: u?.tenant ?? '',
        unitId: u?.unitId ?? '',
      },
    };
  });

  /** Bắt đầu tải cel-js nếu chưa tải; gọi được nhiều lần. */
  preload(): Promise<CelModule> {
    return (this.cel ??= import('cel-js'));
  }

  async evaluateBool(expr: string, extra: Record<string, unknown> = {}): Promise<boolean> {
    let cel: CelModule;
    try {
      cel = await this.preload();
    } catch (err) {
      this.cel = null;
      console.warn('[cel] Không tải được bộ đánh giá CEL', err);
      return false;
    }
    const cst = this.compile(cel, expr);
    if (!cst) return false;
    try {
      return cel.evaluate(cst, { ...this.ctx(), ...extra }) === true;
    } catch (err) {
      this.telemetry.record('sdui.cel.error', {
        expr: expr.slice(0, 120),
        error: (err as Error)?.name ?? 'Error',
      });
      return false;
    }
  }

  private compile(cel: CelModule, expr: string): Cst | null {
    if (this.cache.has(expr)) return this.cache.get(expr)!;
    let cst: Cst | null = null;
    const limit = celLimitViolation(expr);
    if (limit) {
      this.telemetry.record('sdui.cel.limit', { reason: limit, length: expr.length });
    } else {
      const r = cel.parse(expr);
      cst = r.isSuccess ? r.cst : null;
      if (!r.isSuccess) this.telemetry.record('sdui.cel.invalid', { expr: expr.slice(0, 120) });
    }
    this.cache.set(expr, cst);
    return cst;
  }
}
