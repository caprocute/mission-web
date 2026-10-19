import { Component, computed, input } from '@angular/core';
import { JOB_STATUS_LABEL, Job } from '../models/job';

/**
 * Thanh tiến trình tự dựng thay cho `p-progressbar` (bản 21.1 gắn `aria-level` không hợp lệ và
 * không nhận tên truy cập, xem WEB-059).
 *
 * Hiển thị tiến trình một tác vụ dài theo dõi bằng `JobTracker` (SCR-0801, SCR-0904…).
 * Thanh tiến trình có tên truy cập; trạng thái đọc bằng `aria-live`, không chỉ thể hiện bằng màu.
 */
@Component({
  selector: 'vt-job-progress',
  template: `
    <div class="job">
      <p class="label" [id]="labelId()">{{ label() }}</p>
      <div
        class="bar"
        role="progressbar"
        [attr.aria-labelledby]="labelId()"
        aria-valuemin="0"
        aria-valuemax="100"
        [attr.aria-valuenow]="indeterminate() ? null : percent()"
        [attr.aria-valuetext]="indeterminate() ? statusText() : percent() + '%'"
      >
        <div
          class="fill"
          [class.indeterminate]="indeterminate()"
          [style.width.%]="indeterminate() ? 30 : percent()"
        ></div>
      </div>
      <p class="status" aria-live="polite">
        {{ statusText() }}
        @if (job()?.message; as m) {
          · {{ m }}
        }
      </p>
      @if (job()?.status === 'THAT_BAI' && job()?.problem; as p) {
        <p class="error" role="alert">{{ p.title }}</p>
      }
      @if (canLeave() && !done()) {
        <p class="hint">Tác vụ vẫn chạy ở máy chủ, đồng chí có thể đóng màn hình.</p>
      }
    </div>
  `,
  styles: `
    .bar {
      height: 12px;
      border-radius: 6px;
      overflow: hidden;
      background: var(--p-progressbar-background, #e5e7eb);
    }
    .fill {
      height: 100%;
      background: var(--p-progressbar-value-background, var(--vt-color-primary, #b8002a));
      transition: width 0.3s ease;
    }
    .fill.indeterminate {
      animation: vt-job-slide 1.4s ease-in-out infinite;
    }
    @keyframes vt-job-slide {
      from {
        transform: translateX(-100%);
      }
      to {
        transform: translateX(340%);
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .fill.indeterminate {
        animation: none;
        width: 100% !important;
        opacity: 0.5;
      }
    }
    .label {
      margin: 0 0 6px;
      font-weight: 600;
    }
    .status,
    .hint,
    .error {
      margin: 6px 0 0;
    }
    .error {
      color: var(--p-red-700, #b91c1c);
    }
  `,
})
export class JobProgressComponent {
  readonly job = input<Job | null>(null);
  readonly label = input('Tiến trình xử lý');
  readonly labelId = input('vt-job-label');
  /** Hiện lời nhắc có thể đóng màn hình (MSG-09-15). */
  readonly canLeave = input(true);

  protected readonly percent = computed(() => this.job()?.progress ?? 0);
  protected readonly indeterminate = computed(() => {
    const j = this.job();
    return !j || (j.progress === undefined && j.status !== 'HOAN_TAT');
  });
  protected readonly done = computed(() => {
    const s = this.job()?.status;
    return s === 'HOAN_TAT' || s === 'THAT_BAI' || s === 'DA_HUY';
  });
  protected readonly statusText = computed(() => {
    const j = this.job();
    return j ? JOB_STATUS_LABEL[j.status] : 'Đang gửi yêu cầu';
  });
}
