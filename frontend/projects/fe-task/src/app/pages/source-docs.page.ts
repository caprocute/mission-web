/*
 * "Nhiệm vụ / Văn bản đến" — đường vào SCR-0502 (TL-02 SCR-0502 "Đường vào"): văn bản chỉ đạo nhận từ SVC-13
 * (EVT-24) của đơn vị, kèm MSG-05-06 khi AI đã sinh nhiệm vụ chờ xác nhận. Quyền: SCR-0502.nhan-van-ban.
 */
import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  SOURCE_DOC_STATUS_LABEL,
  SOURCE_SYSTEM_LABEL,
  SourceDocApi,
  SourceDocStatus,
  SourceDocSummary,
  toProblem,
} from '@vt/api-client';
import { MessageModule } from 'primeng/message';
import { SelectButtonModule } from 'primeng/selectbutton';
import { TagModule } from 'primeng/tag';
import { TASK_CAN } from '../task-permissions';
import { TagSeverity, formatDate } from '../task-ui';

type Filter = SourceDocStatus | 'TAT_CA';

@Component({
  selector: 'vt-source-docs-page',
  imports: [DatePipe, FormsModule, RouterLink, MessageModule, SelectButtonModule, TagModule],
  template: `
    <h1>Văn bản đến</h1>
    @if (!can('SCR-0502.nhan-van-ban')) {
      <p-message severity="warn" text="Chức năng này dành cho lãnh đạo đơn vị nhận văn bản." />
    } @else {
      <p class="muted">
        Văn bản chỉ đạo nhận qua hệ thống văn phòng điện tử. AI trích nhiệm vụ để người giao xác
        nhận từng nhiệm vụ trước khi giao.
      </p>
      <p-selectbutton
        [options]="filters"
        optionLabel="label"
        optionValue="value"
        [ngModel]="filter()"
        (ngModelChange)="filter.set($event)"
        [allowEmpty]="false"
        ariaLabelledBy="flt"
      />
      <span id="flt" class="sr-only">Lọc theo tình trạng</span>

      @if (docs.error()) {
        <p-message severity="error" [text]="error()" />
      } @else if (docs.isLoading() && !rows().length) {
        <p class="muted">Đang tải…</p>
      } @else if (!rows().length) {
        <p class="muted">Không có văn bản nào.</p>
      } @else {
        <table class="tbl">
          <caption class="sr-only">
            Danh sách văn bản đến
          </caption>
          <thead>
            <tr>
              <th scope="col">Số, ký hiệu</th>
              <th scope="col">Ngày</th>
              <th scope="col">Trích yếu</th>
              <th scope="col">Nguồn</th>
              <th scope="col">Nhận lúc</th>
              <th scope="col">Tình trạng</th>
            </tr>
          </thead>
          <tbody>
            @for (d of rows(); track d.id) {
              <tr>
                <td>
                  <a [routerLink]="[d.id]">{{ d.docNumber }}</a>
                </td>
                <td>{{ date(d.docDate) }}</td>
                <td class="excerpt">{{ d.excerpt }}</td>
                <td>{{ system(d) }}</td>
                <td>{{ d.receivedAt | date: 'dd/MM HH:mm' }}</td>
                <td>
                  <p-tag [value]="state(d).label" [severity]="state(d).severity" />
                  @if (d.status === 'CHO_XU_LY' && d.pendingCount) {
                    <div class="small">
                      Văn bản {{ d.docNumber }} đã sinh {{ d.pendingCount }} nhiệm vụ chờ xác nhận.
                    </div>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    }
  `,
  styles: `
    :host {
      display: block;
      padding: 16px 24px;
      --p-togglebutton-color: #374151;
    }
    h1 {
      font-size: 1.5rem;
      margin: 0 0 4px;
    }
    .muted {
      color: #4b5563;
    }
    .small {
      font-size: 0.9em;
      margin-top: 4px;
    }
    .tbl {
      width: 100%;
      border-collapse: collapse;
      margin-top: 16px;
      background: var(--p-content-background, #fff);
    }
    .tbl th {
      text-align: left;
      color: #4b5563;
      font-weight: 500;
    }
    .tbl th,
    .tbl td {
      padding: 10px 8px;
      border-bottom: 1px solid var(--p-content-border-color, #e5e7eb);
      vertical-align: top;
    }
    .excerpt {
      max-width: 420px;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
    @media (max-width: 800px) {
      .tbl th:nth-child(4),
      .tbl td:nth-child(4),
      .tbl th:nth-child(5),
      .tbl td:nth-child(5) {
        display: none;
      }
    }
  `,
})
export class SourceDocsPage {
  private readonly api = inject(SourceDocApi);
  protected readonly can = inject(TASK_CAN);

  protected readonly filters: { value: Filter; label: string }[] = [
    { value: 'CHO_XU_LY', label: 'Chờ xử lý' },
    { value: 'DA_XU_LY', label: 'Đã xử lý' },
    { value: 'DA_BO_QUA', label: 'Đã bỏ qua' },
    { value: 'TAT_CA', label: 'Tất cả' },
  ];
  protected readonly filter = signal<Filter>('CHO_XU_LY');

  protected readonly docs = rxResource({
    params: () => (this.can('SCR-0502.nhan-van-ban') ? { f: this.filter() } : undefined),
    stream: ({ params }) =>
      this.api.list({ status: params.f === 'TAT_CA' ? undefined : params.f, limit: 50 }),
  });
  protected readonly rows = computed(() => this.docs.value()?.items ?? []);
  protected readonly error = computed(() => toProblem(this.docs.error()).title);

  protected date(d: string) {
    return formatDate(d, true);
  }
  protected system(d: SourceDocSummary) {
    return SOURCE_SYSTEM_LABEL[d.sourceSystem];
  }
  protected state(d: SourceDocSummary): { label: string; severity: TagSeverity } {
    if (d.status !== 'CHO_XU_LY')
      return { label: SOURCE_DOC_STATUS_LABEL[d.status], severity: 'secondary' };
    if (d.extraction === 'KHONG_TRICH_DUOC') return { label: 'Không trích được', severity: 'warn' };
    if (d.extraction === 'DANG_TRICH') return { label: 'AI đang trích', severity: 'info' };
    return d.pendingCount
      ? { label: 'Chờ xác nhận', severity: 'info' }
      : { label: 'Chờ giao', severity: 'info' };
  }
}
