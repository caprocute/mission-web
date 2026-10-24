/*
 * SCR-0102 "Khởi tạo tenant" (QT-01 bước 1–9, TL-02a Hình 6.8) — đối chiếu đặc tả:
 * Tác nhân AC-01 (bước 1, 2, 3, 6), AC-02 `tenant_admin` (bước 4, 5); phiên AAL2; máy tính.
 * Đường vào: SCR-0101 nút "Khởi tạo tenant" (`/admin/tenants/new`) hoặc mở lại bản nháp
 *   (`/admin/tenants/{id}/onboarding?step=n`).
 * Trường: 1 mã yêu cầu `ONB-yyyy-nnnn` (chỉ đọc); 2 số hợp đồng ≤ 50; 3 luồng áp dụng DANG/NHA_NUOC (≥ 1);
 *   4 quy mô dự kiến (tài khoản, đơn vị) > 0; 5 mô hình silo/bridge/pool (gợi ý theo quy mô, BR-01-01);
 *   6 cell (tải thấp nhất phù hợp, BR-01-02); 7 tên miền `{mã}.okr.viettel.vn`, chưa dùng; 8 khu vực lưu dữ liệu
 *   (Hoà Lạc mặc định, trong nước); 9 KEK riêng/dùng chung (bắt buộc riêng với silo); 10 Policy Pack gốc theo
 *   luồng (BR-01-03, BR-01-04); 11 UI Pack (Mặc định v3.0); 12 IdP (kiểm tra kết nối); 13 nguồn nhân sự.
 * Vùng: 1 thanh tiến trình sáu bước; 2 thẻ mô hình thuê bao kèm gợi ý; 3 bảng chọn cell, tên miền, vùng lưu
 *   dữ liệu, KEK; 4 tóm tắt cấu hình và kiểm tra trước khi tiếp tục; 5 nút Tiếp tục.
 * Chức năng: Lưu nháp (PATCH /config/tenants/{id} merge-patch, If-Match); Tiếp tục (cuối bước 3 POST
 *   /provision 202 + JobTracker job PROVISION, NFR-07 hiện tiến trình từng bước con); Kiểm tra kết nối IdP
 *   (POST /idp/test theo idpAlias của IdP đã lưu); Kích hoạt (POST /activate, MFA bước lên, EVT-21); Huỷ
 *   (DELETE khi KHOI_TAO).
 * Hợp đồng (WEB-072, YC-PT-09): hồ sơ là ConfigTenant của GET /config/tenants/{id}; bước tới được là
 *   `onboardingStep` máy chủ tính từ dữ liệu, bước đang xem giữ ở `?step=`; cell từ GET /registry/cells
 *   (platform.yaml), Policy Pack từ GET /policies/packs?origin=GOC_TOAN_CUC, UI Pack từ GET /ui/packs;
 *   kiểm tra bước 6 là `readinessChecks`; không có API kiểm tên miền riêng, trùng tên miền báo khi lưu
 *   (409 CONFIG-409-DOMAIN-TAKEN).
 * Thông báo: MSG-01-03 không còn cell, MSG-01-04 trùng tên miền, MSG-01-05 gói chưa đạt test vàng,
 *   MSG-01-06 cấp phát thành công, MSG-01-07 cấp phát lỗi đã hoàn tác. BR-01-10 lệch mức Cao chặn kích hoạt.
 * Quyền (wiki/15a): SCR-0102.buoc-1-3, SCR-0102.buoc-4-5, SCR-0102.kich-hoat, SCR-0102.huy.
 */
