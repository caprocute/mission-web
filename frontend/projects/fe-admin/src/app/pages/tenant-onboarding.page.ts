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
 * Chức năng: Lưu nháp (PATCH /config/tenants/{id}, If-Match); Tiếp tục (cuối bước 3 POST /provision 202 +
 *   JobTracker, NFR-07 hiện tiến trình từng bước con); Kiểm tra kết nối IdP (POST /idp/test); Kích hoạt
 *   (POST /activate, MFA bước lên, EVT-21); Huỷ (DELETE khi KHOI_TAO).
 * Thông báo: MSG-01-03 không còn cell, MSG-01-04 trùng tên miền, MSG-01-05 gói chưa đạt test vàng,
 *   MSG-01-06 cấp phát thành công, MSG-01-07 cấp phát lỗi đã hoàn tác. BR-01-10 lệch mức Cao chặn kích hoạt.
 * Quyền (wiki/15a): SCR-0102.buoc-1-3, SCR-0102.buoc-4-5, SCR-0102.kich-hoat, SCR-0102.huy.
 */
import { Component, computed, effect, inject, input, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  CHECK_RESULT_LABEL,
  CellOption,
  HR_SOURCE_LABEL,
  HrSource,
  IDP_KIND_LABEL,
  IdpKind,
  Job,
  JobTracker,
  KEK_MODE_LABEL,
  KekMode,
  ONBOARDING_STEPS,
  PROVISION_STEPS,
  PolicyPackOption,
  TENANT_FLOW_LABEL,
  TENANT_SECTOR_LABEL,
  TENANT_STATUS_LABEL,
  TenancyModel,
  TenantApi,
  TenantFlow,
  TenantIdp,
  TenantOnboarding,
  TenantOnboardingInput,
  TenantOnboardingPatch,
  fieldErrors,
  modelAllowed,
  suggestTenancyModel,
  toProblem,
} from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { ThemeService } from '@vt/design-system';
import { Subscription, of } from 'rxjs';
import { ADMIN_CAN } from '../admin-permissions';
import { errorText, fmtAccounts, fmtDateTime, fmtInt, fmtPercent } from '../admin-ui';

/** MSG-01-03 */
export const MSG_01_03 = (model: string) =>
  `Không còn cell ${model} nào dưới ngưỡng 85% sau khi thêm tenant. Vui lòng mở yêu cầu dựng cell mới.`;
/** MSG-01-04 */
export const MSG_01_04 = (domain: string) => `Tên miền ${domain} đã được sử dụng.`;
/** MSG-01-05 */
export const MSG_01_05 = (pack: string) =>
  `Gói chính sách ${pack} chưa đạt 100% test case vàng nên chưa gắn được.`;
/** MSG-01-06 */
export const MSG_01_06 = (minutes: number) =>
  `Cấp phát tenant thành công trong ${minutes} phút. Đã gửi thư cho đầu mối quản trị tenant.`;

const MODEL_CARDS: { model: TenancyModel; title: string; text: string; build: string }[] = [
  {
    model: 'silo',
    title: 'Silo · cell riêng',
    text: 'Toàn bộ hạ tầng riêng (CSDL, Kafka, kho tệp, HSM). Cho khối Đảng TW, tỉnh trên 1 triệu tài khoản, LLVT.',
    build: 'Thời gian dựng: 3–5 ngày',
  },
  {
    model: 'bridge',
    title: 'Bridge · CSDL riêng',
    text: 'Một CSDL PostgreSQL riêng cho tenant trong cụm của cell nhóm tỉnh; service dùng chung. Khoá mã hoá riêng.',
    build: 'Thời gian dựng: ≤ 2 giờ',
  },
  {
    model: 'pool',
    title: 'Pool · bảng chung',
    text: 'Bảng chung có tenant_id và Row-Level Security. Cho xã, chi bộ, đơn vị dưới 5.000 tài khoản.',
    build: 'Thời gian dựng: ≤ 5 phút',
  },
];

const DC_LABEL: Record<string, string> = {
  HOALAC: 'Hoà Lạc',
  PHIANAM: 'Phía Nam',
  TAICHO: 'Tại chỗ',
};

const SECTORS = Object.keys(TENANT_SECTOR_LABEL);
const FLOWS: TenantFlow[] = ['DANG', 'NHA_NUOC'];
const HR_SOURCES = Object.keys(HR_SOURCE_LABEL) as HrSource[];

type Step1 = TenantOnboardingInput;

interface Step2 {
  tenancyModel: TenancyModel;
  cellCode: string | null;
  domain: string;
  dataRegion: string;
  kekMode: KekMode;
}

