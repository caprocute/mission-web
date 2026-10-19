import { Injectable, computed, inject } from '@angular/core';
import { evaluate, parse } from 'cel-js';
import { SessionService } from '@vt/auth';

type Cst = Extract<ReturnType<typeof parse>, { isSuccess: true }>['cst'];

const MAX_EXPR_LENGTH = 512;

/**
 * Đánh giá biểu thức CEL `visibleWhen` trên ngữ cảnh người dùng.
 * cel-js thông dịch bằng bộ phân tích riêng, không dùng `eval`, nên chạy được dưới CSP chặt.
 * Máy chủ (cel-java) vẫn là nguồn sự thật cho mọi quy tắc nghiệp vụ.
 */
@Injectable({ providedIn: 'root' })
export class CelContext {
  private readonly session = inject(SessionService);
  private readonly cache = new Map<string, Cst | null>();

  private readonly ctx = computed(() => {
    const u = this.session.user();
    return { user: { id: u?.id ?? '', roles: u?.roles ?? [], tenant: u?.tenant ?? '', unitId: u?.unitId ?? '' } };
  });

  evaluateBool(expr: string, extra: Record<string, unknown> = {}): boolean {
    const cst = this.compile(expr);
    if (!cst) return false;
    try {
      return evaluate(cst, { ...this.ctx(), ...extra }) === true;
    } catch (err) {
      console.warn('[cel] Lỗi đánh giá', expr, err);
      return false;
    }
  }

  compile(expr: string): Cst | null {
    if (this.cache.has(expr)) return this.cache.get(expr)!;
    let cst: Cst | null = null;
    if (expr.length <= MAX_EXPR_LENGTH) {
      const r = parse(expr);
      cst = r.isSuccess ? r.cst : null;
      if (!r.isSuccess) console.warn('[cel] Biểu thức không hợp lệ', expr, r.errors);
    }
    this.cache.set(expr, cst);
    return cst;
  }
}
