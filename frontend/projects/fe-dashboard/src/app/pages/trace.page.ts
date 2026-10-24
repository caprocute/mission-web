/*
 * SCR-1603 Đi xuống tới minh chứng (QT-16 bước 5; TL-02 SCR-1603; WEB-047).
 * Trường: 1 mắt xích điểm, cấu phần (`appraisal.score_snapshot`); 2 KPI và bảng A, B, C (`scoring.score_trace`);
 * 3 sản phẩm (`product.deliverable`); 4 minh chứng và bảng phiên bản (`product.evidence_version`,
 * `evidence.object_meta`, `evidence.scan_result`; kiểm quyền C2, ghi nhật ký); 5 kế hoạch (`task.plan_version`);
 * 6 mục tiêu, KR (`okr.objective`, `okr.key_result`); 7 căn cứ (`okr.basis`).
 * Chức năng: chọn mắt xích (`GET /analytics/trace/{objectType}/{id}`), mở tệp minh chứng (`GET
 * /files/{objectId}/download-url`, nơi hợp đồng ánh xạ `GET /evidences/{id}/versions/{v}/content`), xuất chuỗi
 * bằng chứng (`POST /analytics/trace/exports`, 202), quay lại bảng điều hành. BR-16-03 chuỗi tám mắt xích;
 * BR-16-04 dữ liệu C2 lấy từ mission-core có kiểm quyền; mắt xích ngoài phạm vi hiện dạng che với MSG-16-06,
 * mở minh chứng hiện MSG-16-07. Mã băm, mã sổ cái chỉ đặt trong `data-*` (YC-PT-10).
 * Quyền (wiki/15a): SCR-1603.xem, .mo-c2, .xuat.
 */
import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AnalyticsApi, FileApi, TRACE_OBJECT_LABEL, TRACE_OBJECT_NOUN, TRACE_STATUS_LABEL, TraceLink, TraceObjectType } from '@vt/api-client/domains';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { TagModule } from 'primeng/tag';
import { DASHBOARD_CAN } from '../dashboard-permissions';
import { errorText, fmtDate, fmtDateTime, fmtNum } from '../dashboard-ui';

interface EvidenceVersionRow {
  versionNo: number;
  status: string;
  at?: string;
  note?: string;
}

