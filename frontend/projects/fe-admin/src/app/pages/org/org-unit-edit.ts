import { Component, inject, input, linkedSignal, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import {
  ORG_UNIT_LEVEL_LABEL,
  ORG_UNIT_TYPE_LABEL,
  OrgFlow,
  OrgTreeApi,
  OrgUnitDetail,
  OrgUnitLevel,
  OrgUnitType,
  OrgUnitUpdate,
  fieldErrors,
  toProblem,
} from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';

const today = () => new Date().toISOString().slice(0, 10);

/**
 * Sửa đơn vị (SCR-0103 nút "Sửa") → `PUT /api/v1/org/units/{id}` kèm `If-Match`. Máy chủ ghi phiên
 * bản mới từ "Hiệu lực từ", không ghi đè (BR-01-06); khối đối tượng bắt buộc (BR-01-05); EVT-14.
 */
@Component({
  selector: 'vt-org-unit-edit',
  imports: [FormsModule, ButtonModule, InputTextModule],
  template: `
    <form class="grid" (ngSubmit)="save()" aria-label="Sửa đơn vị">
      <label class="f wide">
        <span>Tên đơn vị</span>
        <input
          pInputText
          name="name"
          maxlength="255"
          [ngModel]="f().name"
          (ngModelChange)="set('name', $event)"
        />
        @if (fe()['name']) {
          <small class="err">{{ fe()['name'] }}</small>
        }
      </label>
      <label class="f">
        <span>Tên viết tắt</span>
        <input
          pInputText
          name="short"
          maxlength="100"
          [ngModel]="f().shortName ?? ''"
          (ngModelChange)="set('shortName', $event)"
        />
      </label>
      <label class="f">
        <span>Mã CSDL CBCCVC</span>
        <input
          pInputText
          name="ext"
          [ngModel]="f().externalCode ?? ''"
          (ngModelChange)="set('externalCode', $event)"
        />
      </label>
      <label class="f">
        <span>Cấp</span>
        <select
          class="native"
          name="level"
          [ngModel]="f().level"
          (ngModelChange)="set('level', $event)"
        >
          @for (l of levels; track l) {
            <option [value]="l">{{ levelLabel[l] }}</option>
          }
        </select>
      </label>
      <label class="f">
        <span>Loại</span>
        <select
          class="native"
          name="type"
          [ngModel]="f().unitType"
          (ngModelChange)="set('unitType', $event)"
        >
          @for (t of types; track t) {
            <option [value]="t">{{ typeLabel[t] }}</option>
          }
        </select>
      </label>
      <label class="f wide">
        <span>Khối đối tượng (PL 2A)</span>
        <select
          class="native"
          name="sg"
          [ngModel]="f().subjectGroupId"
          (ngModelChange)="set('subjectGroupId', $event)"
        >
          @for (g of groups.value() ?? []; track g.id) {
            <option [value]="g.id">{{ g.name }}</option>
          }
        </select>
        @if (fe()['subjectGroupId']) {
          <small class="err">{{ fe()['subjectGroupId'] }}</small>
        }
      </label>
      <fieldset class="f">
        <legend>Luồng đánh giá</legend>
        @for (fl of flows; track fl) {
          <label class="chk">
            <input
              type="checkbox"
              [checked]="f().flows.includes(fl)"
              (change)="toggleFlow(fl, $any($event.target).checked)"
            />
            {{ fl }}
          </label>
        }
        @if (fe()['flows']) {
          <small class="err">{{ fe()['flows'] }}</small>
        }
      </fieldset>
      <label class="f">
        <span>Hiệu lực từ</span>
        <input
          pInputText
          type="date"
          name="from"
          [ngModel]="f().validFrom"
          (ngModelChange)="set('validFrom', $event)"
        />
        @if (fe()['validFrom']) {
          <small class="err">{{ fe()['validFrom'] }}</small>
        }
      </label>
      @if (error(); as e) {
        <p class="err wide" role="alert">
          {{ e }}
          @if (conflict()) {
            <button type="button" class="link" (click)="reload.emit()">Tải lại</button>
          }
        </p>
      }
      <div class="wide row">
        <p-button
          type="submit"
          label="Ghi phiên bản mới"
          [loading]="busy()"
          [disabled]="!f().name.trim() || !f().flows.length"
        />
        <p-button label="Huỷ" severity="contrast" [outlined]="true" (onClick)="closed.emit()" />
      </div>
    </form>
  `,
  styles: `
    .grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 10px 16px;
    }
    @media (max-width: 700px) {
      .grid {
        grid-template-columns: 1fr;
      }
    }
    .wide {
      grid-column: 1 / -1;
    }
    .f {
      display: flex;
      flex-direction: column;
      gap: 4px;
      border: 0;
      margin: 0;
      padding: 0;
    }
    .f > span,
    legend {
      font-weight: 500;
    }
    .chk {
      display: inline-flex;
      gap: 6px;
      margin-right: 12px;
    }
    .native {
      padding: 8px 10px;
      border: 1px solid #cbd5e1;
      border-radius: 6px;
      font: inherit;
      min-height: 40px;
      background: #fff;
    }
    .err {
      color: #b4001f;
    }
    .row {
      display: flex;
      gap: 8px;
    }
    .link {
      background: none;
      border: 0;
      color: #b8002a;
      text-decoration: underline;
      cursor: pointer;
      font: inherit;
    }
  `,
})
export class OrgUnitEdit {
  readonly unit = input.required<OrgUnitDetail>();
  readonly saved = output<OrgUnitDetail>();
  readonly closed = output<void>();
  readonly reload = output<void>();

  private readonly api = inject(OrgTreeApi);
  protected readonly levels = Object.keys(ORG_UNIT_LEVEL_LABEL) as OrgUnitLevel[];
  protected readonly types = Object.keys(ORG_UNIT_TYPE_LABEL) as OrgUnitType[];
  protected readonly levelLabel = ORG_UNIT_LEVEL_LABEL;
  protected readonly typeLabel = ORG_UNIT_TYPE_LABEL;
  protected readonly flows: OrgFlow[] = ['NHA_NUOC', 'DANG'];
  protected readonly groups = rxResource({ stream: () => this.api.subjectGroups() });

  protected readonly f = linkedSignal<OrgUnitUpdate>(() => {
    const u = this.unit();
    return {
      name: u.name,
      shortName: u.shortName,
      externalCode: u.externalCode,
      level: u.level,
      unitType: u.unitType,
      subjectGroupId: u.subjectGroupId,
      flows: [...u.flows],
      validFrom: today() < u.validFrom ? u.validFrom : today(),
    };
  });
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly conflict = signal(false);
  protected readonly fe = signal<Record<string, string>>({});

  protected set<K extends keyof OrgUnitUpdate>(key: K, value: OrgUnitUpdate[K]) {
    this.f.update((f) => ({ ...f, [key]: value }));
  }

  protected toggleFlow(fl: OrgFlow, on: boolean) {
    this.set(
      'flows',
      on ? [...new Set([...this.f().flows, fl])] : this.f().flows.filter((x) => x !== fl),
    );
  }

  protected save() {
    const f = this.f();
    this.busy.set(true);
    this.error.set(null);
    this.fe.set({});
    this.conflict.set(false);
    const body: OrgUnitUpdate = {
      ...f,
      name: f.name.trim(),
      shortName: f.shortName?.trim() || undefined,
      externalCode: f.externalCode?.trim() || undefined,
    };
    this.api.update(this.unit(), body).subscribe({
      next: (r) => {
        this.busy.set(false);
        this.saved.emit(r);
      },
      error: (e) => {
        this.busy.set(false);
        const p = toProblem(e);
        const fe = fieldErrors(p);
        this.fe.set(fe);
        this.conflict.set(p.status === 409 && p.code === 'ORG-409-CONFLICT');
        this.error.set(Object.keys(fe).length ? null : p.title);
      },
    });
  }
}
