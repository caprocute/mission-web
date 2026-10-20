import { Component, computed, inject, linkedSignal, model, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  GOLDEN_CASE_SOURCE_LABEL,
  GoldenTestCase,
  GoldenTestCaseSource,
  PolicyApi,
  PolicyPackVersion,
  RATING_LEVELS,
  RATING_LEVEL_LABEL,
  fieldErrors,
  policyVersionLabel,
} from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { TextareaModule } from 'primeng/textarea';
import { STUDIO_STEP_UP } from '../../studio-step-up';
import { errorText, problemCode } from '../../studio-ui';
import { DIALOG_STYLES } from './policy-dialogs';
import { PolicyWorkspace } from './policy-workspace';

const SOURCES = Object.keys(GOLDEN_CASE_SOURCE_LABEL) as GoldenTestCaseSource[];

/**
 * Thêm ca kiểm thử vào bộ vàng của gói (SCR-1503 "Thêm ca kiểm thử", BR-15-08): ca mới chờ người phụ
 * trách nghiệp vụ ký duyệt, chưa tính vào tỷ lệ đạt. Dữ liệu đầu vào lấy từ tệp hồ sơ mẫu đã xuất
 * (JSON, đọc bằng `JSON.parse`), không chứa dữ liệu cá nhân thật.
 */
@Component({
  selector: 'vt-add-test-case-dialog',
  imports: [FormsModule, ButtonModule, DialogModule, TextareaModule],
  template: `
    <p-dialog
      header="Thêm ca kiểm thử"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(640px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      <p class="muted">
        Ca mới được lưu ở trạng thái chờ ký duyệt và chỉ được tính vào kết quả kiểm thử sau khi
        người phụ trách nghiệp vụ ký duyệt.
      </p>
      <label class="f">
        <span>Mã ca <span class="req" aria-hidden="true">*</span></span>
        <input
          class="native"
          name="code"
          maxlength="64"
          required
          data-testid="case-code"
          [attr.aria-invalid]="!!err()['caseCode']"
          [ngModel]="code()"
          (ngModelChange)="code.set($event)"
        />
        @if (err()['caseCode']; as e) {
          <small class="err">{{ e }}</small>
        }
      </label>
      <label class="f">
        <span>Tình huống kiểm thử <span class="req" aria-hidden="true">*</span></span>
        <textarea
          pTextarea
          name="desc"
          rows="2"
          maxlength="2000"
          required
          data-testid="case-desc"
          [ngModel]="desc()"
          (ngModelChange)="desc.set($event)"
        ></textarea>
      </label>
      <div class="row">
        <label class="f">
          <span>Mức xếp loại mong đợi</span>
          <select
            class="native"
            name="level"
            data-testid="case-level"
            [ngModel]="level()"
            (ngModelChange)="level.set($event)"
          >
            <option value="">Không kiểm tra mức</option>
            @for (l of levels; track l) {
              <option [value]="l">{{ levelLabel[l] }}</option>
            }
          </select>
        </label>
        <label class="f">
          <span>Điểm mong đợi</span>
          <input
            class="native"
            type="number"
            name="score"
            min="0"
            max="100"
            step="0.1"
            data-testid="case-score"
            [ngModel]="score()"
            (ngModelChange)="score.set($event)"
          />
        </label>
      </div>
      @if (err()['expected']; as e) {
        <p class="err">{{ e }}</p>
      }
      <div class="row">
        <label class="f">
          <span>Nguồn</span>
          <select
            class="native"
            name="source"
            [ngModel]="source()"
            (ngModelChange)="source.set($event)"
          >
            @for (s of sources; track s) {
              <option [value]="s">{{ sourceLabel[s] }}</option>
            }
          </select>
        </label>
        <label class="f">
          <span>Căn cứ quy định</span>
          <input
            class="native"
            name="legal"
            maxlength="200"
            placeholder="Ví dụ: PL 3 mục 3"
            [ngModel]="legal()"
            (ngModelChange)="legal.set($event)"
          />
        </label>
      </div>
      <label class="f">
        <span>Dữ liệu hồ sơ mẫu</span>
        <input
          type="file"
          accept="application/json,.json"
          name="input"
          (change)="readInput($event)"
        />
        <small class="muted">
          @if (inputName(); as n) {
            Đã chọn {{ n }}.
          } @else {
            Tệp dữ liệu hồ sơ mẫu xuất từ hệ thống, không chứa thông tin cá nhân thật.
          }
        </small>
      </label>
      @if (error(); as e) {
        <p class="err" role="alert">{{ e }}</p>
      }
      <ng-template #footer>
        <p-button
          label="Huỷ"
          severity="contrast"
          [outlined]="true"
          (onClick)="visible.set(false)"
        />
        <p-button
          label="Thêm ca"
          data-testid="case-save"
          [loading]="busy()"
          [disabled]="!valid()"
          (onClick)="save()"
        />
      </ng-template>
    </p-dialog>
  `,
  styles:
    DIALOG_STYLES +
    `
    .row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
    .row > .f { min-width: 0; }
    .req { color: #b4001f; }
    @media (max-width: 560px) { .row { grid-template-columns: 1fr; } }
  `,
})
export class AddTestCaseDialog {
  readonly visible = model(false);
  readonly created = output<GoldenTestCase>();