@Component({
  selector: 'vt-trace-page',
  imports: [RouterLink, ButtonModule, MessageModule, TagModule],
  template: `
    <p class="crumb">
      @if (can('SCR-1601.xem')) {
        <a routerLink="/dashboard">Bảng điều hành</a>
      }
    </p>
    <div class="head">
      <div>
        <h1>Truy vết kết quả đánh giá</h1>
        <p class="muted">
          Chuỗi liên kết từ điểm đánh giá tới minh chứng, và ngược lên kế hoạch, mục tiêu, căn cứ.
        </p>
      </div>
      <div class="actions">
        @if (can('SCR-1603.xuat') && chain()) {
          <p-button
            label="Xuất chuỗi bằng chứng"
            icon="pi pi-download"
            severity="secondary"
            [loading]="exporting()"
            (onClick)="exportChain()"
          />
        }
        @if (can('SCR-1601.xem')) {
          <a pButton routerLink="/dashboard" label="Quay lại bảng điều hành" [outlined]="true"></a>
        }
      </div>
    </div>

    @if (notice(); as n) {
      <p-message severity="success" role="status">{{ n }}</p-message>
    }
    @if (error(); as e) {
      <p-message severity="error" role="alert">{{ e }}</p-message>
    }

    @if (res.error()) {
      <p-message severity="error" role="alert">{{ err(res.error()) }}</p-message>
    } @else if (chain(); as c) {
      <ol class="chain" [attr.data-ledger-ref]="c.ledgerRef ?? null" aria-label="Chuỗi truy vết">
        @for (l of c.links; track l.objectType + l.id) {
          <li
            [class.focus]="isFocus(l)"
            [class.masked]="l.masked"
            [attr.data-kind]="l.objectType"
            [attr.data-id]="l.id"
          >
            <span class="k">{{ kind(l.objectType) }}</span>
            @if (l.masked) {
              <span class="lbl"
                ><i class="pi pi-lock" aria-hidden="true"></i> Không có quyền xem</span
              >
            } @else if (isFocus(l)) {
              <span class="lbl" aria-current="step">{{ l.label }}</span>
            } @else {
              <a class="lbl" [routerLink]="['../../', l.objectType, l.id]">{{ l.label }}</a>
            }
            @if (!l.masked && l.value) {
              <span class="val">{{ l.value }}</span>
            }
            @if (!l.masked && l.status) {
              <span class="small">{{ statusLabel(l.status) }}</span>
            }
          </li>
        }
      </ol>

      @for (l of maskedLinks(); track l.id) {
        <p-message severity="warn" role="status">{{ maskedText(l) }}</p-message>
      }

      @if (focus(); as f) {
        <div class="grid cols">
          <section class="box" aria-labelledby="tv-d-h">
            <h2 class="h" id="tv-d-h">{{ kind(f.objectType) }}: {{ f.label }}</h2>
            @switch (f.objectType) {
              @case ('KPI_NHIEM_VU') {
                @if (f.details?.['task']) {
                  <p>{{ f.details?.['task'] }}</p>
                }
                <div class="table-wrap">
                  <table class="tbl">
                    <caption class="sr-only">
                      Thành phần KPI của nhiệm vụ
                    </caption>
                    <thead>
                      <tr>
                        <th scope="col">Thành phần</th>
                        <th scope="col">Giá trị</th>
                        <th scope="col">Căn cứ</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr>
                        <td>Khối lượng (A)</td>
                        <td>{{ dnum(f.details?.['a']) }}</td>
                        <td>Sản phẩm đạt theo kế hoạch</td>
                      </tr>
                      <tr>
                        <td>Chất lượng (B)</td>
                        <td>{{ dnum(f.details?.['b']) }}</td>
                        <td>{{ f.details?.['bNote'] ?? '—' }}</td>
                      </tr>
                      <tr>
                        <td>Tiến độ (C)</td>
                        <td>{{ dnum(f.details?.['c']) }}</td>
                        <td>{{ f.details?.['cNote'] ?? '—' }}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
                <p>
                  KPI nhiệm vụ = (A + B + C) / 3 = <strong>{{ f.value }}</strong>
                  @if (f.details?.['policyPack']) {
                    , theo {{ lower(f.details?.['policyPack']) }}
                  }
                  .
                </p>
              }
              @case ('MINH_CHUNG') {
                @if (versions().length) {
                  <p-message severity="info" role="status">
                    Minh chứng này đã có {{ versions().length }} phiên bản. Phiên bản được tính điểm
                    là v{{ f.versionNo }}.
                  </p-message>
                }
                <dl class="meta">
                  <dt>Tệp</dt>
                  <dd>{{ f.details?.['fileName'] ?? f.label }}</dd>
                  @if (f.details?.['sizeBytes']) {
                    <dt>Dung lượng</dt>
                    <dd>{{ size(f.details?.['sizeBytes']) }}</dd>
                  }
                  @if (f.status) {
                    <dt>Trạng thái</dt>
                    <dd>{{ statusLabel(f.status) }}</dd>
                  }
                  @if (f.details?.['documentNumber']) {
                    <dt>Số văn bản</dt>
                    <dd>{{ f.details?.['documentNumber'] }}</dd>
                  }
                  @if (f.details?.['submittedBy']) {
                    <dt>Người nộp</dt>
                    <dd>{{ f.details?.['submittedBy'] }}</dd>
                  }
                  @if (f.details?.['confirmedBy']) {
                    <dt>Người xác nhận</dt>
                    <dd>{{ f.details?.['confirmedBy'] }}</dd>
                  }
                  <dt>Kiểm tra tệp</dt>
                  <dd>
                    {{
                      f.details?.['scan'] === 'SACH' ? 'Không phát hiện mã độc' : 'Đang kiểm tra'
                    }}; nội dung được xác thực toàn vẹn
                  </dd>
                  @if (f.details?.['retainUntil']) {
                    <dt>Lưu trữ</dt>
                    <dd>Không thể sửa, xoá đến ngày {{ d(f.details?.['retainUntil']) }}</dd>
                  }
                </dl>
                @if (versions().length) {
                  <div class="table-wrap">
                    <table class="tbl versions">
                      <caption class="sr-only">
                        Các phiên bản minh chứng
                      </caption>
                      <thead>
                        <tr>
                          <th scope="col">Phiên bản</th>
                          <th scope="col">Trạng thái</th>
                          <th scope="col">Thời điểm</th>
                          <th scope="col">Ghi chú</th>
                        </tr>
                      </thead>
                      <tbody>
                        @for (v of versions(); track v.versionNo) {
                          <tr>
                            <td>v{{ v.versionNo }}</td>
                            <td>{{ statusLabel(v.status) }}</td>
                            <td>{{ dt(v.at) }}</td>
                            <td>{{ v.note ?? '' }}</td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  </div>
                  <p class="small muted">Mọi phiên bản được giữ nguyên, không ghi đè.</p>
                }
                @if (can('SCR-1603.mo-c2') && f.details?.['objectId']) {
                  <p-button
                    label="Mở tệp minh chứng"
                    icon="pi pi-external-link"
                    [loading]="opening()"
                    (onClick)="openFile(f)"
                  />
                }
              }
              @default {
                <dl class="meta">
                  @if (f.value) {
                    <dt>Giá trị</dt>
                    <dd>{{ f.value }}</dd>
                  }
                  @if (f.status) {
                    <dt>Trạng thái</dt>
                    <dd>{{ statusLabel(f.status) }}</dd>
                  }
                  @if (f.unitName) {
                    <dt>Đơn vị</dt>
                    <dd>{{ f.unitName }}</dd>
                  }
                  @if (f.details?.['rating']) {
                    <dt>Xếp loại</dt>
                    <dd>{{ f.details?.['rating'] }}</dd>
                  }
                  @if (f.details?.['groupKpi']) {
                    <dt>Cách tính</dt>
                    <dd>
                      KPI nhóm {{ dnum(f.details?.['groupKpi'], 3) }} ×
                      {{ f.details?.['weight'] }} điểm = {{ f.value }}
                    </dd>
                  }
                </dl>
                @if (f.details?.['note']) {
                  <p>{{ f.details?.['note'] }}</p>
                }
              }
            }
          </section>
          <section class="box" aria-labelledby="tv-up-h">
            <h2 class="h" id="tv-up-h">Ngược lên mục tiêu</h2>
            @if (upward(); as u) {
              <p>{{ u }}</p>
            } @else {
              <p class="muted">Không có thông tin mục tiêu trong phạm vi xem của đồng chí.</p>
            }
          </section>
        </div>
      }
    } @else {
      <p class="muted">Đang tải…</p>
    }
  `,
  styleUrls: ['../dashboard.css'],
  styles: `
    .chain {
      list-style: none;
      padding: 0;
      margin: 12px 0;
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
      gap: 8px;
      counter-reset: link;
    }
    .chain li {
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-top: 4px solid #1e3a8a;
      border-radius: 6px;
      padding: 8px 10px;
      display: flex;
      flex-direction: column;
      gap: 2px;
      background: var(--p-content-background, #fff);
    }
    .chain li.focus {
      border-color: #1e3a8a;
      background: #eff6ff;
    }
    .chain li.masked {
      border-top-color: #6b7280;
      background: #f3f4f6;
    }
    .chain .k {
      font-size: 0.8rem;
      color: #374151;
      text-transform: uppercase;
      letter-spacing: 0.02em;
    }
    .chain .lbl {
      font-weight: 600;
      overflow-wrap: anywhere;
    }
    .chain .val {
      font-size: 1.25rem;
      font-weight: 700;
      color: #1e3a8a;
    }
  `,
})
export class TracePage {
  private readonly api = inject(AnalyticsApi);
  private readonly files = inject(FileApi);
  protected readonly can = inject(DASHBOARD_CAN);
  readonly objectType = input.required<TraceObjectType>();
  readonly id = input.required<string>();

