import { InjectionToken, inject } from '@angular/core';
import { PermissionService } from '@vt/auth';

/**
 * Khoá chức năng của fe-dossier theo ma trận phân quyền (wiki/15a, `docs/phan-quyen/ma-tran.json`),
 * dạng `SCR-qqnn.<khoá>`. Chỉ liệt kê khoá mà remote đang dùng.
 */
export type DossierPermissionKey =
  | 'SCR-1101.tao'
  | 'SCR-1101.gui-du-thao'
  | 'SCR-1101.gop-y'
  | 'SCR-1101.thu-ky'
  | 'SCR-1101.ket-thuc';

/** Mọi khoá của SCR-1101: có một khoá là vào được màn hình hội nghị kiểm điểm. */
export const MEETING_KEYS: DossierPermissionKey[] = [
  'SCR-1101.tao',
  'SCR-1101.gui-du-thao',
  'SCR-1101.gop-y',
  'SCR-1101.thu-ky',
  'SCR-1101.ket-thuc',
];

/**
 * Hàm hỏi "vai trò hiện tại có thể có quyền này không", nối vào `can()` của `@vt/auth`. Màn hình kết hợp
 * thêm vai trò của người dùng trong từng hội nghị (`myRole`: chủ trì, thư ký, thành viên, người được kiểm
 * điểm) và trạng thái hội nghị; máy chủ quyết định cuối cùng và trả 403 khi chặn. Kiểm thử thay token này.
 */
export const DOSSIER_CAN = new InjectionToken<(key: DossierPermissionKey) => boolean>(
  'vt.fe-dossier.can',
  {
    providedIn: 'root',
    factory: () => {
      const permissions = inject(PermissionService);
      return (key) => permissions.can(key);
    },
  },
);
