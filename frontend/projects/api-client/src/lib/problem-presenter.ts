import { Injectable, InjectionToken, inject } from '@angular/core';
import { MessageService } from 'primeng/api';
import { isLoadShed } from './load-shedding';
import { ProblemDetails, fieldErrors, isConcurrencyConflict, toProblem } from './problem-details';

/** Thông báo xếp hàng khi hệ thống cắt tải (WEB-073); không hiện mã kỹ thuật. */
export const LOAD_SHED_MESSAGE =
  'Hệ thống đang bận do nhiều người dùng cùng lúc. Nội dung đồng chí nhập vẫn được giữ, vui lòng gửi lại sau ít phút.';

/**
 * Bảng `code` của Problem Details → văn bản MSG của TL-02 (ví dụ `TASK-409-PERIOD-LOCKED` →
 * MSG-08-06). Mỗi remote bổ sung bảng của mình bằng `provideProblemMessages({...})`.
 */
export const PROBLEM_MESSAGES = new InjectionToken<Record<string, string>[]>('vt.problemMessages', {
  providedIn: 'root',
  factory: () => [],
});

export function provideProblemMessages(messages: Record<string, string>) {
  return { provide: PROBLEM_MESSAGES, useValue: messages, multi: true };
}

/** Kết quả trình bày một lỗi: dùng cho thông báo chung và lỗi cạnh từng trường. */
export interface PresentedProblem {
  problem: ProblemDetails;
  /** Văn bản hiển thị: MSG ánh xạ từ `code`, nếu không có thì `title`. */
  message: string;
  /** Lỗi theo trường từ `errors[]`, khoá là tên trường. */
  fields: Record<string, string>;
  /** Xung đột ghi đồng thời (409/412): giao diện phải giữ dữ liệu và cho so sánh, tải lại. */
  conflict: boolean;
  /** Hệ thống cắt tải (429/503): nội dung được giữ, người dùng có thể gửi lại. */
  retryable: boolean;
  /** Mã truy vết hiển thị cho người dùng báo hỗ trợ (chỉ khi không có MSG riêng). */
  traceId?: string;
}

export interface PresentOptions {
  /** Hiện toast (cần `MessageService` và `<p-toast>` ở shell). Mặc định: bật trừ khi chỉ có lỗi theo trường hoặc xung đột. */
  toast?: boolean;
}

/**
 * Trình bày lỗi RFC 9457 thống nhất cho mọi remote (wiki/06 "Lỗi theo RFC 9457"):
 * lỗi có `errors[]` hiện cạnh trường; lỗi có `code` ánh xạ được thì hiện MSG; còn lại hiện
 * `title` kèm `traceId`. Xung đột ghi không bật toast mà để màn hình hiện `<vt-conflict-notice>`.
 */
@Injectable({ providedIn: 'root' })
export class ProblemPresenter {
  private readonly messages = inject(PROBLEM_MESSAGES);
  private readonly toastService = inject(MessageService, { optional: true });

  describe(err: unknown): PresentedProblem {
    const problem = toProblem(err);
    const mapped = problem.code ? this.lookup(problem.code) : undefined;
    const fields = fieldErrors(problem);
    const retryable = isLoadShed(problem);
    return {
      problem,
      message: mapped ?? (retryable ? LOAD_SHED_MESSAGE : problem.title),
      fields,
      conflict: isConcurrencyConflict(problem),
      retryable,
      traceId: mapped || retryable ? undefined : problem.traceId,
    };
  }

  present(err: unknown, opts: PresentOptions = {}): PresentedProblem {
    const p = this.describe(err);
    const onlyFields = Object.keys(p.fields).length > 0;
    const toast = opts.toast ?? !(onlyFields || p.conflict);
    if (toast && this.toastService) {
      this.toastService.add({
        severity: p.problem.status >= 500 || p.problem.status === 0 ? 'error' : 'warn',
        summary: p.message,
        detail: p.traceId ? `Mã tra cứu: ${p.traceId}` : p.retryable ? undefined : p.problem.detail,
        life: 8000,
      });
    }
    return p;
  }

  private lookup(code: string): string | undefined {
    for (let i = this.messages.length - 1; i >= 0; i--) {
      const text = this.messages[i][code];
      if (text) return text;
    }
    return undefined;
  }
}
