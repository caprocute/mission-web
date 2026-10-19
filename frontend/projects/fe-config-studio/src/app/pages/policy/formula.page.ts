import { Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import {
  DAY_KIND_LABEL,
  POLICY_VERSION_STATUS_LABEL,
  PolicyApi,
  PolicyRounding,
  PolicyRule,
  PolicyRuleSaved,
  QUALITY_METHOD_LABEL,
  RATING_LEVEL_LABEL,
  RETURN_REASON_LABEL,
  ROUNDING_MODE_LABEL,
  RatingLevel,
  fieldErrors,
  policyVersionLabel,
  toProblem,
} from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { concatMap, from, tap, toArray } from 'rxjs';
import { STUDIO_ACCESS } from '../../studio-permissions';
import { errorText, fmtNum } from '../../studio-ui';
import { celSyntaxError, unknownVariables } from './cel-check';
import { PolicyDiffDialog, PolicyExpressionsDialog, PolicySubmitDialog } from './policy-dialogs';
import { PolicyWorkspace } from './policy-workspace';

/** Ba khối của điểm KPI nhiệm vụ (PL 3): số lượng, chất lượng, tiến độ. */
const BLOCKS = ['A', 'B', 'C'] as const;
const GROUP_TOTAL = 100;

interface PreviewGroup {
  key: string;
  kpi_nhom: number;
  diem_toi_da: number;
  diem: number;
}

/**
 * Config Studio · Công thức (SCR-1501, QT-15): khối KPI nhiệm vụ = (A + B + C)/3 với ba biểu thức
 * sửa được, tham số (BR-15-02, BR-15-03), tổng hợp điểm theo nhóm có kiểm tổng tức thời (MSG-15-02),
 * xem trước với ca test vàng, so sánh với bản đang áp dụng, lưu nháp, chạy kiểm thử và gửi phê
 * duyệt phát hành. Trình duyệt chỉ kiểm cú pháp và biến chưa khai báo để báo sớm; máy chủ kiểm kiểu
 * bằng cel-java và trả 422 (MSG-15-01). Sửa bản "Đạt kiểm thử" đưa về "Bản nháp" (MSG-15-03).
 */
@Component({
  selector: 'vt-formula-page',
  imports: [
    FormsModule,
    ButtonModule,
    PolicyDiffDialog,
    PolicyExpressionsDialog,
    PolicySubmitDialog,
  ],
  template: `
    <div class="toolbar">
      @if (ws.published(); as p) {
        <p-button
          [label]="'So sánh với bản ' + vlabel(p.version)"
          severity="contrast"
          [outlined]="true"
          (onClick)="showDiff.set(true)"
        />
      }
      <p-button
        label="Xem toàn bộ biểu thức"
        severity="contrast"
        [outlined]="true"
        (onClick)="showAll.set(true)"
      />
      @if (canEdit()) {
        <p-button
          label="Lưu nháp"
          severity="contrast"
          [outlined]="true"
          data-testid="save"
          [loading]="saving()"
          [disabled]="!dirty().length || blocked()"
          (onClick)="save()"
        />
        <p-button
          label="Chạy kiểm thử"
          severity="contrast"
          [outlined]="true"
          data-testid="run-tests"
          [loading]="ws.running()"
          [disabled]="dirty().length > 0"
          (onClick)="ws.runTests()"
        />
      }
      @if (canSubmit()) {
        <p-button
          label="Gửi phê duyệt phát hành"
          data-testid="submit"
          [disabled]="ws.status() !== 'DAT_KIEM_THU' || dirty().length > 0"
          (onClick)="showSubmit.set(true)"
        />
      }
    </div>
    @if (canEdit() && dirty().length) {
      <p class="hint">Có thay đổi chưa lưu. Lưu nháp trước khi chạy kiểm thử.</p>
    } @else if (canSubmit() && ws.status() === 'NHAP') {
      <p class="hint">Chạy kiểm thử và đạt toàn bộ test case trước khi gửi phê duyệt.</p>
    } @else if (!canEdit() && ws.status(); as s) {
      <p class="hint" data-testid="readonly">
        @if (!access.author('SCR-1501.soan')) {
          Đồng chí được xem công thức, không chỉnh sửa.
        } @else {
          Phiên bản ở trạng thái "{{ statusLabel[s] }}", chỉ xem.
        }
      </p>
    }
    @if (notice(); as n) {
      <p class="note" role="status">{{ n }}</p>
    }
    @if (saveError(); as e) {
      <p class="err" role="alert">{{ e }}</p>
    }
    @if (rules.error()) {
      <p class="err" role="alert">Không tải được công thức. Vui lòng thử lại.</p>
    }

    @if (rule('kpi_nhiem_vu'); as kpi) {
      <section class="card" aria-labelledby="h-kpi">
        <div class="card-head">
          <h2 id="h-kpi">Công thức điểm KPI của nhiệm vụ</h2>
          @if (kpi.legalRef) {
            <span class="tag">{{ kpi.legalRef }}</span>
          }
        </div>
        <p class="strip" data-testid="kpi-strip">
          <span class="tok kpi">Điểm KPI</span>
          <span class="op">=</span>
          @for (t of tokens(kpi.expression); track $index) {
            @if (t.key) {
              <button
                type="button"
                class="tok"
                [class.on]="selected() === t.key"
                [attr.data-block]="t.key"
                (click)="select(t.key)"
              >
                {{ ws.label(t.key) }}
              </button>
            } @else {
              <span class="op">{{ t.text }}</span>
            }
          }
        </p>
        <div class="blocks">
          @for (k of blocks; track k) {
            @if (rule(k); as r) {
              <div
                class="block"
                [class.on]="selected() === k"
                [class.bad]="!!problems()[k]"
                [attr.data-block-card]="k"
              >
                <label class="block-title" [for]="'expr-' + k">{{ ws.label(k) }}</label>
                <textarea
                  class="code"
                  rows="3"
                  spellcheck="false"
                  [id]="'expr-' + k"
                  [readOnly]="!canEdit()"
                  [attr.aria-invalid]="!!problems()[k]"
                  [attr.aria-describedby]="'note-' + k"
                  [ngModel]="r.expression"
                  (ngModelChange)="setExpr(k, $event)"
                  (focus)="selected.set(k)"
                ></textarea>
                <p class="small" [id]="'note-' + k">
                  @if (problems()[k]; as p) {
                    <span class="err">{{ p }}</span>
                  } @else if (warnings()[k]; as w) {
                    <span class="warn">{{ w }}</span>
                  } @else {
                    {{ ws.notes()[k] }}
                  }
                </p>
              </div>
            }
          }
        </div>
      </section>

      <div class="grid">
        <section class="card" aria-labelledby="h-params">
          <h2 id="h-params">Tham số</h2>
          <label class="f">
            <span>Cách tính chất lượng ({{ ws.label('B') }})</span>
            <select
              class="native"
              [disabled]="!canEdit()"
              [ngModel]="param('B', 'cach_tinh_chat_luong')"
              (ngModelChange)="setParam('B', 'cach_tinh_chat_luong', $event)"
            >
              @for (o of qualityOpts; track o[0]) {
                <option [value]="o[0]">{{ o[1] }}</option>
              }
            </select>
          </label>
          <fieldset class="f">
            <legend>Trừ tiến độ ({{ ws.label('C') }})</legend>
            <div class="inline">
              <input
                class="native num"
                type="number"
                min="0"
                max="0.2"
                step="0.01"
                aria-label="Mức trừ cho mỗi ngày chậm"
                data-testid="tru-tien-do"
                [readOnly]="!canEdit()"
                [attr.aria-invalid]="!!slowError()"
                [ngModel]="param('C', 'tru_tien_do')"
                (ngModelChange)="setParam('C', 'tru_tien_do', $event)"
              />
              <span>mỗi</span>
              <select
                class="native"
                aria-label="Loại ngày tính chậm"
                [disabled]="!canEdit()"
                [ngModel]="param('C', 'loai_ngay')"
                (ngModelChange)="setParam('C', 'loai_ngay', $event)"
              >
                @for (o of dayOpts; track o[0]) {
                  <option [value]="o[0]">{{ o[1] }}</option>
                }
              </select>
            </div>
            @if (slowError(); as e) {
              <small class="err">{{ e }}</small>
            }
          </fieldset>
          <label class="f">
            <span>Lý do trả lại có tính vào chất lượng?</span>
            <select
              class="native"
              [disabled]="!canEdit()"
              [ngModel]="param('B', 'ly_do_tra_lai')"
              (ngModelChange)="setParam('B', 'ly_do_tra_lai', $event)"
            >
              @for (o of returnOpts; track o[0]) {
                <option [value]="o[0]">{{ o[1] }}</option>
              }
            </select>
          </label>
          <label class="f">
            <span>Làm tròn điểm</span>
            <select
              class="native"
              [disabled]="!canEdit()"
              [ngModel]="roundingKey()"
              (ngModelChange)="setRounding($event)"
            >
              @for (o of roundingOpts; track o.key) {
                <option [value]="o.key">{{ o.label }}</option>
              }
            </select>
          </label>
        </section>

        <section class="card" aria-labelledby="h-sum">
          <h2 id="h-sum">Tổng hợp điểm</h2>
          @if (rule('diem'); as d) {
            <label class="sr-only" for="expr-diem">Biểu thức tổng điểm</label>
            <textarea
              id="expr-diem"
              class="code"
              rows="3"
              spellcheck="false"
              [readOnly]="!canEdit()"
              [attr.aria-invalid]="!!problems()['diem']"
              [ngModel]="d.expression"
              (ngModelChange)="setExpr('diem', $event)"
            ></textarea>
            @if (problems()['diem']; as p) {
              <p class="small err">{{ p }}</p>
            } @else if (warnings()['diem']; as w) {
              <p class="small warn">{{ w }}</p>
            }
          }
          <table class="groups" data-testid="groups">
            <thead>
              <tr>
                <th scope="col">Nhóm</th>
                <th scope="col" class="r">Điểm tối đa</th>
              </tr>
            </thead>
            <tbody>
              @for (g of groups(); track g.key) {
                <tr>
                  <th scope="row">{{ ws.label(g.key) }}</th>
                  <td class="r">
                    <input
                      class="native num"
                      type="number"
                      min="0"
                      max="100"
                      step="1"
                      [attr.aria-label]="'Điểm tối đa nhóm ' + ws.label(g.key)"
                      [attr.data-group]="g.key"
                      [readOnly]="!canEdit()"
                      [attr.aria-invalid]="!!groupError()"
                      [ngModel]="g.max"
                      (ngModelChange)="setGroupMax(g.key, $event)"
                    />
                  </td>
                </tr>
              }
            </tbody>
            <tfoot>
              <tr>
                <th scope="row">Tổng</th>
                <td class="r" data-testid="group-total">{{ groupTotal() }}</td>
              </tr>
            </tfoot>
          </table>
          @if (groupError(); as e) {
            <p class="err small" role="alert" data-testid="group-error">{{ e }}</p>
          } @else {
            <p class="small muted">
              KPI nhóm là trung bình KPI các nhiệm vụ trong nhóm. Tổng điểm tối đa các nhóm phải
              bằng
              {{ total }}.
            </p>
          }
        </section>

        <section class="card" aria-labelledby="h-preview">
          <h2 id="h-preview">Xem trước với hồ sơ mẫu</h2>
          <label class="f">
            <span>Hồ sơ mẫu</span>
            <select
              class="native"
              data-testid="case"
              [ngModel]="caseCode()"
              (ngModelChange)="caseCode.set($event)"
            >
              @for (c of cases.value()?.items ?? []; track c.id) {
                <option [value]="c.caseCode">{{ c.caseCode }} · {{ c.description }}</option>
              }
            </select>
          </label>
          @if (preview.error(); as e) {
            <p class="err" role="alert">{{ previewError() }}</p>
          } @else if (preview.value(); as p) {
            <table class="preview" data-testid="preview">
              <tbody>
                <tr>
                  <th scope="row">{{ p.outputs['nhiem_vu'] ?? 'Nhiệm vụ' }}</th>
                  <td class="muted">
                    {{ ws.label('A') }} {{ num(p.outputs['A']) }} · {{ ws.label('B') }}
                    {{ num(p.outputs['B']) }} · {{ ws.label('C') }} {{ num(p.outputs['C']) }}
                  </td>
                  <td class="r strong">{{ num(p.outputs['kpi_nhiem_vu']) }}</td>
                </tr>
                @for (g of previewGroups(); track g.key) {
                  <tr>
                    <th scope="row">Nhóm {{ ws.label(g.key).toLowerCase() }}</th>
                    <td class="r muted">× {{ g.diem_toi_da }}</td>
                    <td class="r strong">{{ num(g.diem, 1) }}</td>
                  </tr>
                }
                <tr class="total">
                  <th scope="row">Điểm</th>
                  <td></td>
                  <td class="r" data-testid="preview-score">{{ num(p.outputs['diem'], 1) }}</td>
                </tr>
                @if (p.ratingLevel) {
                  <tr>
                    <th scope="row">Mức xếp loại</th>
                    <td colspan="2" class="r strong">{{ ratingLabel(p.ratingLevel) }}</td>
                  </tr>
                }
              </tbody>
            </table>
            @if (p.explanation) {
              <p class="explain">{{ p.explanation }}</p>
            }
            @if (dirty().length) {
              <p class="small muted">Kết quả theo bản đã lưu; lưu nháp để xem theo thay đổi mới.</p>
            }
          } @else if (preview.isLoading()) {
            <p class="muted">Đang tính…</p>
          }
        </section>
      </div>
    }

    @if (ws.published(); as p) {
      <vt-policy-diff-dialog [base]="p.version" [(visible)]="showDiff" />
    }
    <vt-policy-expressions-dialog [rules]="draft()" [(visible)]="showAll" />
    <vt-policy-submit-dialog [(visible)]="showSubmit" (sent)="onSent()" />
  `,
  styles: `
    :host {
      display: block;
    }
    .toolbar {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      justify-content: flex-end;
      margin-bottom: 8px;
    }
    .hint {
      text-align: right;
      color: #4b5563;
      margin: 0 0 12px;
    }
    .note {
      background: #eff6ff;
      border-left: 4px solid #1d4ed8;
      padding: 10px 12px;
      color: #1e3a8a;
    }
    .card {
      border: 1px solid #e5e7eb;
      border-radius: 8px;
      padding: 16px;
      background: #fff;
      margin-bottom: 16px;
      min-width: 0;
    }
    .card-head {
      display: flex;
      gap: 8px;
      align-items: center;
    }
    h2 {
      font-size: 1.0625rem;
      margin: 0 0 12px;
    }
    .card-head h2 {
      margin: 0;
    }
    .tag {
      background: #f1f5f9;
      border-radius: 999px;
      padding: 2px 8px;
      font-size: 0.8125rem;
      color: #374151;
    }
    .strip {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      align-items: center;
      background: #f8fafc;
      border-radius: 8px;
      padding: 12px;
      margin: 12px 0;
    }
    .tok {
      border: 1px solid #94a3b8;
      background: #fff;
      border-radius: 6px;
      padding: 6px 12px;
      font: inherit;
      font-weight: 600;
      color: #111827;
      min-height: 40px;
    }
    button.tok {
      cursor: pointer;
    }
    .tok.kpi {
      border-color: #b4001f;
      color: #b4001f;
      display: inline-flex;
      align-items: center;
    }
    .tok.on {
      border-color: #1d4ed8;
      background: #eff6ff;
      outline: 2px solid #1d4ed8;
    }
    .op {
      font-size: 1.25rem;
      color: #374151;
      font-family: ui-monospace, Menlo, Consolas, monospace;
    }
    .blocks {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
      gap: 12px;
    }
    .block {
      border: 1px solid #cbd5e1;
      border-radius: 8px;
      padding: 12px;
    }
    .block.on {
      border-color: #1d4ed8;
      box-shadow: 0 0 0 1px #1d4ed8;
    }
    .block.bad {
      border-color: #b4001f;
      box-shadow: 0 0 0 1px #b4001f;
    }
    .block-title {
      display: block;
      font-weight: 600;
      margin-bottom: 8px;
    }
    .code {
      width: 100%;
      box-sizing: border-box;
      font-family: ui-monospace, Menlo, Consolas, monospace;
      font-size: 0.875rem;
      background: #0f172a;
      color: #e2e8f0;
      border: 1px solid #0f172a;
      border-radius: 6px;
      padding: 10px;
      resize: vertical;
    }
    .code:focus-visible {
      outline: 2px solid #1d4ed8;
      outline-offset: 2px;
    }
    .small {
      font-size: 0.875rem;
      margin: 6px 0 0;
      color: #4b5563;
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
      gap: 16px;
    }
    .f {
      display: flex;
      flex-direction: column;
      gap: 4px;
      margin: 0 0 12px;
      border: 0;
      padding: 0;
      min-width: 0;
    }
    .f > span,
    .f > legend {
      font-weight: 500;
      margin-bottom: 4px;
      padding: 0;
    }
    .inline {
      display: flex;
      gap: 8px;
      align-items: center;
    }
    .inline select {
      flex: 1;
      min-width: 0;
      width: 100%;
    }
    .native {
      padding: 8px 10px;
      border: 1px solid #94a3b8;
      border-radius: 6px;
      font: inherit;
      min-height: 40px;
      background: #fff;
      color: #111827;
    }
    .native.num {
      width: 6.5em;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      margin-top: 12px;
    }
    th,
    td {
      text-align: left;
      padding: 8px 6px;
      border-bottom: 1px solid #f1f5f9;
      font-weight: 400;
    }
    thead th {
      background: #f8fafc;
      color: #374151;
      font-weight: 500;
    }
    .r {
      text-align: right;
    }
    .strong {
      font-weight: 600;
    }
    tfoot th,
    tfoot td {
      font-weight: 600;
    }
    .preview .total th,
    .preview .total td {
      font-weight: 700;
      color: #b4001f;
    }
    .explain {
      background: #f5f3ff;
      border-left: 4px solid #6d28d9;
      padding: 10px 12px;
      margin: 12px 0 0;
      color: #2e1065;
    }
    .muted {
      color: #4b5563;
    }
    .err {
      color: #b4001f;
    }
    .warn {
      color: #92400e;
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
export class FormulaPage {
  private readonly api = inject(PolicyApi);
  protected readonly access = inject(STUDIO_ACCESS);
  protected readonly ws = inject(PolicyWorkspace);

  protected readonly blocks = BLOCKS;
  protected readonly total = GROUP_TOTAL;
  protected readonly statusLabel = POLICY_VERSION_STATUS_LABEL;
  protected readonly vlabel = policyVersionLabel;
  protected readonly qualityOpts = Object.entries(QUALITY_METHOD_LABEL);
  protected readonly returnOpts = Object.entries(RETURN_REASON_LABEL);
  protected readonly dayOpts = Object.entries(DAY_KIND_LABEL);
  protected readonly roundingOpts = [0, 1, 2, 3].flatMap((digits) =>
    Object.entries(ROUNDING_MODE_LABEL).map(([mode, label]) => ({
      key: `${digits}:${mode}`,
      label: `${digits === 0 ? 'Số nguyên' : `${digits} chữ số thập phân`} · ${label}`,
    })),
  );

  protected readonly rules = rxResource({
    params: () => this.ws.ref() ?? undefined,
    stream: ({ params }) => this.api.rules(params),
  });
  /** Bản đã lưu trên máy chủ, cập nhật sau mỗi quy tắc lưu thành công. */
  private readonly base = linkedSignal(() => this.rules.value() ?? []);
  /** Bản đang soạn; nguồn là lần tải, không reset khi `base` đổi sau lưu từng phần. */
  protected readonly draft = linkedSignal(() => structuredClone(this.rules.value() ?? []));

  protected readonly canEdit = computed(
    () => this.ws.editable() && this.access.author('SCR-1501.soan'),
  );
  protected readonly canSubmit = computed(() => this.access.author('SCR-1501.gui-duyet'));

  protected readonly selected = signal<string>('A');
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  private readonly serverErrors = signal<Record<string, string>>({});
  protected readonly showDiff = signal(false);
  protected readonly showAll = signal(false);
  protected readonly showSubmit = signal(false);

  protected readonly dirty = computed(() => {
    const base = new Map(this.base().map((r) => [r.ruleKey, JSON.stringify(strip(r))]));
    return this.draft().filter((r) => base.get(r.ruleKey) !== JSON.stringify(strip(r)));
  });

  /** Lỗi chặn lưu theo khối: cú pháp (MSG-15-01) hoặc lỗi máy chủ trả về. */
  protected readonly problems = computed(() => {
    const res: Record<string, string> = { ...this.serverErrors() };
    for (const r of this.draft()) {
      const syntax = celSyntaxError(r.expression);
      if (syntax) {
        res[r.ruleKey] = `Biểu thức không hợp lệ: ${syntax} tại khối ${this.ws.label(r.ruleKey)}.`;
      }
    }
    return res;
  });

  /** Nhắc biến chưa khai báo; không chặn lưu vì máy chủ là nơi kiểm cuối. */
  protected readonly warnings = computed(() => {
    const declared = this.ws.variables().map((v) => v.name);
    const keys = this.draft().map((r) => r.ruleKey);
    const res: Record<string, string> = {};
    for (const r of this.draft()) {
      const unknown = unknownVariables(r.expression, declared, keys);
      if (unknown.length) res[r.ruleKey] = `Chưa có dữ liệu tên ${unknown.join(', ')}.`;
    }
    return res;
  });

  protected readonly groups = computed(() =>
    Object.entries(this.groupMax()).map(([key, max]) => ({ key, max })),
  );
  protected readonly groupTotal = computed(() =>
    Object.values(this.groupMax()).reduce((a, n) => a + (Number(n) || 0), 0),
  );
  protected readonly groupError = computed(() =>
    this.groups().length && this.groupTotal() !== GROUP_TOTAL
      ? `Tổng điểm tối đa các nhóm là ${this.groupTotal()}, phải bằng ${GROUP_TOTAL}.`
      : null,
  );
  protected readonly slowError = computed(() => {
    const v = Number(this.param('C', 'tru_tien_do'));
    return Number.isFinite(v) && v >= 0 && v <= 0.2
      ? null
      : 'Mức trừ tiến độ phải từ 0,00 đến 0,20.';
  });
  protected readonly blocked = computed(
    () => Object.keys(this.problems()).length > 0 || !!this.groupError() || !!this.slowError(),
  );

  protected readonly roundingKey = computed(() => {
    const r = this.rule('diem')?.rounding;
    return r ? `${r.digits}:${r.mode}` : '1:NUA_LEN';
  });

  protected readonly cases = rxResource({
    params: () => this.ws.ref()?.id,
    stream: ({ params }) => this.api.goldenCases(params),
  });
  protected readonly caseCode = linkedSignal<string | null>(
    () => this.cases.value()?.items[0]?.caseCode ?? null,
  );
  private readonly previewTick = signal(0);
  protected readonly preview = rxResource({
    params: () => {
      const ref = this.ws.ref();
      const code = this.caseCode();
      return ref && code ? { ref, code, tick: this.previewTick() } : undefined;
    },
    stream: ({ params }) => this.api.evaluate(params.ref, params.code),
  });
  protected readonly previewGroups = computed(
    () => (this.preview.value()?.outputs['nhom'] ?? []) as PreviewGroup[],
  );
  protected readonly previewError = computed(() =>
    errorText(this.preview.error(), 'Không tính thử được hồ sơ mẫu.'),
  );

  protected rule(key: string): PolicyRule | undefined {
    return this.draft().find((r) => r.ruleKey === key);
  }

  protected tokens(expr: string): { text: string; key?: string }[] {
    const keys = new Set(this.draft().map((r) => r.ruleKey));
    return expr
      .split(/([A-Za-z_]\w*)/)
      .filter((t) => t.trim())
      .map((t) => (keys.has(t) ? { text: t, key: t } : { text: t.trim() }));
  }

  protected select(key: string) {
    this.selected.set(key);
    document.getElementById(`expr-${key}`)?.focus();
  }

  protected param(key: string, name: string): unknown {
    return this.rule(key)?.params?.[name];
  }

  private groupMax(): Record<string, number> {
    return (this.param('nhom', 'diem_toi_da') ?? {}) as Record<string, number>;
  }

  private patch(key: string, fn: (r: PolicyRule) => PolicyRule) {
    this.notice.set(null);
    this.serverErrors.update(({ [key]: _drop, ...rest }) => rest);
    this.draft.update((list) => list.map((r) => (r.ruleKey === key ? fn(r) : r)));
  }

  protected setExpr(key: string, expression: string) {
    this.patch(key, (r) => ({ ...r, expression }));
  }

  protected setParam(key: string, name: string, value: unknown) {
    const v = name === 'tru_tien_do' ? Number(value) : value;
    this.patch(key, (r) => ({ ...r, params: { ...(r.params ?? {}), [name]: v } }));
  }

  protected setGroupMax(group: string, value: unknown) {
    this.setParam('nhom', 'diem_toi_da', { ...this.groupMax(), [group]: Number(value) || 0 });
  }

  protected setRounding(key: string) {
    const [digits, mode] = key.split(':');
    const rounding = { digits: Number(digits), mode } as PolicyRounding;
    this.patch('diem', (r) => ({ ...r, rounding }));
  }

  protected num(v: unknown, digits = 3) {
    return fmtNum(v, digits);
  }

  protected ratingLabel(level: string) {
    return RATING_LEVEL_LABEL[level as RatingLevel] ?? level;
  }

  /** Lưu lần lượt các quy tắc đã sửa (PUT kèm `If-Match`); dừng ở quy tắc đầu tiên bị từ chối. */
  protected save() {
    const ref = this.ws.ref();
    const changed = this.dirty();
    if (!ref || !changed.length || this.blocked()) return;
    this.saving.set(true);
    this.saveError.set(null);
    this.notice.set(null);
    let reverted = false;
    from(changed)
      .pipe(
        concatMap((r) =>
          this.api
            .saveRule(ref, r, {
              expression: r.expression,
              resultType: r.resultType,
              rounding: r.rounding,
              legalRef: r.legalRef,
              params: r.params,
            })
            .pipe(tap((saved) => (reverted = this.applySaved(saved) || reverted))),
        ),
        toArray(),
      )
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.notice.set(
            reverted
              ? 'Phiên bản đã thay đổi sau lần kiểm thử gần nhất nên trở về trạng thái nháp. Vui lòng chạy lại test case.'
              : 'Đã lưu nháp.',
          );
          this.ws.version.reload();
          this.previewTick.update((n) => n + 1);
        },
        error: (e) => {
          this.saving.set(false);
          const p = toProblem(e);
          const failed = changed.find((r) => !this.base().some((b) => sameRule(b, r)));
          if (p.status === 422 && failed) {
            const fe = fieldErrors(p);
            this.serverErrors.update((m) => ({
              ...m,
              [failed.ruleKey]: p.detail || Object.values(fe)[0] || p.title,
            }));
            this.saveError.set(null);
          } else {
            this.saveError.set(
              p.status === 409
                ? 'Nội dung đã được người khác cập nhật hoặc phiên bản không còn sửa được. Vui lòng tải lại trang.'
                : errorText(e, 'Không lưu được nháp.'),
            );
          }
          if (reverted) this.ws.version.reload();
        },
      });
  }

  private applySaved(saved: PolicyRuleSaved): boolean {
    const { versionStatus: _s, statusReverted, ...rule } = saved;
    this.base.update((list) => list.map((r) => (r.ruleKey === rule.ruleKey ? rule : r)));
    this.draft.update((list) =>
      list.map((r) => (r.ruleKey === rule.ruleKey ? { ...r, rowVersion: rule.rowVersion } : r)),
    );
    return !!statusReverted;
  }

  protected onSent() {
    this.notice.set('Đã gửi phê duyệt phát hành. Phiên bản chờ cấp có thẩm quyền phê duyệt.');
    this.ws.version.reload();
    this.ws.pack.reload();
  }
}

/** Phần nội dung so sánh được của quy tắc (bỏ `rowVersion`, `id`). */
function strip(r: PolicyRule) {
  return [r.expression, r.resultType, r.rounding ?? null, r.legalRef ?? null, r.params ?? null];
}

function sameRule(a: PolicyRule, b: PolicyRule) {
  return a.ruleKey === b.ruleKey && JSON.stringify(strip(a)) === JSON.stringify(strip(b));
}
