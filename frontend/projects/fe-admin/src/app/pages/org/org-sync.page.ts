/*
 * SCR-0104 "Đồng bộ và đối soát nhân sự" (QT-01 bước 7–8, TL-02a Hình 6.10) — đối chiếu đặc tả:
 * Tác nhân AC-02; AC-10 chỉ đọc (wiki/15a). Đường vào: menu quản trị "Tenant / Đồng bộ nhân sự" (`/admin/org/sync`).
 * Trường: 1 nguồn đồng bộ (CBCCVC, Đảng viên, SCIM); 2 lần chạy, kết quả; 3 cán bộ, CCCD che 4 số cuối (C2);
 *   4 trường lệch; 5 giá trị nguồn, giá trị hiện có; 6 mức (Cao chặn kích hoạt, BR-01-10); 7 đề xuất;
 *   8 quyết định xử lý; 9 mã ngoài, mã nội bộ (`integ.mapping_xref`); 10 hiệu lực từ theo quyết định nguồn.
 * Vùng: 1 bốn thẻ trạng thái nguồn và tổng lệch; 2 danh sách lệch lọc theo trường và mức; 3 xử lý hàng loạt;
 *   4 chi tiết bản ghi (EVT-15); nhật ký đồng bộ, lần lỗi giữ trong `integ.dead_letter`.
 * Chức năng (org.yaml): Đồng bộ ngay (POST /org/sync-runs, 202); Lịch đồng bộ (/org/sync-schedules, If-Match);
 *   Lấy theo nguồn, Giữ dữ liệu nội bộ (POST /org/discrepancies/resolve, AAL2, EVT-15/16, kết quả từng mục);
 *   Ánh xạ thủ công (/discrepancies/{id}/map, EVT-16); Ghi chú, giao xử lý (/discrepancies/{id}/assign,
 *   EVT-21); Chạy lại thông điệp lỗi (/org/sync-runs/{id}/retry). Thẻ nguồn ghép từ GET /org/sync-runs,
 *   tổng lệch từ `counts` của GET /org/discrepancies (hợp đồng không có API tổng quan riêng).
 * Thông báo: MSG-01-12 còn lệch mức Cao, MSG-01-13 đã áp dụng, MSG-01-14 lần đồng bộ lỗi, MSG-01-15 không khớp người.
 * Quyền (wiki/15a): SCR-0104.xem, SCR-0104.dong-bo, SCR-0104.xu-ly-lech, SCR-0104.giao-xu-ly.
 */
import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Job, JobTracker } from '@vt/api-client';
import {
  OrgDiscrepancy,
  OrgSyncApi,
  OrgSyncRun,
  SYNC_RUN_STATUS_LABEL,
  SYNC_SOURCE_LABEL,
  SYNC_SOURCE_TITLE,
  SyncSource,
  hasDeadLetters,
} from '@vt/api-client/domains';
import { ThemeService, VtSkeletonComponent } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { Observable, Subscription } from 'rxjs';
import { ADMIN_CAN } from '../../admin-permissions';
import { errorText, fmtDateTime, fmtInt } from '../../admin-ui';
import { OrgSyncList } from './org-sync-list';
import { OrgSyncSchedulePanel } from './org-sync-schedule';
import { OrgSyncSide } from './org-sync-side';

const SOURCE_ORDER: SyncSource[] = ['CBCCVC', 'DANG_VIEN', 'SCIM', 'HRM'];

/** MSG-01-12 (BR-01-10). */
export const MSG_01_12 = (n: number) =>
  `Còn ${n} mục lệch mức Cao. Đơn vị chưa thể được kích hoạt.`;
/** MSG-01-14 */
export const MSG_01_14 = (source: string, at: string, reason: string) =>
  `Lần đồng bộ ${source} lúc ${at} lỗi: ${reason}. Dữ liệu đã được lưu lại để chạy lại.`;

