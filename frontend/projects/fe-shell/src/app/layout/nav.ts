import { PermissionKey, screenKeys } from '@vt/auth';

/**
 * Nhóm màn hình của từng mục menu và tuyến cha trong shell (wiki/02, wiki/15a). Mục menu hiện và
 * tuyến cha cho qua khi vai trò hiện tại có ít nhất một chức năng của nhóm; tuyến con của từng SCR
 * trong remote chặn riêng bằng `permissionGuard`.
 */
export const SECTION_KEYS = {
  // SCR-0401 (thư viện danh mục, WEB-025) đặt ở `/tasks/catalog` của fe-task
  tasks: screenKeys('SCR-0401', 'SCR-0501', 'SCR-0502', 'SCR-0503', 'SCR-0601', 'SCR-0602'),
  okr: screenKeys('SCR-0201', 'SCR-0202', 'SCR-0203', 'SCR-0301'),
  plans: screenKeys('SCR-0402', 'SCR-0403'),
  evidence: screenKeys('SCR-0701', 'SCR-0703'),
  appraisal: screenKeys('SCR-0901', 'SCR-0902', 'SCR-0903', 'SCR-0904', 'SCR-1001', 'SCR-1002'),
  dossier: screenKeys('SCR-1101', 'SCR-1102'),
  oversight: screenKeys('SCR-1201', 'SCR-1202', 'SCR-1301', 'SCR-1302', 'SCR-1401'),
  dashboard: screenKeys('SCR-1601', 'SCR-1602', 'SCR-1603'),
  configStudio: screenKeys(
    'SCR-1501',
    'SCR-1502',
    'SCR-1503',
    'SCR-1504',
    'SCR-1505',
    'SCR-1506',
    'SCR-1801',
    'SCR-1802',
  ),
  admin: screenKeys(
    'SCR-0101',
    'SCR-0102',
    'SCR-0103',
    'SCR-0104',
    'SCR-0105',
    'SCR-0106',
    'SCR-0107',
    'SCR-0801',
  ),
} satisfies Record<string, PermissionKey[]>;

export interface NavItem {
  label: string;
  icon: string;
  link: string;
  /** Khoá chức năng của nhóm; không khai báo nghĩa là mọi người đã đăng nhập đều thấy. */
  perms?: PermissionKey[];
}

/** Menu chính; mục chỉ hiện khi vai trò có chức năng trong nhóm, quyền thật vẫn do máy chủ quyết định. */
export const NAV: NavItem[] = [
  { label: 'Trang chủ', icon: 'pi pi-home', link: '/' },
  // Mục menu chỉ hiện với vai trò có màn hình đầu vào (việc của tôi, giao việc, thư viện danh mục);
  // vai trò chỉ xem chi tiết nhiệm vụ (SCR-0601, ví dụ inspector) vẫn mở được `/tasks/:id` từ màn
  // hình khác. fe-task tự chuyển tới màn hình đầu tiên mà vai trò được dùng.
  {
    label: 'Việc của tôi',
    icon: 'pi pi-check-square',
    link: '/tasks',
    perms: screenKeys('SCR-0401', 'SCR-0501', 'SCR-0502', 'SCR-0503'),
  },
  { label: 'Mục tiêu, OKR', icon: 'pi pi-bullseye', link: '/okr', perms: SECTION_KEYS.okr },
  { label: 'Kế hoạch quý', icon: 'pi pi-calendar', link: '/plans', perms: SECTION_KEYS.plans },
  { label: 'Minh chứng', icon: 'pi pi-paperclip', link: '/evidence', perms: SECTION_KEYS.evidence },
  {
    label: 'Đánh giá, xếp loại',
    icon: 'pi pi-star',
    link: '/appraisal',
    perms: SECTION_KEYS.appraisal,
  },
  {
    label: 'Kiểm điểm, hồ sơ',
    icon: 'pi pi-folder',
    link: '/dossier',
    perms: SECTION_KEYS.dossier,
  },
  {
    label: 'Giám sát, hậu kiểm',
    icon: 'pi pi-eye',
    link: '/oversight',
    perms: SECTION_KEYS.oversight,
  },
  {
    label: 'Bảng điều hành',
    icon: 'pi pi-chart-bar',
    link: '/dashboard',
    perms: SECTION_KEYS.dashboard,
  },
  {
    label: 'Config Studio',
    icon: 'pi pi-sliders-h',
    link: '/config-studio',
    perms: SECTION_KEYS.configStudio,
  },
  { label: 'Quản trị', icon: 'pi pi-cog', link: '/admin', perms: SECTION_KEYS.admin },
];

/** Mục menu mà người dùng có quyền thấy, theo hàm `canAny` của `PermissionService`. */
export function visibleNav(canAny: (...keys: PermissionKey[]) => boolean): NavItem[] {
  return NAV.filter((i) => !i.perms || canAny(...i.perms));
}
