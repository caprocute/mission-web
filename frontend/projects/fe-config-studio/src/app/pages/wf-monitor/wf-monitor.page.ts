import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { ROLE_CODE_LABEL, RoleAdminApi, RoleCode, WorkflowInstanceApi, WorkflowInstanceSummary, WorkflowHistoryAction } from '@vt/api-client/domains';
import { STUDIO_ACCESS } from '../../studio-permissions';
import { errorText, fmtDate, problemCode } from '../../studio-ui';
import { WfDiagram } from '../wf-template/wf-diagram';
import { WF_TITLE } from '../wf-template/wf-templates.page';

/*
 * SCR-1506 Giám sát tiến trình theo mẫu, QT-15, BS-01 (đã duyệt 19/10/2026).
 * Trường: hồ sơ hoặc việc, mẫu và phiên bản, bước hiện tại, người xử lý, hạn, tình trạng kẹt và lý do, cụm.
 * Đọc từ flowable.wf_task_projection, không truy vấn ACT_* lúc cao điểm. AC-02 xem tiến trình của đơn vị
 * mình; AC-01 xem tổng hợp theo cụm. Ba thao tác có kiểm soát: chuyển người xử lý, gia hạn một bước, xem sơ
 * đồ với bước hiện tại. Không sửa biến tiến trình, không nhảy bước; mọi thao tác ghi sổ cái.
 * Quyền: SCR-1506.xem, chuyen-nguoi-xu-ly, gia-han-buoc, so-do (wiki/15a). BS-01 chưa cấp MSG riêng
 * (wiki/15a ràng buộc 32).
 */

const ACTION_LABEL: Record<WorkflowHistoryAction, string> = {
  TAO_TIEN_TRINH: 'Bắt đầu',
  DUYET: 'Duyệt',
  TRA_LAI: 'Trả lại',
  CHUYEN_NGUOI_XU_LY: 'Chuyển người xử lý',
  GIA_HAN: 'Gia hạn',
  NHAC_HAN: 'Nhắc hạn',
  KET_THUC: 'Kết thúc',
};

const CELL_LABEL: Record<string, string> = {
  'CELL-NB': 'Thí điểm nội bộ',
  'CELL-G1': 'Nhóm tỉnh 1',
  'CELL-G2': 'Nhóm tỉnh 2',
  'CELL-G3': 'Nhóm tỉnh 3',
  'CELL-G4': 'Nhóm tỉnh 4',
  'CELL-SH': 'Xã, chi bộ và đơn vị nhỏ',
  'CELL-HN': 'Hà Nội',
  'CELL-DTW': 'Khối các cơ quan Đảng Trung ương',
  'CELL-HCM': 'Thành phố Hồ Chí Minh',
};

type Dlg = { kind: 'reassign' | 'extend' | 'diagram'; row: WorkflowInstanceSummary } | null;

