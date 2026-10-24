import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import {
  DATA_CENTER_LABEL,
  TENANCY_MODEL_LABEL,
  TENANT_ONBOARDING_STATUSES,
  TENANT_SECTOR_LABEL,
  TENANT_STATUS_LABEL,
  TenantApi,
  TenantStatus,
} from '@vt/api-client';
import { MessageModule } from 'primeng/message';
import { errorText, fmtDate, fmtDateTime, fmtInt } from '../admin-ui';
import { MSG_01_02 } from './tenants.page';

const FLOW_LABEL = { DANG: 'Đảng', NHA_NUOC: 'Nhà nước' } as const;
const DOMAIN_KIND_LABEL = {
  PRIMARY: 'chính',
  ALIAS: 'bí danh',
  EMBED: 'nhúng cổng văn phòng số',
} as const;

/**
 * Chi tiết tenant, mở từ nút "Chi tiết" của SCR-0101. TL-02 không vẽ màn hình riêng nên trang chỉ đọc:
 * thông tin tenant lấy từ `GET /config/tenants/{id}` (`ConfigTenant`), tên miền lấy từ
 * `GET /registry/tenants/{id}` (`RegistryTenant`); nhật ký trạng thái (`registry.tenant_status_log`)
 * và UI Pack lấy từ `ConfigTenant.statusLog`, `ConfigTenant.uiPack`.
 */
@Component({
  selector: 'vt-tenant-detail-page',
  imports: [RouterLink, MessageModule],
  template: `
    <nav class="crumb" aria-label="Đường dẫn">
      <a routerLink="..">Danh sách đơn vị triển khai</a> /
      <span aria-current="page">{{ t()?.name ?? 'Chi tiết' }}</span>
    </nav>
    @if (tenant.error()) {
      <p-message severity="error" styleClass="msg">
        <span>{{ err(tenant.error(), 'Không tải được thông tin đơn vị triển khai.') }}</span>
        <button type="button" class="link" (click)="tenant.reload()">Thử lại</button>
      </p-message>
    } @else if (t(); as t) {
      <h1>{{ t.name }}</h1>
      @if (onboarding()) {
        <p class="warnbox" role="status">
          {{ msg(t.name, t.onboardingStep ?? 1) }}
          <a [routerLink]="['/admin/tenants', t.id, 'onboarding']">Mở hồ sơ khởi tạo</a>
        </p>
      }
      <div class="cols">
        <section class="box" aria-labelledby="info-title">
          <h2 id="info-title" class="h">Thông tin đơn vị triển khai</h2>
          <dl>
            <dt>Mã</dt>
            <dd>{{ t.code }}</dd>
            <dt>Trạng thái</dt>
            <dd>{{ status(t.status) }}</dd>
            <dt>Khối, lĩnh vực</dt>
            <dd>{{ t.sector ? sector(t.sector) : '—' }}</dd>
            <dt>Luồng đánh giá</dt>
            <dd>{{ flows() }}</dd>
            <dt>Cụm máy chủ, mức cô lập</dt>
            <dd>
              {{ t.cellCode ?? '—' }} · {{ t.tenancyModel ? modelLabel[t.tenancyModel] : '—' }}
              @if (t.dataRegion) {
                · vùng lưu dữ liệu {{ dc(t.dataRegion) }}
              }
            </dd>
            <dt>Tài khoản</dt>
            <dd>
              {{ int(t.accountCount ?? 0) }} / hạn mức {{ int(t.maxAccounts ?? 0) }}
              @if (t.expectedAccounts) {
                · dự kiến {{ int(t.expectedAccounts) }}
              }
            </dd>
            <dt>Bộ quy định đánh giá</dt>
            <dd>{{ packs() || 'Chưa gắn' }}</dd>
            <dt>Bộ giao diện</dt>
            <dd>{{ t.uiPack ? uiPack(t.uiPack) : 'Chưa gắn' }}</dd>
            <dt>Hợp đồng</dt>
            <dd>
              {{ t.contractRef ?? '—' }}
              @if (t.contractValidTo) {
                · hết hạn {{ date(t.contractValidTo) }}
              }
            </dd>
          </dl>
        </section>
        <div>
          <section class="box" aria-labelledby="dom-title">
            <h2 id="dom-title" class="h">Tên miền</h2>
            @if (reg.error()) {
              <p class="muted">{{ err(reg.error(), 'Không tải được tên miền.') }}</p>
            }
            <ul>
              @for (d of domains(); track d.domain) {
                <li>
                  {{ d.domain }} <span class="muted">({{ domainKind[d.kind] }})</span>
                  @if (d.verifiedAt) {
                    <span class="muted"> · đã xác minh {{ date(d.verifiedAt) }}</span>
                  }
                </li>
              } @empty {
                @if (t.domain) {
                  <li>{{ t.domain }} <span class="muted">(chính)</span></li>
                }
              }
            </ul>
          </section>
          <section class="box" aria-labelledby="log-title">
            <h2 id="log-title" class="h">Nhật ký trạng thái</h2>
            @if (t.statusLog?.length) {
              <ol class="log">
                @for (l of t.statusLog; track l.id) {
                  <li>
                    <span class="muted">{{ dateTime(l.changedAt) }}</span> ·
                    @if (l.fromStatus) {
                      {{ status(l.fromStatus) }} →
                    }
                    {{ status(l.toStatus) }}
                    @if (l.reason) {
                      <span class="muted"> · {{ l.reason }}</span>
                    }
                  </li>
                }
              </ol>
            } @else {
              <p class="muted">Chưa có thay đổi trạng thái.</p>
            }
          </section>
        </div>
      </div>
    } @else {
      <p class="muted" aria-busy="true">Đang tải thông tin đơn vị triển khai…</p>
    }
  `,
  styles: `
    h1 {
      font-size: 1.4rem;
      margin: 8px 0 12px;
    }
    .crumb {
      color: #4b5563;
    }
    .crumb a {
      color: var(--vt-color-primary, #b8002a);
    }
    .muted {
      color: #4b5563;
    }
    .cols {
      display: grid;
      grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
      gap: 16px;
      align-items: start;
    }
    @media (max-width: 1000px) {
      .cols {
        grid-template-columns: 1fr;
      }
    }
    .box {
      padding: 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: var(--p-content-background, #fff);
      margin-bottom: 12px;
    }
    .h {
      font-size: 1.05rem;
      margin: 0 0 8px;
    }
    dl {
      display: grid;
      grid-template-columns: max-content 1fr;
      gap: 8px 16px;
      margin: 0;
    }
    dt {
      color: #4b5563;
    }
    dd {
      margin: 0;
    }
    ul,
    .log {
      margin: 0;
      padding-left: 20px;
      display: grid;
      gap: 6px;
    }
    .link {
      background: none;
      border: 0;
      padding: 0 0 0 8px;
      color: var(--vt-color-primary, #b8002a);
      text-decoration: underline;
      cursor: pointer;
      font: inherit;
    }
    .warnbox {
      padding: 8px 12px;
      border: 1px solid #f3d27a;
      border-radius: 6px;
      background: #fff8e1;
      color: #6b4400;
    }
    :host ::ng-deep .msg {
      margin: 8px 0;
      width: 100%;
    }
  `,
})
export class TenantDetailPage {
  private readonly api = inject(TenantApi);
  /** Tham số tuyến `:id` (withComponentInputBinding). */
  readonly id = input.required<string>();

