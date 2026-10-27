/*
 * SCR-1301 "Yêu cầu mở lại kỳ", phần lập yêu cầu (QT-13 bước 4; đường vào: nút "Tạo yêu cầu mở lại kỳ" ở
 * danh sách, ở SCR-1202 khi kết luận khiếu nại có điều chỉnh và ở SCR-1401 khi cảnh báo có vi phạm; nơi
 * gọi truyền sẵn `?periodId=&basisKind=&basisCode=&basisId=&profileId=&auditKind=`).
 * Trường: 2 kỳ (chỉ kỳ `DA_KHOA`); 3 loại hậu kiểm; 4 phạm vi (hồ sơ hoặc đơn vị, bảng và trường được sửa;
 *   toàn kỳ chỉ khi căn cứ là quyết định hậu kiểm toàn diện, BR-13-05). Hồ sơ chọn từ danh sách hồ sơ
 *   của kỳ đã chọn (`GET /appraisals/profiles?period=`, `listAppraisalProfiles`), hiện họ tên và đơn vị;
 *   `scopeIds` và `scopeDetail.items[].profileId` là mã hồ sơ dạng `Uuid` theo hợp đồng (HT-34: không
 *   nhận chuỗi tự gõ); 5 thời hạn mở 1–10 ngày làm việc,
 *   mặc định 3; 6 lý do 50–4.000 ký tự; 7 căn cứ. Mã yêu cầu (trường 1) do máy chủ sinh.
 * Chức năng: Tạo yêu cầu (POST /oversight/reopen-requests, AAL2, EVT-21), xong thì sang trang chi tiết để
 *   tính thử ảnh hưởng và theo dõi hai lượt duyệt. Lỗi 409 kỳ chưa khoá, trùng phạm vi; 422 theo trường.
 * Quyền (wiki/15a): SCR-1301.tao (AC-07 trong phạm vi Cd, AC-10 Tn).
 */
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { OrgApi, fieldErrors, toProblem } from '@vt/api-client';
import {
  AppraisalApi,
  AppraisalProfileStatus,
  PeriodApi,
  REOPEN_AUDIT_KIND_LABEL,
  REOPEN_BASIS_LABEL,
  REOPEN_LIMITS,
  REOPEN_SCOPE_LABEL,
  ReopenApi,
  ReopenAuditKind,
  ReopenBasisKind,
  ReopenRequestInput,
  ReopenScope,
  ReopenScopeItem,
} from '@vt/api-client/domains';
import { ThemeService } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { CheckboxModule } from 'primeng/checkbox';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { TextareaModule } from 'primeng/textarea';
import { map } from 'rxjs';
import { OVERSIGHT_CAN } from '../../oversight-permissions';
import { errorText } from '../../oversight-ui';
import { OVERSIGHT_STEP_UP } from '../../step-up';
import { EDITABLE_TABLES } from './reopen-ui';

interface DataRow {
  table: string;
  fields: string[];
}

/** Trạng thái hồ sơ có thể gặp trong kỳ đã khoá (hồ sơ đã chốt, đang khiếu nại). */
const LOCKED_PERIOD_STATUSES: AppraisalProfileStatus[] = [
  'DA_QUYET_DINH',
  'DA_KY_SO',
  'DA_KHOA',
  'KHIEU_NAI',
];

const profileLabel = (p: { person: { name?: string }; orgUnit?: { name?: string } }) =>
  [p.person.name, p.orgUnit?.name].filter(Boolean).join(' · ') || 'Hồ sơ đánh giá';

const options = <K extends string>(labels: Record<K, string>) =>
  (Object.keys(labels) as K[]).map((value) => ({ value, label: labels[value] }));