  protected readonly res = rxResource({
    params: () => ({ type: this.objectType(), id: this.id() }),
    stream: ({ params }) => this.api.trace(params.type, params.id),
  });
  protected readonly chain = computed(() => (this.res.hasValue() ? this.res.value() : null));
  protected readonly focus = computed(() => {
    const c = this.chain();
    return c?.links.find((l) => l.objectType === c.focus.objectType && l.id === c.focus.id) ?? null;
  });
  protected readonly maskedLinks = computed(
    () => this.chain()?.links.filter((l) => l.masked) ?? [],
  );
  protected readonly versions = computed(
    () => (this.focus()?.details?.['versions'] as EvidenceVersionRow[] | undefined) ?? [],
  );
  protected readonly upward = computed(() => {
    const links = this.chain()?.links ?? [];
    const o = links.find((l) => l.objectType === 'MUC_TIEU' && !l.masked);
    const b = links.find((l) => l.objectType === 'CAN_CU' && !l.masked);
    if (!o && !b) return null;
    return [o && `Kết quả này góp phần thực hiện ${o.label}`, b && `theo ${b.label}`]
      .filter(Boolean)
      .join(', ')
      .concat('.');
  });

  protected readonly notice = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly exporting = signal(false);
  protected readonly opening = signal(false);

