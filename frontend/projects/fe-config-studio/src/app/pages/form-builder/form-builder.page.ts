import { Component, computed, inject, input, linkedSignal, signal, untracked } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import {
  UI_FORM_STATUS_LABEL,
  UiFormTestRunResult,
  UiFormVersion,
  UiPackApi,
  uiVersionLabel,
} from '@vt/api-client';
import { VtFormComponent } from '@vt/form-engine';
import { STUDIO_ACCESS } from '../../studio-permissions';
import { errorText, problemCode } from '../../studio-ui';
import { celSyntaxError } from '../policy/cel-check';
import {
  BuilderField,
  BuilderTest,
  FIELD_KINDS,
  FieldKind,
  browserErrors,
  fromVersion,
  isValidKey,
  kindLabel,
  measure50,
  newField,
  previewRules,
  previewSchema,
  testInputs,
  toUpdate,
  visibleKeys,
} from './form-builder-model';
import { FORM_TITLE } from './forms.page';
import { VtSkeletonComponent } from '@vt/design-system';

/** Lớp dữ liệu của trường (BR-18-11): C2 được mã hoá trường khi lưu. */
const CLASSIFICATIONS = [
  { value: 'C1', label: 'Thông thường' },
  { value: 'C2', label: 'Nhạy cảm, mã hoá khi lưu' },
];

/*
 * SCR-1802 Trình dựng biểu mẫu động (QT-18, TL-02b), đối chiếu đặc tả:
 * - Trường: (1) mã biểu mẫu, phiên bản; (2) khoá dữ liệu snake_case ≤ 64, duy nhất; (3) kiểu; (4) nhãn,
 *   gợi ý ≤ 200; (5) bắt buộc; (6) quy tắc kiểm tra CEL (`self`, `form`); (7) điều kiện hiển thị CEL;
 *   (8) thông báo lỗi; (9) lớp dữ liệu C1, C2 (văn bản dài mặc định C2).
 * - Chức năng: thêm trường (phía trình duyệt), Chạy bộ test (POST test-runs, cel-js so với cel-java),
 *   Lưu nháp (PUT), Đưa vào giao diện (POST packs/{id}/versions/{v}/forms, khi bộ test đạt).
 * - Quy tắc: BR-18-04 kiểm tra tức thời ở trình duyệt, máy chủ là nguồn sự thật; BR-18-05 hai engine
 *   cùng kết quả; BR-18-06 trường tính điểm khớp bộ chính sách; BR-18-08 dựng 50 trường ≤ 50 ms;
 *   BR-18-11 lớp dữ liệu.
 * - Thông báo: MSG-18-06 CEL không hợp lệ, MSG-18-07 trường tính điểm, MSG-18-08 hai engine khác nhau.
 * Bộ test lưu trong `uiJson['x-test-cases']` vì hợp đồng chưa có nơi lưu riêng (suy luận, wiki/14).
 */