  private readonly api = inject(PolicyApi);
  private readonly ws = inject(PolicyWorkspace);
  protected readonly levels = RATING_LEVELS;
  protected readonly levelLabel = RATING_LEVEL_LABEL;
  protected readonly sources = SOURCES;
  protected readonly sourceLabel = GOLDEN_CASE_SOURCE_LABEL;

  protected readonly code = linkedSignal(() => (this.visible(), ''));
  protected readonly desc = linkedSignal(() => (this.visible(), ''));
  protected readonly level = linkedSignal(() => (this.visible(), ''));
  protected readonly score = linkedSignal<boolean, number | null>({
    source: this.visible,
    computation: () => null,
  });
  protected readonly source = linkedSignal<boolean, GoldenTestCaseSource>({
    source: this.visible,
    computation: () => 'BA',
  });
  protected readonly legal = linkedSignal(() => (this.visible(), ''));
  protected readonly input = linkedSignal<boolean, Record<string, unknown>>({
    source: this.visible,
    computation: () => ({}),
  });
  protected readonly inputName = linkedSignal<boolean, string | null>({
    source: this.visible,
    computation: () => null,
  });
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly err = signal<Record<string, string>>({});

  protected readonly expected = computed(() => {
    const e: Record<string, unknown> = {};
    if (this.level()) e['muc'] = this.level();
    const s = this.score();
    if (s !== null && `${s}` !== '') e['diem'] = Number(s);
    return e;
  });
  protected readonly valid = computed(
    () => !!this.code().trim() && !!this.desc().trim() && Object.keys(this.expected()).length > 0,
  );

  protected readInput(ev: Event) {
    const file = (ev.target as HTMLInputElement).files?.[0];
    this.error.set(null);
    if (!file) return;
    file.text().then((text) => {
      try {
        const v: unknown = JSON.parse(text);
        if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error('not object');
        this.input.set(v as Record<string, unknown>);
        this.inputName.set(file.name);
      } catch {
        this.error.set('Tệp dữ liệu hồ sơ mẫu không đúng định dạng.');
      }
    });
  }

  protected save() {
    const pack = this.ws.ref()?.id;
    if (!pack) return;
    this.busy.set(true);
    this.error.set(null);
    this.err.set({});
    this.api
      .createTestCase({
        policyPackId: pack,
        caseCode: this.code().trim(),
        description: this.desc().trim(),
        input: this.input(),
        expected: this.expected(),
        source: this.source(),
        ...(this.legal().trim() ? { legalRef: this.legal().trim() } : {}),
      })
      .subscribe({
        next: (c) => {
          this.busy.set(false);
          this.visible.set(false);
          this.created.emit(c);
        },
        error: (e) => {
          this.busy.set(false);
          this.err.set(fieldErrors(e));
          this.error.set(errorText(e, 'Không thêm được ca kiểm thử.'));
        },
      });
  }
}

/**
 * Ký số phê duyệt phát hành (SCR-1503, AC-08): xác thực bước lên AAL2 rồi gửi phê duyệt (202), saga
 * phát hành chạy nền. Người phê duyệt phải khác người soạn (BR-15-09, 403 `POLICY-403-SAME-AUTHOR`).
 */