@Component({
  selector: 'vt-reopen-create-page',
  imports: [
    FormsModule,
    RouterLink,
    ButtonModule,
    CheckboxModule,
    InputTextModule,
    MessageModule,
    SelectModule,
    TextareaModule,
  ],
  template: `
    <p class="crumb"><a routerLink="..">Hậu kiểm, mở lại kỳ</a></p>
    <h1>Tạo yêu cầu mở lại kỳ</h1>
    @if (!simple()) {
      <p class="muted">
        Yêu cầu mở lại chỉ phạm vi được nêu, có thời hạn, và phải được hai người có thẩm quyền khác
        nhau phê duyệt. Hệ thống tự chỉ định người duyệt sau khi tạo.
      </p>
    }
    @if (!can('SCR-1301.tao')) {
      <p-message severity="warn">Đồng chí không có quyền tạo yêu cầu mở lại kỳ.</p-message>
    } @else {
      <form class="form" (ngSubmit)="submit()" novalidate>
        @if (error(); as e) {
          <p-message severity="error" role="alert">{{ e }}</p-message>
        }
        <fieldset>
          <legend>Kỳ và căn cứ</legend>
          <div class="grid">
            <div class="f">
              <label for="rq-period">Kỳ đã khoá</label>
              <p-select
                inputId="rq-period"
                [options]="periods()"
                [loading]="periodsRes.isLoading()"
                optionLabel="label"
                optionValue="value"
                [ngModel]="periodId()"
                (ngModelChange)="setPeriod($event)"
                name="periodId"
                placeholder="Chọn kỳ"
                [attr.aria-describedby]="errs()['periodId'] ? 'e-period' : null"
              />
              @if (errs()['periodId']; as m) {
                <small id="e-period" class="err">{{ m }}</small>
              }
            </div>
            <div class="f">
              <label for="rq-kind">Loại hậu kiểm</label>
              <p-select
                inputId="rq-kind"
                [options]="auditKinds"
                optionLabel="label"
                optionValue="value"
                [ngModel]="auditKind()"
                (ngModelChange)="auditKind.set($event)"
                name="auditKind"
              />
            </div>
            <div class="f">
              <label for="rq-basis-kind">Loại căn cứ</label>
              <p-select
                inputId="rq-basis-kind"
                [options]="basisKinds"
                optionLabel="label"
                optionValue="value"
                [ngModel]="basisKind()"
                (ngModelChange)="basisKind.set($event)"
                name="basisKind"
              />
            </div>
            <div class="f">
              <label for="rq-basis-code">Mã hoặc số căn cứ</label>
              <input
                pInputText
                id="rq-basis-code"
                name="basisCode"
                maxlength="64"
                placeholder="Ví dụ KN-2026-0031"
                [ngModel]="basisCode()"
                (ngModelChange)="basisCode.set($event)"
                [attr.aria-describedby]="errs()['basis'] ? 'e-basis' : null"
              />
              @if (errs()['basis']; as m) {
                <small id="e-basis" class="err">{{ m }}</small>
              }
            </div>
          </div>
        </fieldset>

        <fieldset>
          <legend>Phạm vi mở lại</legend>
          <div class="grid">
            <div class="f">
              <label for="rq-scope">Phạm vi</label>
              <p-select
                inputId="rq-scope"
                [options]="scopes"
                optionLabel="label"
                optionValue="value"
                [ngModel]="scope()"
                (ngModelChange)="setScope($event)"
                name="scope"
                [attr.aria-describedby]="errs()['scope'] ? 'e-scope' : null"
              />
              @if (errs()['scope']; as m) {
                <small id="e-scope" class="err">{{ m }}</small>
              }
            </div>
            @if (scope() === 'HO_SO') {
              <div class="f wide">
                <label for="rq-profile" id="rq-profile-l">Hồ sơ đánh giá</label>
                <!-- tên truy cập là nhãn trường, không phải chữ gợi ý đang hiện trong ô -->
                <p-select
                  inputId="rq-profile"
                  ariaLabelledBy="rq-profile-l"
                  [options]="profileOptions()"
                  [loading]="profilesRes.isLoading()"
                  [disabled]="!periodId()"
                  [filter]="true"
                  filterBy="label"
                  [resetFilterOnHide]="true"
                  (onFilter)="searchProfiles($event.filter)"
                  optionLabel="label"
                  optionValue="value"
                  [ngModel]="null"
                  (ngModelChange)="addTarget($event)"
                  name="profile"
                  [placeholder]="periodId() ? 'Chọn hồ sơ để thêm' : 'Chọn kỳ trước'"
                  emptyMessage="Kỳ này không có hồ sơ đánh giá nào trong phạm vi của đồng chí."
                  emptyFilterMessage="Không tìm thấy hồ sơ phù hợp."
                  [attr.aria-describedby]="'h-profile'"
                />
                <small id="h-profile" class="muted">{{
                  periodId()
                    ? 'Tìm theo họ tên người được đánh giá; mỗi lần chọn thêm một hồ sơ.'
                    : 'Chọn kỳ đã khoá trước để tải danh sách hồ sơ của kỳ.'
                }}</small>
                @if (profilesError(); as m) {
                  <small class="err" role="alert">{{ m }}</small>
                }
              </div>
            } @else if (scope() === 'DON_VI') {
              <div class="f wide">
                <label for="rq-unit">Đơn vị</label>
                <p-select
                  inputId="rq-unit"
                  [options]="units()"
                  [loading]="unitsRes.isLoading()"
                  optionLabel="label"
                  optionValue="value"
                  [ngModel]="null"
                  (ngModelChange)="addTarget($event)"
                  name="unit"
                  placeholder="Chọn đơn vị để thêm"
                />
              </div>
            }
          </div>
          @if (scope() !== 'TOAN_KY') {
            <ul class="chips" aria-label="Đối tượng trong phạm vi">
              @for (t of targets(); track t) {
                <li>
                  <span>{{ targetName(t) }}</span>
                  <p-button
                    icon="pi pi-times"
                    [text]="true"
                    size="small"
                    severity="contrast"
                    [ariaLabel]="'Bỏ ' + targetName(t)"
                    (onClick)="removeTarget(t)"
                  />
                </li>
              } @empty {
                <li class="muted">Chưa có đối tượng nào.</li>
              }
            </ul>
            @if (errs()['scopeIds']; as m) {
              <small class="err">{{ m }}</small>
            }
          }

          <h2 class="h3" id="data-h">Dữ liệu được phép sửa</h2>
          @for (row of dataRows(); track $index; let i = $index) {
            <div class="data-row" role="group" [attr.aria-label]="'Nhóm dữ liệu ' + (i + 1)">
              <div class="f">
                <label [for]="'rq-table-' + i">Nhóm dữ liệu</label>
                <p-select
                  [inputId]="'rq-table-' + i"
                  [options]="tables"
                  optionLabel="label"
                  optionValue="table"
                  [ngModel]="row.table"
                  (ngModelChange)="setTable(i, $event)"
                  [name]="'table' + i"
                />
              </div>
              <fieldset class="fields">
                <legend class="small">Nội dung được phép sửa</legend>
                @for (f of fieldsOf(row.table); track f.key) {
                  <span class="check">
                    <p-checkbox
                      [inputId]="'rq-f-' + i + '-' + f.key"
                      [binary]="true"
                      [ngModel]="row.fields.includes(f.key)"
                      (ngModelChange)="toggleField(i, f.key, $event)"
                      [name]="'f' + i + f.key"
                    />
                    <label [for]="'rq-f-' + i + '-' + f.key">{{ f.label }}</label>
                  </span>
                }
              </fieldset>
              @if (dataRows().length > 1) {
                <p-button
                  icon="pi pi-trash"
                  [text]="true"
                  severity="contrast"
                  [ariaLabel]="'Bỏ nhóm dữ liệu ' + (i + 1)"
                  (onClick)="removeRow(i)"
                />
              }
            </div>
          }
          <p-button
            label="Thêm nhóm dữ liệu"
            icon="pi pi-plus"
            [text]="true"
            size="small"
            (onClick)="addRow()"
          />
          @if (errs()['scopeDetail']; as m) {
            <small class="err block">{{ m }}</small>
          }
        </fieldset>

        <fieldset>
          <legend>Thời hạn và lý do</legend>
          <div class="grid">
            <div class="f">
              <label for="rq-days">Thời hạn mở (ngày làm việc, 1–10)</label>
              <input
                pInputText
                type="number"
                id="rq-days"
                name="days"
                min="1"
                max="10"
                [ngModel]="days()"
                (ngModelChange)="days.set(+$event)"
                [attr.aria-describedby]="errs()['openWorkingDays'] ? 'e-days' : null"
              />
              @if (errs()['openWorkingDays']; as m) {
                <small id="e-days" class="err">{{ m }}</small>
              }
            </div>
          </div>
          <div class="f">
            <label for="rq-reason">Lý do mở lại (50–4.000 ký tự)</label>
            <textarea
              pTextarea
              id="rq-reason"
              name="reason"
              rows="5"
              [maxlength]="limits.reasonMax"
              [ngModel]="reason()"
              (ngModelChange)="reason.set($event)"
              aria-describedby="h-reason"
            ></textarea>
            <small id="h-reason" [class.err]="!!errs()['reason']">
              {{ errs()['reason'] || reason().trim().length + ' / tối thiểu 50 ký tự' }}
            </small>
          </div>
        </fieldset>

        <div class="actions">
          <a routerLink=".." class="p-button p-button-contrast p-button-outlined">Huỷ</a>
          <p-button
            type="submit"
            label="Tạo yêu cầu"
            [loading]="busy()"
            [disabled]="busy() || !ready()"
          />
        </div>
      </form>
    }
  `,
  styles: `
    .crumb {
      margin: 0 0 4px;
    }
    h1 {
      font-size: 1.4rem;
      margin: 4px 0;
    }
    .muted {
      color: #4b5563;
    }
    .small {
      font-size: 0.875em;
    }
    .block {
      display: block;
    }
    .mono {
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 0.875em;
    }
    .form {
      max-width: 960px;
      display: grid;
      gap: 12px;
    }
    fieldset {
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      padding: 12px 16px;
      margin: 0;
    }
    legend {
      font-weight: 600;
      padding: 0 4px;
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
      gap: 12px;
    }
    .f {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .f.wide {
      grid-column: span 2;
    }
    label {
      font-weight: 600;
    }
    .err {
      color: #b91c1c;
    }
    .chips {
      list-style: none;
      padding: 0;
      margin: 8px 0;
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
    }
    .chips li {
      display: inline-flex;
      align-items: center;
      gap: 2px;
      padding: 2px 4px 2px 10px;
      border: 1px solid var(--p-content-border-color, #d1d5db);
      border-radius: 16px;
    }
    .h3 {
      font-size: 1rem;
      margin: 12px 0 6px;
    }
    .data-row {
      display: flex;
      gap: 12px;
      align-items: flex-start;
      flex-wrap: wrap;
      margin-bottom: 8px;
    }
    .fields {
      border: 0;
      padding: 0;
      display: flex;
      gap: 6px 16px;
      flex-wrap: wrap;
      max-width: 520px;
    }
    .check {
      display: inline-flex;
      gap: 6px;
      align-items: center;
    }
    .check label {
      font-weight: 400;
    }
    textarea {
      width: 100%;
    }
    .actions {
      display: flex;
      gap: 8px;
      justify-content: flex-end;
    }
    a.p-button {
      text-decoration: none;
    }
  `,
})
export class ReopenCreatePage {
  private readonly api = inject(ReopenApi);
  private readonly periodApi = inject(PeriodApi);
  private readonly orgApi = inject(OrgApi);
  private readonly appraisalApi = inject(AppraisalApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly theme = inject(ThemeService);
  private readonly stepUp = inject(OVERSIGHT_STEP_UP);
  protected readonly can = inject(OVERSIGHT_CAN);
  protected readonly limits = REOPEN_LIMITS;

  private readonly q = toSignal(this.route.queryParamMap.pipe(map((m) => m)), {
    initialValue: this.route.snapshot.queryParamMap,
  });

  protected readonly auditKinds = options(REOPEN_AUDIT_KIND_LABEL);
  protected readonly basisKinds = options(REOPEN_BASIS_LABEL);
  protected readonly scopes = options(REOPEN_SCOPE_LABEL);
  protected readonly tables = EDITABLE_TABLES;

  // giá trị ban đầu lấy từ nơi gọi (SCR-1202, SCR-1401) nếu có
  protected readonly periodId = signal<string | null>(this.q().get('periodId'));
  protected readonly auditKind = signal<ReopenAuditKind>(
    (this.q().get('auditKind') as ReopenAuditKind) ?? 'PHAN_ANH',
  );
  protected readonly basisKind = signal<ReopenBasisKind>(
    (this.q().get('basisKind') as ReopenBasisKind) ?? 'KHIEU_NAI',
  );
  protected readonly basisCode = signal(this.q().get('basisCode') ?? '');
  private readonly basisId = this.q().get('basisId');
  protected readonly scope = signal<ReopenScope>('HO_SO');
  protected readonly targets = signal<string[]>(
    this.q().get('profileId') ? [this.q().get('profileId')!] : [],
  );
  /** Từ khoá tìm hồ sơ phía máy chủ, chỉ dùng khi kỳ có nhiều hồ sơ hơn một trang. */
  private readonly profileQ = signal('');
  private profileQTimer: ReturnType<typeof setTimeout> | undefined;
  /** Tên đã biết của hồ sơ (giữ khi đổi từ khoá tìm), để chip không bao giờ hiện mã hồ sơ. */
  private readonly profileNames = signal<Record<string, string>>({});
  protected readonly dataRows = signal<DataRow[]>([
    { table: 'product.acceptance', fields: ['c_factor'] },
  ]);
  protected readonly days = signal<number>(REOPEN_LIMITS.daysDefault);
  protected readonly reason = signal('');

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly errs = signal<Record<string, string>>({});

  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  protected readonly periodsRes = rxResource({
    stream: () => this.periodApi.list({ status: 'DA_KHOA' }),
  });
  protected readonly periods = computed(() =>
    ((this.periodsRes.hasValue() ? this.periodsRes.value() : undefined) ?? [])
      .filter((p) => p.status === 'DA_KHOA')
      .map((p) => ({ value: p.id, label: p.name })),
  );
  protected readonly profilesRes = rxResource({
    params: () =>
      this.scope() === 'HO_SO' && this.periodId()
        ? { period: this.periodId()!, q: this.profileQ() }
        : undefined,
    stream: ({ params }) =>
      this.appraisalApi.profiles({
        periodId: params.period,
        status: LOCKED_PERIOD_STATUSES,
        ...(params.q ? { q: params.q } : {}),
        limit: 200,
      }),
  });
  private readonly profilesPage = computed(() =>
    this.profilesRes.hasValue() ? this.profilesRes.value() : undefined,
  );
  /** Hồ sơ của kỳ đã chọn: giá trị là mã hồ sơ, nhãn là họ tên và đơn vị (không hiện mã nội bộ). */
  protected readonly profileOptions = computed(() =>
    (this.profilesPage()?.items ?? [])
      .filter((p) => !this.targets().includes(p.id))
      .map((p) => ({ value: p.id, label: profileLabel(p) })),
  );
  protected readonly profilesError = computed(() =>
    this.profilesRes.error()
      ? errorText(this.profilesRes.error(), 'Không tải được danh sách hồ sơ của kỳ.')
      : null,
  );
  protected readonly unitsRes = rxResource({
    params: () => (this.scope() === 'DON_VI' ? true : undefined),
    stream: () => this.orgApi.units('children'),
  });
  protected readonly units = computed(() =>
    ((this.unitsRes.hasValue() ? this.unitsRes.value() : undefined)?.items ?? []).map((u) => ({
      value: u.id,
      label: u.name,
    })),
  );

  protected readonly ready = computed(
    () =>
      !!this.periodId() &&
      !!this.basisCode().trim() &&
      (this.scope() === 'TOAN_KY' || this.targets().length > 0) &&
      this.dataRows().some((r) => r.fields.length) &&
      this.reason().trim().length >= REOPEN_LIMITS.reasonMin &&
      this.days() >= REOPEN_LIMITS.daysMin &&
      this.days() <= REOPEN_LIMITS.daysMax,
  );

  constructor() {
    // nhớ tên hồ sơ đã tải để chip đã chọn vẫn có tên khi danh sách đổi theo từ khoá
    effect(() => {
      const items = this.profilesPage()?.items ?? [];
      if (!items.length) return;
      const names = { ...untracked(this.profileNames) };
      for (const p of items) names[p.id] = profileLabel(p);
      this.profileNames.set(names);
    });
  }

  protected targetName(id: string) {
    const unit = this.units().find((u) => u.value === id);
    if (unit) return unit.label;
    const name = this.profileNames()[id];
    if (name) return name;
    // hồ sơ đến từ khiếu nại hoặc cảnh báo chưa có trong danh sách: gọi theo căn cứ, không hiện mã
    if (this.basisCode().trim()) return `Hồ sơ đánh giá theo ${this.basisCode().trim()}`;
    return this.scope() === 'DON_VI' ? 'Đơn vị đã chọn' : 'Hồ sơ đánh giá đã chọn';
  }
  /** Đổi kỳ thì bỏ các hồ sơ đã chọn của kỳ cũ (hồ sơ thuộc về một kỳ); đơn vị giữ nguyên. */
  protected setPeriod(id: string | null) {
    if (id === this.periodId()) return;
    this.periodId.set(id);
    if (this.scope() === 'HO_SO') this.targets.set([]);
    this.profileQ.set('');
  }
  /** Đổi phạm vi thì bỏ đối tượng đã chọn: mã hồ sơ và mã đơn vị không dùng lẫn cho nhau. */
  protected setScope(scope: ReopenScope) {
    if (scope === this.scope()) return;
    this.scope.set(scope);
    this.targets.set([]);
  }
  /** Tìm phía máy chủ (`q`) chỉ khi trang đầu chưa đủ mọi hồ sơ; còn lại ô chọn tự lọc tại chỗ. */
  protected searchProfiles(term: string | null | undefined) {
    const q = (term ?? '').trim();
    if (!this.profilesPage()?.nextCursor && !this.profileQ()) return;
    clearTimeout(this.profileQTimer);
    this.profileQTimer = setTimeout(() => this.profileQ.set(q), 300);
  }
  protected addTarget(id: string | null) {
    if (id && !this.targets().includes(id)) this.targets.set([...this.targets(), id]);
  }
  protected removeTarget(id: string) {
    this.targets.set(this.targets().filter((x) => x !== id));
  }
  protected fieldsOf(table: string) {
    return EDITABLE_TABLES.find((t) => t.table === table)?.fields ?? [];
  }
  protected setTable(i: number, table: string) {
    this.dataRows.set(this.dataRows().map((r, n) => (n === i ? { table, fields: [] } : r)));
  }
  protected toggleField(i: number, key: string, on: boolean) {
    this.dataRows.set(
      this.dataRows().map((r, n) =>
        n === i ? { ...r, fields: on ? [...r.fields, key] : r.fields.filter((f) => f !== key) } : r,
      ),
    );
  }
  protected addRow() {
    this.dataRows.set([...this.dataRows(), { table: 'appraisal.self_assessment', fields: [] }]);
  }
  protected removeRow(i: number) {
    this.dataRows.set(this.dataRows().filter((_, n) => n !== i));
  }

  /** `scope_json`: mỗi đối tượng × mỗi bảng một mục; toàn kỳ thì chỉ bảng và trường. */
  private scopeItems(): ReopenScopeItem[] {
    const rows = this.dataRows().filter((r) => r.fields.length);
    if (this.scope() === 'TOAN_KY') return rows.map((r) => ({ table: r.table, fields: r.fields }));
    const key = this.scope() === 'HO_SO' ? 'profileId' : 'unitId';
    return this.targets().flatMap((id) =>
      rows.map((r) => ({ [key]: id, table: r.table, fields: r.fields })),
    );
  }

  protected async submit() {
    if (!this.ready() || this.busy()) return;
    this.error.set(null);
    this.errs.set({});
    if (!(await this.stepUp('Cần xác thực bổ sung để tạo yêu cầu mở lại kỳ.'))) return;
    const input: ReopenRequestInput = {
      periodId: this.periodId()!,
      auditKind: this.auditKind(),
      scope: this.scope(),
      scopeIds: this.scope() === 'TOAN_KY' ? [] : this.targets(),
      scopeDetail: { items: this.scopeItems() },
      openWorkingDays: this.days(),
      reason: this.reason().trim(),
      basis: {
        kind: this.basisKind(),
        code: this.basisCode().trim(),
        ...(this.basisId ? { id: this.basisId } : {}),
      },
    };
    this.busy.set(true);
    this.api.create(input).subscribe({
      next: (r) => {
        this.busy.set(false);
        void this.router.navigate(['..', r.id], { relativeTo: this.route });
      },
      error: (e) => {
        this.busy.set(false);
        const p = toProblem(e);
        this.errs.set(fieldErrors(p));
        this.error.set(errorText(e));
      },
    });
  }
}