@Component({
  selector: 'vt-form-builder-page',
  imports: [VtSkeletonComponent, RouterLink, ButtonModule, VtFormComponent],
  template: `
    <nav class="crumb" aria-label="Đường dẫn">
      <a routerLink="../..">Biểu mẫu động</a>
      <span aria-hidden="true">/</span>
      <span>{{ title() }}</span>
    </nav>
    <header class="head">
      <div>
        <h1>Trình dựng biểu mẫu · {{ title() }}</h1>
        @if (form.value(); as f) {
          <p class="sub" data-testid="form-bar">
            Bản {{ f.version }} · {{ statusLabel[f.status] }}
            @if (f.policyPack; as pp) {
              · áp dụng cùng chính sách đánh giá bản {{ pp.version }}
            }
          </p>
        }
      </div>
      @if (form.value(); as f) {
        <div class="actions">
          @if (can('SCR-1802.dung')) {
            <p-button
              data-testid="run-tests"
              label="Chạy bộ test biểu mẫu"
              severity="secondary"
              [outlined]="true"
              [disabled]="busy() || !tests().length"
              (onClick)="runTests()"
            />
          }
          @if (editable()) {
            <p-button
              data-testid="save"
              label="Lưu nháp"
              severity="secondary"
              [outlined]="true"
              [disabled]="busy() || !dirty() || !!localError()"
              (onClick)="save()"
            />
          }
          @if (can('SCR-1802.dua-vao-pack') && packDraft(); as pd) {
            <p-button
              data-testid="attach"
              [label]="'Đưa vào giao diện bản ' + vlabel(pd.version)"
              [disabled]="busy() || dirty() || !testsPassed()"
              (onClick)="attach()"
            />
          }
        </div>
      }
    </header>

    @if (form.error()) {
      <p class="err" role="alert">Không tải được biểu mẫu. Vui lòng thử lại.</p>
    } @else if (form.isLoading() && !form.value()) {
      <vt-skeleton variant="form" [rows]="5" label="Đang tải biểu mẫu" />
    }
    @if (notice(); as n) {
      <p class="notice" role="status" data-testid="notice">{{ n }}</p>
    }
    @if (error(); as e) {
      <p class="err" role="alert" data-testid="error">{{ e }}</p>
    }
    @if (form.value() && !editable()) {
      <p class="hint" data-testid="readonly">Bản này không còn là bản nháp nên chỉ xem được.</p>
    }

    @if (form.value()) {
      <div class="grid">
        <section class="card">
          <h2>Thành phần</h2>
          @if (editable()) {
            <ul class="palette" data-testid="palette">
              @for (k of kinds; track k.kind) {
                <li>
                  <button
                    type="button"
                    [attr.data-kind]="k.kind"
                    [attr.aria-label]="'Thêm trường ' + k.label.toLowerCase()"
                    (click)="add(k.kind)"
                  >
                    + {{ k.label }}
                  </button>
                </li>
              }
            </ul>
            <p class="hint small">Danh mục trường được phép, không có trường nhập mã tự do.</p>
          }
          <h2 class="mt">Các trường</h2>
          <ol class="fields" data-testid="fields">
            @for (f of fields(); track f.key; let i = $index, last = $last) {
              <li [class.on]="f.key === selectedKey()" [attr.data-field]="f.key">
                <button type="button" class="pick" (click)="selectedKey.set(f.key)">
                  {{ f.label }}
                  <small>{{ kind(f.kind) }}{{ f.required ? ' · bắt buộc' : '' }}</small>
                </button>
                @if (editable()) {
                  <span class="row-act">
                    <button
                      type="button"
                      class="icon"
                      [disabled]="i === 0"
                      [attr.aria-label]="'Đưa ' + f.label + ' lên trên'"
                      (click)="move(i, -1)"
                    >
                      ↑
                    </button>
                    <button
                      type="button"
                      class="icon"
                      [disabled]="last"
                      [attr.aria-label]="'Đưa ' + f.label + ' xuống dưới'"
                      (click)="move(i, 1)"
                    >
                      ↓
                    </button>
                    <button
                      type="button"
                      class="icon"
                      [disabled]="f.scored"
                      [attr.aria-label]="'Xoá ' + f.label"
                      (click)="remove(f)"
                    >
                      ✕
                    </button>
                  </span>
                }
              </li>
            }
          </ol>
        </section>

        <section class="card">
          <h2>Xem trước biểu mẫu</h2>
          <vt-form
            data-testid="preview"
            [schema]="schema()"
            [rules]="rules()"
            [value]="seed()"
            (valueChange)="model.set($event)"
          />
          @for (f of placeholders(); track f.key) {
            <p class="ph">{{ f.label }} · {{ kind(f.kind) }} (nhập trên phiếu thật)</p>
          }
          @if (editable()) {
            <p-button
              data-testid="add-test"
              label="Thêm dữ liệu đang nhập làm ca test"
              severity="secondary"
              [outlined]="true"
              size="small"
              (onClick)="addTest()"
            />
          }
        </section>

        <div class="col">
          @if (selected(); as f) {
            <section class="card" data-testid="props">
              <h2>Thuộc tính trường · {{ f.label }}</h2>
              @if (f.scored) {
                <p class="warn" data-testid="scored">
                  Trường này được bộ chính sách đánh giá dùng để tính điểm nên không thể đổi kiểu
                  hoặc xoá.
                </p>
              }
              <div class="two">
                <label class="f">
                  <span>Khoá dữ liệu</span>
                  <input
                    class="native mono"
                    data-testid="p-key"
                    maxlength="64"
                    [value]="f.key"
                    [disabled]="!editable() || f.scored"
                    [attr.aria-invalid]="!!keyError()"
                    (change)="setKey($event)"
                  />
                  @if (keyError(); as ke) {
                    <small class="err">{{ ke }}</small>
                  }
                </label>
                <label class="f">
                  <span>Kiểu</span>
                  <select
                    class="native"
                    data-testid="p-kind"
                    [disabled]="!editable() || f.scored"
                    (change)="set('kind', $any(value($event)))"
                  >
                    @for (k of kinds; track k.kind) {
                      <option [value]="k.kind" [selected]="k.kind === f.kind">{{ k.label }}</option>
                    }
                  </select>
                </label>
              </div>
              <label class="f">
                <span>Nhãn</span>
                <input
                  class="native"
                  data-testid="p-label"
                  maxlength="200"
                  [value]="f.label"
                  [disabled]="!editable()"
                  (input)="set('label', value($event))"
                />
              </label>
              <label class="f">
                <span>Gợi ý</span>
                <input
                  class="native"
                  maxlength="200"
                  [value]="f.hint"
                  [disabled]="!editable()"
                  (input)="set('hint', value($event))"
                />
              </label>
              <div class="two">
                <label class="check">
                  <input
                    type="checkbox"
                    data-testid="p-required"
                    [checked]="f.required"
                    [disabled]="!editable()"
                    (change)="set('required', checked($event))"
                  />
                  Bắt buộc
                </label>
                <label class="f">
                  <span>Mức bảo vệ dữ liệu</span>
                  <select
                    class="native"
                    [disabled]="!editable()"
                    (change)="set('classification', $any(value($event)))"
                  >
                    @for (c of classes; track c.value) {
                      <option [value]="c.value" [selected]="f.classification === c.value">
                        {{ c.label }}
                      </option>
                    }
                  </select>
                </label>
              </div>
              @if (f.kind === 'select' || f.kind === 'multi') {
                <label class="f">
                  <span>Lựa chọn (mỗi dòng: mã | nhãn)</span>
                  <textarea
                    class="native mono"
                    rows="4"
                    [value]="optionsText(f)"
                    [disabled]="!editable()"
                    (change)="setOptions($event)"
                  ></textarea>
                </label>
              }
              <label class="f">
                <span>Quy tắc kiểm tra</span>
                <textarea
                  class="native mono code"
                  rows="2"
                  data-testid="p-cel"
                  [value]="f.cel"
                  [attr.aria-invalid]="!!celError()"
                  [disabled]="!editable()"
                  (input)="set('cel', value($event))"
                ></textarea>
                <small class="muted"
                  >Dùng self cho giá trị trường, form.&lt;khoá&gt; cho trường khác.</small
                >
                @if (celError(); as ce) {
                  <small class="err" data-testid="cel-error">{{ ce }}</small>
                }
              </label>
              <label class="f">
                <span>Hiển thị khi</span>
                <textarea
                  class="native mono code"
                  rows="2"
                  data-testid="p-show"
                  [value]="f.showWhen"
                  [attr.aria-invalid]="!!showError()"
                  [disabled]="!editable()"
                  (input)="set('showWhen', value($event))"
                ></textarea>
                @if (showError(); as se) {
                  <small class="err">{{ se }}</small>
                }
              </label>
              <label class="f">
                <span>Thông báo lỗi</span>
                <input
                  class="native"
                  data-testid="p-message"
                  maxlength="200"
                  [value]="f.message"
                  [disabled]="!editable()"
                  (input)="set('message', value($event))"
                />
              </label>
            </section>
          }
          <section class="card">
            <h2>Lược đồ dữ liệu sinh ra</h2>
            <pre class="schema" data-testid="schema" tabindex="0">{{ schemaText() }}</pre>
            <div class="chips" data-testid="test-summary" aria-live="polite">
              @if (testRun(); as r) {
                <span class="chip" [class.bad]="!r.parity || r.passed !== r.total">
                  Bộ test {{ r.passed }}/{{ r.total }} ·
                  {{ r.parity ? 'trình duyệt và máy chủ cùng kết quả' : 'có ca khác kết quả' }}
                </span>
                @if (r.renderMs50Fields !== null && r.renderMs50Fields !== undefined) {
                  <span class="chip" [class.bad]="r.renderMs50Fields > 50">
                    Dựng 50 trường: {{ ms(r.renderMs50Fields) }} mili giây
                  </span>
                }
              } @else {
                <span class="muted"
                  >Chưa chạy bộ test cho nội dung hiện tại ({{ tests().length }} ca).</span
                >
              }
            </div>
            @if (testRun(); as r) {
              <ul class="cases" data-testid="cases">
                @for (c of r.cases; track c.caseId) {
                  <li [class.bad]="!c.match" [attr.data-case]="c.caseId">
                    @if (c.match) {
                      {{ c.caseId }}: {{ c.serverValid ? 'hợp lệ' : 'không hợp lệ' }} ở cả hai nơi.
                    } @else {
                      Ca test {{ c.caseId }} cho kết quả khác nhau giữa trình duyệt và máy chủ.
                    }
                  </li>
                }
              </ul>
            }
          </section>
        </div>
      </div>
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .crumb {
      display: flex;
      gap: 6px;
      color: #4b5563;
      font-size: 0.875rem;
      margin-bottom: 8px;
    }
    .crumb a {
      color: #1d4ed8;
    }
    .head {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
      justify-content: space-between;
      align-items: flex-start;
      margin-bottom: 12px;
    }
    h1 {
      font-size: 1.5rem;
      margin: 0;
    }
    h2 {
      font-size: 1.05rem;
      margin: 0 0 10px;
    }
    .mt {
      margin-top: 16px;
    }
    .sub,
    .hint,
    .muted {
      color: #4b5563;
    }
    .sub {
      margin: 4px 0 0;
      max-width: 90ch;
    }
    .small {
      font-size: 0.85rem;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .grid {
      display: grid;
      grid-template-columns: minmax(200px, 3fr) minmax(0, 5fr) minmax(0, 5fr);
      gap: 16px;
      align-items: start;
    }
    @media (max-width: 1200px) {
      .grid {
        grid-template-columns: 1fr;
      }
    }
    .col {
      display: grid;
      gap: 16px;
    }
    .card {
      background: #fff;
      border: 1px solid #e5e7eb;
      border-radius: 12px;
      padding: 16px;
      min-width: 0;
    }
    .palette,
    .fields,
    .cases {
      list-style: none;
      padding: 0;
      margin: 0;
      display: grid;
      gap: 6px;
    }
    .palette button {
      width: 100%;
      text-align: left;
      border: 1px solid #cbd5e1;
      border-radius: 999px;
      background: #fff;
      padding: 8px 14px;
      font: inherit;
      cursor: pointer;
      min-height: 40px;
    }
    .fields li {
      display: flex;
      gap: 6px;
      align-items: center;
      border: 1px solid #e5e7eb;
      border-radius: 8px;
      padding: 4px;
    }
    .fields li.on {
      border-color: #b4001f;
      background: #fff1f2;
    }
    .pick {
      flex: 1;
      text-align: left;
      background: none;
      border: 0;
      font: inherit;
      cursor: pointer;
      padding: 6px;
      display: grid;
    }
    .pick small {
      color: #4b5563;
    }
    .row-act {
      display: inline-flex;
      gap: 2px;
    }
    .icon {
      min-width: 32px;
      min-height: 32px;
      border: 1px solid #cbd5e1;
      background: #fff;
      border-radius: 6px;
      cursor: pointer;
    }
    .icon:disabled {
      opacity: 0.45;
      cursor: default;
    }
    .ph {
      border: 1px dashed #94a3b8;
      border-radius: 6px;
      padding: 8px 12px;
      color: #4b5563;
    }
    .two {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 12px;
      align-items: end;
    }
    .f {
      display: flex;
      flex-direction: column;
      gap: 4px;
      margin-bottom: 10px;
    }
    .f > span {
      font-weight: 500;
    }
    .check {
      display: inline-flex;
      gap: 8px;
      align-items: center;
      margin-bottom: 18px;
    }
    .native {
      padding: 6px 10px;
      border: 1px solid #94a3b8;
      border-radius: 6px;
      font: inherit;
      min-height: 40px;
      background: #fff;
    }
    .mono {
      font-family: ui-monospace, Menlo, Consolas, monospace;
    }
    .code {
      background: #0f172a;
      color: #f8fafc;
    }
    .code:disabled {
      opacity: 0.85;
    }
    .schema {
      background: #0f172a;
      color: #e2e8f0;
      padding: 12px;
      border-radius: 8px;
      font-size: 0.8rem;
      max-height: 280px;
      overflow: auto;
      white-space: pre-wrap;
      margin: 0 0 10px;
    }
    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
      margin-bottom: 8px;
    }
    .chip {
      padding: 4px 10px;
      border-radius: 999px;
      background: #dcfce7;
      color: #14532d;
    }
    .chip.bad,
    .cases li.bad {
      background: #fef2f2;
      color: #991b1b;
    }
    .cases li {
      padding: 4px 8px;
      border-radius: 6px;
      background: #f8fafc;
    }
    .notice {
      background: #eff6ff;
      color: #1e3a8a;
      border-left: 4px solid #1d4ed8;
      padding: 8px 12px;
      border-radius: 6px;
    }
    .warn {
      background: #fffbeb;
      color: #78350f;
      padding: 8px 12px;
      border-radius: 6px;
    }
    .err {
      color: #b4001f;
    }
  `,
})
export class FormBuilderPage {
  /** Tham số tuyến `bieu-mau/:formKey/:formVersion`. */
  readonly formKey = input.required<string>();
  readonly formVersion = input.required<string>();