@Component({
  selector: 'vt-wf-monitor-page',
  imports: [ButtonModule, DialogModule, WfDiagram],
  template: `
    <h1>Giám sát tiến trình</h1>
    <p class="sub">
      Theo dõi các tiến trình duyệt đang chạy, phát hiện tiến trình kẹt và xử lý khi cán bộ nghỉ
      hoặc chuyển công tác. Màn hình không cho sửa nội dung hay bỏ qua bước; mọi thao tác được ghi
      vào sổ cái.
    </p>
    @if (!dlg() && error(); as e) {
      <p class="msg err" role="alert">{{ e }}</p>
    }
    @if (notice(); as n) {
      <p class="msg ok" role="status">{{ n }}</p>
    }

    <form class="filters" (submit)="$event.preventDefault()" aria-label="Lọc tiến trình">
      <label for="f-tpl">Mẫu quy trình</label>
      <select id="f-tpl" [value]="template()" (change)="setTemplate(value($event))">
        <option value="">Tất cả</option>
        @for (t of templates; track t.code) {
          <option [value]="t.code">{{ t.title }}</option>
        }
      </select>
      <label class="inline">
        <input type="checkbox" [checked]="stuckOnly()" (change)="setStuck(checked($event))" />
        Chỉ tiến trình kẹt
      </label>
    </form>

    @if (cells().length > 1) {
      <h2 id="cell-h">Tổng hợp theo cụm</h2>
      <ul class="cells" aria-labelledby="cell-h" data-testid="cells">
        @for (c of cells(); track c.code) {
          <li>
            <strong>{{ cellName(c.code) }}</strong>
            <span>{{ c.total }} tiến trình, {{ c.stuck }} kẹt</span>
          </li>
        }
      </ul>
    }

    @if (page.error()) {
      <p class="err" role="alert">Không tải được danh sách tiến trình. Vui lòng thử lại.</p>
    } @else if (!page.value() && !rows().length) {
      <p>Đang tải…</p>
    } @else if (!rows().length) {
      <p data-testid="empty">Không có tiến trình nào phù hợp.</p>
    } @else {
      <table data-testid="instances">
        <caption class="sr-only">
          Tiến trình đang chạy
        </caption>
        <thead>
          <tr>
            <th scope="col">Hồ sơ, công việc</th>
            <th scope="col">Bước hiện tại</th>
            <th scope="col">Người xử lý</th>
            <th scope="col">Hạn</th>
            <th scope="col">Tình trạng</th>
            @if (cells().length > 1) {
              <th scope="col">Cụm</th>
            }
            <th scope="col"><span class="sr-only">Thao tác</span></th>
          </tr>
        </thead>
        <tbody>
          @for (r of rows(); track r.businessKey) {
            <tr [attr.data-key]="r.businessKey" [class.stuck]="r.stuck">
              <td>
                {{ r.title || 'Hồ sơ không có tiêu đề' }}
                <span class="meta"
                  >{{ tplName(r.templateCode) }} · bản {{ r.templateVersion }}</span
                >
              </td>
              <td>{{ r.currentActivityName || 'Đang chuyển bước' }}</td>
              <td>
                {{ r.assigneeId ? 'Đã có người xử lý' : 'Chưa có người xử lý' }}
                @if (r.candidateRole) {
                  <span class="meta">Nhóm: {{ roleName(r.candidateRole) }}</span>
                }
              </td>
              <td>{{ date(r.dueAt) }}</td>
              <td>
                @if (r.stuck) {
                  <span class="bad">Kẹt</span>
                  <span class="meta">{{ r.stuckReason }}</span>
                } @else {
                  Đang chạy
                }
              </td>
              @if (cells().length > 1) {
                <td>{{ cellName(r.cellCode) }}</td>
              }
              <td class="act">
                @if (can('SCR-1506.so-do')) {
                  <p-button
                    size="small"
                    severity="secondary"
                    [text]="true"
                    label="Xem sơ đồ"
                    [ariaLabel]="'Xem sơ đồ: ' + r.title"
                    (onClick)="open('diagram', r)"
                  />
                }
                @if (r.currentTaskId && can('SCR-1506.chuyen-nguoi-xu-ly')) {
                  <p-button
                    size="small"
                    severity="secondary"
                    [outlined]="true"
                    label="Chuyển người xử lý"
                    [ariaLabel]="'Chuyển người xử lý: ' + r.title"
                    (onClick)="open('reassign', r)"
                  />
                }
                @if (r.currentTaskId && can('SCR-1506.gia-han-buoc')) {
                  <p-button
                    size="small"
                    severity="secondary"
                    [outlined]="true"
                    label="Gia hạn"
                    [ariaLabel]="'Gia hạn: ' + r.title"
                    (onClick)="open('extend', r)"
                  />
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
      @if (cursor()) {
        <p-button severity="secondary" [outlined]="true" label="Xem thêm" (onClick)="more()" />
      }
    }

    <p-dialog
      [visible]="dlg()?.kind === 'reassign'"
      (visibleChange)="!$event && close()"
      [modal]="true"
      closeAriaLabel="Đóng"
      header="Chuyển người xử lý"
      [style]="{ width: '34rem', maxWidth: '95vw' }"
    >
      @if (dlg(); as d) {
        <p>{{ d.row.title }} · bước {{ d.row.currentActivityName }}</p>
        <label for="r-to">Người nhận (cùng nhóm người được xử lý bước này)</label>
        <select
          id="r-to"
          [value]="assignee()"
          (change)="assignee.set(value($event))"
          data-testid="assignee"
        >
          <option value="">Chọn người nhận</option>
          @for (p of people(); track p.id) {
            <option [value]="p.id">{{ p.name }} – {{ p.unit }}</option>
          }
        </select>
        @if (candidates.value() && !people().length) {
          <p class="hint">
            Đơn vị chưa có ai giữ vai trò này còn hiệu lực. Hãy phân công vai trò trước.
          </p>
        }
        @if (error(); as e) {
          <p class="err" role="alert">{{ e }}</p>
        }
        <label for="r-why">Lý do (ghi vào sổ cái)</label>
        <textarea
          id="r-why"
          rows="3"
          [value]="reason()"
          (input)="reason.set(value($event))"
        ></textarea>
        <ng-template #footer>
          <p-button severity="secondary" [text]="true" label="Huỷ" (onClick)="close()" />
          <p-button
            data-testid="confirm-reassign"
            label="Chuyển"
            [disabled]="busy() || !assignee() || reason().trim().length < 10"
            (onClick)="reassign()"
          />
        </ng-template>
      }
    </p-dialog>

    <p-dialog
      [visible]="dlg()?.kind === 'extend'"
      (visibleChange)="!$event && close()"
      [modal]="true"
      closeAriaLabel="Đóng"
      header="Gia hạn bước"
      [style]="{ width: '30rem', maxWidth: '95vw' }"
    >
      @if (dlg(); as d) {
        <p>{{ d.row.title }} · hạn hiện tại {{ date(d.row.dueAt) }}</p>
        <label for="e-due">Hạn mới</label>
        <input
          id="e-due"
          type="date"
          [min]="minDue()"
          [value]="due()"
          (input)="due.set(value($event))"
          data-testid="due"
        />
        @if (error(); as e) {
          <p class="err" role="alert">{{ e }}</p>
        }
        <label for="e-why">Lý do (ghi vào sổ cái)</label>
        <textarea
          id="e-why"
          rows="3"
          [value]="reason()"
          (input)="reason.set(value($event))"
        ></textarea>
        <ng-template #footer>
          <p-button severity="secondary" [text]="true" label="Huỷ" (onClick)="close()" />
          <p-button
            data-testid="confirm-extend"
            label="Gia hạn"
            [disabled]="busy() || !due() || reason().trim().length < 10"
            (onClick)="extend()"
          />
        </ng-template>
      }
    </p-dialog>

    <p-dialog
      [visible]="dlg()?.kind === 'diagram'"
      (visibleChange)="!$event && close()"
      [modal]="true"
      closeAriaLabel="Đóng"
      header="Sơ đồ tiến trình"
      [style]="{ width: '60rem', maxWidth: '95vw' }"
    >
      @if (diagram.value(); as g) {
        <p class="legend">
          <span class="k done">Đã qua</span>
          <span class="k active">Bước hiện tại</span>
        </p>
        <vt-wf-diagram
          [xml]="g.bpmnXml"
          [active]="g.activeActivityIds"
          [done]="g.completedActivityIds"
          [name]="tplName(g.templateCode)"
        />
      } @else if (diagram.error()) {
        <p class="err">Không tải được sơ đồ.</p>
      } @else {
        <p>Đang tải…</p>
      }
      @if (history.value(); as h) {
        <h2>Lịch sử xử lý</h2>
        <ol class="history" data-testid="history">
          @for (e of h.entries; track $index) {
            <li>
              {{ date(e.at) }} · {{ actionLabel[e.action] }} · {{ e.activityName }}
              @if (e.actorName) {
                · {{ e.actorName }}
              }
              @if (e.comment) {
                <span class="meta">{{ e.comment }}</span>
              }
            </li>
          }
        </ol>
      }
    </p-dialog>
  `,
  styles: `
    h1 {
      font-size: 1.5rem;
      margin: 0;
    }
    h2 {
      font-size: 1.05rem;
      margin: 16px 0 8px;
    }
    .sub {
      color: #4b5563;
      margin: 4px 0 16px;
      max-width: 80ch;
    }
    .filters {
      display: flex;
      flex-wrap: wrap;
      gap: 8px 12px;
      align-items: center;
      margin-bottom: 12px;
    }
    .filters select {
      width: auto;
    }
    .inline {
      display: flex;
      gap: 6px;
      align-items: center;
    }
    .cells {
      list-style: none;
      padding: 0;
      margin: 0 0 12px;
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .cells li {
      background: #fff;
      border: 1px solid #e5e7eb;
      border-radius: 8px;
      padding: 8px 12px;
      display: grid;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      background: #fff;
    }
    th,
    td {
      text-align: left;
      padding: 10px 8px;
      border-bottom: 1px solid #e5e7eb;
      vertical-align: top;
    }
    thead th {
      background: #f8fafc;
      font-weight: 500;
    }
    tr.stuck td:first-child {
      box-shadow: inset 3px 0 0 #b4001f;
    }
    .meta {
      display: block;
      color: #4b5563;
      font-size: 0.85rem;
    }
    .act {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
    }
    .bad,
    .err {
      color: #b4001f;
    }
    select,
    input,
    textarea {
      font: inherit;
      padding: 6px 8px;
      border: 1px solid #6b7280;
      border-radius: 6px;
      box-sizing: border-box;
    }
    p-dialog select,
    p-dialog input,
    p-dialog textarea {
      width: 100%;
      margin-bottom: 10px;
    }
    p-dialog label {
      display: block;
      font-weight: 500;
      margin-bottom: 4px;
    }
    .legend {
      display: flex;
      gap: 12px;
    }
    .k {
      padding: 2px 8px;
      border-radius: 6px;
      border: 2px solid;
    }
    .k.done {
      border-color: #166534;
      background: #ecfdf3;
    }
    .k.active {
      border-color: #b45309;
      background: #fff7e6;
    }
    .history {
      padding-left: 20px;
    }
    .msg {
      padding: 8px 12px;
      border-radius: 6px;
    }
    .msg.err {
      background: #fdecee;
    }
    .msg.ok {
      background: #ecfdf3;
      color: #14532d;
    }
    .hint {
      color: #4b5563;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
  `,
})
export class WfMonitorPage {
  private readonly api = inject(WorkflowInstanceApi);
  private readonly roles = inject(RoleAdminApi);
  private readonly access = inject(STUDIO_ACCESS);

