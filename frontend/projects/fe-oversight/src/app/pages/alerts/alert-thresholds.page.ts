/*
 * "Ngưỡng phát hiện" của SCR-1401 (QT-14, BR-14-01, BR-14-09): người có vai trò `policy_author` chỉnh ngưỡng
 * của tám dấu hiệu và lưu phiên bản mới có lý do (GET, PUT /api/v1/config/anomaly-thresholds). Job phát
 * hiện đêm dùng phiên bản mới từ lần chạy kế tiếp. Quyền (wiki/15a): SCR-1401.nguong.
 */
import { Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  ANOMALY_SIGNAL_LABEL,
  AlertApi,
  AnomalySignalThreshold,
  THRESHOLD_PARAM_LABEL,
  toProblem,
} from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { CheckboxModule } from 'primeng/checkbox';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { TextareaModule } from 'primeng/textarea';
import { OVERSIGHT_CAN } from '../../oversight-permissions';
import { errorText, fmtDateTime } from '../../oversight-ui';

const REASON_MIN = 20;

@Component({
  selector: 'vt-alert-thresholds-page',
  imports: [
    FormsModule,
    RouterLink,
    ButtonModule,
    CheckboxModule,
    InputTextModule,
    MessageModule,
    TextareaModule,
  ],
  template: `
    <p class="crumb">
      @if (can('SCR-1401.xem')) {
        <a routerLink="../alerts">Trung tâm cảnh báo bất thường</a>
      } @else {
        <a routerLink="..">Giám sát, hậu kiểm</a>
      }
    </p>
    <h1>Ngưỡng phát hiện dấu hiệu bất thường</h1>
    <p class="muted">
      Ngưỡng áp dụng cho lần rà soát kế tiếp lúc 02:00. Mỗi lần thay đổi được lưu thành một phiên
      bản mới kèm lý do.
    </p>

    @if (notice(); as n) {
      <p-message severity="success" role="status">{{ n }}</p-message>
    }
    @if (error(); as e) {
      <p-message severity="error" role="alert">{{ e }}</p-message>
    }

    @if (res.error()) {
      <p-message severity="error">{{ err(res.error()) }}</p-message>
    } @else if (current(); as t) {
      <p class="small">
        Phiên bản hiện hành {{ t.version }}
        @if (t.updatedAt) {
          , cập nhật {{ dt(t.updatedAt) }}
        }
        @if (t.updatedBy) {
          bởi {{ t.updatedBy.name }}
        }
        @if (t.reason) {
          . Lý do: {{ t.reason }}
        }
      </p>
      <form class="box" (ngSubmit)="save()">
        <div class="table-wrap">
          <table class="tbl">
            <caption class="sr-only">
              Ngưỡng của tám dấu hiệu
            </caption>
            <thead>
              <tr>
                <th scope="col">Áp dụng</th>
                <th scope="col">Dấu hiệu</th>
                <th scope="col">Ngưỡng</th>
              </tr>
            </thead>
            <tbody>
              @for (s of draft(); track s.signalCode; let i = $index) {
                <tr [attr.data-signal]="s.signalCode">
                  <td>
                    <p-checkbox
                      [inputId]="'th-on-' + i"
                      [binary]="true"
                      [ngModel]="s.enabled"
                      (ngModelChange)="patch(i, { enabled: $event })"
                      [name]="'on-' + i"
                      [ariaLabel]="'Áp dụng dấu hiệu ' + signalLabel[s.signalCode]"
                    />
                  </td>
                  <td>
                    <label [for]="'th-on-' + i" class="plain">{{
                      signalLabel[s.signalCode]
                    }}</label>
                  </td>
                  <td>
                    @for (k of keys(s); track k) {
                      <div class="param" [attr.data-param]="k">
                        <label [for]="'th-' + i + '-' + k" class="plain">{{ paramLabel(k) }}</label>
                        <input
                          pInputText
                          type="number"
                          min="0"
                          [id]="'th-' + i + '-' + k"
                          [name]="'p-' + i + '-' + k"
                          [ngModel]="s.params[k]"
                          (ngModelChange)="setParam(i, k, $event)"
                          [disabled]="!s.enabled"
                        />
                        <span class="small">{{ paramUnit(k) }}</span>
                      </div>
                    } @empty {
                      <span class="muted small">Luôn phát hiện, không có ngưỡng</span>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <div class="field">
          <label for="th-reason">Lý do thay đổi (bắt buộc)</label>
          <textarea
            pTextarea
            id="th-reason"
            name="reason"
            rows="3"
            maxlength="2000"
            [ngModel]="reason()"
            (ngModelChange)="reason.set($event)"
          ></textarea>
          @if (errs()['reason']; as m) {
            <p class="err">{{ m }}</p>
          }
          @if (errs()['signals']; as m) {
            <p class="err">{{ m }}</p>
          }
        </div>
        <div class="actions">
          <p-button
            type="submit"
            label="Lưu phiên bản mới"
            [loading]="busy()"
            [disabled]="busy()"
          />
          <p-button
            label="Khôi phục giá trị đang áp dụng"
            severity="contrast"
            [text]="true"
            (onClick)="reset()"
          />
        </div>
      </form>
    } @else {
      <p class="muted">Đang tải…</p>
    }
  `,
  styleUrls: ['../complaints/complaints.css'],
  styles: `
    .param {
      display: flex;
      gap: 8px;
      align-items: center;
      flex-wrap: wrap;
      margin-bottom: 4px;
    }
    .param input {
      width: 96px;
    }
    label.plain {
      font-weight: 400;
      margin: 0;
      display: inline;
    }
    textarea {
      width: 100%;
    }
    .box {
      margin-top: 12px;
    }
  `,
})
export class AlertThresholdsPage {
  private readonly api = inject(AlertApi);
  protected readonly can = inject(OVERSIGHT_CAN);
  protected readonly signalLabel = ANOMALY_SIGNAL_LABEL;

