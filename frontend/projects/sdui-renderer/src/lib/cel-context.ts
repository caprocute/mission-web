import { Injectable, computed, inject } from '@angular/core';
import { SessionService } from '@vt/auth';
import { SduiNode } from './ui-pack';

type CelModule = typeof import('cel-js');
type Cst = Extract<ReturnType<CelModule['parse']>, { isSuccess: true }>['cst'];

const MAX_EXPR_LENGTH = 512;

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
  private readonly cache = new Map<string, Cst | null>();
  private cel: Promise<CelModule> | null = null;

  private readonly ctx = computed(() => {
    const u = this.session.user();
    return { user: { id: u?.id ?? '', roles: u?.roles ?? [], tenant: u?.tenant ?? '', unitId: u?.unitId ?? '' } };
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
      console.warn('[cel] Lỗi đánh giá', expr, err);
      return false;
    }
  }

  private compile(cel: CelModule, expr: string): Cst | null {
    if (this.cache.has(expr)) return this.cache.get(expr)!;
    let cst: Cst | null = null;
    if (expr.length <= MAX_EXPR_LENGTH) {
      const r = cel.parse(expr);
      cst = r.isSuccess ? r.cst : null;
      if (!r.isSuccess) console.warn('[cel] Biểu thức không hợp lệ', expr, r.errors);
    }
    this.cache.set(expr, cst);
    return cst;
  }
}