  protected readonly tenant = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.api.get(params),
  });
  protected readonly reg = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.api.registry(params),
  });
  protected readonly t = computed(() => (this.tenant.hasValue() ? this.tenant.value() : undefined));
  protected readonly domains = computed(() =>
    this.reg.hasValue() ? (this.reg.value().domains ?? []) : [],
  );
  protected readonly packs = computed(() =>
    (this.t()?.policyBindings ?? [])
      .filter((b) => !!b.packCode)
      .map((b) => (b.version ? `${b.packCode} v${b.version}` : b.packCode))
      .join(' + '),
  );
  protected readonly onboarding = computed(() => {
    const t = this.t();
    return !!t && TENANT_ONBOARDING_STATUSES.includes(t.status);
  });
  protected readonly flows = computed(
    () =>
      this.t()
        ?.flows.map((f) => FLOW_LABEL[f])
        .join(', ') ?? '',
  );

  protected readonly domainKind = DOMAIN_KIND_LABEL;
  protected readonly modelLabel = TENANCY_MODEL_LABEL;
  protected dc = (code: string) => DATA_CENTER_LABEL[code] ?? 'Trung tâm dữ liệu khác';
  protected readonly int = fmtInt;
  protected readonly date = fmtDate;
  protected readonly dateTime = fmtDateTime;
  protected readonly err = errorText;
  protected readonly msg = MSG_01_02;
  protected status = (s: TenantStatus) => TENANT_STATUS_LABEL[s];
  protected sector = (s: string) => TENANT_SECTOR_LABEL[s] ?? 'Lĩnh vực khác';
  protected uiPack = (p: { name: string; version: string }) =>
    p.version ? `${p.name} v${p.version}` : p.name;
}