  protected readonly res = rxResource({ stream: () => this.api.thresholds() });
  protected readonly current = linkedSignal(() =>
    this.res.hasValue() ? this.res.value() : undefined,
  );
  protected readonly draft = linkedSignal<AnomalySignalThreshold[]>(() =>
    structuredClone(this.current()?.signals ?? []),
  );
  protected readonly reason = signal('');
  protected readonly errs = signal<Record<string, string>>({});
  protected readonly notice = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly changed = computed(
    () => JSON.stringify(this.draft()) !== JSON.stringify(this.current()?.signals ?? []),
  );

  protected keys(s: AnomalySignalThreshold) {
    return Object.keys(s.params);
  }
  protected paramLabel(k: string) {
    return THRESHOLD_PARAM_LABEL[k]?.label ?? 'Ngưỡng';
  }
  protected paramUnit(k: string) {
    return THRESHOLD_PARAM_LABEL[k]?.unit ?? '';
  }
  protected patch(i: number, p: Partial<AnomalySignalThreshold>) {
    this.draft.set(this.draft().map((s, j) => (j === i ? { ...s, ...p } : s)));
  }
  protected setParam(i: number, k: string, v: string | number | null) {
    const s = this.draft()[i];
    this.patch(i, { params: { ...s.params, [k]: v === '' || v === null ? NaN : Number(v) } });
  }
  protected reset() {
    this.draft.set(structuredClone(this.current()?.signals ?? []));
    this.errs.set({});
  }
  protected dt(iso?: string | null) {
    return fmtDateTime(iso);
  }
  protected err(e: unknown) {
    return errorText(e);
  }

  protected save() {
    const cur = this.current();
    if (!cur) return;
    this.notice.set(null);
    this.error.set(null);
    const e: Record<string, string> = {};
    if (this.reason().trim().length < REASON_MIN)
      e['reason'] = `Lý do thay đổi cần ít nhất ${REASON_MIN} ký tự.`;
    if (this.draft().some((s) => Object.values(s.params).some((v) => !Number.isFinite(v) || v < 0)))
      e['signals'] = 'Mỗi ngưỡng phải là số không âm.';
    else if (!this.changed()) e['signals'] = 'Chưa có ngưỡng nào thay đổi.';
    this.errs.set(e);
    if (Object.keys(e).length) return;
    this.busy.set(true);
    this.api
      .saveThresholds(cur, { signals: this.draft(), reason: this.reason().trim() })
      .subscribe({
        next: (t) => {
          this.busy.set(false);
          this.current.set(t);
          this.reason.set('');
          this.notice.set(
            `Đã lưu phiên bản ngưỡng ${t.version}; áp dụng từ lần rà soát kế tiếp lúc 02:00.`,
          );
        },
        error: (x) => {
          this.busy.set(false);
          const p = toProblem(x);
          if (p.errors?.length) {
            this.errs.set({ reason: p.errors.map((m) => m.message).join(' ') });
            return;
          }
          this.error.set(errorText(x));
          if (p.status === 409 || p.status === 412) this.res.reload();
        },
      });
  }
}
