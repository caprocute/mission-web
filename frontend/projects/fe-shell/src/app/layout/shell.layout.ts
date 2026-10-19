import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import { NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { SessionService } from '@vt/auth';
import { ThemeService } from '@vt/design-system';
import { RUNTIME_CONFIG } from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { AvatarModule } from 'primeng/avatar';
import { TooltipModule } from 'primeng/tooltip';
import { NAV } from './nav';

/** Khung ứng dụng: thanh trên, menu bên, vùng nội dung (dùng cho mọi màn hình sau đăng nhập). */
@Component({
  selector: 'vt-shell-layout',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ButtonModule, AvatarModule, TooltipModule],
  templateUrl: './shell.layout.html',
  styleUrl: './shell.layout.scss',
})
export class ShellLayout {
  protected readonly session = inject(SessionService);
  protected readonly theme = inject(ThemeService);
  protected readonly cfg = inject(RUNTIME_CONFIG);
  protected readonly navOpen = signal(false);
  private readonly title = inject(Title);

  /** Tiêu đề trang hiện tại, hiển thị trên thanh trên ở màn hình hẹp. */
  protected readonly pageTitle = toSignal(
    inject(Router).events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map(() => this.title.getTitle()),
    ),
    { initialValue: '' },
  );

  protected readonly nav = computed(() => {
    const u = this.session.user();
    return NAV.filter((i) => !i.roles || (u && i.roles.some((r) => u.roles.includes(r))));
  });
  protected readonly initials = computed(() =>
    (this.session.user()?.name ?? '?').split(' ').filter(Boolean).slice(-2).map((w) => w[0]).join('').toUpperCase(),
  );
}
