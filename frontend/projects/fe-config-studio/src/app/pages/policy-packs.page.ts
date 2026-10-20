import { Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import {
  POLICY_VERSION_STATUS_LABEL,
  PolicyApi,
  PolicyPack,
  PolicyPackVersionSummary,
  isPolicyVersionEditable,
  policyVersionLabel,
} from '@vt/api-client';
import { FLOW_LABEL, fmtDate } from '../studio-ui';

/**
 * Trang đầu Config Studio (QT-15): danh sách Policy Pack của tenant cùng các phiên bản, mở một phiên
 * bản để xem hoặc soạn công thức (SCR-1501) và bảng quyết định (SCR-1502).
 */
@Component({
  selector: 'vt-policy-packs-page',
  imports: [RouterLink],
  template: `
    <h1>Chính sách đánh giá</h1>
    <p class="sub">Công thức tính điểm và bảng xếp loại áp dụng cho các kỳ đánh giá của đơn vị.</p>
    @if (packs.error()) {
      <p class="err" role="alert">Không tải được danh sách. Vui lòng thử lại.</p>
    } @else if (packs.isLoading() && !packs.value()) {
      <p>Đang tải…</p>
    }
    @for (p of packs.value()?.items ?? []; track p.id) {
      <section class="card" [attr.data-pack]="p.code">
        <h2>{{ p.name }}</h2>
        <p class="meta">
          {{ flow[p.flow] ?? p.flow }}
          @if (p.legalBasis) {
            · Căn cứ: {{ p.legalBasis }}
          }
        </p>
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
                <td>{{ vlabel(v.version) }}</td>
                <td>
                  <span class="st" [attr.data-status]="v.status">{{ statusLabel[v.status] }}</span>
                </td>
                <td>{{ date(v.effectiveFrom) }}</td>
                <td>{{ v.changeNote ?? '—' }}</td>
                <td class="act">
                  <a [routerLink]="['goi', p.id, v.version, 'cong-thuc']">
                    {{ editable(v) ? 'Mở để soạn' : 'Xem' }}
                    <span class="sr-only">phiên bản {{ vlabel(v.version) }}</span>
                  </a>
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
    h1 {
      font-size: 1.5rem;
      margin: 0;
    }
    .sub {
      color: #4b5563;
      margin: 4px 0 16px;
    }
    .card {
      border: 1px solid #e5e7eb;
      border-radius: 8px;
      padding: 16px;
      margin-bottom: 16px;
      background: #fff;
      overflow-x: auto;
    }
    h2 {
      font-size: 1.125rem;
      margin: 0;
    }
    .meta {
      color: #4b5563;
      margin: 4px 0 12px;
    }
    table {
      width: 100%;
      border-collapse: collapse;
    }
    th,
    td {
      text-align: left;
      padding: 8px;
      border-bottom: 1px solid #f1f5f9;
    }
    th {
      color: #374151;
      font-weight: 500;
      background: #f8fafc;
    }
    .st {
      padding: 2px 8px;
      border-radius: 999px;
      background: #f1f5f9;
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
    .act a {
      color: #1d4ed8;
      font-weight: 500;
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
