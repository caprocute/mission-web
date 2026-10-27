import { Component, computed, input, model, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AssignCheckItem, AssignCheckResult, SimilarTask, TASK_STATUS_LABEL } from '@vt/api-client';
import { VtSkeletonComponent } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { TextareaModule } from 'primeng/textarea';
import { formatDate } from '../../task-ui';

/**
 * SCR-0501 vùng 4 và phần bên phải: "Kiểm tra trước khi giao" (QT-05 bước 5), lý do tạo mới khi có
 * nhiệm vụ gần giống (BR-05-06, MSG-05-03) và khối lượng của người chủ trì
 * theo tuần (BR-05-11, MSG-05-04). Kết quả kiểm tra do máy chủ trả; trang chỉ hiển thị.
 */
@Component({
  selector: 'vt-assign-side-panel',
  imports: [VtSkeletonComponent, FormsModule, ButtonModule, TagModule, TextareaModule],
  template: `
    <section class="box" aria-labelledby="chk-title" aria-live="polite">
      <h2 id="chk-title" class="h">Kiểm tra trước khi giao</h2>
      @if (result(); as r) {
        <ul class="checks">
          @for (i of items(); track $index) {
            <li>
              <p-tag [value]="tag(i).label" [severity]="tag(i).severity" styleClass="chk-tag" />
              <span>{{ i.message }}</span>
            </li>
          }
        </ul>
        @if (r.similar.length) {
          <ul class="similar">
            @for (s of r.similar; track s.id) {
              <li>
                <div>
                  <strong>{{ s.code }}</strong> · {{ s.title }}
                </div>
                <div class="muted small">
                  {{ status(s) }} · chủ trì {{ s.leadName }} · hạn {{ date(s.dueDate) }} ·
                  {{ s.progress }}%
                </div>
                @if (canViewSimilar()) {
                  <p-button
                    label="Xem nhiệm vụ gần giống"
                    [link]="true"
                    size="small"
                    styleClass="lnk"
                    (onClick)="viewSimilar.emit(s)"
                  />
                }
              </li>
            }
          </ul>
          <label for="as-reason">Lý do vẫn tạo nhiệm vụ mới <span class="req">*</span></label>
          <textarea
            id="as-reason"
            pTextarea
            rows="2"
            maxlength="1000"
            class="full"
            [ngModel]="similarReason()"
            (ngModelChange)="similarReason.set($event)"
          ></textarea>
          <small class="muted"
            >Nếu là cùng một công việc, hãy dùng nhiệm vụ cũ để không tính điểm hai lần.</small
          >
        }
      } @else if (loading()) {
        <vt-skeleton variant="list" [rows]="3" label="Đang kiểm tra trước khi giao" />
      } @else {
        <p class="muted">Điền nội dung nhiệm vụ để hệ thống kiểm tra.</p>
      }
    </section>

    @if (!simple()) {
      <!-- Giao việc phát sự kiện NhiemVuDaGiao (EVT-04) để gửi thông báo; không in giải thích vòng đời lên màn hình (YC-PT-24) -->
      @if (result()?.workload; as w) {
        <section class="box" aria-labelledby="wl-title">
          <h2 id="wl-title" class="h">Khối lượng của người chủ trì</h2>
          <p class="muted small">
            {{ w.personName }}: chủ trì {{ w.leadCount }}, phối hợp {{ w.coCount }} việc trong quý;
            ngưỡng cảnh báo {{ w.weeklyThreshold }} việc/tuần.
          </p>
          <table class="wl">
            <thead>
              <tr>
                <th scope="col">Tuần</th>
                <th scope="col">Việc</th>
                <th scope="col">Mức</th>
              </tr>
            </thead>
            <tbody>
              @for (x of w.weeks; track x.label) {
                <tr>
                  <td>{{ x.label }}</td>
                  <td>{{ x.count }}</td>
                  <td>
                    <div class="bar" aria-hidden="true">
                      <span
                        [style.width.%]="pct(x.count, w.weeklyThreshold)"
                        [class.over]="x.count > w.weeklyThreshold"
                        [class.near]="x.count === w.weeklyThreshold"
                      ></span>
                    </div>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </section>
      }
    }
  `,
  styles: `
    .box {
      padding: 12px 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: var(--p-content-background, #fff);
      margin-bottom: 12px;
    }
    .h {
      font-size: 1.05rem;
      margin: 0 0 10px;
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .num {
      display: inline-grid;
      place-items: center;
      width: 24px;
      height: 24px;
      border-radius: 50%;
      background: #111827;
      color: #fff;
      font-size: 0.8rem;
    }
    .checks,
    .similar {
      list-style: none;
      padding: 0;
      margin: 0;
    }
    .checks li {
      display: flex;
      align-items: flex-start;
      gap: 8px;
      padding: 4px 0;
      line-height: 1.5;
    }
    :host ::ng-deep .chk-tag {
      flex: none;
      min-width: 52px;
      justify-content: center;
    }
    .similar li {
      padding: 8px 0;
      border-top: 1px solid var(--p-content-border-color, #e5e7eb);
    }
    :host ::ng-deep .lnk {
      padding-left: 0;
    }
    label {
      display: block;
      margin: 8px 0 6px;
      font-weight: 500;
    }
    .req {
      color: #b91c1c;
    }
    .full {
      width: 100%;
    }
    .muted {
      color: #4b5563;
    }
    .small {
      font-size: 0.9em;
    }
    .wl {
      width: 100%;
      border-collapse: collapse;
    }
    .wl th {
      text-align: left;
      color: #4b5563;
      font-weight: 500;
    }
    .wl th,
    .wl td {
      padding: 6px 4px;
      border-bottom: 1px solid var(--p-content-border-color, #e5e7eb);
    }
    .bar {
      height: 8px;
      border-radius: 4px;
      background: #e5e7eb;
      overflow: hidden;
      min-width: 80px;
    }
    .bar span {
      display: block;
      height: 100%;
      background: #15803d;
    }
    .bar span.near {
      background: #b45309;
    }
    .bar span.over {
      background: #b91c1c;
    }
  `,
})
export class AssignSidePanel {
  readonly result = input<AssignCheckResult | undefined>();
  readonly loading = input(false);
  readonly simple = input(false);
  readonly canViewSimilar = input(true);
  /** Giao đồng loạt: mỗi đơn vị nhận tự là chủ trì, bỏ dòng BR-05-01 (một chủ trì). */
  readonly bulk = input(false);
  readonly similarReason = model('');
  readonly viewSimilar = output<SimilarTask>();

  protected readonly items = computed(() =>
    (this.result()?.items ?? []).filter((i) => !(this.bulk() && i.rule === 'BR-05-01')),
  );

  protected tag(i: AssignCheckItem): { label: string; severity: 'success' | 'danger' | 'warn' } {
    if (i.passed) return { label: 'Đạt', severity: 'success' };
    return i.level === 'CHAN'
      ? { label: 'Chưa đạt', severity: 'danger' }
      : { label: 'Lưu ý', severity: 'warn' };
  }
  protected status(s: SimilarTask) {
    return TASK_STATUS_LABEL[s.status];
  }
  protected date(d: string) {
    return formatDate(d, true);
  }
  protected pct(n: number, threshold: number) {
    return Math.min(100, Math.round((n / Math.max(1, threshold + 1)) * 100));
  }
}