  protected readonly date = fmtDate;
  protected readonly actionLabel = ACTION_LABEL;
  protected readonly templates = Object.entries(WF_TITLE).map(([code, title]) => ({ code, title }));
  protected readonly template = signal('');
  protected readonly stuckOnly = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly dlg = signal<Dlg>(null);
  protected readonly assignee = signal('');
  protected readonly reason = signal('');
  protected readonly due = signal('');
  /** Các trang đã tải thêm (phân trang con trỏ). */
  private readonly extra = signal<WorkflowInstanceSummary[]>([]);
  private readonly nextCursor = signal<string | null | undefined>(undefined);

  protected readonly page = rxResource({
    params: () => ({
      template: this.template() || undefined,
      stuck: this.stuckOnly() || undefined,
    }),
    stream: ({ params }) => this.api.list(params),
  });
  protected readonly rows = computed(() => [...(this.page.value()?.items ?? []), ...this.extra()]);
  protected readonly cursor = computed(() =>
    this.nextCursor() === undefined ? (this.page.value()?.nextCursor ?? null) : this.nextCursor(),
  );
  protected readonly cells = computed(() => {
    const by = new Map<string, { code: string; total: number; stuck: number }>();
    for (const r of this.rows()) {
      const code = r.cellCode ?? '';
      const c = by.get(code) ?? { code, total: 0, stuck: 0 };
      c.total++;
      if (r.stuck) c.stuck++;
      by.set(code, c);
    }
    return [...by.values()];
  });

