import { Component, computed, inject } from '@angular/core';
import {
  ActivatedRouteSnapshot,
  Router,
  RouterLink,
  RouterLinkActive,
  RouterOutlet,
} from '@angular/router';
import { PermissionKey, PermissionService } from '@vt/auth';
import { STUDIO_ACCESS } from './studio-permissions';

/** Ai mở được gói chính sách: người soạn, người phê duyệt xem kết quả kiểm thử khi ký. */
export const POLICY_KEYS: PermissionKey[] = [
  'SCR-1501.soan',
  'SCR-1503.chay',
  'SCR-1503.xem-duyet',
  'SCR-1504.cau-hinh',
];
export const UI_PACK_KEYS: PermissionKey[] = [
  'SCR-1801.soan',
  'SCR-1801.gui-duyet',
  'SCR-1801.duyet',
  'SCR-1801.quay-lui',
];
export const FORM_KEYS: PermissionKey[] = ['SCR-1802.dung'];
/** Thư viện mẫu quy trình dùng chung: chỉ đội sản phẩm nền tảng (BS-01 SCR-1505). */
export const WF_TEMPLATE_KEYS: PermissionKey[] = ['SCR-1505.thu-vien'];
/** Giám sát tiến trình theo mẫu (BS-01 SCR-1506): quản trị đơn vị và quản trị nền tảng. */
export const WF_MONITOR_KEYS: PermissionKey[] = ['SCR-1506.xem'];

/** Các phần của Config Studio theo ma trận wiki/15a; trang đầu là phần đầu tiên người dùng có quyền. */
export const STUDIO_SECTIONS: { path: string; label: string; keys: PermissionKey[] }[] = [
  { path: '', label: 'Chính sách đánh giá', keys: POLICY_KEYS },
  { path: 'giao-dien', label: 'Giao diện đơn vị', keys: UI_PACK_KEYS },
  { path: 'bieu-mau', label: 'Biểu mẫu động', keys: FORM_KEYS },
  { path: 'mau-luong', label: 'Mẫu quy trình dùng chung', keys: WF_TEMPLATE_KEYS },
  { path: 'tien-trinh', label: 'Giám sát tiến trình', keys: WF_MONITOR_KEYS },
];

/**
 * Trang đầu `/config-studio`: người có quyền chính sách vào danh sách chính sách; quản trị chỉ có quyền
 * giao diện, biểu mẫu thì chuyển sang phần tương ứng. Máy chủ vẫn kiểm quyền và trả 403.
 */
export const studioLanding = (route: ActivatedRouteSnapshot) => {
  const perms = inject(PermissionService);
  if (perms.canAny(...POLICY_KEYS)) return true;
  const base = route.pathFromRoot.flatMap((r) => r.url.map((s) => s.path));
  const to = STUDIO_SECTIONS.find((s) => s.path && perms.canAny(...s.keys));
  return to ? inject(Router).createUrlTree(['/', ...base, to.path]) : true;
};

/** Khung Config Studio: thanh chuyển phần (chỉ hiện khi người dùng có từ hai phần trở lên). */
@Component({
  selector: 'vt-studio-layout',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  template: `
    @if (sections().length > 1) {
      <nav class="sections" aria-label="Các phần cấu hình">
        @for (s of sections(); track s.path) {
          <a
            [routerLink]="s.path ? s.path : '.'"
            routerLinkActive="on"
            [routerLinkActiveOptions]="{ exact: !s.path }"
            ariaCurrentWhenActive="page"
            >{{ s.label }}</a
          >
        }
      </nav>
    }
    <router-outlet />
  `,
  styles: `
    /* Chữ nút viền phụ của Aura (#64748b) chỉ đạt 4,4 : 1 trên nền trang, nâng lên đạt AA. */
    :host {
      display: block;
      --p-button-outlined-secondary-color: #374151;
    }
    .sections {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-bottom: 16px;
    }
    .sections a {
      padding: 6px 14px;
      border-radius: 999px;
      border: 1px solid #cbd5e1;
      color: #374151;
      text-decoration: none;
      min-height: 36px;
      display: inline-flex;
      align-items: center;
    }
    .sections a.on {
      background: #b4001f;
      border-color: #b4001f;
      color: #fff;
    }
  `,
})
export class StudioLayout {
  private readonly access = inject(STUDIO_ACCESS);
  protected readonly sections = computed(() =>
    STUDIO_SECTIONS.filter((s) => s.keys.some((k) => this.access.can(k))),
  );
}