@Component({
  selector: 'vt-approve-policy-dialog',
  imports: [FormsModule, ButtonModule, DialogModule, TextareaModule],
  template: `
    <p-dialog
      header="Ký số phê duyệt phát hành"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(560px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      @if (ws.version.value()?.value; as v) {
        <p>
          Phê duyệt phát hành phiên bản {{ vlabel(v.version) }} của
          <strong>{{ ws.pack.value()?.name }}</strong
          >. Sau khi ký, phiên bản được áp dụng từ kỳ đã chọn; các kỳ đã khoá tiếp tục dùng phiên
          bản cũ.
        </p>
        @if (v.createdBy; as by) {
          <p class="muted">Người soạn: {{ by.name }}</p>
        }
      }
      <label class="f">
        <span>Ý kiến phê duyệt</span>
        <textarea
          pTextarea
          name="comment"
          rows="3"
          maxlength="2000"
          [ngModel]="comment()"
          (ngModelChange)="comment.set($event)"
        ></textarea>
      </label>
      <p class="muted">Hệ thống sẽ yêu cầu xác thực nhiều lớp trước khi ký.</p>
      @if (error(); as e) {
        <p class="err" role="alert">{{ e }}</p>
      }
      <ng-template #footer>
        <p-button
          label="Huỷ"
          severity="contrast"
          [outlined]="true"
          (onClick)="visible.set(false)"
        />
        <p-button
          label="Ký số phê duyệt"
          data-testid="approve-confirm"
          [loading]="busy()"
          (onClick)="approve()"
        />
      </ng-template>
    </p-dialog>
  `,
  styles: DIALOG_STYLES,
})
export class ApprovePolicyDialog {
  readonly visible = model(false);
  readonly approved = output<void>();

  private readonly api = inject(PolicyApi);
  private readonly stepUp = inject(STUDIO_STEP_UP);
  protected readonly ws = inject(PolicyWorkspace);
  protected readonly vlabel = policyVersionLabel;
  protected readonly comment = linkedSignal(() => (this.visible(), ''));
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected async approve() {
    const ref = this.ws.ref();
    const v = this.ws.version.value();
    if (!ref || !v) return;
    this.error.set(null);
    this.busy.set(true);
    if (!(await this.stepUp('Ký số phê duyệt phát hành chính sách đánh giá'))) {
      this.busy.set(false);
      this.error.set('Chưa xác thực nhiều lớp nên chưa ký được.');
      return;
    }
    const comment = this.comment().trim();
    this.api.approve(ref, v.etag, comment ? { comment } : {}).subscribe({
      next: () => {
        this.busy.set(false);
        this.visible.set(false);
        this.approved.emit();
      },
      error: (e) => {
        this.busy.set(false);
        const code = problemCode(e);
        this.error.set(
          code === 'POLICY-403-SAME-AUTHOR'
            ? 'Người phê duyệt phải khác người soạn phiên bản này.'
            : code === 'POLICY-409-NOT-PENDING'
              ? 'Phiên bản không còn ở trạng thái chờ phê duyệt. Vui lòng tải lại.'
              : errorText(e, 'Không ký phê duyệt được.'),
        );
      },
    });
  }
}

/** Từ chối phát hành có ý kiến (ít nhất 10 ký tự): phiên bản trở về bản nháp để soạn lại. */
@Component({
  selector: 'vt-reject-policy-dialog',
  imports: [FormsModule, ButtonModule, DialogModule, TextareaModule],
  template: `
    <p-dialog
      header="Từ chối phát hành"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(560px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      <label class="f">
        <span>Ý kiến từ chối <span aria-hidden="true">*</span></span>
        <textarea
          pTextarea
          name="reason"
          rows="4"
          maxlength="2000"
          required
          data-testid="reject-reason"
          [ngModel]="reason()"
          (ngModelChange)="reason.set($event)"
        ></textarea>
        <small class="muted">Nêu rõ nội dung cần sửa, ít nhất 10 ký tự.</small>
      </label>
      @if (error(); as e) {
        <p class="err" role="alert">{{ e }}</p>
      }
      <ng-template #footer>
        <p-button
          label="Huỷ"
          severity="contrast"
          [outlined]="true"
          (onClick)="visible.set(false)"
        />
        <p-button
          label="Từ chối"
          severity="danger"
          data-testid="reject-confirm"
          [loading]="busy()"
          [disabled]="reason().trim().length < 10"
          (onClick)="reject()"
        />
      </ng-template>
    </p-dialog>
  `,
  styles: DIALOG_STYLES,
})
export class RejectPolicyDialog {
  readonly visible = model(false);
  readonly rejected = output<PolicyPackVersion>();

  private readonly api = inject(PolicyApi);
  private readonly ws = inject(PolicyWorkspace);
  protected readonly reason = linkedSignal(() => (this.visible(), ''));
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected reject() {
    const ref = this.ws.ref();
    const v = this.ws.version.value();
    if (!ref || !v) return;
    this.busy.set(true);
    this.error.set(null);
    this.api.reject(ref, v.etag, { reason: this.reason().trim() }).subscribe({
      next: (res) => {
        this.busy.set(false);
        this.visible.set(false);
        this.rejected.emit(res);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(errorText(e, 'Không gửi được ý kiến từ chối.'));
      },
    });
  }
}
