import { InjectionToken, Injector, inject } from '@angular/core';
import { RUNTIME_CONFIG, fieldErrors, mockStepUp, toProblem } from '@vt/api-client';
import { ROLE_ADMIN_MESSAGES } from '@vt/api-client/domains';
import { SessionService, TEXT_FORBIDDEN } from '@vt/auth';

/** Cửa sổ MFA cho thao tác thay đổi quyền: 15 phút (BR-01-15, BR-00-02). */
const MAX_AGE_SEC = 900;

/**
 * Xác thực bước lên trước khi cấp, duyệt, từ chối, thu hồi vai trò, khai báo hoặc thu hồi uỷ quyền và
 * xuất lịch sử quyền (BR-01-15; TL-01: thay đổi quyền là thao tác nhạy cảm). Gọi `requireStepUp` của
 * `@vt/auth` để shell mở hộp MFA khi phiên chưa đạt `aal2` trong 15 phút; trả `false` khi người dùng
 * huỷ. Với dữ liệu giả lập, báo máy chủ giả lập là phiên vừa MFA. Máy chủ vẫn kiểm và trả 401 nếu thiếu.
 */
export const ACCESS_STEP_UP = new InjectionToken<(reason: string) => Promise<boolean>>(
  'vt.fe-admin.access-step-up',
  {
    providedIn: 'root',
    factory: () => {
      const cfg = inject(RUNTIME_CONFIG);
      const injector = inject(Injector);
      return async (reason: string) => {
        let session: SessionService | null = null;
        try {
          session = injector.get(SessionService);
        } catch {
          session = null;
        }
        if (session) {
          try {
            await session.requireStepUp('aal2', MAX_AGE_SEC, reason);
          } catch {
            return false;
          }
        }
        if (cfg.useMockApi) mockStepUp();
        return true;
      };
    },
  },
);

/** Mã người dùng hiện tại, để ẩn nút duyệt với đề nghị do chính mình lập (BR-01-13). */
export const ACCESS_ME = new InjectionToken<() => string | null>('vt.fe-admin.access-me', {
  providedIn: 'root',
  factory: () => {
    const injector = inject(Injector);
    return () => {
      try {
        return injector.get(SessionService).user()?.id ?? null;
      } catch {
        return null;
      }
    };
  },
});

/** Lỗi hiển thị: mã nghiệp vụ có MSG thì hiện MSG (kể cả 403), 403 khác hiện câu không có quyền chung. */
export function accessError(
  err: unknown,
  fallback: string,
): { text: string; fields: Record<string, string> } {
  const p = toProblem(err);
  const mapped = p.code ? ROLE_ADMIN_MESSAGES[p.code] : undefined;
  const text = mapped ?? (p.status === 403 ? TEXT_FORBIDDEN : p.title || fallback);
  return { text, fields: fieldErrors(p) };
}

/** Ngày chọn trên form (giờ Việt Nam) thành thời điểm đầu ngày, cuối ngày theo `timestamptz`. */
export const dayStart = (d: string) => `${d}T00:00:00+07:00`;
export const dayEnd = (d: string) => `${d}T23:59:59+07:00`;

/** Ngày hôm nay và cộng thêm `n` ngày, dạng `YYYY-MM-DD` theo lịch địa phương. */
export function isoDay(n = 0): string {
  const d = new Date(Date.now() + n * 86_400_000);
  const p = (x: number) => String(x).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** Lớp màu nhãn trạng thái dùng chung cho vai trò và uỷ quyền. */
export function statusTone(status: string): 'ok' | 'wait' | 'off' | 'soon' {
  if (status === 'HIEU_LUC') return 'ok';
  if (status === 'CHO_DUYET') return 'wait';
  if (status === 'SAP_HIEU_LUC') return 'soon';
  return 'off';
}

/** Kiểu dùng chung cho nhãn trạng thái, bảng và hộp thoại của ba màn hình quyền. */
export const ACCESS_STYLES = `
  .muted {
    color: #4b5563;
  }
  .small {
    font-size: 0.875em;
  }
  .box {
    padding: 16px;
    border: 1px solid #e5e7eb;
    border-radius: 8px;
    background: #fff;
    margin-bottom: 16px;
  }
  .h {
    font-size: 1.05rem;
    margin: 0 0 12px;
  }
  .scroll {
    overflow-x: auto;
  }
  .tbl {
    width: 100%;
    border-collapse: collapse;
  }
  .tbl th,
  .tbl td {
    text-align: left;
    padding: 8px 6px;
    border-bottom: 1px solid #e5e7eb;
    vertical-align: top;
  }
  .pill {
    display: inline-block;
    padding: 1px 10px;
    border-radius: 999px;
    font-size: 0.85em;
    white-space: nowrap;
  }
  .pill.ok {
    background: #e7f6ec;
    color: #146c2e;
  }
  .pill.wait {
    background: #fff4d6;
    color: #6b4400;
  }
  .pill.soon {
    background: #e8f0fe;
    color: #1a4fa0;
  }
  .pill.off {
    background: #eef0f3;
    color: #374151;
  }
  .err {
    color: #9b1c1c;
  }
  .link {
    background: none;
    border: 0;
    padding: 2px 4px;
    color: #b8002a;
    text-decoration: underline;
    cursor: pointer;
    font: inherit;
  }
  a {
    color: #b8002a;
  }
  .acts {
    display: flex;
    gap: 4px;
    flex-wrap: wrap;
  }
  /* nút chữ đỏ, nút viền xám của PrimeNG không đạt tương phản 4.5:1 trên nền trắng (WCAG 1.4.3) */
  :host ::ng-deep .p-button-danger.p-button-text {
    color: #9b1c1c;
  }
  :host ::ng-deep .p-button-secondary.p-button-outlined,
  :host ::ng-deep .p-button-secondary.p-button-text {
    color: #374151;
  }
  :host ::ng-deep .msg {
    margin: 8px 0;
    width: 100%;
  }
`;
