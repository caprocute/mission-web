import { Component, computed, inject, input } from '@angular/core';
import { toSignal, toObservable } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, of, switchMap } from 'rxjs';
import { OkrApi, progressPct } from '@vt/api-client';
import { PermissionService } from '@vt/auth';
import { CardModule } from 'primeng/card';
import { VtProgressBarComponent, VtSkeletonComponent } from '@vt/design-system';

/** Số mục tiêu hiện trên trang chủ (BR-02-01 khuyến nghị tối đa 3 mục tiêu mỗi kỳ). */
const TOP = 3;

/**
 * `vt.widget.okrProgress`: tiến độ mục tiêu của kỳ đang mở trên SCR-0002 (QT-02 → SCR-0201 →
 * `listOkrObjectives` → MOD-01). `scope = me` lấy OKR cá nhân (`owner=me`), `unit` lấy mục tiêu tập
 * thể của đơn vị trong ngữ cảnh làm việc; máy chủ chọn kỳ đang mở khi không truyền kỳ. Chỉ hiện (và
 * chỉ gọi API) khi vai trò có `SCR-0201.xem` (YC-PT-21, wiki/15a).
 */
@Component({
  selector: 'vt-okr-progress-widget',
  imports: [VtSkeletonComponent, RouterLink, CardModule, VtProgressBarComponent],
  template: `
    @if (canView) {
      <p-card [header]="scope() === 'unit' ? 'Tiến độ OKR của đơn vị' : 'Tiến độ OKR của tôi'">
        @for (o of top(); track o.id) {
          <div class="row">
            <span class="title">{{ o.code }}. {{ o.title }}</span>
            <vt-progress-bar [value]="o.pct" [label]="o.title" />
          </div>
        } @empty {
          @if (loaded()) {
            <p class="muted">Chưa có mục tiêu trong kỳ đánh giá đang mở.</p>
          } @else {
            <vt-skeleton variant="text" [rows]="3" label="Đang tải mục tiêu" />
          }
        }
        <a routerLink="/okr" class="more">Xem cây mục tiêu →</a>
      </p-card>
    }
  `,
  styles: `
    .row {
      display: grid;
      gap: 6px;
      margin-bottom: 16px;
    }
    .title {
      font-weight: 500;
    }
    .muted {
      color: var(--p-text-muted-color, #566275);
    }
    .more {
      display: inline-block;
      margin-top: 4px;
      color: var(--vt-color-primary-strong, #d1002d);
      text-decoration: none;
    }
  `,
})
export class OkrProgressWidget {
  readonly scope = input<'me' | 'unit'>('me');
  protected readonly canView = inject(PermissionService).can('SCR-0201.xem');
  private readonly okr = inject(OkrApi);
  // widget SDUI phải chịu được lỗi API: lỗi thì hiện như chưa có mục tiêu, không làm hỏng trang chủ
  private readonly page = toSignal(
    toObservable(this.scope).pipe(
      switchMap((scope) =>
        this.canView
          ? this.okr
              .objectives(
                scope === 'unit'
                  ? { ownerType: 'TAP_THE', limit: TOP }
                  : { owner: 'me', limit: TOP },
              )
              .pipe(catchError(() => of({ items: [], nextCursor: null })))
          : of(null),
      ),
    ),
  );
  protected readonly loaded = computed(() => this.page() !== undefined);
  protected readonly top = computed(() =>
    (this.page()?.items ?? [])
      .slice(0, TOP)
      .map((o) => ({ id: o.id, code: o.code, title: o.title, pct: progressPct(o.progress) })),
  );
}