  protected isFocus(l: TraceLink) {
    const f = this.chain()?.focus;
    return !!f && f.objectType === l.objectType && f.id === l.id;
  }
  protected kind(t: TraceObjectType) {
    return TRACE_OBJECT_LABEL[t] ?? 'Đối tượng';
  }
  protected statusLabel(s: string) {
    return TRACE_STATUS_LABEL[s] ?? 'Đang xử lý';
  }
  protected maskedText(l: TraceLink) {
    return `Đồng chí không có quyền xem chi tiết ${TRACE_OBJECT_NOUN[l.objectType] ?? 'đối tượng'} thuộc ${l.unitName ?? 'đơn vị khác'}.`;
  }
  protected dnum(v: unknown, digits = 2) {
    return typeof v === 'number' ? fmtNum(v, digits) : '—';
  }
  protected size(v: unknown) {
    if (typeof v !== 'number') return '—';
    return v >= 1_048_576 ? `${fmtNum(v / 1_048_576)} MB` : `${fmtNum(v / 1024, 0)} KB`;
  }
  protected lower(v: unknown) {
    const s = String(v ?? '');
    return s.charAt(0).toLowerCase() + s.slice(1);
  }
  protected dt(iso?: string | null) {
    return fmtDateTime(iso);
  }
  protected d(v: unknown) {
    return fmtDate(typeof v === 'string' ? v : null);
  }
  protected err(e: unknown) {
    return errorText(e, 'Không tải được chuỗi truy vết.');
  }

  /** Mở tệp qua URL ký trước có hạn; máy chủ ghi nhật ký truy cập C2 (BR-16-04). */
  protected openFile(l: TraceLink) {
    const objectId = String(l.details?.['objectId'] ?? '');
    this.error.set(null);
    this.opening.set(true);
    this.files.downloadUrl(objectId).subscribe({
      next: (u) => {
        this.opening.set(false);
        window.open(u.url, '_blank', 'noopener');
      },
      error: (e) => {
        this.opening.set(false);
        this.error.set(errorText(e, 'Không mở được tệp minh chứng.'));
      },
    });
  }

  protected exportChain() {
    const c = this.chain();
    if (!c) return;
    this.notice.set(null);
    this.error.set(null);
    this.exporting.set(true);
    this.api
      .exportTrace({ objectType: c.focus.objectType, id: c.focus.id, formats: ['PDF', 'JSON'] })
      .subscribe({
        next: () => {
          this.exporting.set(false);
          this.notice.set(
            'Đã nhận yêu cầu xuất chuỗi bằng chứng. Hệ thống sẽ thông báo khi tệp sẵn sàng để tải về.',
          );
        },
        error: (e) => {
          this.exporting.set(false);
          this.error.set(errorText(e, 'Không xuất được chuỗi bằng chứng.'));
        },
      });
  }
}