@Component({
  selector: 'vt-org-sync-page',
  imports: [ButtonModule, OrgSyncList, OrgSyncSide, OrgSyncSchedulePanel, VtSkeletonComponent],
  template: `
    <header class="head">
      <div>
        <h1>Đồng bộ và đối soát nhân sự</h1>
        <p class="muted">
          Cập nhật nhân sự từ các cơ sở dữ liệu nguồn và xử lý thông tin chưa khớp.
        </p>
      </div>
      @if (can('SCR-0104.dong-bo')) {
        <div class="tools">
          <p-button
            label="Lịch đồng bộ"
            severity="contrast"
            [outlined]="true"
            [attr.aria-expanded]="schedOpen()"
            (onClick)="schedOpen.set(!schedOpen())"
          />
          <p-button label="Đồng bộ ngay" [loading]="running()" (onClick)="syncNow()" />
        </div>
      }
    </header>

    @if (schedOpen()) {
      <vt-org-sync-schedule (closed)="schedOpen.set(false)" />
    }

    <section class="cards" aria-label="Trạng thái nguồn">
      @if ((runs.isLoading() && !runs.hasValue()) || (totals.isLoading() && !totals.hasValue())) {
        <vt-skeleton variant="stats" [rows]="5" label="Đang tải trạng thái đồng bộ" />
      } @else {
        @for (s of sources(); track s.connector) {
          <article class="card">
            <h2 class="lbl">{{ srcTitle[s.connector] }}</h2>
            <p class="big" [class]="'big r-' + s.status">{{ statusLabel[s.status] }}</p>
            <p class="muted">{{ cardLine(s) }}</p>
          </article>
        }
        @if (counts(); as c) {
          <article class="card">
            <h2 class="lbl">Lệch cần đối soát</h2>
            <p class="big total">{{ int(c.CAO + c.VUA + c.THAP) }}</p>
            <p class="muted">
              {{ int(c.CAO) }} mục mức Cao; đơn vị chưa kích hoạt được khi còn mục mức Cao
            </p>
          </article>
        }
      }
    </section>
    @if (loadError(); as e) {
      <p class="note err" role="alert">{{ err(e, 'Không tải được trạng thái đồng bộ.') }}</p>
    }
    @if (counts()?.CAO; as n) {
      <p class="note warn" role="status">{{ msg0112(n) }}</p>
    }
    @for (r of failed(); track r.id) {
      <p class="note err">{{ failText(r) }}</p>
    }
    @if (job(); as j) {
      <p class="note" [class]="'note ' + jobKind(j)" role="status">
        {{
          j.status === 'THAT_BAI'
            ? (j.problem?.title ?? 'Đồng bộ không thành công.')
            : (j.message ?? 'Đang đồng bộ…')
        }}
      </p>
    }
    @if (notice(); as n) {
      <p [class]="'note ' + n.kind" role="status">{{ n.text }}</p>
    }

    <div class="cols" [class.one]="simple()">
      <section class="box" aria-labelledby="z2">
        <h2 id="z2" class="h">Danh sách lệch dữ liệu</h2>
        <vt-org-sync-list
          [simple]="simple()"
          [version]="version()"
          [(focused)]="focused"
          (done)="onDone($event)"
        />
      </section>
      <vt-org-sync-side
        [row]="detail()"
        [runs]="runRows()"
        [runsLoading]="runs.isLoading() && !runs.hasValue()"
        [simple]="simple()"
        [canRetryRun]="can('SCR-0104.dong-bo')"
        (retry)="retry($event)"
      />
    </div>
  `,
  styles: `
    .head {
      display: flex;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
      align-items: flex-start;
    }
    h1 {
      font-size: 1.4rem;
      margin: 0 0 4px;
    }
    .muted {
      color: #4b5563;
      margin: 0;
    }
    .tools {
      display: flex;
      gap: 8px;
    }
    .cards {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 16px;
      margin: 16px 0 8px;
    }
    @media (max-width: 1100px) {
      .cards {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }
    .card {
      padding: 14px 16px;
      border: 1px solid #e5e7eb;
      border-radius: 8px;
      background: #fff;
    }
    .lbl {
      font-size: 0.95rem;
      font-weight: 400;
      color: #4b5563;
      margin: 0;
    }
    .big {
      font-size: 1.5rem;
      font-weight: 700;
      margin: 4px 0;
      color: #146c2e;
    }
    .r-THANH_CONG_MOT_PHAN {
      color: #8a5a00;
    }
    .r-LOI,
    .total {
      color: #b4001f;
    }
    .cols {
      display: grid;
      grid-template-columns: minmax(0, 2.2fr) minmax(300px, 1fr);
      gap: 16px;
      align-items: start;
      margin-top: 8px;
    }
    .cols.one {
      grid-template-columns: 1fr;
    }
    @media (max-width: 1100px) {
      .cols {
        grid-template-columns: 1fr;
      }
    }
    .box {
      padding: 16px;
      border: 1px solid #e5e7eb;
      border-radius: 8px;
      background: #fff;
      min-width: 0;
    }
    .h {
      font-size: 1.05rem;
      margin: 0 0 12px;
    }
    .note {
      margin: 8px 0;
      padding: 8px 12px;
      border-radius: 6px;
      border: 1px solid;
    }
    .warn {
      border-color: #f3d27a;
      background: #fff8e1;
      color: #6b4400;
    }
    .err {
      border-color: #f5b5bf;
      background: #fdeef0;
      color: #a3001f;
    }
    .ok {
      border-color: #a7dbb6;
      background: #ecf8ef;
      color: #146c2e;
    }
    .info {
      border-color: #bcd0f5;
      background: #eef4ff;
      color: #1e3a8a;
    }
  `,
})
export class OrgSyncPage {
  private readonly api = inject(OrgSyncApi);
  private readonly jobs = inject(JobTracker);
  private readonly theme = inject(ThemeService);
  protected readonly can = inject(ADMIN_CAN);
  private readonly list = viewChild(OrgSyncList);

