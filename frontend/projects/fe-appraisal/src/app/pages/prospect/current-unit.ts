import { InjectionToken, Injector, inject } from '@angular/core';
import { SessionService } from '@vt/auth';

/**
 * Đơn vị đang làm việc của người xem (QT-00 bước 7), dùng làm `orgUnitId` của bảng phân nhóm SCR-1002 khi
 * đường dẫn không ghi `?unit=`. Chạy riêng remote (không có shell) thì không có phiên, trả `null`.
 */
export const APPRAISAL_CURRENT_UNIT = new InjectionToken<() => string | null>(
  'vt.fe-appraisal.current-unit',
  {
    providedIn: 'root',
    factory: () => {
      const injector = inject(Injector);
      return () => {
        try {
          return injector.get(SessionService).user()?.unitId ?? null;
        } catch {
          return null;
        }
      };
    },
  },
);
