import { Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { LegalRefPipe, toProblem } from '@vt/api-client';
import {
  DecisionRule,
  DecisionTable,
  DecisionTableSaved,
  EXPLANATION_VARIABLES,
  HIT_POLICY_LABEL,
  POLICY_VERSION_STATUS_LABEL,
  PolicyApi,
  PolicyRule,
  RATING_LEVELS,
  GENERAL_CRITERIA_LEVEL_LABEL,
  RATING_LEVEL_LABEL,
  RatingLevel,
  shadowedDecisionRows,
  unknownTemplateVariables,
} from '@vt/api-client/domains';
import { ButtonModule } from 'primeng/button';
import { Observable, concatMap, from, of, tap, toArray } from 'rxjs';
import { STUDIO_ACCESS } from '../../studio-permissions';
import { errorText } from '../../studio-ui';
import { celSyntaxError } from './cel-check';
import { PolicyWorkspace } from './policy-workspace';
import { VtSkeletonComponent } from '@vt/design-system';

const TABLE_KEY = 'xep_loai';
const TEMPLATE_KEY = 'ha_muc';
/** Quy tắc biểu thức hiển thị dưới bảng (SCR-1502 vùng 3, 4). */
const RULE_KEYS = ['muc_cuoi', 'nguoi_dung_dau'] as const;

/** Ý nghĩa các biến dùng trong mẫu câu giải thích. */
const VARIABLE_HINT: Record<string, string> = {
  muc_tren: 'mức phía trên',
  diem: 'điểm',
  nguong: 'ngưỡng điểm',
  dieu_kien_thieu: 'điều kiện còn thiếu',
  gia_tri: 'giá trị thực tế',
  dieu_khoan: 'điều khoản căn cứ',
  phien_ban: 'phiên bản chính sách',
};

/**
 * Config Studio · Bảng quyết định xếp loại (SCR-1502, QT-15): bảng DMN 1.4 hit policy FIRST sửa
 * trực tiếp (thêm, xoá, đổi thứ tự dòng), cảnh báo dòng không bao giờ được chọn (MSG-15-04), quy tắc
 * mức cuối cùng và quy tắc người đứng đầu (QĐ 14.3.3), mẫu câu giải thích có kiểm biến (MSG-15-05),
 * xuất tệp DMN. Chỉ bản nháp sửa được; máy chủ kiểm lại khi lưu.
 */
@Component({
  selector: 'vt-decision-table-page',
  imports: [VtSkeletonComponent, FormsModule, ButtonModule, LegalRefPipe],
  template: `
    <div class="toolbar">
      <p-button
        label="Xuất tệp bảng quyết định"
        severity="contrast"
        [outlined]="true"
        icon="pi pi-download"
        data-testid="export-dmn"
        [loading]="exporting()"
        (onClick)="exportDmn()"
      />
      @if (canEdit()) {
        <p-button
          label="Lưu nháp"
          severity="contrast"
          [outlined]="true"
          data-testid="save"
          [loading]="saving()"
          [disabled]="!dirty() || blocked()"
          (onClick)="save()"
        />
        <p-button
          label="Chạy kiểm thử"
          data-testid="run-tests"
          [loading]="ws.running()"
          [disabled]="dirty() || ws.versionSyncing()"
          (onClick)="ws.runTests()"
        />
      }
    </div>
    @if (!canEdit() && ws.status(); as s) {
      <p class="hint" data-testid="readonly">
        @if (s === 'DAT_KIEM_THU') {
          Bảng quyết định chỉ sửa được ở bản nháp.
        } @else {
          Phiên bản ở trạng thái "{{ statusLabel[s] }}", chỉ xem.
        }
      </p>
    } @else if (dirty()) {
      <p class="hint">Có thay đổi chưa lưu. Lưu nháp trước khi chạy kiểm thử.</p>
    }
    @if (notice(); as n) {
      <p class="note" role="status">{{ n }}</p>
    }
    @if (error(); as e) {
      <p class="err" role="alert">{{ e }}</p>
    }

    @if (table.value(); as t) {
      <section class="card" aria-labelledby="h-table">
        <h2 id="h-table">Bảng xếp loại</h2>
        <p class="muted">
          {{ hitLabel[t.hitPolicy] }}, xét lần lượt từ trên xuống.
          @if (t.legalRef | legalRef; as lr) {
            Căn cứ: {{ lr }}.
          }
        </p>
        <div class="scroll">
          <table class="dt" data-testid="decision-table">
            <thead>
              <tr>
                <th scope="col">Dòng</th>
                @for (c of t.inputs; track c.name) {
                  <th scope="col">{{ c.label ?? c.name }}</th>
                }
                <th scope="col" class="out">Mức xếp loại</th>
                <th scope="col" class="out">Điều khoản căn cứ</th>
                <th scope="col" class="out">Lời giải thích</th>
                @if (canEdit()) {
                  <th scope="col"><span class="sr-only">Thao tác</span></th>
                }
              </tr>
            </thead>
            <tbody>
              @for (r of rows(); track $index; let i = $index) {
                <tr [class.shadow]="shadowed().has(i + 1)" [attr.data-row]="i + 1">
                  <th scope="row">{{ i + 1 }}</th>
                  @for (c of t.inputs; track c.name) {
                    <td>
                      @if (canEdit()) {
                        <input
                          class="cell"
                          [attr.aria-label]="'Dòng ' + (i + 1) + ', ' + (c.label ?? c.name)"
                          [ngModel]="r.inputs[c.name]"
                          (ngModelChange)="setInput(i, c.name, $event)"
                          placeholder="—"
                        />
                      } @else {
                        {{ show(r.inputs[c.name]) }}
                      }
                    </td>
                  }
                  <td>
                    @if (!r.ratingLevel) {
                      <!-- Bảng không xếp loại (tiêu chí chung): đầu ra là outputValue, chỉ xem -->
                      <span class="lv" [attr.data-output]="r.outputValue">{{
                        outputLabel(r.outputValue)
                      }}</span>
                    } @else if (canEdit()) {
                      <select
                        class="cell level"
                        [attr.aria-label]="'Dòng ' + (i + 1) + ', mức xếp loại'"
                        [ngModel]="r.ratingLevel"
                        (ngModelChange)="setRow(i, { ratingLevel: $event })"
                      >
                        @for (l of levels; track l) {
                          <option [value]="l">{{ levelLabel[l] }}</option>
                        }
                      </select>
                    } @else {
                      <span class="lv">{{ levelLabel[r.ratingLevel] }}</span>
                    }
                  </td>
                  <td>
                    @if (canEdit()) {
                      <input
                        class="cell clause"
                        [attr.aria-label]="'Dòng ' + (i + 1) + ', điều khoản căn cứ'"
                        [attr.aria-invalid]="!r.clause.trim()"
                        [ngModel]="r.clause"
                        (ngModelChange)="setRow(i, { clause: $event })"
                      />
                    } @else {
                      {{ r.clause }}
                    }
                  </td>
                  <td>
                    @if (canEdit()) {
                      <input
                        class="cell wide"
                        [attr.aria-label]="'Dòng ' + (i + 1) + ', lời giải thích'"
                        [attr.aria-invalid]="!r.explanation.trim()"
                        [ngModel]="r.explanation"
                        (ngModelChange)="setRow(i, { explanation: $event })"
                      />
                    } @else {
                      {{ r.explanation }}
                    }
                  </td>
                  @if (canEdit()) {
                    <td class="acts">
                      <p-button
                        icon="pi pi-arrow-up"
                        [text]="true"
                        severity="contrast"
                        [ariaLabel]="'Đưa dòng ' + (i + 1) + ' lên'"
                        [disabled]="i === 0"
                        (onClick)="move(i, -1)"
                      />
                      <p-button
                        icon="pi pi-arrow-down"
                        [text]="true"
                        severity="contrast"
                        [ariaLabel]="'Đưa dòng ' + (i + 1) + ' xuống'"
                        [disabled]="i === rows().length - 1"
                        (onClick)="move(i, 1)"
                      />
                      <p-button
                        icon="pi pi-trash"
                        [text]="true"
                        severity="danger"
                        [ariaLabel]="'Xoá dòng ' + (i + 1)"
                        [disabled]="rows().length === 1"
                        (onClick)="remove(i)"
                      />
                    </td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>
        @if (canEdit()) {
          <p-button
            label="Thêm dòng"
            icon="pi pi-plus"
            [text]="true"
            data-testid="add-row"
            (onClick)="add()"
          />
          <p class="small muted">
            Ô để trống nghĩa là không xét điều kiện đó. Dòng thiếu điều khoản căn cứ hoặc lời giải
            thích chưa lưu được.
          </p>
        }
        @for (w of shadowWarnings(); track w.row) {
          <p class="warn" role="alert" data-testid="shadow-warning">
            Dòng {{ w.row }} không bao giờ được chọn vì dòng {{ w.by }} phía trên luôn thoả mãn
            trước.
          </p>
        }
      </section>

      <div class="grid">
        @if (rules.isLoading() && !rules.hasValue()) {
          <vt-skeleton variant="cards" [rows]="2" label="Đang tải quy tắc xếp loại" />
        }
        @for (k of ruleKeys; track k) {
          @if (expr()[k] !== undefined) {
            <section class="card" [attr.aria-labelledby]="'h-' + k">
              <div class="card-head">
                <h2 [id]="'h-' + k">
                  {{ k === 'muc_cuoi' ? 'Quy tắc mức cuối cùng' : 'Quy tắc người đứng đầu' }}
                </h2>
                @if (ruleOf(k)?.legalRef | legalRef; as lr) {
                  <span class="tag">{{ lr }}</span>
                }
              </div>
              <label class="sr-only" [for]="'expr-' + k">Biểu thức</label>
              <textarea
                class="code"
                rows="5"
                spellcheck="false"
                [id]="'expr-' + k"
                [readOnly]="!canEdit()"
                [attr.aria-invalid]="!!exprError()[k]"
                [ngModel]="expr()[k]"
                (ngModelChange)="setExpr(k, $event)"
              ></textarea>
              @if (exprError()[k]; as e) {
                <p class="small err">{{ e }}</p>
              } @else if (k === 'muc_cuoi') {
                <p class="small muted">
                  Lấy mức thấp hơn giữa mức theo kết quả nhiệm vụ và mức theo tiêu chí chung. Tiêu
                  chí chung quy đổi: đạt 100% là hoàn thành xuất sắc; từ 90% đến dưới 100% là hoàn
                  thành tốt; từ 60% đến dưới 90% là hoàn thành; dưới 60% là không hoàn thành.
                </p>
              } @else {
                <p class="small muted">
                  Người đứng đầu xếp không hoàn thành nhiệm vụ khi tập thể không đạt chỉ tiêu theo
                  quy định.
                </p>
              }
            </section>
          }
        }
        <section class="card" aria-labelledby="h-tpl">
          <h2 id="h-tpl">Mẫu câu giải thích</h2>
          <label class="sr-only" for="tpl">Mẫu câu khi chuyển xuống mức liền kề</label>
          <textarea
            #tplBox
            id="tpl"
            class="text"
            rows="5"
            data-testid="template"
            [readOnly]="!canEdit()"
            [attr.aria-invalid]="!!tplError()"
            [ngModel]="tpl()"
            (ngModelChange)="tpl.set($event)"
          ></textarea>
          @if (tplError(); as e) {
            <p class="small err" role="alert" data-testid="template-error">{{ e }}</p>
          }
          <!-- Tên biến là cú pháp của mẫu câu, không hiện cho người soạn: nút chèn biến theo nhãn
               nghiệp vụ, ô soạn giữ nguyên dạng {ten_bien} để máy chủ thay giá trị (wiki/16) -->
          <p class="small muted" id="tpl-vars">
            {{ canEdit() ? 'Chèn nội dung tự điền vào mẫu câu:' : 'Nội dung tự điền dùng được:' }}
          </p>
          <ul class="vars" aria-labelledby="tpl-vars">
            @for (v of variables(); track v.name) {
              <li>
                @if (canEdit()) {
                  <button
                    type="button"
                    class="var"
                    [attr.data-var]="v.name"
                    (click)="insertVariable(v.name, tplBox)"
                  >
                    {{ v.hint || 'nội dung tự điền' }}
                  </button>
                } @else {
                  {{ v.hint || 'nội dung tự điền' }}
                }
              </li>
            }
          </ul>
        </section>
      </div>
    } @else if (table.error()) {
      <p class="err" role="alert">Không tải được bảng quyết định. Vui lòng thử lại.</p>
    } @else {
      <vt-skeleton variant="table" [columns]="6" label="Đang tải bảng quyết định" />
    }
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
      margin-bottom: 12px;
    }
    h2 {
      font-size: 1.0625rem;
      margin: 0 0 8px;
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
    .scroll {
      overflow-x: auto;
    }
    .dt {
      border-collapse: collapse;
      width: 100%;
      min-width: 1320px;
    }
    .dt th,
    .dt td {
      text-align: left;
      padding: 8px 6px;
      border-bottom: 1px solid #f1f5f9;
      vertical-align: middle;
    }
    .dt thead th {
      background: #f8fafc;
      color: #374151;
      font-weight: 500;
    }
    .dt thead th.out {
      background: #eef2ff;
    }
    .dt tr.shadow {
      background: #fff1f2;
    }
    .cell {
      width: 100%;
      min-width: 6em;
      box-sizing: border-box;
      padding: 6px 8px;
      border: 1px solid #94a3b8;
      border-radius: 6px;
      font: inherit;
      min-height: 36px;
      background: #fff;
      color: #111827;
    }
    .cell.level {
      min-width: 15em;
    }
    .cell.clause {
      min-width: 10em;
    }
    .cell.wide {
      min-width: 20em;
    }
    .cell[aria-invalid='true'] {
      border-color: #b4001f;
    }
    .lv {
      font-weight: 500;
      color: #1e3a8a;
    }
    .acts {
      white-space: nowrap;
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
      gap: 16px;
    }
    .code,
    .text {
      width: 100%;
      box-sizing: border-box;
      border-radius: 6px;
      padding: 10px;
      resize: vertical;
      font: inherit;
    }
    .code {
      font-family: ui-monospace, Menlo, Consolas, monospace;
      font-size: 0.875rem;
      background: #0f172a;
      color: #e2e8f0;
      border: 1px solid #0f172a;
    }
    .text {
      border: 1px solid #94a3b8;
      color: #111827;
    }
    .code:focus-visible,
    .text:focus-visible {
      outline: 2px solid #1d4ed8;
      outline-offset: 2px;
    }
    .small {
      font-size: 0.875rem;
      margin: 6px 0 0;
    }
    .vars {
      display: flex;
      flex-wrap: wrap;
      gap: 6px 8px;
      list-style: none;
      margin: 4px 0 0;
      padding: 0;
      font-size: 0.875rem;
      color: #374151;
    }
    .var {
      min-height: 32px;
      padding: 4px 10px;
      border: 1px solid #cbd5e1;
      border-radius: 16px;
      background: #f8fafc;
      color: inherit;
      font: inherit;
      cursor: pointer;
    }
    .var:focus-visible {
      outline: 2px solid var(--p-primary-color, #0f5bd8);
      outline-offset: 2px;
    }
    .muted {
      color: #4b5563;
    }
    .err {
      color: #b4001f;
    }
    .warn {
      color: #92400e;
      background: #fffbeb;
      border-left: 4px solid #d97706;
      padding: 8px 12px;
      margin: 8px 0 0;
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
export class DecisionTablePage {
  private readonly api = inject(PolicyApi);
  private readonly access = inject(STUDIO_ACCESS);
  protected readonly ws = inject(PolicyWorkspace);

  protected readonly ruleKeys = RULE_KEYS;
  protected readonly levels = RATING_LEVELS;
  protected readonly levelLabel = RATING_LEVEL_LABEL;

  /** Nhãn đầu ra của bảng không xếp loại; giá trị chưa có nhãn hiện như máy chủ trả. */
  protected outputLabel(v: string | undefined): string {
    return (GENERAL_CRITERIA_LEVEL_LABEL as Record<string, string>)[v ?? ''] ?? v ?? '';
  }
  protected readonly hitLabel = HIT_POLICY_LABEL;
  protected readonly statusLabel = POLICY_VERSION_STATUS_LABEL;

  protected readonly table = rxResource({
    params: () => this.ws.ref() ?? undefined,
    stream: ({ params }) => this.api.decisionTable(params, TABLE_KEY),
  });
  protected readonly rules = rxResource({
    params: () => this.ws.ref() ?? undefined,
    stream: ({ params }) => this.api.rules(params),
  });

  protected readonly rows = linkedSignal(() => structuredClone(this.table.value()?.rules ?? []));
  protected readonly tpl = linkedSignal(
    () => this.table.value()?.explanationTemplates?.[TEMPLATE_KEY] ?? '',
  );
  protected readonly expr = linkedSignal(() => {
    const res: Record<string, string> = {};
    for (const r of this.rules.value() ?? []) {
      if ((RULE_KEYS as readonly string[]).includes(r.ruleKey)) res[r.ruleKey] = r.expression;
    }
    return res;
  });

  /** Bảng quyết định chỉ sửa ở bản nháp (`PUT decision-tables` khi `NHAP`). */
  protected readonly canEdit = computed(
    () => this.ws.status() === 'NHAP' && this.access.author('SCR-1502.soan'),
  );
  /** Biến dùng được trong mẫu câu: biến ngữ cảnh và các cột điều kiện của bảng. */
  protected readonly variables = computed(() => {
    const cols = (this.table.value()?.inputs ?? []).map((i) => ({
      name: i.name,
      hint: (i.label ?? '').toLowerCase(),
    }));
    return [
      ...EXPLANATION_VARIABLES.map((name) => ({ name, hint: VARIABLE_HINT[name] ?? '' })),
      ...cols.filter((c) => !EXPLANATION_VARIABLES.includes(c.name)),
    ];
  });

  protected readonly saving = signal(false);
  protected readonly exporting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected readonly shadowWarnings = computed(() => shadowedDecisionRows(this.rows()));
  protected readonly shadowed = computed(() => new Set(this.shadowWarnings().map((w) => w.row)));
  protected readonly tplError = computed(() => {
    const bad = unknownTemplateVariables(this.tpl(), this.table.value()?.inputs ?? []);
    return bad.length
      ? `Mẫu câu giải thích dùng biến ${bad.join(', ')} không có trong ngữ cảnh xếp loại.`
      : null;
  });
  protected readonly exprError = computed(() => {
    const res: Record<string, string> = {};
    for (const [k, v] of Object.entries(this.expr())) {
      const e = celSyntaxError(v);
      if (e) res[k] = `Biểu thức không hợp lệ: ${e}.`;
    }
    return res;
  });

  private readonly tableDirty = computed(() => {
    const t = this.table.value();
    if (!t) return false;
    return (
      JSON.stringify(t.rules) !== JSON.stringify(this.rows()) ||
      (t.explanationTemplates?.[TEMPLATE_KEY] ?? '') !== this.tpl()
    );
  });
  private readonly dirtyRules = computed(() =>
    (this.rules.value() ?? []).filter(
      (r) => this.expr()[r.ruleKey] !== undefined && this.expr()[r.ruleKey] !== r.expression,
    ),
  );
  protected readonly dirty = computed(() => this.tableDirty() || this.dirtyRules().length > 0);
  protected readonly blocked = computed(
    () =>
      !!this.tplError() ||
      Object.keys(this.exprError()).length > 0 ||
      this.rows().some((r) => !r.clause.trim() || !r.explanation.trim()),
  );

  protected token(v: string) {
    return `{${v}}`;
  }

  /** Chèn `{biến}` vào vị trí con trỏ của ô mẫu câu rồi đặt con trỏ ngay sau biến vừa chèn. */
  protected insertVariable(name: string, box: HTMLTextAreaElement): void {
    const value = this.tpl();
    const start = box.selectionStart ?? value.length;
    const end = box.selectionEnd ?? start;
    const token = this.token(name);
    const next = value.slice(0, start) + token + value.slice(end);
    // ghi thẳng vào ô trước: ngModel ghi lại cùng giá trị sau đó nên con trỏ không nhảy về cuối
    box.value = next;
    box.focus();
    box.setSelectionRange(start + token.length, start + token.length);
    this.tpl.set(next);
  }

  protected ruleOf(key: string): PolicyRule | undefined {
    return this.rules.value()?.find((r) => r.ruleKey === key);
  }

  protected show(v: string | undefined): string {
    const s = (v ?? '').trim();
    if (!s || s === '-') return '—';
    return s.replace(/"/g, '');
  }

  private edit(fn: (rows: DecisionRule[]) => DecisionRule[]) {
    this.notice.set(null);
    this.rows.update((rows) => fn([...rows]));
  }

  protected setInput(i: number, name: string, value: string) {
    this.edit((rows) => {
      const inputs = { ...rows[i].inputs };
      if (value.trim()) inputs[name] = value;
      else delete inputs[name];
      rows[i] = { ...rows[i], inputs };
      return rows;
    });
  }

  protected setRow(i: number, patch: Partial<DecisionRule>) {
    this.edit((rows) => {
      rows[i] = { ...rows[i], ...patch };
      return rows;
    });
  }

  protected move(i: number, d: number) {
    this.edit((rows) => {
      [rows[i], rows[i + d]] = [rows[i + d], rows[i]];
      return rows;
    });
  }

  protected remove(i: number) {
    this.edit((rows) => rows.filter((_, j) => j !== i));
  }

  protected add() {
    this.edit((rows) => [
      ...rows,
      { inputs: {}, ratingLevel: 'HTNV' as RatingLevel, clause: '', explanation: '' },
    ]);
  }

  protected setExpr(key: string, value: string) {
    this.notice.set(null);
    this.expr.update((m) => ({ ...m, [key]: value }));
  }

  protected save() {
    const ref = this.ws.ref();
    const t = this.table.value();
    if (!ref || !t || this.blocked()) return;
    this.saving.set(true);
    this.error.set(null);
    this.notice.set(null);
    const saveTable: Observable<DecisionTableSaved | null> = this.tableDirty()
      ? this.api.saveDecisionTable(ref, t, {
          hitPolicy: t.hitPolicy,
          inputs: t.inputs,
          rules: this.rows(),
          explanationTemplates: { ...(t.explanationTemplates ?? {}), [TEMPLATE_KEY]: this.tpl() },
          legalRef: t.legalRef,
        })
      : of(null);
    const rules = this.dirtyRules();
    saveTable
      .pipe(
        tap((saved) => {
          if (saved) {
            const { warnings: _w, ...rest } = saved;
            this.table.set(rest as DecisionTable);
          }
        }),
        concatMap(() => from(rules)),
        concatMap((r) =>
          this.api.saveRule(ref, r, {
            expression: this.expr()[r.ruleKey],
            resultType: r.resultType,
            rounding: r.rounding,
            legalRef: r.legalRef,
            params: r.params,
          }),
        ),
        toArray(),
      )
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.notice.set('Đã lưu nháp.');
          if (rules.length) this.rules.reload();
          this.ws.version.reload();
        },
        error: (e) => {
          this.saving.set(false);
          const p = toProblem(e);
          this.error.set(
            p.status === 409
              ? 'Nội dung đã được người khác cập nhật hoặc phiên bản không còn sửa được. Vui lòng tải lại trang.'
              : errorText(e, 'Không lưu được nháp.'),
          );
        },
      });
  }

  /** Tải tệp DMN 1.4 của bảng. */
  protected exportDmn() {
    const ref = this.ws.ref();
    if (!ref) return;
    this.exporting.set(true);
    this.api.exportDmn(ref, TABLE_KEY).subscribe({
      next: (blob) => {
        this.exporting.set(false);
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `bang-xep-loai-${ref.v}.dmn`;
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 0);
      },
      error: (e) => {
        this.exporting.set(false);
        this.error.set(errorText(e, 'Không xuất được tệp.'));
      },
    });
  }
}