  protected readonly srcTitle = SYNC_SOURCE_TITLE;
  protected readonly statusLabel = SYNC_RUN_STATUS_LABEL;
  protected readonly int = fmtInt;
  protected readonly err = errorText;
  protected readonly msg0112 = MSG_01_12;

  /** Chế độ Đảng viên cao tuổi rút gọn: bỏ cột nguồn và đoạn giải thích (NFR-11). */
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  protected readonly runs = rxResource({ stream: () => this.api.runs({ limit: 20 }) });
  /** Một dòng để lấy `counts` theo mức (tổng lệch, MSG-01-12). */
  protected readonly totals = rxResource({ stream: () => this.api.discrepancies({ limit: 1 }) });
  protected readonly runRows = computed(() =>
    this.runs.hasValue() ? this.runs.value().items : [],
  );
  protected readonly counts = computed(() =>
    this.totals.hasValue() ? this.totals.value().counts : null,
  );
  /** Thẻ vùng 1: lần chạy gần nhất của mỗi nguồn có trong nhật ký. */
  protected readonly sources = computed(() => {
    const seen = new Set<SyncSource>();
    return this.runRows()
      .filter((r) => !seen.has(r.connector) && !!seen.add(r.connector))
      .sort((a, b) => SOURCE_ORDER.indexOf(a.connector) - SOURCE_ORDER.indexOf(b.connector));
  });
  protected readonly loadError = computed(() => this.runs.error() ?? this.totals.error());
  /** Lần chạy còn thông điệp lỗi chờ chạy lại: MSG-01-14. */
  protected readonly failed = computed(() => this.runRows().filter(hasDeadLetters));

  protected readonly schedOpen = signal(false);
  protected readonly version = signal(0);
  protected readonly focused = signal<OrgDiscrepancy | null>(null);
  protected readonly detail = computed(() => this.focused() ?? this.list()?.firstRow() ?? null);
  protected readonly job = signal<Job | null>(null);
  protected readonly running = computed(
    () => !!this.job() && !['HOAN_TAT', 'THAT_BAI', 'DA_HUY'].includes(this.job()!.status),
  );
  protected readonly notice = signal<{ kind: 'ok' | 'err'; text: string } | null>(null);
  private sub?: Subscription;

  protected jobKind(j: Job): string {
    return j.status === 'THAT_BAI' ? 'err' : j.status === 'HOAN_TAT' ? 'ok' : 'info';
  }

  protected cardLine(s: OrgSyncRun): string {
    const at = fmtDateTime(s.startedAt);
    if (s.status === 'THANH_CONG_MOT_PHAN') return `${at} · ${s.errors} bản ghi sai cấu trúc`;
    if (s.status === 'LOI') return `${at} · ${s.errors} lỗi`;
    return `${at} · ${fmtInt(s.records)} bản ghi · ${fmtInt(s.changes)} thay đổi`;
  }

  protected failText(r: OrgSyncRun): string {
    return MSG_01_14(
      SYNC_SOURCE_LABEL[r.connector],
      fmtDateTime(r.startedAt),
      r.reason ?? `${r.errors} lỗi`,
    );
  }

  protected onDone(text: string) {
    this.notice.set({ kind: 'ok', text });
    this.totals.reload();
  }

  protected syncNow() {
    this.track('ORG_SYNC', 'Đang tạo lần đồng bộ…', this.api.syncNow());
  }

  protected retry(r: OrgSyncRun) {
    const at = fmtDateTime(r.startedAt);
    this.track('ORG_SYNC_RETRY', `Đang chạy lại lần đồng bộ lúc ${at}…`, this.api.retry(r.id));
  }

  private track(type: string, message: string, start: Observable<string>) {
    this.sub?.unsubscribe();
    this.notice.set(null);
    this.job.set({ id: '', type, status: 'CHO', progress: 0, message });
    this.sub = start.subscribe({
      next: (loc) => {
        this.sub = this.jobs.track(loc).subscribe({
          next: (j) => this.job.set(j),
          complete: () => this.refresh(),
          error: (e) => this.fail(e),
        });
      },
      error: (e) => this.fail(e),
    });
  }

  private fail(e: unknown) {
    this.job.set(null);
    this.notice.set({ kind: 'err', text: errorText(e, 'Không đồng bộ được.') });
  }

  private refresh() {
    this.runs.reload();
    this.totals.reload();
    this.version.update((n) => n + 1);
  }
}
