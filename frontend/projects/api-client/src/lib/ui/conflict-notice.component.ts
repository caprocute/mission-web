import { Component, computed, input, output } from '@angular/core';
import { ButtonModule } from 'primeng/button';

/** Một trường đem ra so sánh khi xung đột ghi. */
export interface ConflictField<T> {
  label: string;
  value: (x: T) => unknown;
}

/**
 * Mẫu UI xử lý xung đột ghi đồng thời (409/412 khi `If-Match` cũ), theo TL-02 "Quy ước giao diện
 * chung": giữ nguyên dữ liệu người dùng đang nhập, hiện phần khác biệt giữa bản của họ và bản
 * mới trên máy chủ, để họ chủ động chọn tải lại hay ghi đè. Không tự tải lại làm mất dữ liệu.
 * Màn hình tự tải bản mới (`theirs`) rồi đưa vào component này.
 */
@Component({
  selector: 'vt-conflict-notice',
  imports: [ButtonModule],
  template: `
    <section class="conflict" role="alert" aria-labelledby="vt-conflict-title">
      <h2 id="vt-conflict-title" class="title">
        <i class="pi pi-exclamation-triangle" aria-hidden="true"></i> {{ message() }}
      </h2>
      @if (theirs(); as server) {
        @if (rows().length) {
          <table class="diff">
            <caption>
              Khác biệt giữa bản đồng chí đang sửa và bản mới trên máy chủ
            </caption>
            <thead>
              <tr>
                <th scope="col">Thông tin</th>
                <th scope="col">Bản của đồng chí</th>
                <th scope="col">Bản mới trên máy chủ</th>
              </tr>
            </thead>
            <tbody>
              @for (r of rows(); track r.label) {
                <tr>
                  <th scope="row">{{ r.label }}</th>
                  <td>{{ r.mine }}</td>
                  <td>{{ r.theirs }}</td>
                </tr>
              }
            </tbody>
          </table>
        } @else {
          <p>Các thông tin đồng chí đang sửa không bị thay đổi trên máy chủ.</p>
        }
      } @else {
        <!-- Khung xương của bảng khác biệt (YC-PT-28); api-client không phụ thuộc @vt/design-system
             nên dựng khối mờ tại chỗ, cùng quy ước với vt-skeleton -->
        <div class="sk" role="status" aria-busy="true">
          <span class="sk-sr">Đang tải bản mới nhất để so sánh</span>
          @for (i of [1, 2, 3]; track i) {
            <div class="sk-row" aria-hidden="true"><span></span><span></span><span></span></div>
          }
        </div>
      }
      <div class="actions">
        <p-button
          label="Tải lại bản mới"
          icon="pi pi-refresh"
          severity="secondary"
          (onClick)="reload.emit()"
        />
        @if (allowOverwrite()) {
          <p-button
            label="Giữ bản của tôi và lưu lại"
            icon="pi pi-save"
            [disabled]="!theirs()"
            (onClick)="overwrite.emit()"
          />
        }
      </div>
    </section>
  `,
  styles: `
    .sk-sr {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
    .sk-row {
      display: grid;
      grid-template-columns: 1fr 2fr 2fr;
      gap: 0.75rem;
      padding: 0.5rem 0;
    }
    .sk-row span {
      height: 0.875rem;
      border-radius: 4px;
      background: var(--vt-sk-base, #e2e8f0);
      animation: sk-pulse 1.4s ease-in-out infinite;
    }
    @keyframes sk-pulse {
      50% {
        opacity: 0.5;
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .sk-row span {
        animation: none;
      }
    }
    .conflict {
      border: 2px solid var(--p-orange-500, #b45309);
      border-radius: 8px;
      padding: 12px 16px;
      background: var(--p-content-background, #fff);
    }
    .title {
      font-size: 1rem;
      margin: 0 0 8px;
    }
    .diff {
      width: 100%;
      border-collapse: collapse;
      margin: 8px 0;
    }
    .diff caption {
      text-align: left;
      margin-bottom: 4px;
    }
    .diff th,
    .diff td {
      text-align: left;
      padding: 6px 8px;
      border-bottom: 1px solid var(--p-content-border-color, #d1d5db);
      vertical-align: top;
    }
    .actions {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
      margin-top: 8px;
    }
  `,
})
export class ConflictNoticeComponent<T = unknown> {
  /** Bản người dùng đang sửa (đã gửi đi và bị từ chối). */
  readonly mine = input.required<T>();
  /** Bản mới nhất trên máy chủ; `null` khi đang tải. */
  readonly theirs = input<T | null>(null);
  readonly fields = input<ConflictField<T>[]>([]);
  readonly message = input('Dữ liệu đã được người khác cập nhật trong lúc đồng chí đang sửa.');
  /** Cho phép ghi đè có chủ ý (gửi lại với `rowVersion` mới). */
  readonly allowOverwrite = input(true);

  readonly reload = output<void>();
  readonly overwrite = output<void>();

  protected readonly rows = computed(() => {
    const server = this.theirs();
    if (!server) return [];
    const mine = this.mine();
    return this.fields()
      .map((f) => ({ label: f.label, mine: show(f.value(mine)), theirs: show(f.value(server)) }))
      .filter((r) => r.mine !== r.theirs);
  });
}

function show(v: unknown): string {
  if (v === null || v === undefined || v === '') return '(trống)';
  return typeof v === 'object' ? JSON.stringify(v) : String(v);
}
