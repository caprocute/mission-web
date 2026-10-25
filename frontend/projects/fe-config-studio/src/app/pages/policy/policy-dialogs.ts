import {
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  model,
  output,
  signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import {
  DAY_KIND_LABEL,
  PERIOD_KIND_LABEL,
  PeriodApi,
  PolicyApi,
  PolicyDiffChange,
  PolicyPackVersion,
  PolicyRule,
  QUALITY_METHOD_LABEL,
  RETURN_REASON_LABEL,
  policyVersionLabel,
} from '@vt/api-client/domains';
import { ButtonModule } from 'primeng/button';
import { VtSkeletonComponent } from '@vt/design-system';
import { DialogModule } from 'primeng/dialog';
import { TextareaModule } from 'primeng/textarea';
import { errorText, fmtDate, fmtNum, problemCode } from '../../studio-ui';
import { PolicyWorkspace } from './policy-workspace';

/** Tên tham số hiển thị cho người soạn. */
export const PARAM_LABEL: Record<string, string> = {
  cach_tinh_chat_luong: 'Cách tính chất lượng',
  ly_do_tra_lai: 'Lý do trả lại có tính vào chất lượng',
  tru_tien_do: 'Mức trừ tiến độ mỗi ngày chậm',
  loai_ngay: 'Loại ngày tính chậm',
  diem_toi_da: 'Điểm tối đa của nhóm',
};

export const VALUE_LABEL: Record<string, string> = {
  ...QUALITY_METHOD_LABEL,
  ...RETURN_REASON_LABEL,
  ...DAY_KIND_LABEL,
};

/** So sánh phiên bản đang mở với bản đang áp dụng (SCR-1501 nút "So sánh"). */
@Component({
  selector: 'vt-policy-diff-dialog',
  imports: [DialogModule, VtSkeletonComponent],
  template: `
    <p-dialog
      [header]="'So sánh với bản ' + vlabel(base())"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(860px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      @if (diff.error(); as e) {
        <p class="err" role="alert">Không so sánh được. Vui lòng thử lại.</p>
      } @else if (diff.value(); as d) {
        @if (d.changes.length) {
          <table data-testid="diff">
            <thead>
              <tr>
                <th scope="col">Nội dung</th>
                <th scope="col">Bản {{ vlabel(d.base) }}</th>
                <th scope="col">Bản {{ vlabel(d.target) }}</th>
              </tr>
            </thead>
            <tbody>
              @for (c of d.changes; track $index) {
                <tr>
                  <th scope="row">{{ title(c) }}</th>
                  <td class="before">
                    @if (isExpr(c)) {
                      <pre>{{ c.before ?? '—' }}</pre>
                    } @else {
                      {{ show(c.before) }}
                    }
                  </td>
                  <td class="after">
                    @if (isExpr(c)) {
                      <pre>{{ c.after ?? '—' }}</pre>
                    } @else {
                      {{ show(c.after) }}
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        } @else {
          <p>Hai phiên bản giống nhau.</p>
        }
      } @else {
        <vt-skeleton variant="table" [columns]="3" [rows]="4" label="Đang so sánh hai phiên bản" />
      }
    </p-dialog>
  `,
  styleUrl: './policy-dialogs.css',
})
export class PolicyDiffDialog {
  readonly base = input.required<string>();
  readonly visible = model(false);

  private readonly api = inject(PolicyApi);
  private readonly ws = inject(PolicyWorkspace);
  protected readonly vlabel = policyVersionLabel;
  protected readonly diff = rxResource({
    params: () => {
      const ref = this.ws.ref();
      return this.visible() && ref ? { ref, base: this.base() } : undefined;
    },
    stream: ({ params }) => this.api.diff(params.ref, params.base),
  });

  protected isExpr(c: PolicyDiffChange) {
    return c.kind === 'RULE';
  }

  protected title(c: PolicyDiffChange): string {
    const what = this.ws.label(c.key);
    if (c.kind === 'DECISION_TABLE') return 'Bảng quyết định xếp loại';
    if (c.kind === 'PARAM') {
      const p = (c.path ?? '').replace(/^params\./, '');
      // ui-text-ok: tham số, giá trị và lựa chọn do người soạn chính sách tự đặt, không có bảng nhãn cố định
      return `${what}: ${PARAM_LABEL[p] ?? p}`;
    }
    return c.change === 'THEM' ? `${what} (thêm mới)` : `${what}: biểu thức`;
  }

  protected show(v: unknown): string {
    if (v === null || v === undefined) return '—';
    if (typeof v === 'number') return fmtNum(v);
    // ui-text-ok: tham số, giá trị và lựa chọn do người soạn chính sách tự đặt, không có bảng nhãn cố định
    if (typeof v === 'string') return VALUE_LABEL[v] ?? v;
    if (typeof v === 'object') {
      return Object.entries(v as Record<string, unknown>)
        .map(([k, x]) => `${this.ws.label(k)}: ${this.show(x)}`)
        .join('; ');
    }
    return String(v);
  }
}

/** Toàn bộ biểu thức của phiên bản theo thứ tự tính (SCR-1501 "Xem dạng CEL"). */
@Component({
  selector: 'vt-policy-expressions-dialog',
  imports: [DialogModule],
  template: `
    <p-dialog
      header="Toàn bộ biểu thức"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(860px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      <table>
        <thead>
          <tr>
            <th scope="col">Khối</th>
            <th scope="col">Biểu thức</th>
            <th scope="col">Căn cứ</th>
          </tr>
        </thead>
        <tbody>
          @for (r of rules(); track r.ruleKey) {
            <tr>
              <th scope="row">{{ ws.label(r.ruleKey) }}</th>
              <td>
                <pre>{{ r.expression }}</pre>
              </td>
              <td>{{ r.legalRef ?? '—' }}</td>
            </tr>
          }
        </tbody>
      </table>
    </p-dialog>
  `,
  styleUrl: './policy-dialogs.css',
})
export class PolicyExpressionsDialog {
  readonly rules = input.required<PolicyRule[]>();
  readonly visible = model(false);
  protected readonly ws = inject(PolicyWorkspace);
}

/**
 * "Gửi phê duyệt phát hành" (SCR-1501 vùng 5, BR-15-10): chọn kỳ hiệu lực sắp tới chưa khoá, ghi
 * chú; phạm vi áp dụng là luồng của gói. Phiên bản chuyển "Chờ phê duyệt" để cấp có thẩm quyền ký.
 */
@Component({
  selector: 'vt-policy-submit-dialog',
  imports: [FormsModule, ButtonModule, DialogModule, TextareaModule, VtSkeletonComponent],
  template: `
    <p-dialog
      header="Gửi phê duyệt phát hành"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(560px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      <p class="muted">
        Phiên bản {{ vlabel(ws.ref()?.v ?? '') }} đã đạt toàn bộ test case. Sau khi gửi, phiên bản
        không sửa được nữa cho tới khi có kết quả phê duyệt.
      </p>
      @if (periodsRes.isLoading()) {
        <vt-skeleton variant="form" [rows]="1" label="Đang tải kỳ đánh giá" />
      } @else {
        <label class="f">
          <span>Kỳ đánh giá bắt đầu áp dụng</span>
          <select
            class="native"
            name="period"
            data-testid="submit-period"
            [ngModel]="period()"
            (ngModelChange)="period.set($event)"
          >
            <option value="">Chọn kỳ đánh giá</option>
            @for (p of periods(); track p.id) {
              <option [value]="p.id">
                {{ p.name }} ({{ kindLabel[p.kind] }}, từ {{ date(p.startDate) }})
              </option>
            }
          </select>
          @if (!periodsRes.isLoading() && !periods().length) {
            <small class="muted">Chưa có kỳ đánh giá sắp tới nào để áp dụng.</small>
          }
        </label>
      }
      <label class="f">
        <span>Ghi chú gửi người phê duyệt</span>
        <textarea
          pTextarea
          name="note"
          rows="3"
          maxlength="1000"
          [ngModel]="note()"
          (ngModelChange)="note.set($event)"
        ></textarea>
      </label>
      @if (error(); as e) {
        <p class="err" role="alert">{{ e }}</p>
      }
      <ng-template #footer>
        <p-button
          label="Huỷ"
          severity="contrast"
          [outlined]="true"
          (onClick)="visible.set(false)"
        />
        <p-button
          label="Gửi phê duyệt"
          [loading]="busy()"
          [disabled]="!period()"
          (onClick)="send()"
        />
      </ng-template>
    </p-dialog>
  `,
  styleUrl: './policy-dialogs.css',
})
export class PolicySubmitDialog {
  readonly visible = model(false);
  readonly sent = output<PolicyPackVersion>();

  private readonly api = inject(PolicyApi);
  private readonly periodApi = inject(PeriodApi);
  protected readonly ws = inject(PolicyWorkspace);
  protected readonly vlabel = policyVersionLabel;
  protected readonly date = fmtDate;
  protected readonly kindLabel = PERIOD_KIND_LABEL;

  protected readonly periodsRes = rxResource({
    params: () => this.visible() || undefined,
    stream: () => this.periodApi.list({ status: 'MO' }),
  });
  /** Kỳ chưa bắt đầu và chưa khoá (BR-15-10). */
  protected readonly periods = computed(() => {
    const today = new Date().toISOString().slice(0, 10);
    const flow = this.ws.pack.value()?.flow;
    return (this.periodsRes.value() ?? []).filter(
      (p) => p.startDate > today && (!flow || p.flow === flow || p.flow === 'CA_HAI'),
    );
  });
  protected readonly period = linkedSignal(() => (this.visible(), ''));
  protected readonly note = linkedSignal(() => (this.visible(), ''));
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected send() {
    const ref = this.ws.ref();
    const v = this.ws.version.value();
    const flow = this.ws.pack.value()?.flow;
    if (!ref || !v || !flow) return;
    this.busy.set(true);
    this.error.set(null);
    this.api
      .submit(ref, v.etag, {
        effectivePeriodId: this.period(),
        scopes: [{ flow }],
        note: this.note().trim() || undefined,
      })
      .subscribe({
        next: (res) => {
          this.busy.set(false);
          this.visible.set(false);
          this.sent.emit(res);
        },
        error: (e) => {
          this.busy.set(false);
          this.error.set(
            problemCode(e) === 'POLICY-409-NOT-TESTED'
              ? 'Phiên bản chưa đạt toàn bộ test case nên chưa gửi phê duyệt được. Vui lòng chạy lại kiểm thử.'
              : errorText(e, 'Không gửi được phê duyệt.'),
          );
        },
      });
  }
}
