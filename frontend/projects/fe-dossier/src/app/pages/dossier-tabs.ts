import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { DOSSIER_CAN, MEETING_KEYS, PACKAGE_KEYS } from '../dossier-permissions';

/**
 * Chuyển giữa hai màn hình của "Hồ sơ số" (SCR-1101 Hội nghị kiểm điểm, SCR-1102 Gói hồ sơ số); chỉ hiện khi
 * vai trò dùng được cả hai (wiki/15a).
 */
@Component({
  selector: 'vt-dossier-tabs',
  imports: [RouterLink, RouterLinkActive],
  template: `
    @if (both()) {
      <nav class="tabs" aria-label="Hồ sơ số">
        <a routerLink="../kiem-diem" routerLinkActive="on" ariaCurrentWhenActive="page"
          >Hội nghị kiểm điểm</a
        >
        <a routerLink="../goi-ho-so" routerLinkActive="on" ariaCurrentWhenActive="page"
          >Gói hồ sơ số</a
        >
      </nav>
    }
  `,
  styles: `
    .tabs {
      display: flex;
      gap: 4px;
      border-bottom: 1px solid var(--p-content-border-color);
      margin-bottom: 16px;
    }
    a {
      padding: 10px 14px;
      min-height: 44px;
      box-sizing: border-box;
      color: inherit;
      text-decoration: none;
      border-bottom: 3px solid transparent;
    }
    a.on {
      border-color: #b4001f;
      font-weight: 600;
    }
  `,
})
export class DossierTabs {
  private readonly can = inject(DOSSIER_CAN);
  protected readonly both = computed(
    () => MEETING_KEYS.some((k) => this.can(k)) && PACKAGE_KEYS.some((k) => this.can(k)),
  );
}