import {
  Component,
  computed,
  effect,
  inject,
  input,
  linkedSignal,
  signal,
  untracked,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { EXTERNAL_SYNC_FLAG, FeatureFlagService, Job, JobTracker, fieldErrors, toProblem } from '@vt/api-client';
import { CHECK_RESULT_LABEL, CellOption, CheckResult, ConfigTenant, IDP_KIND_LABEL, IdpKind, KEK_MODE_LABEL, KekMode, ONBOARDING_STEPS, PROVISION_STEPS, PolicyPackOption, READINESS_CHECK_LABEL, STAFF_SOURCE_LABEL, StaffSourceType, TENANCY_MODEL_LABEL, TENANT_FLOW_LABEL, TENANT_SECTOR_LABEL, TENANT_STATUS_LABEL, TenancyModel, TenantApi, TenantFlow, TenantIdp, TenantOnboarding, TenantOnboardingInput, TenantOnboardingPatch, TenantSector, UiPackOption, idpKind, modelAllowed, packNotReady, tenantSuggestion } from '@vt/api-client/domains';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { ThemeService } from '@vt/design-system';
import { Observable, Subscription, concatMap, from, map, of, switchMap, toArray } from 'rxjs';
import { ADMIN_CAN } from '../admin-permissions';
import { errorText, fmtAccounts, fmtDate, fmtDateTime, fmtInt, fmtPercent } from '../admin-ui';

/** MSG-01-03 */
export const MSG_01_03 = (model: string) =>
  `Không còn cụm máy chủ mức cô lập ${model} nào dưới ngưỡng 85% sau khi thêm đơn vị. Đề nghị lập yêu cầu dựng cụm máy chủ mới.`;
/** MSG-01-04 */
export const MSG_01_04 = (domain: string) => `Tên miền ${domain} đã được sử dụng.`;
/** MSG-01-05 */
export const MSG_01_05 = (pack: string) =>
  `Bộ quy định đánh giá ${pack} chưa đạt 100% bộ ca kiểm thử chuẩn nên chưa gắn được.`;
/** MSG-01-06 */
export const MSG_01_06 = (minutes: number) =>
  `Cấp phát hạ tầng cho đơn vị thành công trong ${minutes} phút. Đã gửi thư cho đầu mối quản trị của đơn vị.`;

const MODEL_CARDS: { model: TenancyModel; title: string; text: string; build: string }[] = [
  {
    model: 'silo',
    title: 'Cụm riêng',
    text: 'Toàn bộ hạ tầng riêng cho đơn vị (CSDL, hàng đợi thông điệp, kho tệp, thiết bị HSM). Dành cho khối Đảng Trung ương, tỉnh trên 1 triệu tài khoản, lực lượng vũ trang.',
    build: 'Thời gian dựng: 3–5 ngày',
  },
  {
    model: 'bridge',
    title: 'CSDL riêng',
    text: 'Một CSDL riêng cho đơn vị trong cụm máy chủ nhóm tỉnh; các dịch vụ dùng chung. Khoá mã hoá riêng.',
    build: 'Thời gian dựng: không quá 2 giờ',
  },
  {
    model: 'pool',
    title: 'Dùng chung',
    text: 'Dữ liệu lưu chung với các đơn vị khác, được tách riêng theo từng đơn vị. Dành cho xã, chi bộ, đơn vị dưới 5.000 tài khoản.',
    build: 'Thời gian dựng: không quá 5 phút',
  },
];

const DC_LABEL: Record<string, string> = {
  HOALAC: 'Hoà Lạc',
  PHIANAM: 'Phía Nam',
  TAICHO: 'Tại chỗ',
};

const SECTORS = Object.keys(TENANT_SECTOR_LABEL) as TenantSector[];
const FLOWS: TenantFlow[] = ['DANG', 'NHA_NUOC'];
const STAFF_SOURCES = Object.keys(STAFF_SOURCE_LABEL) as StaffSourceType[];
const DOMAIN_RE = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/;
const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
/** Mã lỗi của hợp đồng `config.yaml` mà màn hình xử lý riêng. */
const VERSION_CONFLICT = 'CONFIG-409-VERSION-CONFLICT';
const DOMAIN_TAKEN = 'CONFIG-409-DOMAIN-TAKEN';

/** Bản soạn bước 1 trên màn hình; gửi đi dưới dạng `ConfigTenantCreate` hoặc `ConfigTenantPatch`. */
interface Step1 {
  name: string;
  code: string;
  sector: TenantSector;
  contractRef: string;
  contractValidTo?: string;
  flows: TenantFlow[];
  expectedAccounts: number;
  expectedUnits: number;
  adminName: string;
  adminEmail: string;
}

interface Step2 {
  tenancyModel: TenancyModel;
  cellCode: string | null;
  domain: string;
  dataRegion: string;
  kekMode: KekMode;
}

interface Step3 {
  /** Id phiên bản Policy Pack theo luồng (`policyBindings[].packVersionId`). */
  policyPacks: Partial<Record<TenantFlow, string>>;
  uiPackVersionId: string;
}

/** Một IdP đang soạn ở bước 4 kèm kết quả kiểm tra gần nhất và lời nhắn của lần kiểm tra. */
interface IdpRow {
  id?: string;
  idpAlias: string;
  protocol: TenantIdp['protocol'];
  issuer: string;
  isVneid: boolean;
  emailDomains?: string[];
  lastTestOk?: boolean;
  message?: string;
}

function emptyStep1(): Step1 {
  return {
    name: '',
    code: '',
    sector: 'CHINH_QUYEN',
    contractRef: '',
    contractValidTo: undefined,
    flows: ['DANG', 'NHA_NUOC'],
    expectedAccounts: 0,
    expectedUnits: 0,
    adminName: '',
    adminEmail: '',
  };
}

const toRow = (i: TenantIdp): IdpRow => ({
  id: i.id,
  idpAlias: i.idpAlias,
  protocol: i.protocol,
  issuer: i.issuer,
  isVneid: !!i.isVneid,
  emailDomains: i.emailDomains,
  lastTestOk: i.lastTestOk,
});

/** IdP gửi trong PATCH: chỉ trường ghi được của `TenantIdp` (máy chủ từ chối trường lạ). */
const toIdp = (r: IdpRow): TenantIdp => ({
  ...(r.id ? { id: r.id } : {}),
  idpAlias: r.idpAlias.trim(),
  protocol: r.protocol,
  issuer: r.issuer.trim(),
  isVneid: r.isVneid,
  ...(r.emailDomains?.length ? { emailDomains: r.emailDomains } : {}),
});

/** Số phút saga chạy, tính từ nhật ký trạng thái (vào `DANG_CAP_PHAT` tới khi rời trạng thái đó). */
function provisionMinutes(t: ConfigTenant): number {
  const log = t.statusLog ?? [];
  const start = log.find((l) => l.toStatus === 'DANG_CAP_PHAT');
  const end = log.find((l) => l.fromStatus === 'DANG_CAP_PHAT');
  if (!start || !end) return 1;
  const ms = Date.parse(end.changedAt) - Date.parse(start.changedAt);
  return Math.max(1, Math.ceil(ms / 60_000));
}

@Component({
  selector: 'vt-tenant-onboarding-page',
  imports: [FormsModule, RouterLink, ButtonModule, InputTextModule, MessageModule, SelectModule],
  template: `
    <nav class="muted" aria-label="Đường dẫn">
      <a routerLink="/admin/tenants">Danh sách đơn vị triển khai</a> /
      <span aria-current="page">Khởi tạo đơn vị triển khai</span>
    </nav>

    @if (loadError(); as e) {
      <p-message severity="error" styleClass="msg">
        <span>{{ e }}</span>
        <button type="button" class="link" (click)="record.reload()">Thử lại</button>
      </p-message>
    } @else if (!id() || ob()) {
      <header class="head">
        <div>
          <h1>Khởi tạo đơn vị triển khai{{ ob() ? ': ' + ob()!.name : '' }}</h1>
          @if (ob(); as o) {
            <p class="muted">
              Mã yêu cầu {{ o.onboardingCode }} · hợp đồng {{ o.contractRef }}
              @if (o.contractValidTo) {
                hiệu lực đến {{ date(o.contractValidTo) }}
              }
              @if (o.createdAt) {
                · bắt đầu {{ dateTime(o.createdAt) }}
              }
              · {{ statusLabel[o.status] }}
            </p>
          } @else {
            <p class="muted">Mã yêu cầu do hệ thống cấp khi lưu bước 1.</p>
          }
        </div>
        <div class="actions">
          @if (ob() && canEditCurrent() && !job()) {
            <p-button
              label="Lưu nháp"
              severity="contrast"
              [outlined]="true"
              [loading]="saving()"
              (onClick)="saveDraft()"
            />
          }
          @if (ob()?.status === 'KHOI_TAO' && can('SCR-0102.huy') && !job()) {
            @if (confirmCancel()) {
              <p-button
                label="Xác nhận huỷ yêu cầu"
                severity="danger"
                [loading]="saving()"
                (onClick)="cancel()"
              />
              <p-button
                label="Không huỷ"
                severity="contrast"
                [text]="true"
                (onClick)="confirmCancel.set(false)"
              />
            } @else {
              <p-button
                label="Huỷ"
                severity="contrast"
                [outlined]="true"
                (onClick)="confirmCancel.set(true)"
              />
            }
          }
        </div>
      </header>

      <nav class="box stepper" aria-label="Các bước khởi tạo">
        <ol>
          @for (s of steps; track s; let i = $index) {
            <li
              [class.done]="i + 1 < reached()"
              [class.cur]="i + 1 === viewStep()"
              [attr.aria-current]="i + 1 === viewStep() ? 'step' : null"
            >
              @if (i + 1 <= reached() && i + 1 !== viewStep()) {
                <button type="button" class="stp" (click)="go(i + 1)">
                  <span class="num">{{ i + 1 < reached() ? '✓' : i + 1 }}</span> {{ s }}
                </button>
              } @else {
                <span class="stp"
                  ><span class="num">{{ i + 1 }}</span> {{ s }}</span
                >
              }
            </li>
          }
        </ol>
      </nav>

      @if (notice(); as n) {
        <p-message severity="success" [text]="n" styleClass="msg" role="status" />
      }
      @if (error(); as e) {
        <p-message severity="error" styleClass="msg" role="alert">
          <span>{{ e }}</span>
          @if (conflict()) {
            <button type="button" class="link" (click)="reloadAfterConflict()">Tải lại</button>
          }
        </p-message>
      }
      @if (provisionFailure(); as pf) {
        <!-- MSG-01-07 -->
        <p-message severity="error" styleClass="msg">{{ pf }}</p-message>
      }

      <div class="cols">
        <div>
          @switch (viewStep()) {
            @case (1) {
              <section class="box" aria-labelledby="s1-title">
                <h2 id="s1-title" class="h">1. Thông tin đơn vị</h2>
                <fieldset class="grid" [disabled]="!canEditSetup()">
                  <legend class="sr-only">Thông tin đơn vị</legend>
                  <label class="f wide">
                    <span>Tên đơn vị triển khai <span class="req" aria-hidden="true">*</span></span>
                    <input
                      pInputText
                      [ngModel]="s1().name"
                      (ngModelChange)="set1('name', $event)"
                      name="name"
                      maxlength="200"
                      required
                    />
                    @if (fe()['name']) {
                      <small class="err">{{ fe()['name'] }}</small>
                    }
                  </label>
                  <label class="f">
                    <span>Mã đơn vị <span class="req" aria-hidden="true">*</span></span>
                    <input
                      pInputText
                      [ngModel]="s1().code"
                      (ngModelChange)="set1('code', $event)"
                      name="code"
                      maxlength="32"
                      [disabled]="!!ob()"
                      aria-describedby="code-hint"
                    />
                    <small id="code-hint" class="muted"
                      >Chữ thường không dấu, chữ số, dấu gạch dưới; dùng để đặt tên miền</small
                    >
                    @if (fe()['code']) {
                      <small class="err">{{ fe()['code'] }}</small>
                    }
                  </label>
                  <label class="f">
                    <span>Khối, lĩnh vực</span>
                    <select
                      class="native"
                      [ngModel]="s1().sector"
                      (ngModelChange)="set1('sector', $event)"
                      name="sector"
                      [disabled]="!!ob()"
                    >
                      @for (s of sectors; track s) {
                        <option [value]="s">{{ sectorLabel[s] }}</option>
                      }
                    </select>
                  </label>
                  <label class="f">
                    <span>Số hợp đồng <span class="req" aria-hidden="true">*</span></span>
                    <input
                      pInputText
                      [ngModel]="s1().contractRef"
                      (ngModelChange)="set1('contractRef', $event)"
                      name="contractRef"
                      maxlength="50"
                    />
                    @if (fe()['contractRef']) {
                      <small class="err">{{ fe()['contractRef'] }}</small>
                    }
                  </label>
                  <label class="f">
                    <span>Hợp đồng hiệu lực đến</span>
                    <input
                      pInputText
                      type="date"
                      [ngModel]="s1().contractValidTo"
                      (ngModelChange)="set1('contractValidTo', $event)"
                      name="validTo"
                    />
                  </label>
                  <fieldset class="f wide inline">
                    <legend>
                      Luồng đánh giá áp dụng <span class="req" aria-hidden="true">*</span>
                    </legend>
                    @for (fl of flows; track fl) {
                      <label class="chk">
                        <input
                          type="checkbox"
                          [checked]="s1().flows.includes(fl)"
                          (change)="toggleFlow(fl, $any($event.target).checked)"
                        />
                        {{ flowLabel[fl] }}
                      </label>
                    }
                    @if (fe()['flows']) {
                      <small class="err">{{ fe()['flows'] }}</small>
                    }
                  </fieldset>
                  <label class="f">
                    <span>Số tài khoản dự kiến <span class="req" aria-hidden="true">*</span></span>
                    <input
                      pInputText
                      type="number"
                      min="1"
                      [ngModel]="s1().expectedAccounts"
                      (ngModelChange)="set1('expectedAccounts', $event)"
                      name="accounts"
                    />
                    @if (fe()['expectedAccounts']) {
                      <small class="err">{{ fe()['expectedAccounts'] }}</small>
                    }
                  </label>
                  <label class="f">
                    <span>Số đơn vị dự kiến</span>
                    <input
                      pInputText
                      type="number"
                      min="0"
                      [ngModel]="s1().expectedUnits"
                      (ngModelChange)="set1('expectedUnits', $event)"
                      name="units"
                    />
                  </label>
                  <label class="f">
                    <span
                      >Đầu mối quản trị của đơn vị
                      <span class="req" aria-hidden="true">*</span></span
                    >
                    <input
                      pInputText
                      [ngModel]="s1().adminName"
                      (ngModelChange)="set1('adminName', $event)"
                      name="adminName"
                      maxlength="200"
                    />
                    @if (fe()['adminContact.fullName']) {
                      <small class="err">{{ fe()['adminContact.fullName'] }}</small>
                    }
                  </label>
                  <label class="f">
                    <span>Thư điện tử đầu mối <span class="req" aria-hidden="true">*</span></span>
                    <input
                      pInputText
                      type="email"
                      [ngModel]="s1().adminEmail"
                      (ngModelChange)="set1('adminEmail', $event)"
                      name="adminEmail"
                      maxlength="254"
                    />
                    @if (fe()['adminContact.email']) {
                      <small class="err">{{ fe()['adminContact.email'] }}</small>
                    }
                  </label>
                </fieldset>
              </section>
            }
            @case (2) {
              <section class="box" aria-labelledby="s2-title">
                <h2 id="s2-title" class="h">2. Mức cô lập dữ liệu</h2>
                <fieldset class="models" [disabled]="!canEditSetup()">
                  <legend class="sr-only">Mức cô lập dữ liệu</legend>
                  @for (m of modelCards; track m.model) {
                    <label
                      class="model"
                      [class.sel]="s2().tenancyModel === m.model"
                      [class.off]="!allowed(m.model)"
                    >
                      <input
                        type="radio"
                        name="model"
                        [value]="m.model"
                        [checked]="s2().tenancyModel === m.model"
                        [disabled]="!allowed(m.model)"
                        (change)="setModel(m.model)"
                      />
                      <strong>{{ m.title }}</strong>
                      <span class="muted small block">{{ m.text }}</span>
                      <span class="small block">{{ m.build }}</span>
                    </label>
                  }
                </fieldset>
                <p class="hint">
                  Gợi ý của hệ thống: <strong>{{ modelLabel[suggestion()] }}</strong
                  >, vì {{ suggestionReason() }}.
                </p>
              </section>
              <section class="box" aria-labelledby="s3-title">
                <h2 id="s3-title" class="h">Chọn cụm máy chủ</h2>
                @if (cellOptions.isLoading()) {
                  <p class="muted" aria-busy="true">Đang tính tải của các cụm máy chủ…</p>
                } @else if (!eligibleCells().length) {
                  <p class="warnbox" role="alert">{{ msg0103(modelLabel[s2().tenancyModel]) }}</p>
                }
                <div class="scroll">
                  <table class="tbl">
                    <caption class="sr-only">
                      Cụm máy chủ phù hợp với mức cô lập
                      {{
                        modelLabel[s2().tenancyModel]
                      }}
                    </caption>
                    <thead>
                      <tr>
                        <th scope="col"><span class="sr-only">Chọn</span></th>
                        <th scope="col">Cụm máy chủ</th>
                        <th scope="col">Trung tâm dữ liệu</th>
                        <th scope="col" class="r">Số đơn vị</th>
                        <th scope="col" class="r">Tài khoản hiện có</th>
                        <th scope="col">Sau khi thêm</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (c of cellRows(); track c.code) {
                        <tr [class.sel]="s2().cellCode === c.code" [class.off]="!c.eligible">
                          <td>
                            <input
                              type="radio"
                              name="cell"
                              [id]="'cell-' + c.code"
                              [checked]="s2().cellCode === c.code"
                              [disabled]="!c.eligible || !canEditSetup()"
                              (change)="patch2({ cellCode: c.code })"
                            />
                          </td>
                          <td>
                            <label [for]="'cell-' + c.code"
                              ><strong>{{ c.code }}</strong> · {{ c.name }}</label
                            >
                          </td>
                          <td>
                            {{ dc(c.primaryDc) }}
                            @if (c.standbyDc) {
                              · dự phòng {{ dc(c.standbyDc) }}
                            }
                          </td>
                          <td class="r">{{ int(c.tenantCount) }}</td>
                          <td class="r">{{ accounts(c.accountCount) }}</td>
                          <td>
                            <span class="meter" aria-hidden="true"
                              ><span
                                class="fill"
                                [class.hi]="!c.eligible"
                                [style.width.%]="min100(c.loadAfter)"
                              ></span
                            ></span>
                            {{ percent(c.loadAfter) }}
                            @if (c.status !== 'HOAT_DONG') {
                              · đang bảo trì
                            } @else if (!c.eligible) {
                              · vượt ngưỡng
                            }
                          </td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
                <fieldset class="grid three" [disabled]="!canEditSetup()">
                  <legend class="sr-only">Tên miền, vùng dữ liệu, khoá mã hoá</legend>
                  <label class="f">
                    <span>Tên miền truy cập <span class="req" aria-hidden="true">*</span></span>
                    <input
                      pInputText
                      [ngModel]="s2().domain"
                      (ngModelChange)="patch2({ domain: $event })"
                      name="domain"
                      aria-describedby="domain-hint"
                    />
                    <small
                      id="domain-hint"
                      [class.err]="domainOk() === false || !domainFormatOk()"
                      [class.muted]="domainOk() !== false && domainFormatOk()"
                    >
                      @if (domainOk() === false) {
                        {{ msg0104(s2().domain) }}
                      } @else if (!domainFormatOk()) {
                        Tên miền chưa đúng định dạng, ví dụ donvi.okr.viettel.vn
                      } @else {
                        Hệ thống kiểm tra trùng tên miền khi lưu
                      }
                    </small>
                    @if (fe()['domain']) {
                      <small class="err">{{ fe()['domain'] }}</small>
                    }
                  </label>
                  <label class="f">
                    <span>Khu vực lưu dữ liệu <span class="req" aria-hidden="true">*</span></span>
                    <select
                      class="native"
                      [ngModel]="s2().dataRegion"
                      (ngModelChange)="patch2({ dataRegion: $event })"
                      name="region"
                    >
                      <option value="HOALAC">Việt Nam · Hoà Lạc (chính)</option>
                      <option value="PHIANAM">Việt Nam · Phía Nam</option>
                    </select>
                  </label>
                  <label class="f">
                    <span>Khoá mã hoá dữ liệu <span class="req" aria-hidden="true">*</span></span>
                    <select
                      class="native"
                      [ngModel]="s2().kekMode"
                      (ngModelChange)="patch2({ kekMode: $event })"
                      name="kek"
                    >
                      <option value="RIENG">{{ kekLabel.RIENG }}</option>
                      <option value="DUNG_CHUNG_CELL" [disabled]="s2().tenancyModel === 'silo'">
                        {{ kekLabel.DUNG_CHUNG_CELL }}
                      </option>
                    </select>
                    @if (fe()['kekMode']) {
                      <small class="err">{{ fe()['kekMode'] }}</small>
                    }
                  </label>
                </fieldset>
              </section>
            }
            @case (3) {
              @if (job(); as j) {
                <section class="box" aria-labelledby="prov-title" aria-live="polite">
                  <h2 id="prov-title" class="h">Tiến trình cấp phát hạ tầng</h2>
                  <p class="muted">
                    <!-- NFR-07 -->
                    Đơn vị mới sẵn sàng trong vòng một ngày (CSDL riêng không quá 2 giờ, dùng chung
                    không quá 5 phút). Việc cấp phát vẫn tiếp tục khi đồng chí đóng màn hình.
                  </p>
                  <ol class="saga">
                    @for (p of provisionSteps; track p; let i = $index) {
                      <li
                        [class.done]="sagaState(i) === 'done'"
                        [class.cur]="sagaState(i) === 'cur'"
                        [class.bad]="sagaState(i) === 'bad'"
                      >
                        <span class="dot" aria-hidden="true">{{
                          sagaState(i) === 'done' ? '✓' : sagaState(i) === 'bad' ? '!' : i + 1
                        }}</span>
                        {{ p }}
                        <span class="sr-only">: {{ sagaText(i) }}</span>
                      </li>
                    }
                  </ol>
                  <p>{{ j.progress ?? 0 }}% · {{ j.message ?? '' }}</p>
                </section>
              } @else {
                <section class="box" aria-labelledby="pp-title">
                  <h2 id="pp-title" class="h">3. Bộ quy định đánh giá và bộ giao diện</h2>
                  <fieldset class="grid" [disabled]="!canEditSetup()">
                    <legend class="sr-only">Bộ quy định đánh giá và bộ giao diện</legend>
                    @for (fl of ob()?.flows ?? []; track fl) {
                      <label class="f wide">
                        <span
                          >Bộ quy định đánh giá luồng {{ flowLabel[fl] }}
                          <span class="req" aria-hidden="true">*</span></span
                        >
                        <select
                          class="native"
                          [ngModel]="s3().policyPacks[fl] ?? ''"
                          (ngModelChange)="setPack(fl, $event)"
                          [attr.name]="'pack-' + fl"
                        >
                          <option value="" disabled>Chọn bộ quy định đã phát hành</option>
                          @for (p of packsFor(fl); track p.id) {
                            <option [value]="p.id">
                              {{ packText(p) }} · đạt {{ goldenText(p) }} bộ ca kiểm thử chuẩn
                            </option>
                          }
                        </select>
                        @if (packWarning(fl); as w) {
                          <small class="err">{{ w }}</small>
                        }
                      </label>
                    }
                    @if (fe()['policyBindings']) {
                      <small class="err wide">{{ fe()['policyBindings'] }}</small>
                    }
                    <label class="f wide">
                      <span
                        >Bộ giao diện của đơn vị <span class="req" aria-hidden="true">*</span></span
                      >
                      <select
                        class="native"
                        [ngModel]="s3().uiPackVersionId"
                        (ngModelChange)="
                          s3.set({ policyPacks: s3().policyPacks, uiPackVersionId: $event })
                        "
                        name="ui"
                      >
                        <option value="" disabled>Chọn bộ giao diện đã phát hành</option>
                        @for (u of uiOptions(); track u.id) {
                          <option [value]="u.id">{{ uiPackText(u) }}</option>
                        }
                      </select>
                      @if (fe()['uiPackVersionId']) {
                        <small class="err">{{ fe()['uiPackVersionId'] }}</small>
                      }
                    </label>
                  </fieldset>
                  <p class="muted small">
                    Nút "Tiếp tục" lưu lựa chọn rồi bắt đầu cấp phát hạ tầng: tạo vùng lưu trữ dữ
                    liệu, khoá mã hoá, không gian đăng nhập, tên miền, chứng chỉ TLS và cài đặt bộ
                    quy định đánh giá, bộ giao diện cho đơn vị.
                  </p>
                </section>
              }
            }
            @case (4) {
              <section class="box" aria-labelledby="idp-title">
                <h2 id="idp-title" class="h">4. Đăng nhập</h2>
                <p class="muted small">
                  Khai báo hệ thống đăng nhập của đơn vị (liên kết chuẩn OpenID Connect hoặc SAML2),
                  bật đăng nhập bằng VNeID. Không gian đăng nhập của đơn vị đã được tạo khi cấp
                  phát. Việc gán nhóm người dùng với vai trò thực hiện ở chức năng Phân quyền.
                </p>
                <div class="scroll">
                  <table class="tbl">
                    <caption class="sr-only">
                      Hệ thống đăng nhập của đơn vị
                    </caption>
                    <thead>
                      <tr>
                        <th scope="col">Loại</th>
                        <th scope="col">Tên định danh</th>
                        <th scope="col">Địa chỉ</th>
                        <th scope="col">Kết nối</th>
                        <th scope="col"><span class="sr-only">Thao tác</span></th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (i of idps(); track $index; let k = $index) {
                        <tr>
                          <td>{{ idpLabel[kindOf(i)] }}</td>
                          <td>
                            <input
                              pInputText
                              class="full"
                              [ngModel]="i.idpAlias"
                              (ngModelChange)="editIdp(k, { idpAlias: $event })"
                              [attr.aria-label]="'Tên định danh hệ thống đăng nhập ' + (k + 1)"
                              maxlength="64"
                              [disabled]="!canEditConfig()"
                            />
                          </td>
                          <td>
                            <input
                              pInputText
                              class="full"
                              [ngModel]="i.issuer"
                              (ngModelChange)="editIdp(k, { issuer: $event })"
                              [attr.aria-label]="'Địa chỉ hệ thống đăng nhập ' + (k + 1)"
                              placeholder="https://…"
                              maxlength="255"
                              [disabled]="!canEditConfig()"
                            />
                          </td>
                          <td>
                            <span class="status" [class]="'status r-' + idpResult(i)">{{
                              idpStatus(i)
                            }}</span>
                            @if (i.message) {
                              <span class="small block muted">{{ i.message }}</span>
                            }
                          </td>
                          <td>
                            @if (canEditConfig()) {
                              <p-button
                                icon="pi pi-trash"
                                severity="contrast"
                                [text]="true"
                                size="small"
                                [ariaLabel]="'Xoá ' + (i.idpAlias || idpLabel[kindOf(i)])"
                                (onClick)="removeIdp(k)"
                              />
                            }
                          </td>
                        </tr>
                      } @empty {
                        <tr>
                          <td colspan="5" class="muted">Chưa khai báo hệ thống đăng nhập nào.</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
                @if (fe()['idps']) {
                  <small class="err">{{ fe()['idps'] }}</small>
                }
                @if (canEditConfig()) {
                  <div class="row">
                    <label for="idp-kind" class="sr-only">Loại hệ thống đăng nhập thêm mới</label>
                    <select id="idp-kind" class="native" [(ngModel)]="newIdpKind" name="newIdp">
                      @for (k of addableIdps; track k) {
                        <option [value]="k">{{ idpLabel[k] }}</option>
                      }
                    </select>
                    <p-button
                      label="Thêm hệ thống đăng nhập"
                      icon="pi pi-plus"
                      severity="contrast"
                      [outlined]="true"
                      (onClick)="addIdp()"
                    />
                    <p-button
                      label="Kiểm tra kết nối"
                      icon="pi pi-link"
                      [loading]="testing()"
                      [disabled]="!idps().length"
                      (onClick)="testIdp()"
                    />
                  </div>
                }
              </section>
            }
            @case (5) {
              <section class="box" aria-labelledby="hr-title">
                <h2 id="hr-title" class="h">5. Tổ chức, nhân sự</h2>
                @if (externalSync()) {
                  <fieldset class="f" [disabled]="!canEditConfig()">
                    <legend>Nguồn nhân sự <span class="req" aria-hidden="true">*</span></legend>
                    @for (h of hrSources; track h) {
                      <label class="chk block">
                        <input
                          type="checkbox"
                          [checked]="hr().includes(h)"
                          (change)="toggleHr(h, $any($event.target).checked)"
                        />
                        {{ hrLabel[h] }}
                      </label>
                    }
                    @if (fe()['staffSources']) {
                      <small class="err">{{ fe()['staffSources'] }}</small>
                    }
                  </fieldset>
                  <p class="muted small">
                    <!-- SCR-0103, SCR-0104, BR-01-10 -->
                    Cây tổ chức được nhập ở chức năng Cây tổ chức; đồng bộ và đối soát nhân sự ở chức
                    năng Đồng bộ và đối soát nhân sự. Đơn vị chỉ sẵn sàng kích hoạt khi đã có cây tổ
                    chức và không còn mục lệch mức Cao.
                  </p>
                } @else {
                  <!-- ADR-34: đồng bộ từ hệ thống ngoài đang tắt, không cần chọn nguồn nhân sự -->
                  <p class="muted">
                    Cây tổ chức được nhập ở chức năng Cây tổ chức. Tài khoản cán bộ do quản trị đơn
                    vị tạo trực tiếp ở chức năng Người dùng và phân quyền.
                  </p>
                }
              </section>
            }
            @case (6) {
              <section class="box" aria-labelledby="chk-title">
                <h2 id="chk-title" class="h">6. Kiểm tra tổng thể và kích hoạt</h2>
                @if (rechecking()) {
                  <p class="muted" aria-busy="true">Đang kiểm tra…</p>
                } @else if (!checkRows().length) {
                  <p class="muted">Kết quả kiểm tra có sau khi cấp phát hạ tầng xong.</p>
                }
                <ul class="checks">
                  @for (c of checkRows(); track c.code) {
                    <li>
                      <span class="status" [class]="'status r-' + c.result">{{
                        checkLabel[c.result]
                      }}</span>
                      {{ c.label }}
                      @if (c.detail) {
                        <span class="muted"> · {{ c.detail }}</span>
                      }
                    </li>
                  }
                </ul>
                <p-button
                  label="Kiểm tra lại"
                  icon="pi pi-refresh"
                  severity="contrast"
                  [outlined]="true"
                  size="small"
                  [loading]="rechecking()"
                  (onClick)="recheck()"
                />
                @if (ob()?.status === 'DANG_VAN_HANH') {
                  <p class="okbox" role="status">
                    Đơn vị đã được kích hoạt và đang vận hành. Bước tiếp theo: quản trị của đơn vị
                    tạo kỳ đánh giá đầu tiên.
                    <a routerLink="/admin/tenants">Về danh sách đơn vị triển khai</a>
                  </p>
                }
              </section>
            }
          }

          <div class="nav">
            @if (viewStep() > 1 && !job()) {
              <p-button
                label="Quay lại"
                icon="pi pi-angle-left"
                severity="contrast"
                [outlined]="true"
                (onClick)="go(viewStep() - 1)"
              />
            }
            <span class="spacer"></span>
            @if (job()) {
              <!-- saga cấp phát đang chạy: không thao tác tiếp cho tới khi có kết quả -->
            } @else if (viewStep() < 6) {
              @if (canContinue()) {
                <p-button
                  [label]="continueLabel()"
                  icon="pi pi-angle-right"
                  iconPos="right"
                  [loading]="saving()"
                  [disabled]="!stepValid()"
                  (onClick)="next()"
                />
              } @else if (viewStep() < reached()) {
                <p-button
                  label="Bước sau"
                  icon="pi pi-angle-right"
                  iconPos="right"
                  severity="contrast"
                  [outlined]="true"
                  (onClick)="go(viewStep() + 1)"
                />
              }
            } @else if (ob()?.status !== 'DANG_VAN_HANH' && can('SCR-0102.kich-hoat')) {
              <p-button
                label="Kích hoạt"
                icon="pi pi-check"
                [loading]="saving()"
                [disabled]="!readyToActivate()"
                (onClick)="activate()"
              />
            }
          </div>
        </div>

        @if (!simple()) {
          <aside>
            <section class="box" aria-labelledby="sum-title">
              <h2 id="sum-title" class="h">Tóm tắt cấu hình</h2>
              <dl>
                <dt>Đơn vị</dt>
                <dd>
                  {{ s1().name || '—' }}
                  @if (s1().code) {
                    · mã {{ s1().code }}
                  }
                </dd>
                <dt>Luồng đánh giá</dt>
                <dd>{{ flowsText() }}</dd>
                <dt>Quy mô dự kiến</dt>
                <dd>
                  {{ int(s1().expectedAccounts || 0) }} tài khoản ·
                  {{ int(s1().expectedUnits || 0) }} đơn vị
                </dd>
                <dt>Cụm máy chủ</dt>
                <dd>{{ s2().cellCode ?? 'Chưa chọn' }} · {{ modelLabel[s2().tenancyModel] }}</dd>
                <dt>Tên miền</dt>
                <dd>{{ s2().domain || '—' }}</dd>
                <dt>Bộ quy định đánh giá</dt>
                <dd>{{ packsText() }}</dd>
                <dt>Bộ giao diện</dt>
                <dd>{{ uiText() }}</dd>
                <dt>Đăng nhập</dt>
                <dd>{{ idpsText() }}</dd>
                @if (externalSync()) {
                  <dt>Nguồn nhân sự</dt>
                  <dd>{{ hrText() }}</dd>
                }
              </dl>
            </section>
            @if (viewStep() <= 3) {
              <section class="box" aria-labelledby="pre-title">
                <h2 id="pre-title" class="h">Kiểm tra trước khi tiếp tục</h2>
                <ul class="checks">
                  @for (c of preChecks(); track c.label) {
                    <li>
                      <span class="status" [class]="'status r-' + c.result">{{
                        checkLabel[c.result]
                      }}</span>
                      {{ c.label }}
                    </li>
                  }
                </ul>
              </section>
            }
          </aside>
        }
      </div>
    } @else {
      <p class="muted" aria-busy="true">Đang tải hồ sơ khởi tạo…</p>
    }
  `,
  styles: `
    .head {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 12px;
      flex-wrap: wrap;
    }
    h1 {
      font-size: 1.4rem;
      margin: 8px 0 4px;
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
    .actions,
    .row {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
      align-items: center;
    }
    .row {
      margin-top: 12px;
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
      margin: 0 0 12px;
    }
    .stepper ol {
      list-style: none;
      display: flex;
      flex-wrap: wrap;
      gap: 8px 20px;
      margin: 0;
      padding: 0;
    }
    .stp {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      background: none;
      border: 0;
      padding: 4px;
      font: inherit;
      color: #4b5563;
    }
    button.stp {
      cursor: pointer;
      text-decoration: underline;
    }
    .num,
    .dot {
      display: inline-grid;
      place-items: center;
      width: 26px;
      height: 26px;
      border-radius: 50%;
      border: 1px solid #9ca3af;
      font-size: 0.85em;
    }
    li.done .num {
      background: #146c2e;
      border-color: #146c2e;
      color: #fff;
    }
    li.cur .stp {
      color: #9b0022;
      font-weight: 600;
    }
    .cols {
      display: grid;
      grid-template-columns: minmax(0, 2fr) minmax(280px, 1fr);
      gap: 16px;
      align-items: start;
    }
    @media (max-width: 1100px) {
      .cols {
        grid-template-columns: 1fr;
      }
    }
    fieldset {
      border: 0;
      margin: 0;
      padding: 0;
      min-width: 0;
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 12px 16px;
    }
    .grid.three {
      grid-template-columns: repeat(3, minmax(0, 1fr));
      margin-top: 12px;
    }
    @media (max-width: 800px) {
      .grid,
      .grid.three {
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
    }
    .f > span,
    legend {
      font-weight: 500;
    }
    .inline {
      flex-direction: row;
      flex-wrap: wrap;
      gap: 16px;
      align-items: center;
    }
    .chk {
      display: inline-flex;
      gap: 8px;
      align-items: center;
    }
    .chk.block {
      display: flex;
      margin: 6px 0;
    }
    .req,
    .err {
      color: #b4001f;
    }
    .native {
      padding: 8px 10px;
      border: 1px solid var(--p-inputtext-border-color, #cbd5e1);
      border-radius: 6px;
      font: inherit;
      background: #fff;
      color: inherit;
      min-height: 40px;
    }
    .models {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 12px;
    }
    @media (max-width: 900px) {
      .models {
        grid-template-columns: 1fr;
      }
    }
    .model {
      border: 1px solid #d1d5db;
      border-radius: 8px;
      padding: 12px;
      cursor: pointer;
      display: block;
    }
    .model.sel {
      border: 2px solid #9b0022;
      background: #fdf2f4;
    }
    .model.off {
      opacity: 0.6;
      cursor: not-allowed;
    }
    .hint {
      margin: 12px 0 0;
      padding: 10px 12px;
      border-left: 4px solid #1e40af;
      background: #eef4ff;
      border-radius: 4px;
    }
    .scroll {
      overflow-x: auto;
    }
    .tbl {
      width: 100%;
      border-collapse: collapse;
    }
    .tbl th {
      text-align: left;
    }
    .tbl th,
    .tbl td {
      padding: 8px;
      border-bottom: 1px solid var(--p-content-border-color, #e5e7eb);
      vertical-align: middle;
    }
    .tbl tr.sel {
      background: #fdf2f4;
    }
    .tbl tr.off td {
      color: #6b7280;
    }
    .r {
      text-align: right;
    }
    .full {
      width: 100%;
    }
    .meter {
      display: inline-block;
      width: 60px;
      height: 8px;
      border-radius: 4px;
      background: #e5e7eb;
      overflow: hidden;
      vertical-align: middle;
      margin-right: 4px;
    }
    .meter .fill {
      display: block;
      height: 100%;
      background: #1f9d55;
    }
    .meter .fill.hi {
      background: #c53030;
    }
    .status {
      display: inline-block;
      padding: 1px 8px;
      border-radius: 999px;
      font-size: 0.85em;
      white-space: nowrap;
      margin-right: 6px;
    }
    .r-DAT {
      background: #e7f6ec;
      color: #146c2e;
    }
    .r-CANH_BAO,
    .r-CHUA_KIEM_TRA {
      background: #fff4d6;
      color: #6b4400;
    }
    .r-LOI {
      background: #fde8e8;
      color: #9b1c1c;
    }
    .checks,
    .saga {
      list-style: none;
      padding: 0;
      margin: 0 0 12px;
      display: grid;
      gap: 8px;
    }
    .saga .dot {
      margin-right: 8px;
    }
    .saga li.done .dot {
      background: #146c2e;
      color: #fff;
      border-color: #146c2e;
    }
    .saga li.cur {
      font-weight: 600;
    }
    .saga li.bad .dot {
      background: #9b1c1c;
      color: #fff;
    }
    dl {
      display: grid;
      grid-template-columns: max-content 1fr;
      gap: 6px 12px;
      margin: 0;
    }
    dt {
      color: #4b5563;
    }
    dd {
      margin: 0;
    }
    .nav {
      display: flex;
      gap: 8px;
      align-items: center;
      margin-bottom: 16px;
    }
    .spacer {
      flex: 1;
    }
    .warnbox,
    .okbox {
      padding: 8px 12px;
      border-radius: 6px;
    }
    .warnbox {
      border: 1px solid #f3d27a;
      background: #fff8e1;
      color: #6b4400;
    }
    .okbox {
      border: 1px solid #9fd8b0;
      background: #e7f6ec;
      color: #145c28;
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
    :host ::ng-deep .msg {
      margin: 8px 0;
      width: 100%;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
  `,
})
export class TenantOnboardingPage {
  private readonly api = inject(TenantApi);
  private readonly jobs = inject(JobTracker);
  private readonly router = inject(Router);
  protected readonly can = inject(ADMIN_CAN);
  private readonly flags = inject(FeatureFlagService);
  /** Đồng bộ nhân sự từ hệ thống ngoài có bật không (ADR-34, mặc định tắt). */
  protected readonly externalSync = computed(() => this.flags.isEnabled(EXTERNAL_SYNC_FLAG));
  private readonly theme = inject(ThemeService);
  /** Chế độ Đảng viên cao tuổi rút gọn: bỏ vùng tóm tắt, chỉ còn bước đang làm (NFR-11). */
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  /** Tham số tuyến `:id` (không có khi khai báo mới) và `?step=`. */
  readonly id = input<string>();
  readonly step = input<string>();

  protected readonly steps = ONBOARDING_STEPS;
  protected readonly provisionSteps = PROVISION_STEPS;
  protected readonly modelCards = MODEL_CARDS;
  protected readonly sectors = SECTORS;
  protected readonly flows = FLOWS;
  protected readonly hrSources = STAFF_SOURCES;
  protected readonly sectorLabel = TENANT_SECTOR_LABEL;
  protected readonly flowLabel = TENANT_FLOW_LABEL;
  protected readonly statusLabel = TENANT_STATUS_LABEL;
  protected readonly kekLabel = KEK_MODE_LABEL;
  protected readonly modelLabel = TENANCY_MODEL_LABEL;
  protected readonly idpLabel = IDP_KIND_LABEL;
  protected readonly hrLabel = STAFF_SOURCE_LABEL;
  protected readonly checkLabel = CHECK_RESULT_LABEL;
  protected readonly addableIdps: IdpKind[] = ['OIDC', 'SAML', 'VNEID'];
  protected readonly int = fmtInt;
  protected readonly accounts = fmtAccounts;
  protected readonly percent = fmtPercent;
  protected readonly dateTime = fmtDateTime;
  protected readonly date = fmtDate;
  protected readonly msg0103 = MSG_01_03;
  protected readonly msg0104 = MSG_01_04;
  protected readonly min100 = (r: number) => Math.min(100, r * 100);
  protected readonly dc = (code: string) => DC_LABEL[code] ?? 'Trung tâm dữ liệu khác';
  protected readonly kindOf = (i: Pick<IdpRow, 'protocol' | 'isVneid'>) => idpKind(i);

  protected readonly record = rxResource({
    params: () => this.id(),
    stream: ({ params }) => (params ? this.api.get(params) : of(null)),
  });
  /** Bản hồ sơ hiện tại (`ConfigTenant`); cập nhật sau mỗi lần lưu mà không tải lại. */
  protected readonly ob = linkedSignal<TenantOnboarding | null>(() =>
    this.record.hasValue() ? this.record.value() : null,
  );
  protected readonly loadError = computed(() =>
    this.record.error() ? errorText(this.record.error(), 'Không tải được hồ sơ khởi tạo.') : null,
  );

  /**
   * Bước xa nhất mở được: `onboardingStep` do máy chủ tính từ dữ liệu đã lưu (null khi đã vận hành:
   * xem được cả sáu bước). Khi saga đang chạy, màn hình dừng ở bước 3 để hiện tiến trình.
   */
  protected readonly reached = computed(() => {
    const o = this.ob();
    if (!o) return 1;
    if (o.status === 'DANG_CAP_PHAT') return 3;
    return o.onboardingStep ?? 6;
  });
  /**
   * Bước đang xem: `?step=` nếu có, không thì bước xa nhất. Chỉ tính lại khi đổi hồ sơ hoặc tham số
   * tuyến; lưu nháp làm máy chủ tính lại `onboardingStep` nhưng không tự chuyển bước đang xem.
   */
  private readonly stepKey = computed(() => `${this.ob()?.id ?? ''}|${this.step() ?? ''}`);
  protected readonly viewStep = linkedSignal<string, number>({
    // `computed` chuỗi: linkedSignal chỉ tính lại khi khoá đổi giá trị, không theo mỗi lần lưu
    source: this.stepKey,
    computation: () => {
      const reached = untracked(this.reached);
      const wanted = Number(untracked(this.step)) || reached;
      return Math.max(1, Math.min(wanted, reached));
    },
  });

  // ---- dữ liệu từng bước (bản đang soạn) ----
  protected readonly s1 = linkedSignal<Step1>(() => {
    const o = this.ob();
    if (!o) return emptyStep1();
    return {
      name: o.name,
      code: o.code,
      sector: o.sector ?? 'CHINH_QUYEN',
      contractRef: o.contractRef ?? '',
      contractValidTo: o.contractValidTo,
      flows: [...o.flows],
      expectedAccounts: o.expectedAccounts ?? 0,
      expectedUnits: o.expectedUnits ?? 0,
      adminName: o.adminContact?.fullName ?? '',
      adminEmail: o.adminContact?.email ?? '',
    };
  });
  protected readonly s2 = linkedSignal<Step2>(() => {
    const o = this.ob();
    const model = o?.tenancyModel ?? (o ? tenantSuggestion(o) : 'pool');
    return {
      tenancyModel: model,
      cellCode: o?.cellCode ?? null,
      domain: o?.domain ?? (o ? `${o.code.replace(/_/g, '-')}.okr.viettel.vn` : ''),
      dataRegion: o?.dataRegion ?? 'HOALAC',
      kekMode: o?.kekMode ?? (model === 'pool' ? 'DUNG_CHUNG_CELL' : 'RIENG'),
    };
  });
  protected readonly s3 = linkedSignal<Step3>(() => {
    const o = this.ob();
    const packs: Partial<Record<TenantFlow, string>> = {};
    for (const b of o?.policyBindings ?? []) packs[b.flow] = b.packVersionId;
    return { policyPacks: packs, uiPackVersionId: o?.uiPackVersionId ?? '' };
  });
  protected readonly idps = linkedSignal<IdpRow[]>(() => (this.ob()?.idps ?? []).map(toRow));
  protected readonly hr = linkedSignal<StaffSourceType[]>(() =>
    (this.ob()?.staffSources ?? []).map((x) => x.type),
  );
  protected newIdpKind: IdpKind = 'OIDC';

  protected readonly cellOptions = rxResource({
    params: () =>
      this.viewStep() === 2 && this.ob()
        ? { model: this.s2().tenancyModel, accounts: this.ob()!.expectedAccounts ?? 0 }
        : undefined,
    stream: ({ params }) => this.api.cellOptions(params.model, params.accounts),
  });
  protected readonly cellRows = computed<CellOption[]>(() =>
    this.cellOptions.hasValue() ? this.cellOptions.value() : [],
  );
  protected readonly eligibleCells = computed(() => this.cellRows().filter((c) => c.eligible));

  private readonly packsDang = rxResource({
    params: () => (this.ob() ? 'DANG' : undefined),
    stream: () => this.api.policyPacks('DANG'),
  });
  private readonly packsNn = rxResource({
    params: () => (this.ob() ? 'NHA_NUOC' : undefined),
    stream: () => this.api.policyPacks('NHA_NUOC'),
  });
  protected readonly uiPacks = rxResource({
    params: () => (this.ob() ? true : undefined),
    stream: () => this.api.uiPacks(),
  });
  /** UI Pack chọn được; giữ cả bộ đang gắn nếu không còn trong danh sách đang phát hành. */
  protected readonly uiOptions = computed<UiPackOption[]>(() => {
    const list = this.uiPacks.hasValue() ? this.uiPacks.value() : [];
    const o = this.ob();
    if (o?.uiPackVersionId && o.uiPack && !list.some((u) => u.id === o.uiPackVersionId)) {
      return [{ id: o.uiPackVersionId, name: o.uiPack.name, version: o.uiPack.version }, ...list];
    }
    return list;
  });

  /** Kiểm tra tổng thể của bước 6 (`ConfigTenant.readinessChecks`). */
  protected readonly checkRows = computed(() =>
    (this.ob()?.readinessChecks ?? []).map((c) => ({
      code: c.code,
      label: READINESS_CHECK_LABEL[c.code],
      result: (c.passed ? 'DAT' : 'LOI') as CheckResult,
      detail: c.detail,
    })),
  );

  protected readonly domainOk = signal<boolean | null>(null);
  protected readonly saving = signal(false);
  protected readonly testing = signal(false);
  protected readonly rechecking = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly conflict = signal(false);
  protected readonly fe = signal<Record<string, string>>({});
  protected readonly confirmCancel = signal(false);
  protected readonly job = signal<Job | null>(null);
  /** MSG-01-07: lỗi của saga cấp phát gần nhất đã bù trừ (từ `Job.problem`). */
  protected readonly provisionFailure = signal<string | null>(null);
  private tracking: Subscription | null = null;
  private lastJobChecked: string | null = null;

  // ---- quyền ----
  protected readonly canEditSetup = computed(
    () => this.can('SCR-0102.buoc-1-3') && (!this.ob() || this.ob()!.status === 'KHOI_TAO'),
  );
  protected readonly canEditConfig = computed(
    () =>
      this.can('SCR-0102.buoc-4-5') &&
      ['DANG_CAU_HINH', 'SAN_SANG'].includes(this.ob()?.status ?? ''),
  );
  protected readonly canEditCurrent = computed(() =>
    this.viewStep() <= 3 ? this.canEditSetup() : this.viewStep() <= 5 && this.canEditConfig(),
  );
  protected readonly canContinue = computed(() => this.canEditCurrent());

  constructor() {
    // vùng 3: mặc định cell phù hợp có tải sau khi thêm thấp nhất (BR-01-02)
    effect(() => {
      const best = [...this.eligibleCells()].sort((a, b) => a.loadAfter - b.loadAfter)[0];
      if (best && !this.s2().cellCode && this.canEditSetup()) {
        this.s2.update((s) => ({ ...s, cellCode: best.code }));
      }
    });
    // bước 3: mặc định bộ giao diện đầu tiên đang phát hành khi chưa chọn
    effect(() => {
      const first = this.uiOptions()[0];
      if (first && !this.s3().uiPackVersionId && this.canEditSetup()) {
        this.s3.update((s) => ({ ...s, uiPackVersionId: first.id }));
      }
    });
    // mở lại khi saga đang chạy: theo dõi tiếp tác vụ ở máy chủ (NFR-07); saga gần nhất đã bù trừ
    // về KHOI_TAO thì đọc job để hiện MSG-01-07
    effect(() => {
      const o = this.ob();
      if (!o?.provisionJobId) return;
      if (o.status === 'DANG_CAP_PHAT' && !this.tracking) {
        this.track(this.api.provisionLocation(o.provisionJobId));
      } else if (o.status === 'KHOI_TAO' && this.lastJobChecked !== o.provisionJobId) {
        this.lastJobChecked = o.provisionJobId;
        this.api.provisionJob(o.provisionJobId).subscribe({
          next: (j) =>
            this.provisionFailure.set(
              j.status === 'THAT_BAI'
                ? (j.problem?.title ?? 'Cấp phát hạ tầng không thành công.')
                : null,
            ),
          error: () => this.provisionFailure.set(null),
        });
      }
    });
  }

  // ---- gợi ý, kiểm tra nhanh phía trình duyệt (máy chủ kiểm lại) ----
  protected readonly suggestion = computed<TenancyModel>(() => {
    const o = this.ob();
    return o ? tenantSuggestion(o) : 'pool';
  });
  protected readonly suggestionReason = computed(() => {
    const o = this.ob();
    if (!o) return '';
    const n = fmtInt(o.expectedAccounts ?? 0);
    // BR-01-01
    if (this.suggestion() === 'silo') {
      return (o.expectedAccounts ?? 0) > 1_000_000
        ? `quy mô dự kiến ${n} tài khoản vượt 1.000.000`
        : `đơn vị thuộc diện cách ly`;
    }
    if (this.suggestion() === 'bridge') {
      return `quy mô dự kiến ${n} tài khoản, từ 5.000 đến 1.000.000`;
    }
    return `quy mô dự kiến ${n} tài khoản dưới 5.000`;
  });
  protected allowed(model: TenancyModel): boolean {
    return !this.ob() || modelAllowed(model, this.suggestion());
  }

  protected packsFor(flow: TenantFlow): PolicyPackOption[] {
    const r = flow === 'DANG' ? this.packsDang : this.packsNn;
    return r.hasValue() ? r.value() : [];
  }
  protected packText(p: Pick<PolicyPackOption, 'name' | 'version'>): string {
    return `${p.name} phiên bản ${p.version}`;
  }
  protected goldenText(p: PolicyPackOption): string {
    return p.goldenPassRate === null ? 'chưa chạy' : fmtPercent(p.goldenPassRate);
  }
  protected uiPackText(u: UiPackOption): string {
    return u.version ? `${u.name} phiên bản ${u.version}` : u.name;
  }
  protected packWarning(flow: TenantFlow): string | null {
    const id = this.s3().policyPacks[flow];
    const p = this.packsFor(flow).find((x) => x.id === id);
    return p && packNotReady(p) ? MSG_01_05(this.packText(p)) : null;
  }

  protected readonly domainFormatOk = computed(() => {
    const d = this.s2().domain.trim().toLowerCase();
    return !d || DOMAIN_RE.test(d);
  });

  protected readonly preChecks = computed(() => {
    const out: { label: string; result: CheckResult }[] = [];
    const cell = this.cellRows().find((c) => c.code === this.s2().cellCode);
    out.push({
      label: cell
        ? `Dung lượng ${cell.code} sau khi thêm ${fmtPercent(cell.loadAfter)}, dưới 85%`
        : 'Dung lượng cụm máy chủ sau khi thêm dưới 85%',
      result: cell?.eligible || (this.ob()?.cellCode && !cell) ? 'DAT' : 'LOI',
    });
    const d = this.s2().domain.trim();
    out.push({
      label: 'Tên miền đúng định dạng, chưa dùng; chứng chỉ TLS cấp tự động',
      result: this.domainOk() === false || !d || !this.domainFormatOk() ? 'LOI' : 'DAT',
    });
    const flows = this.ob()?.flows ?? this.s1().flows;
    const packsOk = flows.every((f) => this.s3().policyPacks[f] && !this.packWarning(f));
    out.push({
      label: 'Bộ quy định đánh giá đã phát hành, đạt 100% bộ ca kiểm thử chuẩn, đủ cho mỗi luồng',
      result: packsOk ? 'DAT' : this.viewStep() < 3 ? 'CANH_BAO' : 'LOI',
    });
    return out;
  });

  protected readonly stepValid = computed(() => {
    switch (this.viewStep()) {
      case 1: {
        const s = this.s1();
        return (
          !!s.name.trim() &&
          /^[a-z0-9_]{2,32}$/.test(s.code) &&
          !!s.contractRef.trim() &&
          s.flows.length > 0 &&
          Number(s.expectedAccounts) > 0 &&
          !!s.adminName.trim() &&
          EMAIL_RE.test(s.adminEmail.trim())
        );
      }
      case 2: {
        const s = this.s2();
        const cell = this.cellRows().find((c) => c.code === s.cellCode);
        return (
          !!cell?.eligible &&
          !!s.domain.trim() &&
          this.domainFormatOk() &&
          this.domainOk() !== false
        );
      }
      case 3: {
        const flows = this.ob()?.flows ?? [];
        return (
          flows.every((f) => this.s3().policyPacks[f] && !this.packWarning(f)) &&
          !!this.s3().uiPackVersionId
        );
      }
      case 4:
        return this.idps().length > 0 && this.idps().every((i) => i.lastTestOk === true);
      case 5:
        return !this.externalSync() || this.hr().length > 0;
      default:
        return true;
    }
  });

  protected readonly continueLabel = computed(() => {
    const n = this.viewStep();
    if (n === 3) return 'Tiếp tục: bắt đầu cấp phát hạ tầng';
    return `Tiếp tục: ${ONBOARDING_STEPS[n]}`;
  });

  protected readonly readyToActivate = computed(
    () =>
      this.checkRows().length > 0 &&
      this.checkRows().every((c) => c.result !== 'LOI') &&
      ['SAN_SANG', 'DANG_CAU_HINH'].includes(this.ob()?.status ?? ''),
  );

  // ---- tóm tắt (vùng 4) ----
  protected readonly flowsText = computed(
    () =>
      this.s1()
        .flows.map((f) => TENANT_FLOW_LABEL[f])
        .join(' + ') || '—',
  );
  protected readonly packsText = computed(() => {
    const chosen = Object.entries(this.s3().policyPacks) as [TenantFlow, string][];
    if (!chosen.length) return 'Chưa chọn';
    return chosen
      .map(([flow, id]) => {
        const p = this.packsFor(flow).find((x) => x.id === id);
        return p ? this.packText(p) : `Đã chọn cho luồng ${TENANT_FLOW_LABEL[flow]}`;
      })
      .join('; ');
  });
  protected readonly uiText = computed(() => {
    const u = this.uiOptions().find((x) => x.id === this.s3().uiPackVersionId);
    return u ? this.uiPackText(u) : 'Chưa chọn';
  });
  protected readonly idpsText = computed(
    () =>
      this.idps()
        .map((i) => i.idpAlias || IDP_KIND_LABEL[idpKind(i)])
        .join('; ') || 'Không gian đăng nhập của đơn vị (tạo khi cấp phát)',
  );
  protected readonly hrText = computed(
    () =>
      this.hr()
        .map((h) => STAFF_SOURCE_LABEL[h])
        .join('; ') || 'Chưa chọn',
  );

  // ---- thao tác ----
  protected set1<K extends keyof Step1>(key: K, value: Step1[K]) {
    this.s1.update((s) => ({ ...s, [key]: value }));
  }
  protected toggleFlow(flow: TenantFlow, on: boolean) {
    this.s1.update((s) => ({
      ...s,
      flows: on ? [...new Set([...s.flows, flow])] : s.flows.filter((f) => f !== flow),
    }));
  }
  protected setModel(model: TenancyModel) {
    this.s2.update((s) => ({
      ...s,
      tenancyModel: model,
      cellCode: null,
      kekMode: model === 'silo' ? 'RIENG' : s.kekMode,
    }));
  }
  protected patch2(p: Partial<Step2>) {
    if ('domain' in p) this.domainOk.set(null);
    this.s2.update((s) => ({ ...s, ...p }));
  }
  protected setPack(flow: TenantFlow, id: string) {
    this.s3.update((s) => ({ ...s, policyPacks: { ...s.policyPacks, [flow]: id } }));
  }
  protected editIdp(k: number, p: Partial<IdpRow>) {
    this.idps.update((list) =>
      list.map((i, j) => (j === k ? { ...i, ...p, lastTestOk: undefined, message: undefined } : i)),
    );
  }
  protected addIdp() {
    const kind = this.newIdpKind;
    this.idps.update((l) => [
      ...l,
      {
        idpAlias: kind === 'VNEID' ? 'vneid' : '',
        protocol: kind === 'SAML' ? 'SAML' : 'OIDC',
        issuer: '',
        isVneid: kind === 'VNEID',
      },
    ]);
  }
  protected removeIdp(k: number) {
    this.idps.update((l) => l.filter((_, j) => j !== k));
  }
  protected idpResult(i: IdpRow): CheckResult | 'CHUA_KIEM_TRA' {
    return i.lastTestOk === true ? 'DAT' : i.lastTestOk === false ? 'LOI' : 'CHUA_KIEM_TRA';
  }
  protected idpStatus(i: IdpRow): string {
    return i.lastTestOk === true ? 'Đạt' : i.lastTestOk === false ? 'Lỗi' : 'Chưa kiểm tra';
  }
  protected toggleHr(h: StaffSourceType, on: boolean) {
    this.hr.update((l) => (on ? [...new Set([...l, h])] : l.filter((x) => x !== h)));
  }

  // ui-text-ok: giá trị trạng thái nội bộ của bước, không hiện lên màn hình
  protected sagaState(i: number): 'done' | 'cur' | 'todo' | 'bad' {
    const j = this.job();
    // ui-text-ok: giá trị trạng thái nội bộ của bước, không hiện lên màn hình
    if (!j) return 'todo';
    if (j.status === 'HOAN_TAT') return 'done';
    const cur = Math.max(0, PROVISION_STEPS.indexOf(j.message as (typeof PROVISION_STEPS)[number]));
    if (i < cur) return 'done';
    if (i === cur) return j.status === 'THAT_BAI' ? 'bad' : 'cur';
    // ui-text-ok: giá trị trạng thái nội bộ của bước, không hiện lên màn hình
    return 'todo';
  }
  protected sagaText(i: number): string {
    return { done: 'đã xong', cur: 'đang thực hiện', todo: 'chờ thực hiện', bad: 'lỗi' }[
      this.sagaState(i)
    ];
  }

  protected go(n: number) {
    this.notice.set(null);
    this.error.set(null);
    this.viewStep.set(n);
    const id = this.ob()?.id;
    if (id) {
      void this.router.navigate(['/admin/tenants', id, 'onboarding'], {
        queryParams: { step: n },
        replaceUrl: true,
      });
    }
  }

  /** Phần thay đổi của bước đang xem để gửi PATCH (chỉ trường của `ConfigTenantPatch`). */
  private stepPatch(): TenantOnboardingPatch {
    switch (this.viewStep()) {
      case 1: {
        const s = this.s1();
        return {
          name: s.name.trim(),
          contractRef: s.contractRef.trim(),
          ...(s.contractValidTo ? { contractValidTo: s.contractValidTo } : {}),
          flows: s.flows,
          expectedAccounts: Number(s.expectedAccounts),
          ...(Number(s.expectedUnits) > 0 ? { expectedUnits: Number(s.expectedUnits) } : {}),
          adminContact: { fullName: s.adminName.trim(), email: s.adminEmail.trim() },
        };
      }
      case 2: {
        const s = this.s2();
        return {
          tenancyModel: s.tenancyModel,
          ...(s.cellCode ? { cellCode: s.cellCode } : {}),
          domain: s.domain.trim().toLowerCase(),
          dataRegion: s.dataRegion,
          kekMode: s.kekMode,
        };
      }
      case 3: {
        const s = this.s3();
        const flows = this.ob()?.flows ?? [];
        return {
          policyBindings: flows
            .filter((f) => s.policyPacks[f])
            .map((flow) => ({ flow, packVersionId: s.policyPacks[flow]! })),
          ...(s.uiPackVersionId ? { uiPackVersionId: s.uiPackVersionId } : {}),
        };
      }
      case 4:
        return { idps: this.idps().map(toIdp) };
      case 5:
        return { staffSources: this.hr().map((type) => ({ type })) };
      default:
        return {};
    }
  }

  private fail(e: unknown, fallback: string) {
    const p = toProblem(e);
    const fields = fieldErrors(p);
    // MSG-01-04 hiện ở dòng gợi ý của ô tên miền, không nhắc lại dưới ô
    const { domain: _taken, ...rest } = fields;
    this.fe.set(p.code === DOMAIN_TAKEN ? rest : fields);
    this.conflict.set(p.status === 409 && p.code === VERSION_CONFLICT);
    if (p.code === DOMAIN_TAKEN || (p.status === 422 && fields['domain'])) {
      this.domainOk.set(p.code === DOMAIN_TAKEN ? false : null);
    }
    this.error.set(Object.keys(fields).length ? null : errorText(e, fallback));
    this.saving.set(false);
  }

  private clear() {
    this.error.set(null);
    this.notice.set(null);
    this.fe.set({});
    this.conflict.set(false);
  }

  protected saveDraft() {
    const o = this.ob();
    if (!o) return;
    this.clear();
    this.saving.set(true);
    this.api.saveDraft(o, this.stepPatch()).subscribe({
      next: (r) => {
        this.ob.set(r);
        this.saving.set(false);
        this.notice.set('Đã lưu nháp.');
      },
      error: (e) => this.fail(e, 'Không lưu được nháp.'),
    });
  }

  protected next() {
    this.clear();
    const n = this.viewStep();
    const o = this.ob();
    this.saving.set(true);
    if (!o) {
      const s = this.s1();
      const body: TenantOnboardingInput = {
        code: s.code,
        name: s.name.trim(),
        sector: s.sector,
        contractRef: s.contractRef.trim(),
        ...(s.contractValidTo ? { contractValidTo: s.contractValidTo } : {}),
        flows: s.flows,
        expectedAccounts: Number(s.expectedAccounts),
        ...(Number(s.expectedUnits) > 0 ? { expectedUnits: Number(s.expectedUnits) } : {}),
        adminContact: { fullName: s.adminName.trim(), email: s.adminEmail.trim() },
      };
      this.api.create(body).subscribe({
        next: (r) => {
          this.saving.set(false);
          void this.router.navigate(['/admin/tenants', r.id, 'onboarding'], {
            queryParams: { step: 2 },
            replaceUrl: true,
          });
        },
        error: (e) => this.fail(e, 'Không khai báo được đơn vị triển khai.'),
      });
      return;
    }
    // bước 3 chỉ sang bước 4 khi saga cấp phát xong (máy chủ ghi nhận)
    this.api.saveDraft(o, this.stepPatch()).subscribe({
      next: (r) => {
        this.ob.set(r);
        if (n === 3) {
          this.startProvision(r);
          return;
        }
        this.saving.set(false);
        this.go(n + 1);
      },
      error: (e) => this.fail(e, 'Không lưu được hồ sơ.'),
    });
  }

  private startProvision(o: TenantOnboarding) {
    this.provisionFailure.set(null);
    this.api.provision(o).subscribe({
      next: (location) => {
        this.saving.set(false);
        this.track(location);
      },
      error: (e) => this.fail(e, 'Không bắt đầu được việc cấp phát hạ tầng.'),
    });
  }

  private track(location: string) {
    this.viewStep.set(3);
    this.job.set({ id: '', type: 'PROVISION', status: 'CHO', progress: 0 });
    this.tracking = this.jobs.track(location).subscribe({
      next: (j) => this.job.set(j),
      error: (e) => {
        this.tracking = null;
        this.job.set(null);
        this.error.set(errorText(e, 'Mất kết nối khi theo dõi tiến trình cấp phát hạ tầng.'));
      },
      complete: () => {
        const j = this.job();
        this.tracking = null;
        this.lastJobChecked = j?.id ?? null;
        this.api.get(this.ob()!.id).subscribe((r) => {
          this.ob.set(r);
          this.job.set(null);
          if (j?.status === 'HOAN_TAT') {
            this.go(4);
            this.notice.set(MSG_01_06(provisionMinutes(r)));
          } else {
            // MSG-01-07: máy chủ đã bù trừ về KHOI_TAO
            this.provisionFailure.set(j?.problem?.title ?? 'Cấp phát hạ tầng không thành công.');
          }
        });
      },
    });
  }

  /**
   * Bước 4: lưu danh sách IdP đang soạn rồi kiểm tra từng IdP theo `idpAlias` (hợp đồng kiểm một IdP
   * mỗi lần); máy chủ ghi `lastTestOk`, màn hình đọc lại hồ sơ để có `rowVersion` mới.
   */
  protected testIdp() {
    const o = this.ob();
    if (!o) return;
    this.clear();
    this.testing.set(true);
    this.api
      .saveDraft(o, { idps: this.idps().map(toIdp) })
      .pipe(
        switchMap((saved) =>
          from(saved.idps ?? []).pipe(
            concatMap((i) =>
              this.api.testIdp(saved.id, i.idpAlias).pipe(map((res) => [i.idpAlias, res] as const)),
            ),
            toArray(),
          ),
        ),
        switchMap((results) =>
          this.api.get(o.id).pipe(map((fresh) => ({ fresh, results: new Map(results) }))),
        ),
      )
      .subscribe({
        next: ({ fresh, results }) => {
          this.ob.set(fresh);
          this.idps.update((list) =>
            list.map((i) => {
              const res = results.get(i.idpAlias);
              return res ? { ...i, lastTestOk: res.success, message: res.message } : i;
            }),
          );
          this.testing.set(false);
        },
        error: (e) => {
          this.testing.set(false);
          this.fail(e, 'Không kiểm tra được kết nối hệ thống đăng nhập.');
        },
      });
  }

  /** Bước 6 "Kiểm tra lại": đọc lại hồ sơ để có `readinessChecks` mới nhất. */
  protected recheck() {
    const o = this.ob();
    if (!o) return;
    this.rechecking.set(true);
    this.reload(o.id).subscribe({
      next: () => this.rechecking.set(false),
      error: (e) => {
        this.rechecking.set(false);
        this.error.set(errorText(e, 'Không kiểm tra lại được.'));
      },
    });
  }

  private reload(id: string): Observable<TenantOnboarding> {
    return this.api.get(id).pipe(
      map((r) => {
        this.ob.set(r);
        return r;
      }),
    );
  }

  protected activate() {
    const o = this.ob();
    if (!o) return;
    this.clear();
    this.saving.set(true);
    this.api.activate(o).subscribe({
      next: (r) => {
        this.ob.set(r);
        this.saving.set(false);
        this.notice.set(
          `Đã kích hoạt đơn vị ${r.name}. Đã gửi thư cho đầu mối quản trị của đơn vị.`,
        );
      },
      error: (e) => this.fail(e, 'Không kích hoạt được đơn vị triển khai.'),
    });
  }

  protected cancel() {
    const o = this.ob();
    if (!o) return;
    this.clear();
    this.saving.set(true);
    this.api.cancel(o).subscribe({
      next: () => void this.router.navigate(['/admin/tenants']),
      error: (e) => this.fail(e, 'Không huỷ được yêu cầu.'),
    });
  }

  protected reloadAfterConflict() {
    this.clear();
    this.record.reload();
  }
}