  private readonly api = inject(UiPackApi);
  private readonly access = inject(STUDIO_ACCESS);
  protected readonly kinds = FIELD_KINDS;
  protected readonly classes = CLASSIFICATIONS;
  protected readonly kind = kindLabel;
  protected readonly statusLabel = UI_FORM_STATUS_LABEL;
  protected readonly vlabel = uiVersionLabel;
  protected readonly busy = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly form = rxResource({
    params: () => ({ key: this.formKey(), v: this.formVersion() }),
    stream: ({ params }) => this.api.form(params),
  });
  private readonly loaded = computed(() => {
    const f = this.form.value();
    return f ? fromVersion(f) : { fields: [], tests: [] };
  });
  protected readonly fields = linkedSignal<BuilderField[]>(() => this.loaded().fields);
  protected readonly tests = linkedSignal<BuilderTest[]>(() => this.loaded().tests);
  protected readonly selectedKey = linkedSignal<string | null>(
    () => this.loaded().fields[0]?.key ?? null,
  );
  protected readonly selected = computed(
    () => this.fields().find((f) => f.key === this.selectedKey()) ?? null,
  );
  /** Kết quả bộ test của nội dung đang hiện; sửa trường thì kết quả cũ không còn giá trị. */
  protected readonly testRun = linkedSignal<UiFormTestRunResult | null>(() => {
    this.fields();
    this.tests();
    return untracked(() => (this.dirty() ? null : (this.form.value()?.lastTestRun ?? null)));
  });

