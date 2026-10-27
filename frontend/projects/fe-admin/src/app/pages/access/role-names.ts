import { Injectable, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { CustomRoleApi, ROLE_CODE_LABEL, RoleCode } from '@vt/api-client/domains';
import { catchError, of } from 'rxjs';
import { ADMIN_CAN } from '../../admin-permissions';

/** Tên hiển thị khi người xem không đọc được danh sách vai trò tuỳ chỉnh (ví dụ lãnh đạo đơn vị). */
const CUSTOM_FALLBACK = 'Vai trò riêng của đơn vị';

/**
 * Tên vai trò cho các màn hình phân quyền: vai trò hệ thống theo baseline mục 10, vai trò tuỳ chỉnh
 * (`tc_…`, BS-04) theo tên quản trị đặt. Danh sách vai trò tuỳ chỉnh chỉ nạp khi người dùng được xem
 * SCR-0110; lỗi thì dùng tên chung, không chặn màn hình.
 */
@Injectable({ providedIn: 'root' })
export class RoleNames {
  private readonly api = inject(CustomRoleApi);
  private readonly can = inject(ADMIN_CAN);

  private readonly customs = rxResource({
    params: () => (this.can('SCR-0110.xem') ? true : undefined),
    stream: () => this.api.list().pipe(catchError(() => of([]))),
  });

  /** Vai trò tuỳ chỉnh đang dùng, để chọn khi cấp vai trò. */
  readonly activeCustom = computed(() =>
    (this.customs.hasValue() ? this.customs.value() : []).filter((r) => r.status === 'HIEU_LUC'),
  );

  private readonly names = computed(() => {
    const m = new Map<string, string>();
    for (const r of this.customs.hasValue() ? this.customs.value() : []) m.set(r.code, r.name);
    return m;
  });

  label(code: string | null | undefined): string {
    if (!code) return '';
    return (
      ROLE_CODE_LABEL[code as RoleCode] ??
      this.names().get(code) ??
      (code.startsWith('tc_') ? CUSTOM_FALLBACK : code)
    );
  }
}
