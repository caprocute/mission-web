import {
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  linkedSignal,
  signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import {
  WF_ROLLOUT_STATUS_LABEL,
  WF_TEMPLATE_STATUS_LABEL,
  WfLintIssue,
  WorkflowTemplate,
  WorkflowTemplateApi,
  WorkflowTemplateSummary,
  lintBpmn,
  toProblem,
} from '@vt/api-client';
import { STUDIO_ACCESS } from '../../studio-permissions';
import { STUDIO_STEP_UP } from '../../studio-step-up';
import { errorText, fmtDate, problemCode } from '../../studio-ui';
import { WfDiagram } from './wf-diagram';
import {
  PARAM_TYPES,
  ParamDef,
  WF_BLOCKS,
  WfBlock,
  insertBlock,
  newParam,
  paramErrors,
  paramsFromSchema,
  schemaFromParams,
} from './wf-model';

/*
 * SCR-1505 Thư viện và trình soạn mẫu luồng (nội bộ nền tảng), QT-15, BS-01 (đã duyệt 19/10/2026).
 * Trường: mã mẫu, phiên bản, trạng thái (NHAP, DAT_KIEM_THU, CHO_PHE_DUYET, HIEU_LUC, NGUNG), số đơn vị
 * đang dùng (wf_binding), số test; sơ đồ BPMN; khai báo tham số (param_schema, sinh biểu mẫu SCR-1504).
 * Chức năng: soạn, kiểm tra luật ngay khi sửa (4 quy tắc chống mạng nhện TL-01, ≤ 12 user task mỗi nhánh,
 * đa thể hiện có giới hạn), chạy test và thử tải rút gọn (≤ 20 câu SQL mỗi lần hoàn thành user task),
 * đề nghị phát hành (merge request), phê duyệt bốn mắt có xác thực nhiều lớp, theo dõi triển khai theo cell.
 * Quyền: SCR-1505.thu-vien, soan, chay-test, de-nghi-phat-hanh, phe-duyet, trien-khai-cell (wiki/15a).
 * BS-01 chưa cấp BR, MSG riêng cho màn hình này (wiki/15a ràng buộc 31 "Chưa có MSG").
 */

const POLL_MS = 1500;

/** Tên mẫu cho người dùng (hợp đồng chưa có trường tên, suy luận, wiki/14). */
export const WF_TITLE: Record<string, string> = {
  'duyet-ho-so-danh-gia': 'Duyệt hồ sơ đánh giá',
  'giao-nhiem-vu-nghiem-thu': 'Giao nhiệm vụ và nghiệm thu',
};
const titleOf = (code: string) => WF_TITLE[code] ?? 'Mẫu quy trình';

@Component({
  selector: 'vt-wf-templates-page',
  imports: [RouterLink, ButtonModule, WfDiagram],
  template: `
    <h1>Mẫu quy trình dùng chung</h1>
    <p class="sub">
      Mẫu quy trình duyệt dùng chung cho mọi đơn vị. Đơn vị chỉ chọn mẫu và điền tham số ở màn hình
      cấu hình luồng duyệt; mọi thay đổi mẫu phải đạt kiểm thử và được người thứ hai phê duyệt.
    </p>
    @if (error(); as e) {
      <p class="msg err" role="alert">{{ e }}</p>
    }
    @if (notice(); as n) {
      <p class="msg ok" role="status">{{ n }}</p>
    }
    <div class="grid">
      <nav class="lib" aria-label="Thư viện mẫu quy trình" data-testid="library">
        @if (list.error()) {
          <p class="err">Không tải được thư viện mẫu.</p>
        } @else if (!list.value()) {
          <p>Đang tải…</p>
        }
        @for (g of groups(); track g.code) {
          <h2>{{ g.title }}</h2>
          <ul>
            @for (t of g.items; track t.id) {
              <li>
                <a
                  [routerLink]="['/config-studio', 'mau-luong', t.id]"
                  [attr.aria-current]="t.id === id() ? 'page' : null"
                  [attr.data-tpl]="t.id"
                >
                  <span class="v">Bản {{ t.versionNo }}</span>
                  <span class="st" [attr.data-status]="t.status">{{ statusLabel[t.status] }}</span>
                  <span class="meta"
                    >{{ t.tenantBindingCount ?? 0 }} đơn vị dùng · {{ t.testCount ?? 0 }} kịch bản
                    test</span
                  >
                </a>
              </li>
            }
          </ul>
        }
      </nav>

      <section class="main" aria-label="Sơ đồ mẫu">
        @if (!id()) {
          <p class="hint">Chọn một phiên bản mẫu trong thư viện để xem hoặc soạn.</p>
        } @else if (tpl.error()) {
          <p class="err" role="alert">Không tải được mẫu quy trình.</p>
        } @else if (tpl.value(); as t) {
          <div class="bar" data-testid="tpl-bar">
            <strong>{{ title(t.templateCode) }} · bản {{ t.versionNo }}</strong>
            <span class="st" [attr.data-status]="t.status">{{ statusLabel[t.status] }}</span>
            <span>Cập nhật {{ date(t.updatedAt) }}</span>
            @if (t.mergeRequestUrl) {
              <a [href]="t.mergeRequestUrl" target="_blank" rel="noopener"
                >Xem đề nghị phát hành trong kho mã</a
              >
            }
          </div>
          @if (!editable()) {
            <p class="hint">
              @if (t.status === 'HIEU_LUC' || t.status === 'NGUNG') {
                Phiên bản đã phát hành không sửa được; mọi thay đổi tạo phiên bản mới.
              } @else {
                Bạn đang xem phiên bản này ở chế độ chỉ đọc.
              }
            </p>
          }
          <vt-wf-diagram [xml]="xml()" [issues]="issueIds()" [name]="title(t.templateCode)" />

          <div class="checks" data-testid="lint" aria-live="polite">
            @if (issues().length) {
              <h2>{{ issues().length }} lỗi cần sửa trước khi lưu</h2>
              <ul>
                @for (i of issues(); track $index) {
                  <li class="bad">{{ i.message }}</li>
                }
              </ul>
            } @else {
              <p class="good">Sơ đồ đạt các quy tắc của mẫu quy trình dùng chung.</p>
            }
          </div>

          @if (editable()) {
            <div class="blocks" role="group" aria-label="Khối dựng sẵn">
              <span>Thêm khối dựng sẵn:</span>
              @for (b of blocks; track b.id) {
                <p-button
                  size="small"
                  severity="secondary"
                  [outlined]="true"
                  [label]="b.label"
                  (onClick)="addBlock(b.id)"
                />
              }
            </div>
            <label class="src" for="wf-src">Mã nguồn sơ đồ</label>
            <textarea
              id="wf-src"
              rows="10"
              spellcheck="false"
              [value]="xml()"
              (input)="xml.set(value($event))"
            ></textarea>
          }

          <div class="actions">
            @if (editable()) {
              <p-button
                data-testid="save"
                label="Lưu nháp"
                severity="secondary"
                [outlined]="true"
                [disabled]="!dirty() || busy() || !valid()"
                (onClick)="save()"
              />
            }
            @if (
              (t.status === 'NHAP' || t.status === 'DAT_KIEM_THU') && can('SCR-1505.chay-test')
            ) {
              <p-button
                data-testid="run-tests"
                label="Chạy kiểm thử"
                severity="secondary"
                [outlined]="true"
                [disabled]="dirty() || busy() || !valid()"
                (onClick)="runTests()"
              />
            }
            @if (t.status === 'DAT_KIEM_THU' && can('SCR-1505.de-nghi-phat-hanh')) {
              <p-button
                data-testid="submit"
                label="Đề nghị phát hành"
                [disabled]="dirty() || busy()"
                (onClick)="submit()"
              />
            }
            @if (t.status === 'CHO_PHE_DUYET' && can('SCR-1505.phe-duyet')) {
              <p-button
                data-testid="approve"
                label="Phê duyệt phát hành"
                [disabled]="busy()"
                (onClick)="approve()"
              />
            }
            @if (t.status === 'HIEU_LUC' && can('SCR-1505.soan')) {
              <p-button
                data-testid="new-version"
                label="Tạo phiên bản mới"
                severity="secondary"
                [outlined]="true"
                [disabled]="busy()"
                (onClick)="newVersion()"
              />
            }
          </div>

          @if (t.status === 'HIEU_LUC' && rollouts.value(); as r) {
            <h2 id="ro-h">Triển khai lên các cụm máy chủ</h2>
            <table data-testid="rollouts" aria-labelledby="ro-h">
              <thead>
                <tr>
                  <th scope="col">Cụm</th>
                  <th scope="col">Trạng thái</th>
                  <th scope="col">Thời điểm</th>
                </tr>
              </thead>
              <tbody>
                @for (c of r.items; track c.cellCode) {
                  <tr>
                    <td>{{ cell(c.cellCode) }}</td>
                    <td [attr.data-status]="c.status">{{ rolloutLabel[c.status] }}</td>
                    <td>{{ date(c.deployedAt) }}</td>
                  </tr>
                }
              </tbody>
            </table>
            <p class="hint">
              Triển khai cuốn chiếu ngoài giờ cao điểm: cụm thí điểm nội bộ trước, các cụm lớn sau
              cùng. Lỗi ở một cụm thì đặt lại phiên bản trước làm mặc định.
            </p>
          }
        } @else {
          <p>Đang tải…</p>
        }
      </section>

      @if (tpl.value(); as t) {
        <section class="props" aria-labelledby="pr-h" data-testid="params">
          <h2 id="pr-h">Tham số đơn vị được điền</h2>
          <p class="hint">
            Khai báo này sinh ra biểu mẫu tham số ở màn hình cấu hình luồng duyệt của đơn vị.
          </p>
          @for (p of params(); track $index; let i = $index) {
            <fieldset [attr.data-param]="p.key">
              <legend>{{ p.title || 'Tham số mới' }}</legend>
              <label [for]="'pk' + i">Tên kỹ thuật</label>
              <input
                [id]="'pk' + i"
                [value]="p.key"
                [disabled]="!editable()"
                (input)="setParam(i, 'key', value($event))"
              />
              <label [for]="'pt' + i">Tên hiển thị</label>
              <input
                [id]="'pt' + i"
                [value]="p.title"
                [disabled]="!editable()"
                (input)="setParam(i, 'title', value($event))"
              />
              <label [for]="'py' + i">Kiểu</label>
              <select
                [id]="'py' + i"
                [value]="p.type"
                [disabled]="!editable()"
                (change)="setParam(i, 'type', value($event))"
              >
                @for (o of paramTypes; track o.value) {
                  <option [value]="o.value" [selected]="o.value === p.type">{{ o.label }}</option>
                }
              </select>
              @if (p.type === 'integer') {
                <div class="row">
                  <span>
                    <label [for]="'pmin' + i">Nhỏ nhất</label>
                    <input
                      [id]="'pmin' + i"
                      inputmode="numeric"
                      [value]="p.min"
                      [disabled]="!editable()"
                      (input)="setParam(i, 'min', value($event))"
                    />
                  </span>
                  <span>
                    <label [for]="'pmax' + i">Lớn nhất</label>
                    <input
                      [id]="'pmax' + i"
                      inputmode="numeric"
                      [value]="p.max"
                      [disabled]="!editable()"
                      (input)="setParam(i, 'max', value($event))"
                    />
                  </span>
                </div>
              }
              @if (p.type === 'choice') {
                <label [for]="'po' + i">Lựa chọn (mỗi dòng một giá trị)</label>
                <textarea
                  [id]="'po' + i"
                  rows="3"
                  [value]="p.options"
                  [disabled]="!editable()"
                  (input)="setParam(i, 'options', value($event))"
                ></textarea>
              }
              @if (p.type === 'boolean') {
                <label class="inline">
                  <input
                    type="checkbox"
                    [checked]="p.def === 'true'"
                    [disabled]="!editable()"
                    (change)="setParam(i, 'def', checked($event) ? 'true' : 'false')"
                  />
                  Mặc định là có
                </label>
              } @else {
                <label [for]="'pd' + i">Giá trị mặc định</label>
                <input
                  [id]="'pd' + i"
                  [value]="p.def"
                  [disabled]="!editable()"
                  (input)="setParam(i, 'def', value($event))"
                />
              }
              @if (paramErr()[i]; as e) {
                <p class="err" role="alert">{{ e }}</p>
              }
              @if (editable()) {
                <p-button
                  size="small"
                  severity="secondary"
                  [text]="true"
                  label="Xoá tham số"
                  (onClick)="removeParam(i)"
                />
              }
            </fieldset>
          }
          @if (editable()) {
            <p-button
              size="small"
              severity="secondary"
              [outlined]="true"
              label="Thêm tham số"
              (onClick)="addParam()"
            />
          }
          <details>
            <summary>Xem khai báo sinh ra</summary>
            <pre data-testid="schema">{{ schemaText() }}</pre>
          </details>
        </section>
      }
    </div>
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
    .grid {
      display: grid;
      grid-template-columns: 240px minmax(0, 1fr) 300px;
      gap: 16px;
      align-items: start;
    }
    @media (max-width: 1100px) {
      .grid {
        grid-template-columns: 1fr;
      }
    }
    .lib ul {
      list-style: none;
      margin: 0;
      padding: 0;
      display: grid;
      gap: 6px;
    }
    .lib h2 {
      margin-top: 8px;
    }
    .lib a {
      display: grid;
      gap: 2px;
      padding: 8px 10px;
      border: 1px solid #e5e7eb;
      border-radius: 8px;
      background: #fff;
      color: #111827;
      text-decoration: none;
    }
    .lib a[aria-current='page'] {
      border-color: #1d4ed8;
      box-shadow: inset 3px 0 0 #1d4ed8;
    }
    .lib .meta {
      color: #4b5563;
      font-size: 0.85rem;
    }
    .st {
      font-size: 0.85rem;
      font-weight: 500;
      color: #374151;
    }
    .st[data-status='HIEU_LUC'] {
      color: #166534;
    }
    .st[data-status='CHO_PHE_DUYET'] {
      color: #92400e;
    }
    .bar {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
      align-items: center;
      margin-bottom: 8px;
    }
    .bar a,
    .lib-link {
      color: #1d4ed8;
    }
    .checks {
      margin: 12px 0;
    }
    .checks ul {
      margin: 0;
      padding-left: 20px;
    }
    .bad {
      color: #b4001f;
    }
    .good {
      color: #166534;
    }
    .blocks {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      align-items: center;
      margin: 8px 0;
    }
    .src {
      display: block;
      font-weight: 500;
      margin-top: 8px;
    }
    textarea,
    input,
    select {
      width: 100%;
      box-sizing: border-box;
      font: inherit;
      padding: 6px 8px;
      border: 1px solid #6b7280;
      border-radius: 6px;
    }
    #wf-src {
      font-family: ui-monospace, monospace;
      font-size: 0.85rem;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin: 12px 0;
    }
    .props fieldset {
      border: 1px solid #e5e7eb;
      border-radius: 8px;
      margin: 0 0 10px;
      padding: 8px 10px;
      background: #fff;
      display: grid;
      gap: 4px;
    }
    .props legend {
      font-weight: 500;
    }
    .row {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 8px;
    }
    .inline {
      display: flex;
      gap: 8px;
      align-items: center;
    }
    .inline input {
      width: auto;
    }
    pre {
      white-space: pre-wrap;
      font-size: 0.8rem;
      background: #f8fafc;
      padding: 8px;
      border-radius: 6px;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      background: #fff;
    }
    th,
    td {
      text-align: left;
      padding: 8px;
      border-bottom: 1px solid #e5e7eb;
    }
    .msg {
      padding: 8px 12px;
      border-radius: 6px;
    }
    .msg.err,
    .err {
      color: #b4001f;
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
  `,
})
export class WfTemplatesPage {
  private readonly api = inject(WorkflowTemplateApi);
  private readonly router = inject(Router);
  private readonly access = inject(STUDIO_ACCESS);
  private readonly stepUp = inject(STUDIO_STEP_UP);
  private timer: ReturnType<typeof setTimeout> | null = null;

  protected readonly statusLabel = WF_TEMPLATE_STATUS_LABEL;
  protected readonly rolloutLabel = WF_ROLLOUT_STATUS_LABEL;
  protected readonly paramTypes = PARAM_TYPES;
  protected readonly blocks = WF_BLOCKS;
  protected readonly date = fmtDate;
  protected readonly title = titleOf;
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  /** Lỗi luật do máy chủ trả khi lưu (máy chủ là nơi quyết định). */
  private readonly serverIssues = signal<WfLintIssue[]>([]);

  /** Tham số tuyến `mau-luong/:templateId` (gắn qua `withComponentInputBinding`). */
  readonly templateId = input<string>();
  protected readonly id = computed(() => this.templateId() ?? null);
  protected readonly list = rxResource({ stream: () => this.api.list() });
  protected readonly tpl = rxResource({
    params: () => this.id() ?? undefined,
    stream: ({ params }) => this.api.get(params),
  });
  protected readonly rollouts = rxResource({
    params: () => {
      const t = this.tpl.value();
      return t?.status === 'HIEU_LUC' ? t.id : undefined;
    },
    stream: ({ params }) => this.api.rollouts(params),
  });

  protected readonly xml = linkedSignal(() => this.tpl.value()?.bpmnXml ?? '');
  protected readonly params = linkedSignal<ParamDef[]>(() =>
    paramsFromSchema(this.tpl.value()?.paramSchema),
  );

  protected readonly groups = computed(() => {
    const by = new Map<string, WorkflowTemplateSummary[]>();
    for (const t of this.list.value()?.items ?? [])
      by.set(t.templateCode, [...(by.get(t.templateCode) ?? []), t]);
    return [...by].map(([code, items]) => ({
      code,
      title: titleOf(code),
      items: [...items].sort((a, b) => b.versionNo - a.versionNo),
    }));
  });

  protected readonly editable = computed(() => {
    const s = this.tpl.value()?.status;
    return (s === 'NHAP' || s === 'DAT_KIEM_THU') && this.can('SCR-1505.soan');
  });
  protected readonly issues = computed(() => {
    const local = this.xml() ? lintBpmn(this.xml()) : [];
    return local.length ? local : this.serverIssues();
  });
  protected readonly issueIds = computed(() => this.issues().map((i) => i.elementId));
  protected readonly paramErr = computed(() => paramErrors(this.params()));
  protected readonly schema = computed(() => schemaFromParams(this.params()));
  protected readonly schemaText = computed(() => JSON.stringify(this.schema(), null, 2));
  protected readonly valid = computed(
    () => !this.issues().length && !Object.keys(this.paramErr()).length,
  );
  protected readonly dirty = computed(() => {
    const t = this.tpl.value();
    if (!t) return false;
    return (
      this.xml() !== (t.bpmnXml ?? '') ||
      JSON.stringify(this.schema()) !==
        JSON.stringify(schemaFromParams(paramsFromSchema(t.paramSchema)))
    );
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => this.timer && clearTimeout(this.timer));
  }

  protected can(key: Parameters<typeof this.access.can>[0]) {
    return this.access.can(key);
  }

  protected cell(code: string): string {
    return CELL_LABEL[code] ?? 'Cụm khác';
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected checked(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }

  protected setParam(i: number, k: keyof ParamDef, v: string) {
    this.params.update((ps) => ps.map((p, j) => (j === i ? { ...p, [k]: v } : p)));
  }

  protected addParam() {
    this.params.update((ps) => [...ps, newParam(ps.length + 1)]);
  }

  protected removeParam(i: number) {
    this.params.update((ps) => ps.filter((_, j) => j !== i));
  }

  protected addBlock(b: WfBlock) {
    const next = insertBlock(this.xml(), b);
    if (next) this.xml.set(next);
    else
      this.error.set(
        'Không thêm được khối vào sơ đồ này. Vui lòng kiểm tra sơ đồ có điểm kết thúc.',
      );
  }

  private start() {
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
  }

  private fail(e: unknown, fallback: string) {
    this.busy.set(false);
    const p = toProblem(e);
    if (p.code === 'WORKFLOW-422-LINT') {
      this.serverIssues.set(
        (p.errors ?? []).map((x) => ({
          rule: x.rule as WfLintIssue['rule'],
          elementId: x.field.replace(/^bpmnXml\.?/, ''),
          message: x.message,
        })),
      );
    }
    this.error.set(errorText(e, fallback));
  }

  private refresh(t?: WorkflowTemplate) {
    this.list.reload();
    if (t) this.tpl.set(t);
    else this.tpl.reload();
  }

  /** Đọc lại mẫu cho tới khi bước nền (kiểm thử, phát hành) xong. */
  private poll(
    id: string,
    done: (t: WorkflowTemplate) => boolean,
    then: (t: WorkflowTemplate) => void,
  ) {
    const tick = () =>
      this.api.get(id).subscribe({
        next: (t) => {
          if (!done(t)) {
            this.timer = setTimeout(tick, POLL_MS);
            return;
          }
          this.busy.set(false);
          this.refresh(t);
          then(t);
        },
        error: (e) => this.fail(e, 'Không đọc được trạng thái mẫu.'),
      });
    this.timer = setTimeout(tick, POLL_MS);
  }

  protected save() {
    const t = this.tpl.value();
    if (!t) return;
    this.start();
    this.serverIssues.set([]);
    this.api
      .save(t.id, t.rowVersion, { bpmnXml: this.xml(), paramSchema: this.schema() as never })
      .subscribe({
        next: (x) => {
          this.busy.set(false);
          this.notice.set('Đã lưu bản nháp. Chạy kiểm thử lại trước khi đề nghị phát hành.');
          this.refresh(x);
        },
        error: (e) =>
          this.fail(
            e,
            problemCode(e) === 'WORKFLOW-409-VERSION-CONFLICT'
              ? 'Mẫu đã được người khác cập nhật. Vui lòng tải lại.'
              : 'Không lưu được bản nháp.',
          ),
      });
  }

  protected runTests() {
    const t = this.tpl.value();
    if (!t) return;
    this.start();
    this.notice.set('Đang chạy các kịch bản test trên máy kiểm thử riêng…');
    this.api.runTests(t.id).subscribe({
      next: () =>
        this.poll(
          t.id,
          (x) => x.status === 'DAT_KIEM_THU',
          (x) =>
            this.notice.set(
              `Đạt ${x.testCount ?? 0} kịch bản test với tham số của mọi đơn vị đang dùng mẫu, trong ngân sách 20 câu lệnh truy vấn mỗi lần hoàn thành bước.`,
            ),
        ),
      error: (e) => this.fail(e, 'Không chạy được kiểm thử.'),
    });
  }

  protected submit() {
    const t = this.tpl.value();
    if (!t) return;
    this.start();
    this.api.submit(t.id, t.rowVersion).subscribe({
      next: (x) => {
        this.busy.set(false);
        this.notice.set('Đã tạo đề nghị phát hành trong kho mã. Mẫu chờ người thứ hai phê duyệt.');
        this.refresh(x);
      },
      error: (e) => this.fail(e, 'Không đề nghị phát hành được.'),
    });
  }

  protected async approve() {
    const t = this.tpl.value();
    if (!t) return;
    this.start();
    if (!(await this.stepUp('Phê duyệt phát hành mẫu quy trình'))) {
      this.busy.set(false);
      this.error.set('Chưa xác thực nhiều lớp nên chưa phê duyệt được.');
      return;
    }
    this.api.approve(t.id, t.rowVersion).subscribe({
      next: () =>
        this.poll(
          t.id,
          (x) => x.status === 'HIEU_LUC',
          (x) =>
            this.notice.set(
              `Mẫu ${titleOf(x.templateCode)} bản ${x.versionNo} đã phát hành và đang triển khai lần lượt lên các cụm.`,
            ),
        ),
      error: (e) =>
        this.fail(
          e,
          problemCode(e) === 'WORKFLOW-409-ROLLOUT-WINDOW'
            ? 'Đang trong khung cấm triển khai (giờ cao điểm hoặc ba ngày cuối kỳ). Vui lòng phê duyệt sau.'
            : 'Không phê duyệt được.',
        ),
    });
  }

  protected newVersion() {
    const t = this.tpl.value();
    if (!t) return;
    this.start();
    this.api
      .create({
        templateCode: t.templateCode,
        basedOnTemplateId: t.id,
        bpmnXml: t.bpmnXml ?? '',
        paramSchema: (t.paramSchema ?? { type: 'object' }) as never,
      })
      .subscribe({
        next: (x) => {
          this.busy.set(false);
          this.list.reload();
          void this.router.navigate(['/config-studio', 'mau-luong', x.id]);
        },
        error: (e) =>
          this.fail(
            e,
            problemCode(e) === 'WORKFLOW-409-DRAFT-EXISTS'
              ? 'Mẫu này đang có một bản nháp; hãy mở bản nháp đó.'
              : 'Không tạo được phiên bản mới.',
          ),
      });
  }
}

/** Tên cụm máy chủ (cell) cho người đọc (baseline bảng cell, TL-04 mục 11.2). */
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