  protected readonly editable = computed(
    () => this.form.value()?.status === 'NHAP' && this.can('SCR-1802.dung'),
  );
  protected readonly title = computed(() => FORM_TITLE[this.formKey()] ?? 'Biểu mẫu của đơn vị');
  protected readonly dirty = computed(() => {
    const f = this.form.value();
    if (!f) return false;
    const now = toUpdate(f, this.fields(), this.tests());
    const was = toUpdate(f, this.loaded().fields, this.loaded().tests);
    return JSON.stringify(now) !== JSON.stringify(was);
  });

  protected readonly keyError = computed(() => {
    const f = this.selected();
    if (!f) return null;
    if (!isValidKey(f.key)) return 'Khoá chỉ gồm chữ thường không dấu, số và dấu gạch dưới.';
    return this.fields().filter((x) => x.key === f.key).length > 1 ? 'Khoá đã được dùng.' : null;
  });
  protected readonly celError = computed(() => this.exprError(this.selected()?.cel ?? ''));
  protected readonly showError = computed(() => this.exprError(this.selected()?.showWhen ?? ''));
  /** Lỗi ở bất kỳ trường nào (MSG-18-06, khoá) thì chưa cho lưu. */
  protected readonly localError = computed(() => {
    const keys = this.fields().map((f) => f.key);
    if (keys.some((k) => !isValidKey(k)) || new Set(keys).size !== keys.length) return true;
    return this.fields().some((f) => this.exprError(f.cel) || this.exprError(f.showWhen));
  });