  protected readonly candidates = rxResource({
    params: () => {
      const d = this.dlg();
      return d?.kind === 'reassign' && d.row.candidateRole ? d.row.candidateRole : undefined;
    },
    stream: ({ params }) => this.roles.bindings({ role: params as RoleCode, status: 'HIEU_LUC' }),
  });
  protected readonly people = computed(() => {
    const cur = this.dlg()?.row.assigneeId;
    const seen = new Set<string>();
    return (this.candidates.value()?.items ?? [])
      .filter((b) => b.person.id !== cur && !seen.has(b.person.id) && seen.add(b.person.id))
      .map((b) => ({ id: b.person.id, name: b.person.name ?? '', unit: b.orgUnit.name ?? '' }));
  });
  protected readonly diagram = rxResource({
    params: () => (this.dlg()?.kind === 'diagram' ? this.dlg()!.row.businessKey : undefined),
    stream: ({ params }) => this.api.diagram(params),
  });
  protected readonly history = rxResource({
    params: () => (this.dlg()?.kind === 'diagram' ? this.dlg()!.row.businessKey : undefined),
    stream: ({ params }) => this.api.history(params),
  });
  protected readonly minDue = computed(() => {
    const d = this.dlg()?.row.dueAt;
    const base = d && new Date(d) > new Date() ? new Date(d) : new Date();
    base.setDate(base.getDate() + 1);
    return base.toISOString().slice(0, 10);
  });

