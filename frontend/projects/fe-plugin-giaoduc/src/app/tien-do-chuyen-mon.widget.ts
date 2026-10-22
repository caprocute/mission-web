import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PermissionService, screenKeys } from '@vt/auth';
import { CardModule } from 'primeng/card';
import { CHI_SO, nhiemVuCuaToi } from './chuyen-mon-data';

/**
 * `giaoduc.widget.tienDoChuyenMon`: khối "Tiến độ chuyên môn" trên trang chủ của tenant ngành giáo
 * dục. Chỉ hiện (và chỉ gọi API) khi vai trò có SCR-0503 theo ma trận (wiki/15a).
 */
@Component({
  selector: 'vt-giaoduc-tien-do-widget',
  imports: [RouterLink, CardModule],
  template: `
    @if (canView) {
      <p-card>
        <ng-template #title>Tiến độ chuyên môn</ng-template>
        <dl class="grid">
          @for (c of chiSo; track c.key) {
            <div class="item">
              <dt>{{ c.label }}</dt>
              <dd>{{ data.value()?.counts?.[c.key] ?? '—' }}</dd>
            </div>
          }
        </dl>
        @if (data.error()) {
          <p class="muted" role="status">Chưa tải được số liệu, đồng chí vui lòng thử lại sau.</p>
        }
        <a routerLink="/plugin/giaoduc" class="more">Xem nhiệm vụ chuyên môn →</a>
      </p-card>
    }
  `,
  styles: `
    .grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 12px;
      margin: 0 0 12px;
    }
    .item {
      padding: 12px;
      border: 1px solid var(--vt-color-border);
      border-radius: 8px;
    }
    dt {
      color: var(--vt-color-text-muted);
    }
    dd {
      margin: 4px 0 0;
      font-size: 1.5rem;
      font-weight: 600;
    }
    .more {
      display: inline-flex;
      align-items: center;
      min-height: var(--vt-min-touch, 40px);
      color: var(--vt-color-primary-strong);
    }
    .muted {
      color: var(--vt-color-text-muted);
    }
  `,
})
export class TienDoChuyenMonWidget {
  protected readonly canView = inject(PermissionService).canAny(...screenKeys('SCR-0503'));
  protected readonly chiSo = CHI_SO;
  protected readonly data = nhiemVuCuaToi(1);
}
