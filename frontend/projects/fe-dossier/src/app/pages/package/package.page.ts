/*
 * SCR-1102 Gói hồ sơ số và ký số (QT-11 bước 12–14 → SCR-1102 → /api/v1/dossiers/packages, /api/v1/esign →
 * MOD-06, SVC-11, SVC-10 → EVT-17, EVT-18; WEB-041). AC-07 chuẩn bị, AC-08 ký gói (MFA bước lên khi ký).
 *
 * Trường (TL-02b SCR-1102):
 *  1 Mã gói, hạn nộp (chỉ đọc)                    5 Nơi nhận gói con diện TW (Ban Tổ chức TW, Văn phòng TW)
 *  2 Biểu mẫu sinh từ dữ liệu (chỉ đọc)           6 Người ký, nhà cung cấp (chứng thư còn hạn)
 *  3 Trường hợp đặc thù (chỉ đọc)                 7 Mã băm gói rút gọn, số tệp
 *  4 Danh sách kiểm tra trước khi ký (BR-11-09)
 * Chức năng: Sinh lại mẫu lỗi (POST .../regenerate, 202, EVT-17); Xem biểu mẫu (GET /dossiers/forms/{id}/file);
 *  Tải gói ZIP và manifest (POST .../export, 202); Gửi gói con (POST .../transfers, 202); Ký số gói và nộp
 *  (POST .../sign, 202, phiên AAL2, EVT-18).
 * Quy tắc: BR-11-09 (chỉ ký khi danh sách kiểm tra đạt), BR-11-10 (gói đã nộp bị khoá), BR-11-11 (gói con TW).
 * Thông báo: MSG-11-04 (chưa đạt kiểm tra), MSG-11-05 (ký xong), MSG-11-06 (gửi gói con lỗi), MSG-11-07 (tệp lỗi).
 */
import { DatePipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Job, JobTracker, toProblem } from '@vt/api-client';
import {
  DossierApi,
  DossierForm,
  DossierTransferDestination,
  FORM_CODE_LABEL,
  FORM_STATUS_LABEL,
  PACKAGE_KIND_LABEL,
  PACKAGE_STATUS_LABEL,
  TRANSFER_DESTINATION_LABEL,
  TRANSFER_STATUS_LABEL,
} from '@vt/api-client/domains';
import { ThemeService, VtSkeletonComponent } from '@vt/design-system';
import { firstValueFrom, last } from 'rxjs';
import { DOSSIER_CAN } from '../../dossier-permissions';
import { DOSSIER_STEP_UP } from '../../step-up';
import { specialCaseLabel } from '../meeting/meeting-rules';
import {
  actions,
  dnPart,
  isLocked,
  msg1104,
  msg1105,
  msg1106,
  msg1107,
  problemMessage,
  shortHash,
} from './package-rules';

@Component({
  selector: 'vt-package-page',
  imports: [VtSkeletonComponent, DatePipe, RouterLink],
  templateUrl: './package.page.html',
  styleUrl: './package.page.css',
})
export class PackagePage {
  private readonly api = inject(DossierApi);
  private readonly jobs = inject(JobTracker);
  private readonly stepUp = inject(DOSSIER_STEP_UP);
  private readonly theme = inject(ThemeService);
  protected readonly can = inject(DOSSIER_CAN);

  readonly id = input.required<string>();

  protected readonly statusLabel = PACKAGE_STATUS_LABEL;
  protected readonly kindLabel = PACKAGE_KIND_LABEL;
  protected readonly formLabel = FORM_CODE_LABEL;
  protected readonly formStatusLabel = FORM_STATUS_LABEL;
  protected readonly destLabel = TRANSFER_DESTINATION_LABEL;
  protected readonly transferLabel = TRANSFER_STATUS_LABEL;
  protected readonly destinations = Object.keys(
    TRANSFER_DESTINATION_LABEL,
  ) as DossierTransferDestination[];
  protected readonly caseLabel = specialCaseLabel;
  protected readonly short = shortHash;
  protected readonly lateText = msg1107;
  protected readonly lowerFirst = (s: string) => s.charAt(0).toLowerCase() + s.slice(1);

