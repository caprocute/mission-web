/*
 * SCR-1602 chế độ người xem (QT-16 bước 7; WEB-048): mở một dashboard tự dựng hoặc được chia sẻ
 * (`GET /analytics/dashboards/{id}?period=`), số liệu tính với phạm vi của chính người xem (BR-16-01,
 * MSG-16-05). Người tạo có nút "Sửa". Biểu đồ dựng theo `CHART_REGISTRY`; bấm giá trị theo đơn vị mở SCR-1603.
 */
import { Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AnalyticsApi, PeriodApi } from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { WidgetCard } from '../charts/widget-card';
import { DASHBOARD_CAN, DASHBOARD_USER_ID } from '../dashboard-permissions';
import { DefaultDashboardStore } from '../default-dashboard';
import { errorText, fmtDateTime } from '../dashboard-ui';
import { gridArea } from './dashboard-layout';

@Component({
  selector: 'vt-dashboard-view-page',
  imports: [FormsModule, RouterLink, ButtonModule, MessageModule, SelectModule, WidgetCard],
  template: `
    <p class="crumb"><a routerLink="..">Dashboard của tôi</a></p>
    @if (res.error()) {
      <p-message severity="error" role="alert">{{ err(res.error()) }}</p-message>
    } @else if (board(); as b) {
      <div class="head">
        <div>
          <h1>{{ b.title }}</h1>
          <p class="muted">
            @if (b.data?.dataAsOf; as t) {
              Số liệu cập nhật lúc {{ dt(t) }}.
            }
            @if (!mine()) {
              Đồng chí đang xem số liệu trong phạm vi quản lý của mình.
            }
          </p>
        </div>
        <div class="actions">
          <div>
            <label for="dv-period" class="sr-only">Kỳ</label>
            <p-select
              inputId="dv-period"
              [options]="periods()"
              optionLabel="name"
              optionValue="id"
              [ngModel]="periodId()"
              (ngModelChange)="periodId.set($event)"
            />
          </div>
          @if (defaults.ready()) {
            <p-button
              [label]="isDefault() ? 'Bỏ dashboard mặc định' : 'Đặt làm dashboard mặc định'"
              [icon]="isDefault() ? 'pi pi-star-fill' : 'pi pi-star'"
              severity="secondary"
              [outlined]="true"
              [loading]="savingDefault()"
              (onClick)="toggleDefault()"
              data-testid="dashboard-default"
            />
          }
          @if (mine() && can('SCR-1602.dung')) {
            <a
              pButton
              [routerLink]="['edit']"
              icon="pi pi-pencil"
              label="Sửa"
              [outlined]="true"
            ></a>
          }
        </div>
      </div>
      @if (defaultMsg(); as m) {
        <p-message [severity]="m.ok ? 'success' : 'error'" role="status">{{ m.text }}</p-message>
      }
      @if (b.data?.stale) {
        <p-message severity="warn" role="status">
          Số liệu cập nhật lúc {{ dt(b.data?.dataAsOf) }}, chậm hơn thường lệ.
        </p-message>
      }
      <div class="board">
        @for (w of b.layout; track w.id) {
          <section class="box widget" [style.grid-area]="area(w)" [attr.data-widget-id]="w.id">
            <vt-widget-card
              [widget]="w"
              [model]="model()"
              [data]="dataOf(w.id)"
              [periodId]="periodId()"
            />
          </section>
        }
      </div>
    } @else {
      <p class="muted">Đang tải…</p>
    }
  `,
  styleUrls: ['../dashboard.css', './dashboard-layout.css'],
})
export class DashboardViewPage {
  private readonly api = inject(AnalyticsApi);
  private readonly periodApi = inject(PeriodApi);
  protected readonly can = inject(DASHBOARD_CAN);
  private readonly me = inject(DASHBOARD_USER_ID);
  readonly id = input.required<string>();

  private readonly periodsRes = rxResource({ stream: () => this.periodApi.list({ kind: 'QUY' }) });
  protected readonly periods = computed(() =>
    this.periodsRes.hasValue() ? this.periodsRes.value() : [],
  );
  protected readonly periodId = linkedSignal<string | null>(
    () => this.periods().find((p) => p.status === 'MO' && !p.notStarted)?.id ?? null,
  );
  private readonly modelRes = rxResource({ stream: () => this.api.semanticModel() });
  protected readonly model = computed(() =>
    this.modelRes.hasValue() ? this.modelRes.value() : null,
  );
  protected readonly res = rxResource({
    params: () => ({ id: this.id(), periodId: this.periodId() }),
    stream: ({ params }) => this.api.dashboard(params.id, { periodId: params.periodId }),
  });
  protected readonly board = computed(() => (this.res.hasValue() ? this.res.value() : null));
  protected readonly mine = computed(() => this.board()?.ownerUserId === this.me());

  protected readonly defaults = inject(DefaultDashboardStore);
  protected readonly isDefault = computed(() => this.defaults.defaultId() === this.id());
  protected readonly savingDefault = signal(false);
  protected readonly defaultMsg = signal<{ ok: boolean; text: string } | null>(null);

  constructor() {
    this.defaults.load().subscribe({ error: () => undefined });
  }

  /** CNH-03: đặt hoặc bỏ dashboard này làm trang mở đầu của bảng điều hành. */
  protected toggleDefault(): void {
    const setting = !this.isDefault();
    this.savingDefault.set(true);
    this.defaultMsg.set(null);
    this.defaults.set(setting ? this.id() : null).subscribe({
      next: () => {
        this.savingDefault.set(false);
        this.defaultMsg.set({
          ok: true,
          text: setting
            ? 'Đã đặt làm dashboard mặc định. Bảng điều hành sẽ mở dashboard này khi đồng chí đăng nhập.'
            : 'Đã bỏ dashboard mặc định.',
        });
      },
      error: (e) => {
        this.savingDefault.set(false);
        this.defaultMsg.set({ ok: false, text: errorText(e, 'Chưa lưu được dashboard mặc định.') });
        this.defaults.load().subscribe({ error: () => undefined });
      },
    });
  }

  protected dataOf(widgetId: string) {
    return this.board()?.data?.widgets?.find((w) => w.widgetId === widgetId) ?? null;
  }
  protected area(w: { x: number; y: number; w: number; h: number }) {
    return gridArea(w);
  }
  protected dt(iso?: string | null) {
    return fmtDateTime(iso);
  }
  protected err(e: unknown) {
    return errorText(e, 'Không tải được dashboard.');
  }
}
