import { Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { toProblem } from '@vt/api-client';
import {
  OVERDUE_ACTION_LABEL,
  PeriodApi,
  PolicyApi,
  PolicyWorkflowTemplateSummary,
  PolicyWorkflowTemplateVersion,
  WorkflowBinding,
  WorkflowLevelParam,
  WorkflowLintResult,
  WorkflowSimulateResult,
  WorkflowStepPreview,
  stepsForLevels,
  workDays,
  workDaysParam,
} from '@vt/api-client/domains';
import { ROLE_LABEL } from '@vt/auth';
import { ButtonModule } from 'primeng/button';
import { STUDIO_ACCESS } from '../../studio-permissions';
import { FLOW_LABEL, errorText, fmtDate } from '../../studio-ui';
import { celSyntaxError } from './cel-check';
import { PolicyWorkspace } from './policy-workspace';
import { VtSkeletonComponent } from '@vt/design-system';

/** Lựa chọn có nhãn trong `paramSchema` (`oneOf: [{ const, title }]` hoặc `enum`). */
interface Choice {
  value: string;
  label: string;
}

/** Tham số luồng theo khai báo mẫu (SCR-1504 trường 3 đến 8). */
interface WfParams {
  so_cap: number;
  cap: WorkflowLevelParam[];
  thoi_han_moi_buoc: string;
  nhac_truoc: string;
  khi_qua_han: string;
  [k: string]: unknown;
}

type Schema = Record<string, unknown>;
const prop = (s: Schema | undefined, k: string) =>
  ((s?.['properties'] as Schema | undefined)?.[k] ?? undefined) as Schema | undefined;

function choices(s: Schema | undefined, labels: Record<string, string> = {}): Choice[] {
  const one = s?.['oneOf'] as { const: string; title?: string }[] | undefined;
  if (one)
    return one.map((o) => ({ value: o.const, label: o.title ?? labels[o.const] ?? o.const }));
  const en = s?.['enum'] as string[] | undefined;
  // ui-text-ok: tham số, giá trị và lựa chọn do người soạn chính sách tự đặt, không có bảng nhãn cố định
  return (en ?? []).map((v) => ({ value: v, label: labels[v] ?? v }));
}

/** Tham số mặc định sinh từ khai báo mẫu và các bước xem trước. */
function defaults(t: PolicyWorkflowTemplateVersion): WfParams {
  const p = t.paramSchema as Schema;
  const def = (k: string, fb: unknown) => prop(p, k)?.['default'] ?? fb;
  const overdue = choices(prop(p, 'khi_qua_han'));
  return {
    so_cap: Number(def('so_cap', (t.previewSteps ?? []).length)),
    cap: (t.previewSteps ?? []).map((s) => ({
      buoc: s.stepKey,
      vai_tro: s.role,
      ky_so: !!s.signRequired,
      ...(s.batchSign ? { ky_lo: true } : {}),
    })),
    thoi_han_moi_buoc: String(def('thoi_han_moi_buoc', 'P3D_LV')),
    nhac_truoc: String(def('nhac_truoc', 'P1D_LV')),
    khi_qua_han: String(def('khi_qua_han', overdue[0]?.value ?? '')),
  };
}

/** Ghép tham số đã lưu với các bước của mẫu (bước thiếu lấy mặc định). */
function merge(t: PolicyWorkflowTemplateVersion, saved: Record<string, unknown> | null): WfParams {
  const d = defaults(t);
  if (!saved) return d;
  const cap = (saved['cap'] ?? []) as WorkflowLevelParam[];
  return {
    ...d,
    ...(saved as Partial<WfParams>),
    cap: d.cap.map((c) => ({ ...c, ...(cap.find((x) => x.buoc === c.buoc) ?? {}) })),
  };
}

/**
 * Config Studio · Luồng duyệt (SCR-1504, QT-15, ADR-04). Đối chiếu đặc tả TL-02b:
 * - Trường: 1 mẫu luồng (chỉ mẫu đã phát hành); 2 phạm vi áp dụng (khối, kỳ bắt đầu chưa khoá);
 *   3 số cấp duyệt (theo khai báo mẫu, 2 đến 4); 4 vai trò, đơn vị từng cấp; 5 thời hạn và nhắc
 *   (1 đến 15 ngày làm việc, mặc định 3 và 1); 6 hành vi khi quá hạn; 7 bước ký số (không bỏ được
 *   bước ký bắt buộc của mẫu); 8 điều kiện bỏ qua cấp bằng CEL (chỉ cấp mẫu cho phép bỏ qua).
 * - Chức năng: Chọn mẫu (tải khai báo tham số); Mô phỏng luồng; Lưu tham số khi đủ và linter đạt.
 * - Quy tắc: BR-15-11 không vẽ BPMN tự do; BR-15-12 linter. Thông báo MSG-15-09, MSG-15-10.
 * Trình duyệt chỉ kiểm sơ bộ; máy chủ chạy linter, test của mẫu và trả 422 `POLICY-422-WF-PARAMS`.
 */
@Component({
  selector: 'vt-workflow-page',
  imports: [VtSkeletonComponent, FormsModule, ButtonModule],
  template: `
    <div class="top">
      <p class="sub">
        Luồng duyệt được tạo từ mẫu đã kiểm thử: chọn mẫu, điền tham số, mô phỏng rồi lưu. Tham số
        được phát hành cùng phiên bản chính sách.
      </p>
      @if (canEdit()) {
        <div class="toolbar">
          <p-button
            label="Mô phỏng luồng"
            severity="contrast"
            [outlined]="true"
            data-testid="simulate"
            [loading]="simulating()"
            [disabled]="!complete()"
            (onClick)="simulate()"
          />
          <p-button
            label="Lưu tham số"
            data-testid="save-binding"
            [loading]="saving()"
            [disabled]="!canSave()"
            (onClick)="save()"
          />
        </div>
      }
    </div>
    @if (hint(); as h) {
      <p class="hint" data-testid="wf-hint">{{ h }}</p>
    }
    @if (notice(); as n) {
      <p class="ok" role="status" data-testid="notice">{{ n }}</p>
    }
    @if (error(); as e) {
      <p class="err" role="alert" data-testid="msg-15-09">{{ e }}</p>
    }

    <div class="grid">
      <section class="card" aria-labelledby="h-tpl">
        <h2 id="h-tpl">Chọn mẫu luồng</h2>
        @if (templates.error()) {
          <p class="err" role="alert">Không tải được danh mục mẫu luồng.</p>
        }
        <div class="tpls" role="radiogroup" aria-labelledby="h-tpl">
          @for (t of templates.value()?.items ?? []; track t.key) {
            <button
              type="button"
              role="radio"
              class="tpl"
              [attr.aria-checked]="selectedKey() === t.key"
              [attr.data-template]="t.key"
              [disabled]="!canEdit() && selectedKey() !== t.key"
              (click)="choose(t)"
            >
              <strong>{{ t.name ?? 'Mẫu luồng' }}</strong>
              <span class="meta">
                Phiên bản {{ t.versionNo }}
                @if (t.testCount) {
                  · {{ t.testCount }} kiểm thử
                }
                @if (bindingOf(t.processKind); as b) {
                  · đang dùng
                }
              </span>
            </button>
          }
        </div>
        <p class="note" data-testid="msg-15-10">
          Luồng duyệt chỉ được cấu hình bằng mẫu và tham số. Nếu cần quy trình mới, vui lòng gửi yêu
          cầu bổ sung mẫu cho đội sản phẩm.
        </p>
      </section>

      <div class="main">
        @if (tpl.error()) {
          <p class="err" role="alert">Không tải được khai báo tham số của mẫu.</p>
        }
        @if (tpl.value(); as t) {
          <section class="card" aria-labelledby="h-par">
            <h2 id="h-par">
              Tham số áp dụng cho {{ flowLabel() }}
              @if (periodName(); as pn) {
                · từ {{ pn }}
              }
            </h2>
            <fieldset class="f-grid" [disabled]="!canEdit()">
              <legend class="sr-only">Tham số luồng duyệt</legend>
              @if (periodsRes.isLoading()) {
                <vt-skeleton variant="form" [rows]="1" label="Đang tải kỳ đánh giá" />
              } @else {
                <label class="f">
                  <span>Kỳ bắt đầu áp dụng</span>
                  <select
                    class="native"
                    name="period"
                    data-testid="wf-period"
                    [ngModel]="periodId()"
                    (ngModelChange)="periodId.set($event)"
                  >
                    <option value="">Chọn kỳ đánh giá</option>
                    @for (p of periods(); track p.id) {
                      <option [value]="p.id">{{ p.name }} (từ {{ date(p.startDate) }})</option>
                    }
                  </select>
                </label>
              }
              <label class="f">
                <span>Số cấp duyệt</span>
                <select
                  class="native"
                  name="levels"
                  data-testid="wf-levels"
                  [ngModel]="draft().so_cap"
                  (ngModelChange)="patch({ so_cap: +$event })"
                >
                  @for (n of levelOptions(); track n) {
                    <option [ngValue]="n">{{ n }} cấp</option>
                  }
                </select>
              </label>
              <label class="f">
                <span>Khi quá hạn</span>
                <select
                  class="native"
                  name="overdue"
                  data-testid="wf-overdue"
                  [ngModel]="draft().khi_qua_han"
                  (ngModelChange)="patch({ khi_qua_han: $event })"
                >
                  @for (o of overdueOptions(); track o.value) {
                    <option [value]="o.value">{{ o.label }}</option>
                  }
                </select>
              </label>
              <label class="f">
                <span>Thời hạn mỗi bước (ngày làm việc)</span>
                <input
                  class="native"
                  type="number"
                  name="due"
                  min="1"
                  max="15"
                  data-testid="wf-due"
                  [attr.aria-invalid]="!!durationError()"
                  [ngModel]="due()"
                  (ngModelChange)="setDays('thoi_han_moi_buoc', $event)"
                />
              </label>
              <label class="f">
                <span>Nhắc trước hạn (ngày làm việc)</span>
                <input
                  class="native"
                  type="number"
                  name="remind"
                  min="1"
                  max="15"
                  data-testid="wf-remind"
                  [attr.aria-invalid]="!!durationError()"
                  [ngModel]="remind()"
                  (ngModelChange)="setDays('nhac_truoc', $event)"
                />
              </label>
            </fieldset>
            @if (durationError(); as e) {
              <p class="field-err" role="alert">{{ e }}</p>
            }

            <h3>Người xử lý và ký số từng cấp</h3>
            <div class="scroll">
              <table data-testid="wf-levels-table">
                <thead>
                  <tr>
                    <th scope="col">Cấp</th>
                    <th scope="col">Người xử lý</th>
                    <th scope="col">Đơn vị</th>
                    <th scope="col">Ký số</th>
                  </tr>
                </thead>
                <tbody>
                  @for (s of activeSteps(); track s.stepKey; let i = $index) {
                    <tr [attr.data-step]="s.stepKey">
                      <th scope="row">{{ i + 1 }}. {{ s.name }}</th>
                      <td>
                        <select
                          class="native"
                          [attr.aria-label]="'Người xử lý bước ' + s.name"
                          [disabled]="!canEdit()"
                          [ngModel]="level(s.stepKey)?.vai_tro ?? ''"
                          (ngModelChange)="patchLevel(s.stepKey, { vai_tro: $event })"
                        >
                          <option value="">Chọn</option>
                          @for (o of roleOptions(); track o.value) {
                            <option [value]="o.value">{{ o.label }}</option>
                          }
                        </select>
                      </td>
                      <td>
                        <select
                          class="native"
                          [attr.aria-label]="'Đơn vị xử lý bước ' + s.name"
                          [disabled]="!canEdit()"
                          [ngModel]="level(s.stepKey)?.don_vi ?? ''"
                          (ngModelChange)="patchLevel(s.stepKey, { don_vi: $event || undefined })"
                        >
                          <option value="">Theo người được đánh giá</option>
                          @for (o of unitOptions(); track o.value) {
                            <option [value]="o.value">{{ o.label }}</option>
                          }
                        </select>
                      </td>
                      <td class="checks">
                        <label>
                          <input
                            type="checkbox"
                            [disabled]="!canEdit() || !!s.signRequired"
                            [ngModel]="!!level(s.stepKey)?.ky_so"
                            (ngModelChange)="patchLevel(s.stepKey, { ky_so: $event })"
                          />
                          Ký số
                          @if (s.signRequired) {
                            <small class="muted">(bắt buộc)</small>
                          }
                        </label>
                        @if (s.batchSign) {
                          <label>
                            <input
                              type="checkbox"
                              [disabled]="!canEdit()"
                              [ngModel]="!!level(s.stepKey)?.ky_lo"
                              (ngModelChange)="patchLevel(s.stepKey, { ky_lo: $event })"
                            />
                            Ký theo lô
                          </label>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>

            @for (s of skippableSteps(); track s.stepKey) {
              <label class="f cond">
                <span>Điều kiện bỏ qua bước "{{ s.name }}" (để trống nếu không bỏ qua)</span>
                <textarea
                  class="code"
                  rows="2"
                  spellcheck="false"
                  [attr.data-testid]="'skip-' + s.stepKey"
                  [attr.aria-invalid]="!!skipError(s.stepKey)"
                  [disabled]="!canEdit()"
                  [ngModel]="level(s.stepKey)?.bo_qua_khi ?? ''"
                  (ngModelChange)="patchLevel(s.stepKey, { bo_qua_khi: $event || undefined })"
                ></textarea>
                @if (skipError(s.stepKey); as e) {
                  <small class="field-err">{{ e }}</small>
                }
              </label>
            }
          </section>

          <section class="card" aria-labelledby="h-prev">
            <h2 id="h-prev">Xem trước luồng</h2>
            <ol class="flow" data-testid="wf-preview">
              @for (s of preview(); track s.stepKey) {
                <li [attr.data-step]="s.stepKey">
                  <strong>{{ s.name }}</strong>
                  <span>{{ roleText(s.role) }}{{ stepTraits(s) }}</span>
                </li>
              }
              <li class="end">
                <strong>Khoá hồ sơ</strong>
                <span>Tự động khi bước cuối hoàn tất</span>
              </li>
            </ol>
            @if (sim(); as r) {
              <ul class="chips" data-testid="wf-lint">
                @if (r.lint.passed) {
                  <li>Kiểm tra cấu trúc luồng: đạt</li>
                  <li>Tối đa 1 tầng quy trình con</li>
                  <li>Không gọi đồng bộ ra hệ thống ngoài</li>
                } @else {
                  <li class="bad">Kiểm tra cấu trúc luồng: chưa đạt</li>
                }
                @if (r.templateTests; as tt) {
                  <li [class.bad]="tt.passed < tt.total">
                    {{ tt.passed }}/{{ tt.total }} kiểm thử của mẫu đạt với tham số này
                  </li>
                }
              </ul>
              @if (r.sampleRuns?.length) {
                <h3>Chạy thử với hồ sơ mẫu</h3>
                <ul class="samples">
                  @for (x of r.sampleRuns; track x.sample) {
                    <li>
                      {{ x.sample }}:
                      {{
                        x.skippedSteps?.length
                          ? 'bỏ qua ' + stepNames(x.skippedSteps!)
                          : 'đi đủ các bước'
                      }}
                      , kết thúc ở bước khoá hồ sơ.
                    </li>
                  }
                </ul>
              }
              @if (stale()) {
                <p class="hint left">
                  Tham số đã thay đổi sau lần mô phỏng. Mô phỏng lại trước khi lưu.
                </p>
              }
            } @else if (canEdit()) {
              <p class="muted">Bấm "Mô phỏng luồng" để kiểm tra tham số trước khi lưu.</p>
            }
          </section>
        } @else if (tpl.isLoading() || templates.isLoading() || bindings.isLoading()) {
          <vt-skeleton variant="form" [rows]="4" label="Đang tải khai báo tham số" />
        }
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .top {
      display: flex;
      flex-wrap: wrap;
      gap: 8px 16px;
      justify-content: space-between;
      align-items: flex-start;
      margin-bottom: 8px;
    }
    .sub {
      color: #4b5563;
      margin: 0;
      flex: 1 1 360px;
    }
    .toolbar {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .hint {
      text-align: right;
      color: #4b5563;
      margin: 0 0 12px;
    }
    .hint.left {
      text-align: left;
    }
    .ok,
    .err,
    .note {
      padding: 10px 12px;
      border-radius: 6px;
      margin: 0 0 12px;
    }
    .ok {
      background: #dcfce7;
      color: #14532d;
    }
    .err {
      background: #fee2e2;
      color: #7f1d1d;
    }
    .note {
      background: #eff6ff;
      border-left: 4px solid #1d4ed8;
      color: #1e3a8a;
      margin-top: 12px;
    }
    .grid {
      display: grid;
      grid-template-columns: minmax(260px, 1fr) minmax(0, 2.4fr);
      gap: 16px;
      align-items: start;
    }
    @media (max-width: 1000px) {
      .grid {
        grid-template-columns: minmax(0, 1fr);
      }
    }
    .card {
      border: 1px solid #e5e7eb;
      border-radius: 8px;
      padding: 16px;
      background: #fff;
      margin-bottom: 16px;
      min-width: 0;
    }
    h2 {
      font-size: 1.0625rem;
      margin: 0 0 12px;
    }
    h3 {
      font-size: 1rem;
      margin: 16px 0 8px;
    }
    .tpls {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .tpl {
      text-align: left;
      border: 1px solid #cbd5e1;
      border-radius: 8px;
      background: #fff;
      padding: 10px 12px;
      font: inherit;
      cursor: pointer;
      display: flex;
      flex-direction: column;
      gap: 2px;
      color: #111827;
      min-height: 44px;
    }
    .tpl[aria-checked='true'] {
      border: 2px solid #b4001f;
      background: #fff5f6;
    }
    .tpl:disabled {
      cursor: default;
      color: #6b7280;
    }
    .meta {
      color: #4b5563;
      font-size: 0.875rem;
    }
    .f-grid {
      border: 0;
      padding: 0;
      margin: 0;
      min-width: 0;
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 12px;
    }
    .f {
      display: flex;
      flex-direction: column;
      gap: 4px;
      min-width: 0;
    }
    .f > span {
      font-weight: 500;
    }
    .cond {
      margin-top: 12px;
    }
    .native {
      padding: 8px 10px;
      border: 1px solid #cbd5e1;
      border-radius: 6px;
      font: inherit;
      min-height: 40px;
      background: #fff;
      width: 100%;
      box-sizing: border-box;
    }
    .code {
      font-family: ui-monospace, Menlo, Consolas, monospace;
      background: #0f172a;
      color: #e2e8f0;
      border-radius: 6px;
      padding: 10px 12px;
      border: 1px solid #0f172a;
    }
    .code:disabled {
      opacity: 0.85;
    }
    .field-err {
      color: #b4001f;
    }
    .scroll {
      overflow-x: auto;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      min-width: 620px;
    }
    th,
    td {
      text-align: left;
      vertical-align: middle;
      padding: 8px;
      border-bottom: 1px solid #e5e7eb;
    }
    thead th {
      background: #f8fafc;
      font-weight: 500;
    }
    tbody th {
      font-weight: 500;
      white-space: nowrap;
    }
    .checks label {
      display: flex;
      gap: 6px;
      align-items: center;
      min-height: 32px;
    }
    .checks input {
      width: 18px;
      height: 18px;
    }
    .flow {
      list-style: none;
      display: flex;
      flex-wrap: wrap;
      gap: 8px 28px;
      padding: 0;
      margin: 0 0 12px;
    }
    .flow li {
      position: relative;
      border: 1px solid #b4001f;
      background: #fff5f6;
      border-radius: 8px;
      padding: 8px 12px;
      display: flex;
      flex-direction: column;
      align-items: center;
      text-align: center;
      min-width: 120px;
    }
    .flow li:not(:last-child)::after {
      content: '→';
      position: absolute;
      right: -21px;
      top: 50%;
      transform: translateY(-50%);
      color: #4b5563;
    }
    .flow li span {
      color: #4b5563;
      font-size: 0.875rem;
    }
    .flow li.end {
      border-style: dashed;
    }
    .chips {
      list-style: none;
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      padding: 0;
      margin: 0;
    }
    .chips li {
      background: #dcfce7;
      color: #14532d;
      border-radius: 999px;
      padding: 4px 12px;
      font-weight: 500;
    }
    .chips li.bad {
      background: #fee2e2;
      color: #7f1d1d;
    }
    .samples {
      margin: 0;
      padding-left: 20px;
    }
    .muted {
      color: #4b5563;
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
export class WorkflowPage {
  private readonly api = inject(PolicyApi);
  private readonly periodApi = inject(PeriodApi);
  protected readonly ws = inject(PolicyWorkspace);
  private readonly access = inject(STUDIO_ACCESS);
  protected readonly date = fmtDate;

  protected readonly canEdit = computed(
    () => this.ws.editable() && this.access.author('SCR-1504.cau-hinh'),
  );
  protected readonly hint = computed(() =>
    this.ws.status() && !this.canEdit()
      ? this.access.author('SCR-1504.cau-hinh')
        ? 'Phiên bản đã khoá nội dung, chỉ xem tham số luồng duyệt.'
        : 'Đồng chí được xem tham số luồng duyệt, không chỉnh sửa.'
      : null,
  );

  protected readonly templates = rxResource({
    stream: () => this.api.workflowTemplates(),
  });
  protected readonly bindings = rxResource({
    params: () => this.ws.ref() ?? undefined,
    stream: ({ params }) => this.api.workflowBindings(params),
  });

  /** Mẫu đang chọn: mẫu của tham số đã lưu, không có thì mẫu đầu danh mục. */
  protected readonly selected = linkedSignal<
    { list?: PolicyWorkflowTemplateSummary[]; b?: WorkflowBinding[] },
    { key: string; versionNo: number } | null
  >({
    source: () => ({ list: this.templates.value()?.items, b: this.bindings.value() }),
    computation: ({ list, b }, prev) => {
      if (prev?.value) return prev.value;
      if (!list?.length || !b) return null;
      const bound = list.find((t) => b.some((x) => x.templateKey === t.key));
      const t = bound ?? list[0];
      return { key: t.key, versionNo: t.versionNo };
    },
  });
  protected readonly selectedKey = computed(() => this.selected()?.key ?? null);

  protected readonly tpl = rxResource({
    params: () => this.selected() ?? undefined,
    stream: ({ params }) => this.api.workflowTemplate(params.key, params.versionNo),
  });

  /** Tham số đã lưu cho loại quy trình của mẫu đang chọn. */
  private readonly binding = computed(() => {
    const t = this.tpl.value();
    return t ? (this.bindingOf(t.processKind) ?? null) : null;
  });

  protected readonly draft = linkedSignal<
    { t?: PolicyWorkflowTemplateVersion; b: WorkflowBinding | null },
    WfParams
  >({
    source: () => ({ t: this.tpl.value(), b: this.binding() }),
    computation: ({ t, b }) =>
      t
        ? merge(t, b && b.templateKey === t.key ? (b.params as Record<string, unknown>) : null)
        : ({
            so_cap: 0,
            cap: [],
            thoi_han_moi_buoc: '',
            nhac_truoc: '',
            khi_qua_han: '',
          } as WfParams),
  });

  protected readonly periodsRes = rxResource({
    stream: () => this.periodApi.list({ status: 'MO' }),
  });
  /** Kỳ chưa bắt đầu, cùng luồng với gói (kỳ bắt đầu phải chưa khoá). */
  protected readonly periods = computed(() => {
    const today = new Date().toISOString().slice(0, 10);
    const flow = this.ws.pack.value()?.flow;
    const keep = this.binding()?.scope.fromPeriodId;
    return (this.periodsRes.value() ?? []).filter(
      (p) =>
        p.id === keep || (p.startDate > today && (!flow || p.flow === flow || p.flow === 'CA_HAI')),
    );
  });
  protected readonly periodId = linkedSignal(() => this.binding()?.scope.fromPeriodId ?? '');
  protected readonly periodName = computed(
    () => this.periods().find((p) => p.id === this.periodId())?.name ?? null,
  );
  protected readonly flowLabel = computed(() => {
    const f = this.ws.pack.value()?.flow;
    return f ? (FLOW_LABEL[f] ?? 'đơn vị') : 'đơn vị';
  });

  private readonly schema = computed(() => this.tpl.value()?.paramSchema as Schema | undefined);
  private readonly levelSchema = computed(
    () => (prop(this.schema(), 'cap')?.['items'] as Schema | undefined) ?? undefined,
  );
  protected readonly levelOptions = computed(() => {
    const s = prop(this.schema(), 'so_cap');
    const steps = this.tpl.value()?.previewSteps?.length ?? 0;
    const min = Number(s?.['minimum'] ?? steps);
    const max = Number(s?.['maximum'] ?? steps);
    return Array.from({ length: Math.max(0, max - min + 1) }, (_, i) => min + i);
  });
  protected readonly overdueOptions = computed(() =>
    choices(prop(this.schema(), 'khi_qua_han'), OVERDUE_ACTION_LABEL),
  );
  protected readonly roleOptions = computed(() =>
    choices(prop(this.levelSchema(), 'vai_tro'), ROLE_LABEL),
  );
  protected readonly unitOptions = computed(() => choices(prop(this.levelSchema(), 'don_vi')));

  protected readonly activeSteps = computed<WorkflowStepPreview[]>(() =>
    stepsForLevels(this.tpl.value()?.previewSteps ?? [], this.draft().so_cap),
  );
  protected readonly skippableSteps = computed(() => this.activeSteps().filter((s) => s.skippable));

  protected readonly due = computed(() => workDays(this.draft().thoi_han_moi_buoc));
  protected readonly remind = computed(() => workDays(this.draft().nhac_truoc));
  protected readonly durationError = computed(() => {
    const d = this.due();
    const r = this.remind();
    if (d === null || d < 1 || d > 15) return 'Thời hạn mỗi bước phải từ 1 đến 15 ngày làm việc.';
    if (r === null || r < 1 || r > 15) return 'Thời điểm nhắc phải từ 1 đến 15 ngày làm việc.';
    if (r >= d) return 'Thời điểm nhắc phải sớm hơn thời hạn của bước.';
    return null;
  });

  /** Tham số gửi máy chủ: chỉ các cấp đang dùng. */
  private readonly params = computed<WfParams>(() => {
    const d = this.draft();
    const keys = new Set(this.activeSteps().map((s) => s.stepKey));
    return { ...d, cap: d.cap.filter((c) => keys.has(c.buoc)) };
  });

  protected readonly complete = computed(
    () =>
      !!this.tpl.value() &&
      !!this.periodId() &&
      !this.durationError() &&
      this.activeSteps().every((s) => !!this.level(s.stepKey)?.vai_tro) &&
      this.skippableSteps().every((s) => !this.skipError(s.stepKey)),
  );

  protected readonly simulating = signal(false);
  protected readonly saving = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  private readonly simResult = signal<{
    tpl: string;
    key: string;
    result: WorkflowSimulateResult;
  } | null>(null);
  private readonly fingerprint = computed(() =>
    JSON.stringify([this.selectedKey(), this.periodId(), this.params()]),
  );
  /** Kết quả mô phỏng gần nhất của mẫu đang chọn. */
  protected readonly sim = computed(() => {
    const s = this.simResult();
    return s && s.tpl === this.selectedKey() ? s.result : null;
  });
  protected readonly stale = computed(() => {
    const s = this.simResult();
    return !!s && s.key !== this.fingerprint();
  });
  protected readonly canSave = computed(
    () =>
      this.canEdit() && this.complete() && !this.stale() && !!this.simResult()?.result.lint.passed,
  );

  /** Các bước xem trước: theo mô phỏng nếu còn mới, không thì theo tham số đang soạn. */
  protected readonly preview = computed<WorkflowStepPreview[]>(() => {
    const s = this.sim();
    if (s && !this.stale()) return s.steps;
    return this.activeSteps().map((st) => {
      const p = this.level(st.stepKey);
      return {
        ...st,
        role: p?.vai_tro ?? st.role,
        signRequired: !!p?.ky_so,
        batchSign: !!p?.ky_lo,
        skippable: !!p?.bo_qua_khi?.trim(),
      };
    });
  });

  bindingOf(processKind: string | undefined) {
    return (this.bindings.value() ?? []).find((b) => b.processKind === processKind);
  }

  protected level(stepKey: string) {
    return this.draft().cap.find((c) => c.buoc === stepKey);
  }

  protected skipError(stepKey: string) {
    const cond = this.level(stepKey)?.bo_qua_khi?.trim();
    return cond ? celSyntaxError(cond) : null;
  }

  protected choose(t: PolicyWorkflowTemplateSummary) {
    if (this.selectedKey() === t.key) return;
    this.notice.set(null);
    this.error.set(null);
    this.selected.set({ key: t.key, versionNo: t.versionNo });
  }

  protected patch(p: Partial<WfParams>) {
    this.draft.update((d) => ({ ...d, ...p }));
  }

  protected patchLevel(stepKey: string, p: Partial<WorkflowLevelParam>) {
    this.draft.update((d) => ({
      ...d,
      cap: d.cap.map((c) => (c.buoc === stepKey ? { ...c, ...p } : c)),
    }));
  }

  protected setDays(key: 'thoi_han_moi_buoc' | 'nhac_truoc', v: unknown) {
    const n = Number(v);
    this.patch({ [key]: Number.isInteger(n) && n > 0 ? workDaysParam(n) : '' });
  }

  protected roleText(role: string | undefined) {
    return role
      ? (this.roleOptions().find((o) => o.value === role)?.label ?? ROLE_LABEL[role] ?? role)
      : '';
  }

  protected stepTraits(s: WorkflowStepPreview) {
    const t = [
      s.signRequired ? 'ký số' : '',
      s.batchSign ? 'ký lô' : '',
      s.skippable ? 'có thể bỏ qua' : '',
    ].filter(Boolean);
    return t.length ? ` · ${t.join(', ')}` : '';
  }

  protected stepNames(keys: string[]) {
    const steps = this.tpl.value()?.previewSteps ?? [];
    return keys.map((k) => steps.find((s) => s.stepKey === k)?.name.toLowerCase() ?? k).join(', ');
  }

  private lintText(lint: WorkflowLintResult) {
    const name = this.tpl.value()?.name ?? 'đã chọn';
    return `Tham số chưa hợp lệ với mẫu ${name}: ${lint.issues.map((i) => i.message).join('; ')}.`;
  }

  protected simulate() {
    const t = this.tpl.value();
    if (!t) return;
    const key = this.fingerprint();
    this.simulating.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.api
      .simulate({ templateKey: t.key, templateVersionNo: t.versionNo, params: this.params() })
      .subscribe({
        next: (result) => {
          this.simulating.set(false);
          this.simResult.set({ tpl: t.key, key, result });
          if (!result.lint.passed) this.error.set(this.lintText(result.lint));
        },
        error: (e) => {
          this.simulating.set(false);
          this.error.set(errorText(e, 'Không mô phỏng được luồng.'));
        },
      });
  }

  protected save() {
    const t = this.tpl.value();
    const ref = this.ws.ref();
    const period = this.periods().find((p) => p.id === this.periodId());
    if (!t || !ref || !t.processKind) return;
    const cur = this.binding();
    const before = this.ws.status();
    this.saving.set(true);
    this.error.set(null);
    this.api
      .saveWorkflowBinding(ref, t.processKind, cur?.rowVersion ?? null, {
        templateKey: t.key,
        templateVersionNo: t.versionNo,
        scope: {
          ...(this.ws.pack.value()?.flow ? { flow: this.ws.pack.value()!.flow } : {}),
          fromPeriodId: this.periodId(),
          ...(period ? { periodType: period.kind } : {}),
          subjectGroupId: cur?.scope.subjectGroupId ?? null,
        },
        params: this.params(),
      })
      .subscribe({
        next: (b) => {
          this.saving.set(false);
          this.bindings.update((list) => [
            ...(list ?? []).filter((x) => x.processKind !== b.processKind),
            b,
          ]);
          this.notice.set(
            before === 'DAT_KIEM_THU'
              ? 'Đã lưu tham số luồng duyệt. Phiên bản trở về bản nháp, cần chạy lại kiểm thử trước khi gửi phê duyệt.'
              : 'Đã lưu tham số luồng duyệt. Tham số được phát hành cùng phiên bản chính sách.',
          );
          this.ws.version.reload();
        },
        error: (e) => {
          this.saving.set(false);
          const p = toProblem(e) as { status?: number; lint?: WorkflowLintResult; detail?: string };
          this.error.set(
            p.status === 422 && p.lint && !p.detail
              ? this.lintText(p.lint)
              : errorText(e, 'Không lưu được tham số.'),
          );
        },
      });
  }
}
