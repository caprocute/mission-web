import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { OkrReviewSet } from '@vt/api-client';
import { CheckTag } from './check-tag';

/**
 * Vùng 2 của SCR-0203: chi tiết bộ OKR với mỗi mục tiêu, KR, chỉ tiêu, trọng số, liên kết cấp trên
 * hoặc ngang và kết quả kiểm tra từng dòng; "Xem cây" mở SCR-0201 chỉ đọc.
 */
@Component({
  selector: 'vt-review-detail',
  imports: [CheckTag, RouterLink],
  template: `
    @let s = set();
    <section class="box" aria-labelledby="d-h">
      <header class="top">
        <h2 id="d-h" class="sec">
          <span class="num" aria-hidden="true">2</span> {{ s.unit.name }} · {{ periodLabel() }}
        </h2>
        <span class="ver">Chờ duyệt · phiên bản {{ s.versionNo }}</span>
        <a class="link" routerLink=".." [queryParams]="{ unit: s.unit.id, period: s.periodId }">
          Xem cây
        </a>
      </header>
      <div class="wrap">
        <table>
          <caption class="sr-only">
            Mục tiêu và KR của
            {{
              s.unit.name
            }}
            kèm kết quả kiểm tra tự động
          </caption>
          <thead>
            <tr>
              <th scope="col">Mục tiêu / KR</th>
              <th scope="col">Chỉ tiêu</th>
              <th scope="col">Trọng số</th>
              <th scope="col">Liên kết cấp trên</th>
              <th scope="col">Kiểm tra</th>
            </tr>
          </thead>
          <tbody>
            @for (o of s.objectives; track o.id) {
              <tr class="obj">
                <th scope="row" colspan="3">{{ o.code }} · {{ o.title }}</th>
                <td>{{ o.parentCode ?? '—' }}</td>
                <td><vt-check-tag [check]="o.check" /></td>
              </tr>
              @for (k of o.keyResults; track k.id) {
                <tr>
                  <th scope="row" class="kr">{{ k.code }} · {{ k.title }}</th>
                  <td>{{ k.targetText }}</td>
                  <td>{{ pct(k.weight) }}</td>
                  <td>{{ k.link ?? '—' }}</td>
                  <td><vt-check-tag [check]="k.check" /></td>
                </tr>
              }
            }
          </tbody>
        </table>
      </div>
    </section>
  `,
  styles: `
    .box {
      padding: 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: var(--p-content-background, #fff);
    }
    .top {
      display: flex;
      align-items: center;
      gap: 10px;
      flex-wrap: wrap;
      margin-bottom: 12px;
    }
    .sec {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 1.05rem;
      margin: 0;
    }
    .num {
      display: inline-grid;
      place-items: center;
      width: 22px;
      height: 22px;
      border-radius: 50%;
      background: #111827;
      color: #fff;
      font-size: 0.75rem;
    }
    .ver {
      background: #e8f0fe;
      color: #1e40af;
      border-radius: 10px;
      padding: 2px 10px;
      font-size: 0.8125rem;
      font-weight: 600;
    }
    .link {
      margin-left: auto;
      color: #c4002a;
      font-weight: 600;
    }
    .wrap {
      overflow-x: auto;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      font-size: 0.875rem;
    }
    th,
    td {
      padding: 9px 8px;
      border-bottom: 1px solid #e5e7eb;
      text-align: left;
      vertical-align: top;
    }
    thead th {
      background: #f9fafb;
    }
    .kr {
      font-weight: 400;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0, 0, 0, 0);
      white-space: nowrap;
    }
  `,
})
export class ReviewDetail {
  readonly set = input.required<OkrReviewSet>();
  readonly periodLabel = input('');

  protected pct(w: number) {
    return `${Math.round(w * 100)}%`;
  }
}
