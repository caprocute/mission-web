import { Component, computed, inject, linkedSignal, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import {
  OrgSyncApi,
  OrgSyncSchedule,
  SYNC_SOURCE_TITLE,
  ScheduleSet,
  cronToTime,
  timeToCron,
  toProblem,
} from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { errorText } from '../../admin-ui';

/**
 * "Lịch đồng bộ" của SCR-0104: bật tắt và giờ chạy hằng ngày từng nguồn (cron `m h * * *`; biểu
 * thức khác hiện nguyên văn, chỉ bật tắt) → `PUT /org/sync-schedules` cả bộ kèm `If-Match`; 409 thì
 * mời tải lại.
 */
@Component({
  selector: 'vt-org-sync-schedule',
  imports: [FormsModule, ButtonModule],
  template: `
    <section class="box" aria-labelledby="sched">
      <h2 id="sched" class="h">Lịch đồng bộ</h2>
      <table class="tbl">
        <caption class="sr-only">
          Lịch đồng bộ theo nguồn
        </caption>
        <thead>
          <tr>
            <th scope="col">Nguồn</th>
            <th scope="col">Bật</th>
            <th scope="col">Giờ chạy hằng ngày</th>
          </tr>
        </thead>
        <tbody>
          @for (s of rows(); track s.connector; let i = $index) {
            <tr>
              <td>{{ title[s.connector] }}</td>
              <td>
                <input
                  type="checkbox"
                  [attr.aria-label]="'Bật lịch ' + title[s.connector]"
                  [checked]="s.enabled"
                  (change)="edit(i, { enabled: $any($event.target).checked })"
                />
              </td>
              <td>
                @if (time(s.cron); as t) {
                  <input
                    type="time"
                    class="native"
                    [attr.aria-label]="'Giờ chạy ' + title[s.connector]"
                    [ngModel]="t"
                    (ngModelChange)="$event && edit(i, { cron: cron($event) })"
                  />
                } @else {
                  <span>Lịch tuỳ chỉnh (chỉ bật hoặc tắt tại đây)</span>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
      @if (msg(); as m) {
        <p [class]="m.ok ? 'ok' : 'err'" role="status">
          {{ m.text }}
          @if (m.conflict) {
            <button type="button" class="link" (click)="list.reload()">Tải lại</button>
          }
        </p>
      }
      <div class="acts">
        <p-button label="Lưu lịch" [disabled]="!dirty()" [loading]="busy()" (onClick)="save()" />
        <p-button label="Đóng" severity="contrast" [outlined]="true" (onClick)="closed.emit()" />
      </div>
    </section>
  `,
  styles: `
    .box {
      padding: 16px;
      border: 1px solid #e5e7eb;
      border-radius: 8px;
      background: #fff;
      margin: 12px 0 16px;
    }
    .h {
      font-size: 1.05rem;
      margin: 0 0 8px;
    }
    .tbl {
      width: 100%;
      border-collapse: collapse;
      margin-bottom: 8px;
    }
    .tbl th,
    .tbl td {
      text-align: left;
      padding: 6px;
      border-bottom: 1px solid #e5e7eb;
    }
    .native {
      padding: 6px 8px;
      border: 1px solid #cbd5e1;
      border-radius: 6px;
      font: inherit;
    }
    .acts {
      display: flex;
      gap: 8px;
    }
    .ok {
      color: #146c2e;
    }
    .err {
      color: #b4001f;
    }
    .link {
      background: none;
      border: 0;
      color: #b8002a;
      text-decoration: underline;
      cursor: pointer;
      font: inherit;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
  `,
})
export class OrgSyncSchedulePanel {
  readonly closed = output<void>();
  private readonly api = inject(OrgSyncApi);
  protected readonly title = SYNC_SOURCE_TITLE;
  protected readonly time = cronToTime;
  protected readonly cron = timeToCron;
  protected readonly list = rxResource({ stream: () => this.api.schedules() });
  private readonly saved = linkedSignal<ScheduleSet | null>(() =>
    this.list.hasValue() ? this.list.value() : null,
  );
  protected readonly rows = linkedSignal<OrgSyncSchedule[]>(() =>
    (this.saved()?.items ?? []).map((s) => ({ ...s })),
  );
  protected readonly busy = signal(false);
  protected readonly msg = signal<{ ok: boolean; text: string; conflict?: boolean } | null>(null);
  protected readonly dirty = computed(() =>
    this.rows().some((a, i) => {
      const b = this.saved()?.items[i];
      return !!b && (a.enabled !== b.enabled || a.cron !== b.cron);
    }),
  );

  protected edit(i: number, patch: Partial<OrgSyncSchedule>) {
    this.rows.update((r) => r.map((s, k) => (k === i ? { ...s, ...patch } : s)));
  }

  protected save() {
    const cur = this.saved();
    if (!cur) return;
    this.busy.set(true);
    this.msg.set(null);
    this.api.saveSchedules(cur, this.rows()).subscribe({
      next: (set) => {
        this.busy.set(false);
        this.saved.set(set);
        this.msg.set({ ok: true, text: 'Đã lưu lịch đồng bộ.' });
      },
      error: (e) => {
        this.busy.set(false);
        this.msg.set({
          ok: false,
          text: errorText(e, 'Không lưu được lịch đồng bộ.'),
          conflict: toProblem(e).status === 409,
        });
      },
    });
  }
}