  protected readonly model = signal<Record<string, unknown>>({});
  private readonly shownText = computed(() =>
    [...visibleKeys(this.fields(), this.model())].join('|'),
  );
  private readonly shown = computed(() => new Set(this.shownText().split('|')));
  protected readonly schema = computed(() => previewSchema(this.fields(), this.shown()));
  /** Quy tắc của bản xem trước; biểu thức đang sai cú pháp chưa đưa vào để không báo lỗi giả. */
  protected readonly rules = computed(() =>
    previewRules(
      this.fields().filter((f) => !this.exprError(f.cel) && !this.exprError(f.showWhen)),
      this.shown(),
    ),
  );
  /** Giá trị đưa vào bản xem trước chỉ khi lược đồ đổi, để không dựng lại biểu mẫu mỗi lần gõ. */
  protected readonly seed = linkedSignal(() => {
    this.schema();
    return untracked(() => this.model());
  });
  protected readonly placeholders = computed(() =>
    this.fields().filter(
      (f) => this.shown().has(f.key) && !FIELD_KINDS.find((k) => k.kind === f.kind)?.preview,
    ),
  );
  protected readonly schemaText = computed(() => {
    const f = this.form.value();
    return f
      ? JSON.stringify(toUpdate(f, this.fields(), []).schemaJson['properties'], null, 2)
      : '';
  });
  protected readonly testsPassed = computed(() => {
    const r = this.testRun();
    return !!r && r.parity && r.passed === r.total;
  });

