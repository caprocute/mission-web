import { Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { UI_FORM_STATUS_LABEL, UiFormSummary, UiPackApi } from '@vt/api-client';
import { STUDIO_ACCESS } from '../../studio-permissions';
import { errorText, fmtDate } from '../../studio-ui';
import { VtSkeletonComponent } from '@vt/design-system';

/**
 * Danh sách biểu mẫu động của đơn vị (đường vào SCR-1802, QT-18): mở bản nháp để dựng, hoặc tạo bản
 * nháp mới từ bản đã phát hành.
 */
@Component({
  selector: 'vt-forms-page',
  imports: [VtSkeletonComponent, RouterLink, ButtonModule],
  template: `
    <h1>Biểu mẫu động</h1>
    <p class="sub">
      Biểu mẫu nghiệp vụ của đơn vị: trường nhập, điều kiện kiểm tra và điều kiện hiển thị. Công
      thức tính điểm nằm trong bộ chính sách đánh giá, không sửa ở đây.
    </p>
    @if (error(); as e) {
      <p class="err" role="alert">{{ e }}</p>
    }
    @if (forms.error()) {
      <p class="err" role="alert">Không tải được danh sách biểu mẫu. Vui lòng thử lại.</p>
    } @else if (!forms.value()) {
      <vt-skeleton variant="table" [columns]="4" label="Đang tải danh sách biểu mẫu" />
    } @else {
      <table data-testid="forms">
        <caption class="sr-only">
          Biểu mẫu động
        </caption>
        <thead>
          <tr>
            <th scope="col">Biểu mẫu</th>
            <th scope="col">Bản mới nhất</th>
            <th scope="col">Trạng thái</th>
            <th scope="col">Cập nhật</th>
            <th scope="col"><span class="sr-only">Thao tác</span></th>
          </tr>
        </thead>
        <tbody>
          @for (f of forms.value()!.items; track f.formKey) {
            <tr [attr.data-form]="f.formKey">
              <td>{{ title(f) }}</td>
              <td>{{ f.latestVersion }}</td>
              <td>{{ statusLabel[f.status] }}</td>
              <td>{{ date(f.updatedAt) }}</td>
              <td class="act">
                <a [routerLink]="[f.formKey, f.latestVersion]">
                  {{ f.status === 'NHAP' ? 'Mở để dựng' : 'Xem' }}
                  <span class="sr-only">{{ title(f) }}</span>
                </a>
                @if (f.status !== 'NHAP' && access.can('SCR-1802.dung')) {
                  <p-button
                    size="small"
                    severity="secondary"
                    [outlined]="true"
                    label="Tạo bản nháp mới"
                    [disabled]="busy()"
                    (onClick)="newDraft(f)"
                  />
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
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
      max-width: 80ch;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      background: #fff;
    }
    th,
    td {
      text-align: left;
      padding: 10px 8px;
      border-bottom: 1px solid #e5e7eb;
    }
    thead th {
      background: #f8fafc;
      font-weight: 500;
    }
    .act {
      display: flex;
      gap: 12px;
      align-items: center;
      flex-wrap: wrap;
    }
    a {
      color: #1d4ed8;
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
      white-space: nowrap;
    }
  `,
})
export class FormsPage {
  private readonly api = inject(UiPackApi);
  private readonly router = inject(Router);
  protected readonly access = inject(STUDIO_ACCESS);
  protected readonly statusLabel = UI_FORM_STATUS_LABEL;
  protected readonly date = fmtDate;
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly forms = rxResource({ stream: () => this.api.forms() });

  /** Tên biểu mẫu cho người dùng; khoá chỉ dùng nội bộ. */
  protected title(f: UiFormSummary): string {
    return FORM_TITLE[f.formKey] ?? 'Biểu mẫu của đơn vị';
  }

  protected newDraft(f: UiFormSummary) {
    const next = String((parseInt(f.latestVersion, 10) || 0) + 1);
    this.busy.set(true);
    this.error.set(null);
    this.api
      .createFormVersion(f.formKey, { version: next, cloneFromVersion: f.latestVersion })
      .subscribe({
        next: (v) => {
          this.busy.set(false);
          void this.router.navigate(['/config-studio', 'bieu-mau', v.formKey, v.version]);
        },
        error: (e) => {
          this.busy.set(false);
          this.error.set(errorText(e, 'Không tạo được bản nháp.'));
        },
      });
  }
}

/** Tên hiển thị của biểu mẫu theo khoá (hợp đồng chưa có trường tên, suy luận, wiki/14). */
export const FORM_TITLE: Record<string, string> = {
  'phieu-vc-yte': 'Phiếu đánh giá viên chức y tế',
  'mau-1b': 'Phiếu tự đánh giá (Mẫu 1B)',
};
