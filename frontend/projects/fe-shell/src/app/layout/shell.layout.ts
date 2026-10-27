import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import {
  NavigationCancel,
  NavigationEnd,
  NavigationError,
  RouteConfigLoadStart,
  Router,
} from '@angular/router';
import { filter, map } from 'rxjs';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import {
  ContextPickerComponent,
  MSG_00_06,
  PermissionService,
  SessionNoticeComponent,
  SessionService,
  StepUpDialogComponent,
} from '@vt/auth';
import { ThemeService, VtSkeletonComponent } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { AvatarModule } from 'primeng/avatar';
import { Toast } from 'primeng/toast';
// Giữ TooltipModule trong shell để thư viện dùng chung của remote được chia sẻ (AGENTS.md quy tắc 8)
import { TooltipModule } from 'primeng/tooltip';
import { FeatureFlagService } from '@vt/api-client';
import { DisplayPreferenceSync } from './display-preference.sync';
import { visibleNav } from './nav';
import { AppStatusBanner } from './app-status-banner';
import { GlobalSearch } from './global-search';
import { NotificationBell } from './notification-bell';
import { PluginHost } from '../plugins/plugin-host';

/** Khung ứng dụng: thanh trên, menu bên, vùng nội dung (dùng cho mọi màn hình sau đăng nhập). */
@Component({
  selector: 'vt-shell-layout',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    ButtonModule,
    AvatarModule,
    TooltipModule,
    SessionNoticeComponent,
    ContextPickerComponent,
    StepUpDialogComponent,
    NotificationBell,
    GlobalSearch,
    AppStatusBanner,
    Toast,
    VtSkeletonComponent,
  ],
  templateUrl: './shell.layout.html',
  styleUrl: './shell.layout.scss',
})
export class ShellLayout {
  protected readonly session = inject(SessionService);
  protected readonly theme = inject(ThemeService);
  /** CNH-05: chế độ cao tuổi theo người dùng trên mọi thiết bị. */
  protected readonly display = inject(DisplayPreferenceSync);
  protected readonly navOpen = signal(false);
  protected readonly noAssignmentText = MSG_00_06;
  private readonly title = inject(Title);

  /** Tiêu đề trang hiện tại, hiển thị trên thanh trên ở màn hình hẹp. */
  // skeleton-ok: tiêu đề lấy từ dữ liệu tuyến qua Title của trình duyệt sau NavigationEnd, không
  // gọi máy chủ; trang đang chuyển đã có khung xương riêng (routeLoading)
  protected readonly pageTitle = toSignal(
    inject(Router).events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map(() => this.title.getTitle()),
    ),
    { initialValue: '' },
  );

  /**
   * Đang tải mã của phân hệ (remote) cho tuyến mới: hiện khung xương trang thay cho trang cũ hoặc
   * vùng trống (YC-PT-28), tới khi điều hướng kết thúc.
   */
  protected readonly routeLoading = toSignal(
    inject(Router).events.pipe(
      filter(
        (e) =>
          e instanceof RouteConfigLoadStart ||
          e instanceof NavigationEnd ||
          e instanceof NavigationCancel ||
          e instanceof NavigationError,
      ),
      map((e) => e instanceof RouteConfigLoadStart),
    ),
    { initialValue: false },
  );

  private readonly permissions = inject(PermissionService);
  private readonly flags = inject(FeatureFlagService);
  protected readonly nav = computed(() =>
    visibleNav(
      (...keys) => this.permissions.canAny(...keys),
      (key) => this.flags.isEnabled(key),
    ),
  );
  /** Chuông thông báo theo ma trận phân quyền (SCR-0002.doc-thong-bao, wiki/15a). */
  protected readonly canNotify = computed(
    () => !!this.session.user() && this.permissions.can('SCR-0002.doc-thong-bao'),
  );
  protected readonly platformNav = computed(() =>
    this.nav().filter((i) => i.section === 'platform'),
  );
  private readonly plugins = inject(PluginHost);
  /** Mục của plugin chuyên ngành (WEB-051) đứng cuối nhóm "Không gian làm việc". */
  protected readonly workspaceNav = computed(() => [
    ...this.nav().filter((i) => i.section !== 'platform'),
    ...this.plugins.navItems(),
  ]);
  protected readonly initials = computed(() =>
    (this.session.user()?.name ?? '?')
      .split(' ')
      .filter(Boolean)
      .slice(-2)
      .map((w) => w[0])
      .join('')
      .toUpperCase(),
  );
}