  protected can(key: Parameters<typeof this.access.can>[0]) {
    return this.access.can(key);
  }

  protected tplName(code?: string) {
    return (code && WF_TITLE[code]) || 'Mẫu quy trình';
  }

  protected cellName(code?: string) {
    return (code && CELL_LABEL[code]) || 'Cụm khác';
  }

  protected roleName(code: string) {
    return ROLE_CODE_LABEL[code as RoleCode] ?? 'Vai trò khác';
  }

  protected value(e: Event) {
    return (e.target as HTMLInputElement).value;
  }

  protected checked(e: Event) {
    return (e.target as HTMLInputElement).checked;
  }

  protected setTemplate(v: string) {
    this.resetPaging();
    this.template.set(v);
  }

  protected setStuck(v: boolean) {
    this.resetPaging();
    this.stuckOnly.set(v);
  }

  private resetPaging() {
    this.extra.set([]);
    this.nextCursor.set(undefined);
  }

  protected more() {
    const c = this.cursor();
    if (!c) return;
    this.api
      .list({
        template: this.template() || undefined,
        stuck: this.stuckOnly() || undefined,
        cursor: c,
      })
      .subscribe({
        next: (p) => {
          this.extra.update((x) => [...x, ...p.items]);
          this.nextCursor.set(p.nextCursor ?? null);
        },
        error: (e) => this.error.set(errorText(e, 'Không tải thêm được.')),
      });
  }

  protected open(kind: 'reassign' | 'extend' | 'diagram', row: WorkflowInstanceSummary) {
    this.error.set(null);
    this.notice.set(null);
    this.assignee.set('');
    this.reason.set('');
    this.due.set('');
    this.dlg.set({ kind, row });
  }

  protected close() {
    this.dlg.set(null);
  }

  private done(text: string) {
    this.busy.set(false);
    this.dlg.set(null);
    this.notice.set(`${text} Thao tác đã được ghi vào sổ cái.`);
    this.resetPaging();
    this.page.reload();
  }

  protected reassign() {
    const d = this.dlg();
    if (!d?.row.currentTaskId) return;
    this.busy.set(true);
    this.api
      .reassign(d.row.currentTaskId, { assigneeId: this.assignee(), reason: this.reason().trim() })
      .subscribe({
        next: () => this.done(`Đã chuyển người xử lý bước ${d.row.currentActivityName ?? ''}.`),
        error: (e) => {
          this.busy.set(false);
          this.error.set(
            problemCode(e) === 'WORKFLOW-422-ASSIGNEE-NOT-ELIGIBLE'
              ? 'Người nhận không thuộc nhóm người được xử lý bước này. Vui lòng chọn người khác.'
              : errorText(e, 'Không chuyển được người xử lý.'),
          );
        },
      });
  }

  protected extend() {
    const d = this.dlg();
    if (!d?.row.currentTaskId) return;
    this.busy.set(true);
    const dueAt = new Date(`${this.due()}T17:00:00+07:00`).toISOString();
    this.api.extend(d.row.currentTaskId, { dueAt, reason: this.reason().trim() }).subscribe({
      next: () =>
        this.done(`Đã gia hạn bước ${d.row.currentActivityName ?? ''} đến ${fmtDate(dueAt)}.`),
      error: (e) => {
        this.busy.set(false);
        this.error.set(errorText(e, 'Không gia hạn được.'));
      },
    });
  }
}
