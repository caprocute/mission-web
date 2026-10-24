import {
  Component,
  computed,
  effect,
  inject,
  input,
  model,
  output,
  signal,
  untracked,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { OrgTreeApi, UserAdminApi } from '@vt/api-client/domains';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputNumberModule } from 'primeng/inputnumber';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { map } from 'rxjs';
import { ACCESS_STEP_UP, isoDay } from '../access/access-ui';
import { OrgTreeView } from '../org/org-tree-view';
import { PAGE_STYLES, adminError } from './users-ui';

type Kind = 'KIEM_NHIEM' | 'BIET_PHAI';

/**
 * Hộp "Thêm kiêm nhiệm" của SCR-0109 (POST `/org/assignments`, BS-04): đơn vị chọn trên cây, chức danh,
 * hình thức (kiêm nhiệm hoặc biệt phái), tỷ trọng công việc, ngày bắt đầu. Cần xác thực bước lên.
 */
@Component({
  selector: 'vt-assignment-dialog',
  imports: [
    FormsModule,
    ButtonModule,
    DialogModule,
    InputNumberModule,
    InputTextModule,
    MessageModule,
    SelectModule,
    OrgTreeView,
  ],
  template: `
    <p-dialog
      header="Thêm kiêm nhiệm"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(760px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      <p class="lead">
        Giao thêm đơn vị công tác cho đồng chí <strong>{{ personName() }}</strong
        >.
      </p>
      <div class="two">
        <section aria-labelledby="as-unit">
          <h3 id="as-unit" class="h3">Đơn vị</h3>
          <vt-org-tree-view [time]="{}" [simple]="true" [(selected)]="unitId" />
          @if (fe()['orgUnitId']) {
            <small class="err">{{ fe()['orgUnitId'] }}</small>
          }
        </section>
        <div class="fields">
          <p class="muted">
            Đơn vị đã chọn: <strong>{{ unitName() || 'chưa chọn' }}</strong>
          </p>
          <div>
            <label class="f" for="as-title">Chức danh</label>
            <p-select
              inputId="as-title"
              styleClass="full"
              [options]="titles()"
              optionLabel="label"
              optionValue="value"
              [filter]="true"
              filterBy="label"
              placeholder="Chọn chức danh"
              [ngModel]="jobTitleId()"
              (ngModelChange)="jobTitleId.set($event)"
            />
          </div>
          <div>
            <label class="f" for="as-kind">Hình thức</label>
            <p-select
              inputId="as-kind"
              styleClass="full"
              [options]="kinds"
              optionLabel="label"
              optionValue="value"
              [ngModel]="kind()"
              (ngModelChange)="kind.set($event)"
            />
          </div>
          <div>
            <label class="f" for="as-ratio">Tỷ trọng công việc (%)</label>
            <p-inputNumber
              inputId="as-ratio"
              styleClass="full"
              [min]="1"
              [max]="100"
              [ngModel]="ratio()"
              (ngModelChange)="ratio.set($event)"
            />
            @if (fe()['weightRatio']) {
              <small class="err">{{ fe()['weightRatio'] }}</small>
            }
          </div>
          <div>
            <label class="f" for="as-from">Từ ngày</label>
            <input
              id="as-from"
              pInputText
              type="date"
              class="full"
              [ngModel]="from()"
              (ngModelChange)="from.set($event)"
            />
          </div>
        </div>
      </div>
      @if (error(); as e) {
        <p-message severity="error" [text]="e" styleClass="msg" role="alert" />
      }
      <ng-template #footer>
        <p-button label="Huỷ" severity="secondary" [text]="true" (onClick)="visible.set(false)" />
        <p-button
          label="Thêm"
          icon="pi pi-plus"
          [disabled]="!ready()"
          [loading]="saving()"
          (onClick)="save()"
        />
      </ng-template>
    </p-dialog>
  `,
  styles: [
    PAGE_STYLES,
    `
      .lead {
        margin: 0 0 12px;
      }
      .two {
        display: grid;
        grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
        gap: 16px;
      }
      @media (max-width: 720px) {
        .two {
          grid-template-columns: 1fr;
        }
      }
      .fields {
        display: flex;
        flex-direction: column;
        gap: 12px;
      }
      .h3 {
        font-size: 1rem;
        margin: 0 0 8px;
      }
      .muted {
        color: #4b5563;
        margin: 0;
      }
      .err {
        color: #9b1c1c;
        display: block;
      }
      :host ::ng-deep .msg {
        margin-top: 12px;
        width: 100%;
      }
    `,
  ],
})
export class AssignmentDialog {
  private readonly api = inject(UserAdminApi);
  private readonly units = inject(OrgTreeApi);
  private readonly stepUp = inject(ACCESS_STEP_UP);

  readonly visible = model(false);
  readonly personId = input.required<string>();
  readonly personName = input('');
  readonly added = output<void>();

  protected readonly kinds: { value: Kind; label: string }[] = [
    { value: 'KIEM_NHIEM', label: 'Kiêm nhiệm' },
    { value: 'BIET_PHAI', label: 'Biệt phái' },
  ];

  protected readonly unitId = signal<string | null>(null);
  protected readonly jobTitleId = signal<string | null>(null);
  protected readonly kind = signal<Kind>('KIEM_NHIEM');
  protected readonly ratio = signal<number | null>(30);
  protected readonly from = signal(isoDay());
  protected readonly fe = signal<Record<string, string>>({});
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  private readonly jobTitles = rxResource({
    params: () => (this.visible() ? true : undefined),
    stream: () =>
      this.api.jobTitles().pipe(map((items) => items.map((j) => ({ value: j.id, label: j.name })))),
  });
  protected readonly titles = () => (this.jobTitles.hasValue() ? this.jobTitles.value() : []);
  private readonly unit = rxResource({
    params: () => this.unitId() ?? undefined,
    stream: ({ params }) => this.units.unit(params),
  });
  protected readonly unitName = computed(() =>
    this.unit.hasValue() ? (this.unit.value().shortName ?? this.unit.value().name) : '',
  );
  protected readonly ready = () =>
    !!this.unitId() && !!this.jobTitleId() && !!this.from() && (this.ratio() ?? 0) > 0;

  constructor() {
    effect(() => {
      if (this.visible())
        untracked(() => {
          this.unitId.set(null);
          this.jobTitleId.set(null);
          this.kind.set('KIEM_NHIEM');
          this.ratio.set(30);
          this.from.set(isoDay());
          this.fe.set({});
          this.error.set(null);
        });
    });
  }

  protected async save() {
    if (!(await this.stepUp('Giao kiêm nhiệm cần xác thực nhiều lớp.'))) return;
    this.saving.set(true);
    this.error.set(null);
    this.api
      .addAssignment({
        personId: this.personId(),
        orgUnitId: this.unitId()!,
        jobTitleId: this.jobTitleId()!,
        assignmentKind: this.kind(),
        weightRatio: (this.ratio() ?? 0) / 100,
        validFrom: this.from(),
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.visible.set(false);
          this.added.emit();
        },
        error: (e: unknown) => {
          this.saving.set(false);
          const r = adminError(e, 'Không giao được kiêm nhiệm.');
          this.fe.set(r.fields);
          this.error.set(r.text);
        },
      });
  }
}
