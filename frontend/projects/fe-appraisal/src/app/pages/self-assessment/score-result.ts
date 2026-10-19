import { Component, input, output } from '@angular/core';
import { AppraisalScoreView } from '@vt/api-client';
import { vn } from './self-assessment-rules';

/**
 * SCR-0901 vùng 3: bảng kết quả nhiệm vụ do SVC-07 tính, chỉ đọc (trường 5 đến 7, BR-09-16): từng
 * nhiệm vụ với SL duyệt/KH, B, C, KPI (BR-09-01), KPI nhóm × điểm tối đa (BR-09-03), nhãn "Vượt"
 * (BR-09-04) và điểm kết quả. Luồng Nhà nước có thêm phần 30 điểm tiêu chí chung (BR-09-11).
 */
@Component({
  selector: 'vt-score-result',
  template: `
    @let s = view().snapshot;
    <section class="box" aria-labelledby="sr-h">
      <div class="head">
        <h2 id="sr-h" class="sec">
          Kết quả thực hiện nhiệm vụ <span class="chip">Hệ thống tính · không sửa tay</span>
        </h2>
        @if (!simple()) {
          <button type="button" class="link" (click)="trace.emit()">Vết tính</button>
        }
      </div>
      @for (g of s.groups; track g.code) {
        <table>
          <caption class="sr-only">
            {{
              g.label
            }}, tối đa
            {{
              g.maxScore
            }}
            điểm
          </caption>
          <thead>
            <tr>
              <th scope="col" class="name">{{ g.label }} · tối đa {{ g.maxScore }}</th>
              <th scope="col">SL duyệt/KH</th>
              <th scope="col">B</th>
              <th scope="col">C</th>
              <th scope="col">KPI</th>
            </tr>
          </thead>
          <tbody>
            @for (t of g.tasks ?? []; track t.taskId) {
              <tr>
                <th scope="row" class="name">
                  {{ t.title }}
                  @if (t.exceedsTarget) {
                    <span class="over">Vượt</span>
                  }
                </th>
                <td>{{ qty(t.approvedQuantity) }}/{{ qty(t.plannedQuantity) }}</td>
                <td>{{ vn(t.b, 2) }}</td>
                <td>{{ vn(t.c, 2) }}</td>
                <td class="b">{{ vn(t.kpi, 3) }}</td>
              </tr>
            }
          </tbody>
          <tfoot>
            <tr>
              <th scope="row" colspan="4" class="name">
                KPI nhóm {{ g.label.toLowerCase() }} {{ vn(g.kpi, 3) }} × {{ g.maxScore }}
              </th>
              <td class="score">{{ vn(g.score) }}</td>
            </tr>
          </tfoot>
        </table>
      }
      @if (s.generalCriteriaScore !== null && s.generalCriteriaScore !== undefined) {
        <p class="extra">
          <span>Tiêu chí chung (thang 30 điểm)</span><b>{{ vn(s.generalCriteriaScore) }}</b>
        </p>
      }
      <p class="total">
        <span>Điểm kết quả nhiệm vụ</span>
        <span
          ><b>{{ vn(s.totalScore) }}</b> /100</span
        >
      </p>
      <p class="note">
        A = min(SL duyệt/KH, 1) · B theo 5 mức nghiệm thu · C = max(0; 1 − 0,05 × ngày làm việc chậm
        do chủ quan) · Chỉ minh chứng DA_XAC_NHAN được tính.
      </p>
    </section>
  `,
  styles: `
    .box {
      padding: 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: var(--p-content-background, #fff);
    }
    .head {
      display: flex;
      justify-content: space-between;
      align-items: baseline;
      gap: 8px;
    }
    .sec {
      font-size: 1.05rem;
      margin: 0 0 8px;
    }
    .chip {
      font-size: 0.75rem;
      font-weight: 500;
      padding: 2px 8px;
      border-radius: 10px;
      background: #e0f2fe;
      color: #075985;
    }
    .link {
      background: none;
      border: 0;
      color: #b4001f;
      font-weight: 600;
      cursor: pointer;
      text-decoration: underline;
      padding: 4px;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      margin-bottom: 12px;
    }
    th,
    td {
      padding: 8px 6px;
      border-bottom: 1px solid #f3f4f6;
      text-align: right;
      font-weight: 400;
    }
    thead th {
      background: #f9fafb;
      color: #4b5563;
      font-size: 0.85rem;
    }
    .name {
      text-align: left;
    }
    .b,
    tfoot th {
      font-weight: 700;
    }
    .score {
      color: #b4001f;
      font-weight: 700;
    }
    .over {
      font-size: 0.75rem;
      padding: 1px 8px;
      border-radius: 10px;
      background: #dcfce7;
      color: #14532d;
    }
    .extra,
    .total {
      display: flex;
      justify-content: space-between;
      align-items: baseline;
      margin: 8px 0;
    }
    .total {
      padding: 14px 16px;
      border-radius: 8px;
      background: #fdecef;
      font-weight: 600;
    }
    .total b {
      font-size: 1.6rem;
      color: #b4001f;
    }
    .note {
      font-size: 0.8rem;
      color: #4b5563;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0, 0, 0, 0);
    }
  `,
})
export class ScoreResult {
  readonly view = input.required<AppraisalScoreView>();
  readonly simple = input(false);
  readonly trace = output<void>();

  protected readonly vn = vn;
  protected qty(n: number | undefined) {
    return n === undefined ? '—' : n.toLocaleString('vi-VN');
  }
}
