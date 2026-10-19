import { VtRole } from '@vt/auth';

export interface NavItem {
  label: string;
  icon: string;
  link: string;
  roles?: VtRole[];
}

/** Menu chính; mục chỉ hiện với vai trò phù hợp, quyền thật vẫn do máy chủ quyết định. */
export const NAV: NavItem[] = [
  { label: 'Trang chủ', icon: 'pi pi-home', link: '/' },
  { label: 'Việc của tôi', icon: 'pi pi-check-square', link: '/tasks' },
  { label: 'Mục tiêu, OKR', icon: 'pi pi-bullseye', link: '/okr' },
  { label: 'Kế hoạch quý', icon: 'pi pi-calendar', link: '/plans' },
  { label: 'Minh chứng', icon: 'pi pi-paperclip', link: '/evidence' },
  { label: 'Đánh giá, xếp loại', icon: 'pi pi-star', link: '/appraisal' },
  { label: 'Kiểm điểm, hồ sơ', icon: 'pi pi-folder', link: '/dossier' },
  { label: 'Giám sát, hậu kiểm', icon: 'pi pi-eye', link: '/oversight' },
  { label: 'Bảng điều hành', icon: 'pi pi-chart-bar', link: '/dashboard', roles: ['leader', 'manager', 'inspector'] },
  { label: 'Config Studio', icon: 'pi pi-sliders-h', link: '/config-studio', roles: ['policy_author', 'tenant_admin'] },
  { label: 'Quản trị', icon: 'pi pi-cog', link: '/admin', roles: ['platform_admin', 'tenant_admin'] },
];