interface Step3 {
  policyPacks: Partial<Record<TenantFlow, string>>;
  uiPackId: string;
}

function emptyStep1(): Step1 {
  return {
    name: '',
    code: '',
    sector: 'CHINH_QUYEN',
    contractRef: '',
    contractSignedOn: undefined,
    flows: ['DANG', 'NHA_NUOC'],
    expectedAccounts: 0,
    expectedUnits: 0,
    isolationRequired: false,
    adminName: '',
    adminEmail: '',
  };
}

@Component({
  selector: 'vt-tenant-onboarding-page',
  imports: [FormsModule, RouterLink, ButtonModule, InputTextModule, MessageModule, SelectModule],
  template: `
    <nav class="muted" aria-label="Đường dẫn">
      <a routerLink="/admin/tenants">Danh sách tenant</a> /
      <span aria-current="page">Khởi tạo tenant</span>
    </nav>

    @if (loadError(); as e) {
      <p-message severity="error" styleClass="msg">
        <span>{{ e }}</span>
        <button type="button" class="link" (click)="record.reload()">Thử lại</button>
      </p-message>
    } @else if (!id() || ob()) {
      <header class="head">
        <div>
          <h1>Khởi tạo tenant{{ ob() ? ': ' + ob()!.name : '' }}</h1>
          @if (ob(); as o) {
            <p class="muted">
              Mã yêu cầu {{ o.onboardingCode }} · hợp đồng {{ o.contractRef }}
              @if (o.contractSignedOn) {
                ký {{ date(o.contractSignedOn) }}
              }
              · bắt đầu {{ dateTime(o.startedAt) }} · {{ statusLabel[o.status] }}
            </p>
          } @else {
            <p class="muted">Mã yêu cầu được hệ thống sinh khi lưu bước 1.</p>
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
      @if (ob()?.provisionError && ob()?.status === 'KHOI_TAO'; as _) {
        <p-message severity="error" styleClass="msg">
          Cấp phát lỗi ở bước {{ ob()!.provisionError!.step }}: {{ ob()!.provisionError!.reason }}.
          Hệ thống đã hoàn tác các bước trước.
        </p-message>
      }

      <div class="cols">
        <div>
          @switch (viewStep()) {
            @case (1) {
              <section class="box" aria-labelledby="s1-title">
                <h2 id="s1-title" class="h">1. Thông tin tenant</h2>
                <fieldset class="grid" [disabled]="!canEditSetup()">
                  <legend class="sr-only">Thông tin tenant</legend>
                  <label class="f wide">
                    <span>Tên tenant <span class="req" aria-hidden="true">*</span></span>
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
                    <span>Mã tenant <span class="req" aria-hidden="true">*</span></span>
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
                      >Chữ thường không dấu, số, dấu gạch dưới; dùng trong tên miền và URN</small
                    >
                    @if (fe()['code']) {
                      <small class="err">{{ fe()['code'] }}</small>
                    }
                  </label>
                  <label class="f">
                    <span>Nhóm khách hàng</span>
                    <select
                      class="native"
                      [ngModel]="s1().sector"
                      (ngModelChange)="set1('sector', $event)"
                      name="sector"
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
                    <span>Ngày ký hợp đồng</span>
                    <input
                      pInputText
                      type="date"
                      [ngModel]="s1().contractSignedOn"
                      (ngModelChange)="set1('contractSignedOn', $event)"
                      name="signed"
                    />
                  </label>
                  <fieldset class="f wide inline">
                    <legend>Luồng áp dụng <span class="req" aria-hidden="true">*</span></legend>
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
                  <label class="chk wide">
                    <input
                      type="checkbox"
                      [ngModel]="s1().isolationRequired"
                      (ngModelChange)="set1('isolationRequired', $event)"
                      name="isolation"
                    />
                    Yêu cầu cách ly (khối Đảng Trung ương, lực lượng vũ trang): bắt buộc mô hình
                    silo
                  </label>
                  <label class="f">
                    <span>Đầu mối quản trị tenant</span>
                    <input
                      pInputText
                      [ngModel]="s1().adminName"
                      (ngModelChange)="set1('adminName', $event)"
                      name="adminName"
                    />
                  </label>
                  <label class="f">
                    <span>Thư điện tử đầu mối</span>
                    <input
                      pInputText
                      type="email"
                      [ngModel]="s1().adminEmail"
                      (ngModelChange)="set1('adminEmail', $event)"
                      name="adminEmail"
                    />
                  </label>
                </fieldset>
              </section>
            }
            @case (2) {
              <section class="box" aria-labelledby="s2-title">
                <h2 id="s2-title" class="h">2. Mô hình thuê bao</h2>
                <fieldset class="models" [disabled]="!canEditSetup()">
                  <legend class="sr-only">Mô hình thuê bao</legend>
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
                  Gợi ý của hệ thống: <strong>{{ suggestion() }}</strong
                  >, vì {{ suggestionReason() }}.
                </p>
              </section>
              <section class="box" aria-labelledby="s3-title">
                <h2 id="s3-title" class="h">Chọn cell</h2>
                @if (cellOptions.isLoading()) {
                  <p class="muted" aria-busy="true">Đang tính tải theo cell…</p>
                } @else if (!eligibleCells().length) {
                  <p class="warnbox" role="alert">{{ msg0103(s2().tenancyModel) }}</p>
                }
                <div class="scroll">
                  <table class="tbl">
                    <caption class="sr-only">
                      Cell ứng viên cho mô hình
                      {{
                        s2().tenancyModel
                      }}
                    </caption>
                    <thead>
                      <tr>
                        <th scope="col"><span class="sr-only">Chọn</span></th>
                        <th scope="col">Cell</th>
                        <th scope="col">Trung tâm dữ liệu</th>
                        <th scope="col" class="r">Tenant</th>
                        <th scope="col" class="r">Tài khoản hiện có</th>
                        <th scope="col">Sau khi thêm</th>
                        <th scope="col">Phiên bản phần mềm</th>
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
                              · bảo trì
                            } @else if (!c.eligible) {
                              · vượt ngưỡng
                            }
                          </td>
                          <td>{{ c.softwareVersion }}</td>
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
                      (blur)="checkDomain()"
                      name="domain"
                      aria-describedby="domain-hint"
                    />
                    <small
                      id="domain-hint"
                      [class.err]="domainOk() === false"
                      [class.muted]="domainOk() !== false"
                    >
                      @if (domainOk() === false) {
                        {{ msg0104(s2().domain) }}
                      } @else if (domainOk()) {
                        Kiểm tra trùng tại registry.tenant_domain · hợp lệ
                      } @else {
                        Kiểm tra trùng khi rời ô
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
                    <span>Khoá mã hoá (KEK) <span class="req" aria-hidden="true">*</span></span>
                    <select
                      class="native"
                      [ngModel]="s2().kekMode"
                      (ngModelChange)="patch2({ kekMode: $event })"
                      name="kek"
                    >
                      <option value="RIENG">{{ kekLabel.RIENG }}</option>
                      <option value="DUNG_CHUNG" [disabled]="s2().tenancyModel === 'silo'">
                        {{ kekLabel.DUNG_CHUNG }}
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
                  <h2 id="prov-title" class="h">Saga cấp phát</h2>
                  <p class="muted">
                    Mục tiêu NFR-07: tenant mới sẵn sàng trong một ngày (bridge ≤ 2 giờ, pool ≤ 5
                    phút). Tác vụ vẫn chạy ở máy chủ, đồng chí có thể đóng màn hình.
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
                  <h2 id="pp-title" class="h">3. Policy Pack gốc và UI Pack</h2>
                  <fieldset class="grid" [disabled]="!canEditSetup()">
                    <legend class="sr-only">Policy Pack và UI Pack</legend>
                    @for (fl of ob()?.flows ?? []; track fl) {
                      <label class="f wide">
                        <span
                          >Policy Pack gốc luồng {{ flowLabel[fl] }}
                          <span class="req" aria-hidden="true">*</span></span
                        >
                        <select
                          class="native"
                          [ngModel]="s3().policyPacks[fl] ?? ''"
                          (ngModelChange)="setPack(fl, $event)"
                          [attr.name]="'pack-' + fl"
                        >
                          <option value="" disabled>Chọn gói đã phát hành</option>
                          @for (p of packsFor(fl); track p.id) {
                            <option [value]="p.id">
                              {{ p.name }} {{ p.version }} · test case vàng {{ p.goldenPassRate }}%
                            </option>
                          }
                        </select>
                        @if (packWarning(fl); as w) {
                          <small class="err">{{ w }}</small>
                        }
                      </label>
                    }
                    @if (fe()['policyPacks']) {
                      <small class="err wide">{{ fe()['policyPacks'] }}</small>
                    }
                    <label class="f wide">
                      <span>UI Pack <span class="req" aria-hidden="true">*</span></span>
                      <select
                        class="native"
                        [ngModel]="s3().uiPackId"
                        (ngModelChange)="
                          s3.set({ policyPacks: s3().policyPacks, uiPackId: $event })
                        "
                        name="ui"
                      >
                        @for (u of uiPacks.value() ?? []; track u.id) {
                          <option [value]="u.id">{{ u.name }} {{ u.version }}</option>
                        }
                      </select>
                    </label>
                  </fieldset>
                  <p class="muted small">
                    "Tiếp tục" lưu lựa chọn rồi khởi chạy saga cấp phát: tạo CSDL hoặc đăng ký
                    tenant_id, khoá KEK, Organization trong Keycloak, tên miền, chứng chỉ TLS, sao
                    chép gói xuống cell.
                  </p>
                </section>
              }
            }
            @case (4) {
              <section class="box" aria-labelledby="idp-title">
                <h2 id="idp-title" class="h">4. Định danh</h2>
                <p class="muted small">
                  Khai báo IdP của tổ chức (OIDC, SAML hoặc LDAP liên kết), bật VNeID; ánh xạ nhóm
                  sang vai trò làm ở Phân quyền.
                </p>
                <table class="tbl">
                  <caption class="sr-only">
                    IdP của tenant
                  </caption>
                  <thead>
                    <tr>
                      <th scope="col">Loại</th>
                      <th scope="col">Tên</th>
                      <th scope="col">Địa chỉ</th>
                      <th scope="col">Kết nối</th>
                      <th scope="col"><span class="sr-only">Thao tác</span></th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (i of idps(); track $index; let k = $index) {
                      <tr>
                        <td>{{ idpLabel[i.kind] }}</td>
                        <td>
                          <input
                            pInputText
                            class="full"
                            [ngModel]="i.name"
                            (ngModelChange)="editIdp(k, { name: $event })"
                            [attr.aria-label]="'Tên IdP ' + (k + 1)"
                            [disabled]="!canEditConfig() || i.kind === 'KEYCLOAK_ORG'"
                          />
                        </td>
                        <td>
                          @if (i.kind === 'OIDC' || i.kind === 'SAML' || i.kind === 'LDAP') {
                            <input
                              pInputText
                              class="full"
                              [ngModel]="i.url ?? ''"
                              (ngModelChange)="editIdp(k, { url: $event })"
                              [attr.aria-label]="'Địa chỉ IdP ' + (k + 1)"
                              [placeholder]="i.kind === 'LDAP' ? 'ldaps://…' : 'https://…'"
                              [disabled]="!canEditConfig()"
                            />
                          } @else {
                            <span class="muted">—</span>
                          }
                        </td>
                        <td>
                          <span class="status" [class]="'status r-' + i.status">{{
                            idpStatus(i)
                          }}</span>
                          @if (i.message) {
                            <span class="small block muted">{{ i.message }}</span>
                          }
                        </td>
                        <td>
                          @if (canEditConfig() && i.kind !== 'KEYCLOAK_ORG') {
                            <p-button
                              icon="pi pi-trash"
                              severity="contrast"
                              [text]="true"
                              size="small"
                              [ariaLabel]="'Xoá ' + i.name"
                              (onClick)="removeIdp(k)"
                            />
                          }
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
                @if (canEditConfig()) {
                  <div class="row">
                    <label for="idp-kind" class="sr-only">Loại IdP thêm mới</label>
                    <select id="idp-kind" class="native" [(ngModel)]="newIdpKind" name="newIdp">
                      @for (k of addableIdps; track k) {
                        <option [value]="k">{{ idpLabel[k] }}</option>
                      }
                    </select>
                    <p-button
                      label="Thêm IdP"
                      icon="pi pi-plus"
                      severity="contrast"
                      [outlined]="true"
                      (onClick)="addIdp()"
                    />
                    <p-button
                      label="Kiểm tra kết nối IdP"
                      icon="pi pi-link"
                      [loading]="testing()"
                      (onClick)="testIdp()"
                    />
                  </div>
                }
              </section>
            }
            @case (5) {
              <section class="box" aria-labelledby="hr-title">
                <h2 id="hr-title" class="h">5. Tổ chức, nhân sự</h2>
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
                </fieldset>
                <p class="muted small">
                  Cây tổ chức có lịch sử nhập ở SCR-0103, đồng bộ và đối soát lệch nhân sự ở
                  SCR-0104. Tenant chỉ sẵn sàng kích hoạt khi đã có cây tổ chức và không còn lệch
                  mức Cao (BR-01-10).
                </p>
              </section>
            }
            @case (6) {
              <section class="box" aria-labelledby="chk-title">
                <h2 id="chk-title" class="h">6. Kiểm tra tổng thể và kích hoạt</h2>
                @if (checks.isLoading()) {
                  <p class="muted" aria-busy="true">Đang kiểm tra…</p>
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
                  (onClick)="checks.reload()"
                />
                @if (ob()?.status === 'DANG_VAN_HANH') {
                  <p class="okbox" role="status">
                    Tenant đã kích hoạt và đang vận hành. Quản trị tenant tạo kỳ đánh giá đầu tiên.
                    <a routerLink="/admin/tenants">Về danh sách tenant</a>
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
              <!-- saga đang chạy: không thao tác tiếp cho tới khi có kết quả -->
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
                <dt>Tenant</dt>
                <dd>
                  {{ s1().name || '—' }}
                  @if (s1().code) {
                    · mã {{ s1().code }}
                  }
                </dd>
                <dt>Luồng áp dụng</dt>
                <dd>{{ flowsText() }}</dd>
                <dt>Quy mô dự kiến</dt>
                <dd>
                  {{ int(s1().expectedAccounts || 0) }} tài khoản ·
                  {{ int(s1().expectedUnits || 0) }} đơn vị
                </dd>
                <dt>Cell</dt>
                <dd>{{ s2().cellCode ?? 'Chưa chọn' }} · {{ s2().tenancyModel }}</dd>
                <dt>Tên miền</dt>
                <dd>{{ s2().domain || '—' }}</dd>
                <dt>Policy Pack gốc</dt>
                <dd>{{ packsText() }}</dd>
                <dt>UI Pack</dt>
                <dd>{{ uiText() }}</dd>
                <dt>IdP</dt>
                <dd>{{ idpsText() }}</dd>
                <dt>Nguồn nhân sự</dt>
                <dd>{{ hrText() }}</dd>
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
  protected readonly hrSources = HR_SOURCES;
  protected readonly sectorLabel = TENANT_SECTOR_LABEL;
  protected readonly flowLabel = TENANT_FLOW_LABEL;
  protected readonly statusLabel = TENANT_STATUS_LABEL;
  protected readonly kekLabel = KEK_MODE_LABEL;
  protected readonly idpLabel = IDP_KIND_LABEL;
  protected readonly hrLabel = HR_SOURCE_LABEL;
  protected readonly checkLabel = CHECK_RESULT_LABEL;
  protected readonly addableIdps: IdpKind[] = ['OIDC', 'SAML', 'LDAP', 'VNEID'];
  protected readonly int = fmtInt;
  protected readonly accounts = fmtAccounts;
  protected readonly percent = fmtPercent;
  protected readonly dateTime = fmtDateTime;
  protected readonly date = (iso: string) => fmtDateTime(iso).slice(0, 10);
  protected readonly msg0103 = MSG_01_03;
  protected readonly msg0104 = MSG_01_04;
  protected readonly min100 = (r: number) => Math.min(100, r * 100);
  protected readonly dc = (code: string) => DC_LABEL[code] ?? code;

  protected readonly record = rxResource({
    params: () => this.id(),
    stream: ({ params }) => (params ? this.api.onboarding(params) : of(null)),
  });
  /** Bản hồ sơ hiện tại; cập nhật sau mỗi lần lưu mà không tải lại. */
  protected readonly ob = linkedSignal<TenantOnboarding | null>(() =>
    this.record.hasValue() ? this.record.value() : null,
  );
  protected readonly loadError = computed(() =>
    this.record.error() ? errorText(this.record.error(), 'Không tải được hồ sơ khởi tạo.') : null,
  );

  protected readonly reached = computed(() => this.ob()?.step ?? 1);
  protected readonly viewStep = linkedSignal(() => {
    const wanted = Number(this.step()) || this.ob()?.step || 1;
    return Math.max(1, Math.min(wanted, this.reached()));
  });

  // ---- dữ liệu từng bước (bản đang soạn) ----
  protected readonly s1 = linkedSignal<Step1>(() => {
    const o = this.ob();
    if (!o) return emptyStep1();
    return {
      name: o.name,
      code: o.code,
      sector: o.sector,
      contractRef: o.contractRef,
      contractSignedOn: o.contractSignedOn,
      flows: [...o.flows],
      expectedAccounts: o.expectedAccounts,
      expectedUnits: o.expectedUnits,
      isolationRequired: o.isolationRequired,
      adminName: o.adminName,
      adminEmail: o.adminEmail,
    };
  });
  protected readonly s2 = linkedSignal<Step2>(() => {
    const o = this.ob();
    const model =
      o?.tenancyModel ?? suggestTenancyModel(o?.expectedAccounts ?? 0, !!o?.isolationRequired);
    return {
      tenancyModel: model,
      cellCode: o?.cellCode ?? null,
      domain: o?.domain ?? (o ? `${o.code.replace(/_/g, '-')}.okr.viettel.vn` : ''),
      dataRegion: o?.dataRegion ?? 'HOALAC',
      kekMode: o?.kekMode ?? (model === 'pool' ? 'DUNG_CHUNG' : 'RIENG'),
    };
  });
  protected readonly s3 = linkedSignal<Step3>(() => ({
    policyPacks: { ...(this.ob()?.policyPacks ?? {}) },
    uiPackId: this.ob()?.uiPackId ?? 'ui-mac-dinh-3.0',
  }));
  protected readonly idps = linkedSignal<TenantIdp[]>(() =>
    (this.ob()?.idps ?? []).map((i) => ({ ...i })),
  );
  protected readonly hr = linkedSignal<HrSource[]>(() => [...(this.ob()?.hrSources ?? [])]);
  protected newIdpKind: IdpKind = 'OIDC';

  protected readonly cellOptions = rxResource({
    params: () =>
      this.viewStep() === 2 && this.ob()
        ? { model: this.s2().tenancyModel, accounts: this.ob()!.expectedAccounts }
        : undefined,
    stream: ({ params }) => this.api.cellOptions(params.model, params.accounts),
  });
  protected readonly cellRows = computed<CellOption[]>(() =>
    this.cellOptions.hasValue() ? this.cellOptions.value() : [],
  );
  protected readonly eligibleCells = computed(() => this.cellRows().filter((c) => c.eligible));

  private readonly packsDang = rxResource({
    params: () => (this.viewStep() === 3 ? 'DANG' : undefined),
    stream: () => this.api.policyPacks('DANG'),
  });
  private readonly packsNn = rxResource({
    params: () => (this.viewStep() === 3 ? 'NHA_NUOC' : undefined),
    stream: () => this.api.policyPacks('NHA_NUOC'),
  });
  protected readonly uiPacks = rxResource({ stream: () => this.api.uiPacks() });

  protected readonly checks = rxResource({
    params: () => (this.viewStep() === 6 && this.ob() ? this.ob()!.id : undefined),
    stream: ({ params }) => this.api.checks(params),
  });
  protected readonly checkRows = computed(() =>
    this.checks.hasValue() ? this.checks.value() : [],
  );

  protected readonly domainOk = signal<boolean | null>(null);
  protected readonly saving = signal(false);
  protected readonly testing = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly conflict = signal(false);
  protected readonly fe = signal<Record<string, string>>({});
  protected readonly confirmCancel = signal(false);
  protected readonly job = signal<Job | null>(null);
  private tracking: Subscription | null = null;

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
    // mở lại khi saga đang chạy: theo dõi tiếp tác vụ ở máy chủ (NFR-07)
    effect(() => {
      const o = this.ob();
      if (o?.status === 'DANG_CAP_PHAT' && o.provisionLocation && !this.tracking) {
        this.track(o.provisionLocation);
      }
    });
  }

  // ---- gợi ý, kiểm tra nhanh phía trình duyệt (máy chủ kiểm lại) ----
  protected readonly suggestion = computed(() =>
    suggestTenancyModel(this.ob()?.expectedAccounts ?? 0, !!this.ob()?.isolationRequired),
  );
  protected readonly suggestionReason = computed(() => {
    const o = this.ob();
    if (!o) return '';
    const n = fmtInt(o.expectedAccounts);
    if (o.isolationRequired) return `tenant thuộc diện cách ly (BR-01-01)`;
    if (this.suggestion() === 'silo')
      return `quy mô dự kiến ${n} tài khoản vượt 1.000.000 (BR-01-01)`;
    if (this.suggestion() === 'bridge') {
      return `quy mô dự kiến ${n} tài khoản (5.000 – 1.000.000)`;
    }
    return `quy mô dự kiến ${n} tài khoản dưới 5.000`;
  });
  protected allowed(model: TenancyModel): boolean {
    const o = this.ob();
    return !o || modelAllowed(model, o.expectedAccounts, o.isolationRequired);
  }

  protected packsFor(flow: TenantFlow): PolicyPackOption[] {
    const r = flow === 'DANG' ? this.packsDang : this.packsNn;
    return r.hasValue() ? r.value() : [];
  }
  protected packWarning(flow: TenantFlow): string | null {
    const id = this.s3().policyPacks[flow];
    const p = this.packsFor(flow).find((x) => x.id === id);
    return p && p.goldenPassRate < 100 ? MSG_01_05(`${p.name} ${p.version}`) : null;
  }

  protected readonly preChecks = computed(() => {
    const out: { label: string; result: 'DAT' | 'CANH_BAO' | 'LOI' }[] = [];
    const cell = this.cellRows().find((c) => c.code === this.s2().cellCode);
    out.push({
      label: cell
        ? `Dung lượng ${cell.code} sau khi thêm ${fmtPercent(cell.loadAfter)}, dưới 85%`
        : 'Dung lượng cell sau khi thêm dưới 85%',
      result: cell?.eligible || (this.ob()?.cellCode && !cell) ? 'DAT' : 'LOI',
    });
    out.push({
      label: 'Tên miền chưa dùng, chứng chỉ TLS cấp tự động',
      result: this.domainOk() === false ? 'LOI' : this.s2().domain ? 'DAT' : 'LOI',
    });
    const flows = this.ob()?.flows ?? this.s1().flows;
    const packsOk = flows.every((f) => this.s3().policyPacks[f] && !this.packWarning(f));
    out.push({
      label: 'Policy Pack đã phát hành, có test case vàng 100%, đủ gói mỗi luồng',
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
          Number(s.expectedAccounts) > 0
        );
      }
      case 2: {
        const s = this.s2();
        const cell = this.cellRows().find((c) => c.code === s.cellCode);
        return !!cell?.eligible && !!s.domain.trim() && this.domainOk() !== false;
      }
      case 3: {
        const flows = this.ob()?.flows ?? [];
        return flows.every((f) => this.s3().policyPacks[f] && !this.packWarning(f));
      }
      case 4:
        return this.idps().length > 0 && this.idps().every((i) => i.status === 'DAT');
      case 5:
        return this.hr().length > 0;
      default:
        return true;
    }
  });

  protected readonly continueLabel = computed(() => {
    const n = this.viewStep();
    if (n === 3) return 'Tiếp tục: khởi chạy cấp phát';
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
    const ids = Object.values(this.s3().policyPacks);
    if (!ids.length) return 'Chưa chọn';
    const all = [...this.packsFor('DANG'), ...this.packsFor('NHA_NUOC')];
    return ids
      .map((id) => {
        const p = all.find((x) => x.id === id);
        return p ? `${p.name} ${p.version}` : id;
      })
      .join('; ');
  });
  protected readonly uiText = computed(() => {
    const u = (this.uiPacks.hasValue() ? this.uiPacks.value() : []).find(
      (x) => x.id === this.s3().uiPackId,
    );
    return u ? `${u.name} ${u.version}` : this.s3().uiPackId;
  });
  protected readonly idpsText = computed(
    () =>
      this.idps()
        .map((i) => i.name)
        .join('; ') || 'Organization của cell (tạo khi cấp phát)',
  );
  protected readonly hrText = computed(
    () =>
      this.hr()
        .map((h) => HR_SOURCE_LABEL[h])
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
  protected checkDomain() {
    const d = this.s2().domain.trim();
    if (!d) return;
    this.api.domainAvailable(d, this.ob()?.id).subscribe({
      next: (ok) => this.domainOk.set(ok),
      error: () => this.domainOk.set(null),
    });
  }
  protected setPack(flow: TenantFlow, id: string) {
    this.s3.update((s) => ({ ...s, policyPacks: { ...s.policyPacks, [flow]: id } }));
  }
  protected editIdp(k: number, p: Partial<TenantIdp>) {
    this.idps.update((list) =>
      list.map((i, j) =>
        j === k ? { ...i, ...p, status: 'CHUA_KIEM_TRA', message: undefined } : i,
      ),
    );
  }
  protected addIdp() {
    const kind = this.newIdpKind;
    this.idps.update((l) => [
      ...l,
      { kind, name: kind === 'VNEID' ? 'VNeID' : IDP_KIND_LABEL[kind], status: 'CHUA_KIEM_TRA' },
    ]);
  }
  protected removeIdp(k: number) {
    this.idps.update((l) => l.filter((_, j) => j !== k));
  }
  protected idpStatus(i: TenantIdp): string {
    return i.status === 'DAT' ? 'Đạt' : i.status === 'LOI' ? 'Lỗi' : 'Chưa kiểm tra';
  }
  protected toggleHr(h: HrSource, on: boolean) {
    this.hr.update((l) => (on ? [...new Set([...l, h])] : l.filter((x) => x !== h)));
  }

  protected sagaState(i: number): 'done' | 'cur' | 'todo' | 'bad' {
    const j = this.job();
    if (!j) return 'todo';
    if (j.status === 'HOAN_TAT') return 'done';
    const cur = Math.max(0, PROVISION_STEPS.indexOf(j.message as (typeof PROVISION_STEPS)[number]));
    if (i < cur) return 'done';
    if (i === cur) return j.status === 'THAT_BAI' ? 'bad' : 'cur';
    return 'todo';
  }
  protected sagaText(i: number): string {
    return { done: 'xong', cur: 'đang chạy', todo: 'chờ', bad: 'lỗi' }[this.sagaState(i)];
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

  /** Phần thay đổi của bước đang xem để gửi PATCH. */
  private stepPatch(): TenantOnboardingPatch {
    switch (this.viewStep()) {
      case 1: {
        const { code: _c, ...rest } = this.s1();
        void _c;
        return {
          ...rest,
          expectedAccounts: Number(rest.expectedAccounts),
          expectedUnits: Number(rest.expectedUnits),
        };
      }
      case 2: {
        const s = this.s2();
        return { ...s, cellCode: s.cellCode ?? undefined, domain: s.domain.trim() };
      }
      case 3:
        return { ...this.s3() };
      case 4:
        return { idps: this.idps() };
      case 5:
        return { hrSources: this.hr() };
      default:
        return {};
    }
  }

  private fail(e: unknown, fallback: string) {
    const p = toProblem(e);
    this.fe.set(fieldErrors(p));
    this.conflict.set(p.status === 409 && p.code === 'TENANT-409-CONFLICT');
    this.error.set(Object.keys(fieldErrors(p)).length ? null : errorText(e, fallback));
    if (p.status === 422 && fieldErrors(p)['domain']) this.domainOk.set(false);
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
      this.api
        .create({
          ...s,
          expectedAccounts: Number(s.expectedAccounts),
          expectedUnits: Number(s.expectedUnits),
        })
        .subscribe({
          next: (r) =>
            this.api.saveDraft(r, { step: 2 }).subscribe({
              next: (r2) => {
                this.saving.set(false);
                void this.router.navigate(['/admin/tenants', r2.id, 'onboarding'], {
                  queryParams: { step: 2 },
                  replaceUrl: true,
                });
              },
              error: (e) => this.fail(e, 'Không lưu được hồ sơ.'),
            }),
          error: (e) => this.fail(e, 'Không khai báo được tenant.'),
        });
      return;
    }
    // bước 3 chỉ sang bước 4 khi saga cấp phát xong (máy chủ ghi nhận)
    const step = n === 3 ? 3 : Math.min(6, n + 1);
    this.api.saveDraft(o, { ...this.stepPatch(), step }).subscribe({
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
    this.api.provision(o.id).subscribe({
      next: (location) => {
        this.saving.set(false);
        this.track(location);
      },
      error: (e) => this.fail(e, 'Không khởi chạy được saga cấp phát.'),
    });
  }

  private track(location: string) {
    this.viewStep.set(3);
    this.job.set({ id: '', type: 'TENANT_PROVISION', status: 'CHO', progress: 0 });
    this.tracking = this.jobs.track(location).subscribe({
      next: (j) => this.job.set(j),
      error: (e) => {
        this.tracking = null;
        this.job.set(null);
        this.error.set(errorText(e, 'Mất kết nối khi theo dõi saga cấp phát.'));
      },
      complete: () => {
        const j = this.job();
        this.tracking = null;
        this.api.onboarding(this.ob()!.id).subscribe((r) => {
          this.ob.set(r);
          this.job.set(null);
          if (j?.status === 'HOAN_TAT') {
            this.go(4);
            this.notice.set(MSG_01_06(r.provisionedMinutes ?? 0));
          } else if (!r.provisionError) {
            // MSG-01-07 đã hiện qua provisionError của hồ sơ; chỉ báo khi máy chủ không ghi lại
            this.error.set(j?.problem?.title ?? 'Cấp phát lỗi.');
          }
        });
      },
    });
  }

  protected testIdp() {
    const o = this.ob();
    if (!o) return;
    this.clear();
    this.testing.set(true);
    this.api.testIdp(o.id, this.idps()).subscribe({
      next: (list) => {
        this.idps.set(list);
        this.testing.set(false);
        // máy chủ lưu kết quả kiểm tra: lấy rowVersion mới để lưu nháp tiếp
        this.api.onboarding(o.id).subscribe((r) => this.ob.set({ ...r }));
      },
      error: (e) => {
        this.testing.set(false);
        this.error.set(errorText(e, 'Không kiểm tra được kết nối IdP.'));
      },
    });
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
        this.checks.reload();
        this.notice.set(`Đã kích hoạt tenant ${r.name}. Đã gửi thư cho đầu mối quản trị tenant.`);
      },
      error: (e) => this.fail(e, 'Không kích hoạt được tenant.'),
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