  /** Bản UI Pack đang soạn để gắn biểu mẫu (SCR-1802 "Đưa vào UI Pack"). */
  // skeleton-ok: chỉ quyết định có hiện nút "Đưa vào giao diện" trên thanh thao tác, không có vùng dữ liệu riêng
  private readonly packs = rxResource({ stream: () => this.api.packs() });
  // skeleton-ok: như trên, chỉ tìm bản nháp để gắn vào nút thao tác
  private readonly packVersions = rxResource({
    params: () => this.packs.value()?.items[0]?.id,
    stream: ({ params }) => this.api.versions(params),
  });
  protected readonly packDraft = computed(() =>
    this.packVersions.value()?.items.find((v) => v.status === 'NHAP'),
  );

  protected can(key: Parameters<typeof this.access.can>[0]) {
    return this.access.can(key);
  }

  private exprError(expr: string): string | null {
    if (!expr.trim()) return null;
    return celSyntaxError(expr)
      ? `Biểu thức kiểm tra không hợp lệ: ${celSyntaxError(expr)}.`
      : null;
  }

  protected value(e: Event) {
    return (e.target as HTMLInputElement).value;
  }

  protected checked(e: Event) {
    return (e.target as HTMLInputElement).checked;
  }

  protected ms(n: number) {
    return n.toLocaleString('vi-VN', { maximumFractionDigits: 1 });
  }

  protected optionsText(f: BuilderField) {
    return f.options.map((o) => `${o.value} | ${o.label}`).join('\n');
  }

  private patchSelected(fn: (f: BuilderField) => BuilderField) {
    const key = this.selectedKey();
    this.fields.update((list) => list.map((f) => (f.key === key ? fn(f) : f)));
    this.notice.set(null);
  }

  protected set<K extends keyof BuilderField>(prop: K, v: BuilderField[K]) {
    this.patchSelected((f) => {
      const next = { ...f, [prop]: v };
      if (prop === 'kind' && v === 'textarea') next.classification = 'C2';
      return next;
    });
  }

  protected setKey(e: Event) {
    const key = this.value(e).trim();
    const old = this.selectedKey();
    this.patchSelected((f) => ({ ...f, key }));
    this.selectedKey.set(key);
    if (old) {
      this.tests.update((ts) =>
        ts.map((t) => {
          if (!(old in t.input)) return t;
          const { [old]: v, ...rest } = t.input;
          return { ...t, input: { ...rest, [key]: v } };
        }),
      );
    }
  }

