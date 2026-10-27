import { Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { LegalRefPipe } from '@vt/api-client';
import {
  POLICY_VERSION_STATUS_LABEL,
  PolicyApi,
  PolicyPack,
  PolicyPackVersionSummary,
  isPolicyVersionEditable,
  policyVersionLabel,
} from '@vt/api-client/domains';
import { FLOW_LABEL, fmtDate } from '../studio-ui';
import { POLICY_KEYS } from '../studio-layout';
import { STUDIO_ACCESS } from '../studio-permissions';
import { POLICY_TABS } from './policy/policy-tabs';
import { VtSkeletonComponent } from '@vt/design-system';

/**
 * Trang đầu Config Studio (QT-15): danh sách Policy Pack của tenant cùng các phiên bản, mở một phiên
 * bản để xem hoặc soạn công thức (SCR-1501), bảng quyết định (SCR-1502) và luồng phê duyệt động (SCR-1504).
 */
@Component({
  selector: 'vt-policy-packs-page',
  imports: [VtSkeletonComponent, RouterLink, LegalRefPipe],
  template: `
    <header class="vt-page-head">
      <div>
        <h1>Chính sách đánh giá</h1>
        <p>Công thức tính điểm, bảng xếp loại và quy trình phê duyệt áp dụng cho đơn vị.</p>
      </div>
    </header>

    @if (packs.error()) {
      <p class="err" role="alert">Không tải được danh sách. Vui lòng thử lại.</p>
    } @else if (packs.isLoading() && !packs.value()) {
      <vt-skeleton variant="table" [columns]="5" label="Đang tải danh sách chính sách" />
    }
    @for (p of packs.value()?.items ?? []; track p.id) {
      <section class="card" [attr.data-pack]="p.code">
        <div class="card-head">
          <div>
            <h2>{{ p.name }}</h2>
            <p class="meta">
              <span class="flow-pill">{{ flow[p.flow] ?? p.flow }}</span>
              @if (p.legalBasis | legalRef; as lb) {
                · Căn cứ pháp lý: <strong>{{ lb }}</strong>
              }
            </p>
          </div>
        </div>
        <table>
          <caption class="sr-only">
            Các phiên bản của
            {{
              p.name
            }}
          </caption>
          <thead>
            <tr>
              <th scope="col">Phiên bản</th>
              <th scope="col">Trạng thái</th>
              <th scope="col">Áp dụng từ</th>
              <th scope="col">Nội dung thay đổi</th>
              <th scope="col"><span class="sr-only">Thao tác</span></th>
            </tr>
          </thead>
          <tbody>
            @for (v of sorted(p); track v.id) {
              <tr [attr.data-version]="v.version">
                <td>
                  <strong class="ver">{{ vlabel(v.version) }}</strong>
                </td>
                <td>
                  <span class="st" [attr.data-status]="v.status">{{ statusLabel[v.status] }}</span>
                </td>
                <td>{{ date(v.effectiveFrom) }}</td>
                <td>{{ v.changeNote ?? '—' }}</td>
                <td class="act">
                  <!-- Mở thẻ đầu tiên người dùng được xem; chỉ hiện liên kết mà vai trò vào được -->
                  @if (canOpen()) {
                    <a class="btn-link" [routerLink]="['goi', p.id, v.version]">
                      {{ editable(v) && canAuthor() ? 'Mở để soạn' : 'Xem' }}
                      <span class="sr-only">phiên bản {{ vlabel(v.version) }}</span>
                    </a>
                  }
                  @if (canWorkflow()) {
                    <a
                      class="btn-link secondary"
                      [routerLink]="['goi', p.id, v.version, 'luong-duyet']"
                    >
                      Quy trình phê duyệt
                    </a>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      </section>
    } @empty {
      @if (packs.value()) {
        <p>Đơn vị chưa có chính sách đánh giá nào.</p>
      }
    }
  `,
  styles: `
    .card {
      border: 1px solid #e2e8f0;
      border-radius: 14px;
      padding: 18px 20px;
      margin-bottom: 16px;
      background: #fff;
      overflow-x: auto;
      box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
    }
    .card-head {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      margin-bottom: 12px;
    }
    h2 {
      font-size: 1.15rem;
      font-weight: 700;
      color: #0f172a;
      margin: 0;
    }
    .meta {
      color: #475569;
      margin: 6px 0 0;
      font-size: 0.875rem;
    }
    .flow-pill {
      display: inline-block;
      padding: 2px 8px;
      border-radius: 6px;
      background: #f1f5f9;
      color: #0f172a;
      font-weight: 600;
      font-size: 0.8rem;
    }
    table {
      width: 100%;
      border-collapse: collapse;
    }
    th,
    td {
      text-align: left;
      padding: 10px 12px;
      border-bottom: 1px solid #f1f5f9;
    }
    th {
      color: #334155;
      font-weight: 700;
      font-size: 0.8rem;
      text-transform: uppercase;
      letter-spacing: 0.03em;
      background: #f8fafc;
    }
    .ver {
      color: #0f172a;
    }
    .st {
      display: inline-block;
      padding: 3px 10px;
      border-radius: 999px;
      background: #f1f5f9;
      font-size: 0.8rem;
      font-weight: 600;
      white-space: nowrap;
    }
    .st[data-status='DA_PHAT_HANH'] {
      background: #dcfce7;
      color: #14532d;
    }
    .st[data-status='NHAP'],
    .st[data-status='DAT_KIEM_THU'] {
      background: #fef3c7;
      color: #78350f;
    }
    .act {
      text-align: right;
      white-space: nowrap;
    }
    .btn-link {
      display: inline-block;
      padding: 5px 12px;
      border-radius: 8px;
      background: #fff1f2;
      color: #b4001f;
      font-weight: 600;
      font-size: 0.84rem;
      text-decoration: none;
      margin-left: 6px;
    }
    .btn-link.secondary {
      background: #f1f5f9;
      color: #1e293b;
    }
    .btn-link:hover {
      text-decoration: underline;
    }
    .err {
      color: #b4001f;
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
export class PolicyPacksPage {
  private readonly api = inject(PolicyApi);
  private readonly access = inject(STUDIO_ACCESS);
  /** YC-PT-21: liên kết theo đúng guard của tuyến phiên bản và từng thẻ (`POLICY_TABS`). */
  protected readonly canOpen = () =>
    POLICY_KEYS.some((k) => this.access.can(k)) &&
    POLICY_TABS.some((t) => t.keys.some((k) => this.access.can(k)));
  protected readonly canWorkflow = () => this.access.can('SCR-1504.cau-hinh');
  protected readonly canAuthor = () =>
    this.access.author('SCR-1501.soan') || this.access.author('SCR-1502.soan');
  protected readonly packs = rxResource({ stream: () => this.api.packs() });
  protected readonly flow = FLOW_LABEL;
  protected readonly statusLabel = POLICY_VERSION_STATUS_LABEL;
  protected readonly vlabel = policyVersionLabel;
  protected readonly date = fmtDate;

  protected sorted(p: PolicyPack): PolicyPackVersionSummary[] {
    return [...(p.versions ?? [])].sort((a, b) =>
      b.version.localeCompare(a.version, undefined, { numeric: true }),
    );
  }

  protected editable(v: PolicyPackVersionSummary) {
    return isPolicyVersionEditable(v.status);
  }
}