  protected readonly data = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.api.package(params),
  });
  protected readonly p = computed(() => (this.data.hasValue() ? this.data.value() : null));
  protected readonly forms = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.api.packageForms(params),
  });
  protected readonly formList = computed(() =>
    this.forms.hasValue() ? this.forms.value().items : [],
  );
  protected readonly checklist = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.api.packageChecklist(params),
  });
  protected readonly checks = computed(() =>
    this.checklist.hasValue() ? this.checklist.value() : null,
  );
  protected readonly failing = computed(
    () => this.checks()?.items.filter((i) => i.status !== 'DAT').length ?? 0,
  );
  protected readonly special = rxResource({
    params: () => {
      const p = this.p();
      return p ? { periodId: p.period.id, unitId: p.orgUnit?.id } : undefined;
    },
    stream: ({ params }) => this.api.specialCases(params),
  });
  protected readonly groups = computed(() =>
    this.special.hasValue() ? (this.special.value().groups ?? []) : [],
  );
  protected readonly specialTotal = computed(() => this.groups().reduce((a, g) => a + g.count, 0));
  /** Chứng thư của người ký (trường 6), chỉ đọc khi vai trò ký được gói. */
  protected readonly certs = rxResource({
    params: () => (this.can('SCR-1102.ky-goi') ? true : undefined),
    stream: () => this.api.myCertificates(),
  });
  protected readonly cert = computed(() =>
    this.certs.hasValue() ? (this.certs.value()[0] ?? null) : null,
  );
  protected readonly certExpired = computed(() => {
    const c = this.cert();
    return !!c && new Date(c.notAfter) < new Date();
  });
  protected readonly signer = computed(() => {
    const c = this.cert();
    return c ? { name: dnPart(c.subjectDn, 'CN'), ca: dnPart(c.issuerDn, 'O') } : null;
  });

  protected readonly notice = signal<string | null>(null);
  protected readonly warn = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly destination = signal<DossierTransferDestination>('BTC_TW');

  /** Chế độ "Đảng viên cao tuổi": một cột, chữ lớn (NFR-11). */
  protected readonly simple = computed(() => this.theme.mode() === 'elderly');
  protected readonly locked = computed(() => {
    const p = this.p();
    return !!p && isLocked(p);
  });

  protected allow(k: 'regenerate' | 'export' | 'transfer' | 'sign'): boolean {
    const p = this.p();
    return !!p && actions[k](p, this.can);
  }
  protected canView(f: DossierForm): boolean {
    return actions.view(f, this.can);
  }

  /** Chạy một thao tác ghi, báo lỗi theo mã, tải lại gói khi xung đột. */
  private async run<T>(op: () => Promise<T>): Promise<T | null> {
    if (this.busy()) return null;
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.warn.set(null);
    try {
      return await op();
    } catch (e) {
      const p = toProblem(e);
      this.error.set(problemMessage(p));
      if (p.status === 409 || p.status === 428) this.refresh();
      return null;
    } finally {
      this.busy.set(false);
    }
  }

  private refresh() {
    this.data.reload();
    this.forms.reload();
    this.checklist.reload();
  }

  private finish(location: string): Promise<Job | null> {
    return firstValueFrom(this.jobs.track(location).pipe(last(undefined, null)));
  }

  private save(blob: Blob, name: string, open = false) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    if (open) a.target = '_blank';
    else a.download = name;
    a.rel = 'noopener';
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  }

  protected async view(f: DossierForm) {
    const blob = await this.run(() => firstValueFrom(this.api.formFile(f.id)));
    if (blob) this.save(blob, `${f.formCode}.pdf`, true);
  }

  protected async regenerate() {
    const p = this.p();
    if (!p) return;
    const job = await this.run(async () =>
      this.finish((await firstValueFrom(this.api.regenerate(p))).location),
    );
    if (!job) return;
    this.refresh();
    if (job.status === 'HOAN_TAT') this.notice.set('Đã sinh lại các biểu mẫu lỗi.');
    else this.error.set('Chưa sinh lại được biểu mẫu lỗi. Vui lòng thử lại.');
  }

  protected async exportPackage() {
    const p = this.p();
    if (!p) return;
    const res = await this.run(async () => {
      // run() xoá thông báo cũ, nên nhãn chờ đặt bên trong để người dùng thấy trong lúc đóng gói
      this.notice.set('Đang đóng gói tệp, đồng chí vui lòng chờ…');
      const job = await this.finish((await firstValueFrom(this.api.exportPackage(p.id))).location);
      if (job?.status !== 'HOAN_TAT' || !job.resultLocation) return null;
      return firstValueFrom(this.api.download(job.resultLocation));
    });
    if (!res?.body) {
      if (!this.error()) this.error.set('Chưa tạo được tệp gói. Vui lòng thử lại.');
      return;
    }
    this.save(res.body, `${p.code}.zip`);
    this.notice.set(`Đã tải gói ${p.code} kèm manifest.`);
  }

  protected async transfer() {
    const p = this.p();
    if (!p) return;
    const dest = this.destination();
    const job = await this.run(async () =>
      this.finish((await firstValueFrom(this.api.transfer(p.id, { destination: dest }))).location),
    );
    if (!job) return;
    this.data.reload();
    if (job.status === 'HOAN_TAT') {
      this.notice.set(`Đã gửi gói con tới ${this.destLabel[dest]}.`);
      return;
    }
    const pr = job.problem as (Job['problem'] & { retryAt?: string }) | undefined;
    this.warn.set(
      msg1106(
        this.destLabel[dest],
        (pr?.detail ?? 'bên nhận chưa phản hồi').toLowerCase(),
        pr?.retryAt,
      ),
    );
  }

  /** "Ký số gói và nộp": kiểm tra danh sách, xác thực bước lên, chờ người ký xác nhận rồi báo MSG-11-05. */
  protected async sign() {
    const p = this.p();
    if (!p) return;
    if (this.failing()) {
      this.error.set(msg1104(this.failing()));
      document.getElementById('pk-check')?.focus();
      return;
    }
    if (!(await this.stepUp('Ký số gói hồ sơ cần xác thực nhiều lớp.'))) return;
    const job = await this.run(async () => {
      const { location } = await firstValueFrom(this.api.signPackage(p));
      this.notice.set('Đang chờ đồng chí xác nhận ký trên ứng dụng ký số…');
      return this.finish(location);
    });
    if (!job) return;
    if (job.status !== 'HOAN_TAT') {
      this.notice.set(null);
      this.error.set('Chưa ký được gói. Vui lòng thử lại.');
      return;
    }
    const after = await firstValueFrom(this.api.package(p.id));
    this.data.set(after);
    this.checklist.reload();
    this.notice.set(msg1105(after.code, after.submittedAt ?? new Date().toISOString()));
  }
}