  protected setOptions(e: Event) {
    const options = this.value(e)
      .split('\n')
      .map((l) => l.split('|').map((x) => x.trim()))
      .filter(([v]) => !!v)
      .map(([v, l]) => ({ value: v, label: l || v }));
    this.patchSelected((f) => ({ ...f, options }));
  }

  protected add(kind: FieldKind) {
    const f = newField(
      kind,
      this.fields().map((x) => x.key),
    );
    const at = this.fields().findIndex((x) => x.key === this.selectedKey());
    this.fields.update((list) => {
      const next = [...list];
      next.splice(at < 0 ? next.length : at + 1, 0, f);
      return next;
    });
    this.selectedKey.set(f.key);
  }

  protected move(i: number, d: number) {
    this.fields.update((list) => {
      const next = [...list];
      const [x] = next.splice(i, 1);
      next.splice(i + d, 0, x);
      return next;
    });
  }

  protected remove(f: BuilderField) {
    if (f.scored) return;
    this.fields.update((list) => list.filter((x) => x.key !== f.key));
    if (this.selectedKey() === f.key) this.selectedKey.set(this.fields()[0]?.key ?? null);
  }

  protected addTest() {
    const n = this.tests().length + 1;
    let id = `TC-${String(n).padStart(2, '0')}`;
    for (let i = n + 1; this.tests().some((t) => t.caseId === id); i++) {
      id = `TC-${String(i).padStart(2, '0')}`;
    }
    const input = Object.fromEntries(
      Object.entries(this.model()).filter(([, v]) => v !== '' && v !== null && v !== undefined),
    );
    this.tests.update((ts) => [...ts, { caseId: id, input }]);
    const errs = browserErrors(this.fields(), input);
    this.notice.set(
      `Đã thêm ca ${id} (${errs.length ? 'dữ liệu không hợp lệ' : 'dữ liệu hợp lệ'}). Lưu nháp để giữ ca test.`,
    );
  }

  protected runTests() {
    const f = this.form.value();
    if (!f) return;
    this.busy.set(true);
    this.error.set(null);
    this.api
      .runFormTests(
        { key: f.formKey, v: f.version },
        {
          cases: testInputs(this.fields(), this.tests()),
          renderMs50Fields: measure50(this.fields()),
        },
      )
      .subscribe({
        next: (r) => {
          this.busy.set(false);
          this.testRun.set(r);
        },
        error: (e) => {
          this.busy.set(false);
          this.error.set(errorText(e, 'Không chạy được bộ test.'));
        },
      });
  }

  protected save() {
    const f = this.form.value();
    if (!f) return;
    this.busy.set(true);
    this.error.set(null);
    this.api
      .saveForm(
        { key: f.formKey, v: f.version },
        f.rowVersion,
        toUpdate(f, this.fields(), this.tests()),
      )
      .subscribe({
        next: (saved: UiFormVersion) => {
          this.busy.set(false);
          this.form.set(saved);
          this.notice.set('Đã lưu bản nháp. Chạy lại bộ test trước khi đưa vào giao diện.');
        },
        error: (e) => {
          this.busy.set(false);
          const code = problemCode(e);
          this.error.set(
            code === 'UI-409-SCORED-FIELD' || code === 'UI-422-CEL-INVALID'
              ? errorText(e, 'Không lưu được.')
              : errorText(e, 'Không lưu được bản nháp.'),
          );
        },
      });
  }

  protected attach() {
    const f = this.form.value();
    const pack = this.packs.value()?.items[0];
    const pd = this.packDraft();
    if (!f || !pack || !pd) return;
    this.busy.set(true);
    this.error.set(null);
    this.api
      .attachForm({ id: pack.id, v: pd.version }, pd.rowVersion, {
        formKey: f.formKey,
        formVersion: f.version,
      })
      .subscribe({
        next: (v) => {
          this.busy.set(false);
          this.packVersions.reload();
          this.notice.set(
            `Đã đưa biểu mẫu bản ${f.version} vào giao diện bản ${uiVersionLabel(v.version)}.`,
          );
        },
        error: (e) => {
          this.busy.set(false);
          this.error.set(errorText(e, 'Chưa đưa được biểu mẫu vào giao diện.'));
        },
      });
  }
}
