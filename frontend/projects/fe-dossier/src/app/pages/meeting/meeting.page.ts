/*
 * SCR-1101 Hội nghị kiểm điểm (QT-11 bước 4–9 → SCR-1101 → /api/v1/dossiers/meetings → MOD-06, SVC-11,
 * SVC-10 → EVT-21, EVT-17, EVT-18, EVT-11; WEB-040). Thư ký (`appraiser` hoặc `member` được phân công), chủ trì,
 * thành viên, người được kiểm điểm.
 *
 * Trường (TL-02b SCR-1101):
 *  1 Đối tượng kiểm điểm (chỉ đọc)             6 Góp ý: loại ưu điểm, hạn chế, kiến nghị; ≤ 4.000 ký tự
 *  2 Ngày giờ, địa điểm hội nghị                7 Ý kiến tại hội nghị (chỉ thư ký), ≤ 4.000 ký tự
 *  3 Thời điểm gửi dự thảo (chỉ đọc)            8 Hạn chế kỳ trước, thư ký cập nhật trạng thái
 *  4 Số ngày làm việc ở giữa (máy chủ tính)      9 Kết quả biểu quyết: tổng ≤ số người có mặt
 *  5 Thành phần, trạng thái (chỉ đọc)
 * Chức năng: Gửi dự thảo (POST .../send-draft, EVT-21); Tải dự thảo Mẫu 1B (GET /dossiers/forms/{id}/file);
 *  Gửi góp ý (POST .../comments); Sinh biên bản (POST .../minutes, 202, phiên AAL2, EVT-17); Kết thúc hội nghị
 *  (POST .../close, EVT-11, EVT-18); dời ngày hoặc ghi lý do giữ ngày (PATCH /dossiers/meetings/{id}, suy luận
 *  từ "Ngoại lệ và xử lý" của QT-11); cập nhật khắc phục (PATCH /dossiers/remediation-items/{id}).
 * Quy tắc: BR-11-01 (≥ 03 ngày làm việc), BR-11-05 (trường hợp đặc thù), BR-11-06 (góp ý bảo mật, ẩn danh),
 *  BR-11-07 (biểu quyết không thay mức quyết định), BR-11-08 (theo dõi khắc phục).
 * Thông báo: MSG-11-01 (thời hạn không đạt), MSG-11-02 (mời góp ý, máy chủ gửi), MSG-11-03 (kết thúc hội nghị).
 */
import { DatePipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { JobTracker, toProblem } from '@vt/api-client';
import {
  COMMENT_KIND_LABEL,
  DossierApi,
  DossierCommentKind,
  MEETING_ROLE_LABEL,
  MEETING_STATUS_LABEL,
  PARTICIPANT_STATUS_LABEL,
  RATING_LEVEL_LABEL,
} from '@vt/api-client/domains';
import { ThemeService, VtSkeletonComponent } from '@vt/design-system';
import { firstValueFrom, last, map } from 'rxjs';
import { DOSSIER_CAN } from '../../dossier-permissions';
import { DOSSIER_STEP_UP } from '../../step-up';
import { MeetingSidePanel } from './meeting-side.panel';
import {
  MSG_11_03,
  STEPS,
  actions,
  deadlinePassedText,
  isPresent,
  msg1101,
  problemMessage,
  specialCaseLabel,
  stepOf,
} from './meeting-rules';

@Component({
  selector: 'vt-meeting-page',
  imports: [VtSkeletonComponent, DatePipe, RouterLink, MeetingSidePanel],
  templateUrl: './meeting.page.html',
  styleUrl: './meeting.page.css',
})
export class MeetingPage {
  private readonly api = inject(DossierApi);
  private readonly jobs = inject(JobTracker);
  private readonly stepUp = inject(DOSSIER_STEP_UP);
  private readonly theme = inject(ThemeService);
  protected readonly can = inject(DOSSIER_CAN);

  readonly id = input.required<string>();

  protected readonly steps = STEPS;
  protected readonly statusLabel = MEETING_STATUS_LABEL;
  protected readonly roleLabel = MEETING_ROLE_LABEL;
  protected readonly partLabel = PARTICIPANT_STATUS_LABEL;
  protected readonly kindLabel = COMMENT_KIND_LABEL;
  protected readonly levelLabel = RATING_LEVEL_LABEL;
  protected readonly kinds: DossierCommentKind[] = ['UU_DIEM', 'HAN_CHE', 'KIEN_NGHI'];
  protected readonly passedText = deadlinePassedText;
  protected readonly lateText = msg1101;
  protected readonly act = actions;

  protected readonly data = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.api.meeting(params),
  });
  protected readonly m = computed(() => (this.data.hasValue() ? this.data.value() : null));
  protected readonly comments = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.api.comments(params),
  });
  protected readonly commentList = computed(() =>
    this.comments.hasValue() ? this.comments.value().items : [],
  );
  protected readonly anonymous = computed(
    () => this.comments.hasValue() && !!this.comments.value().anonymous,
  );

  /** Trường hợp đặc thù của người được kiểm điểm (cơ quan tổ chức xem, BR-11-05). */
  protected readonly special = rxResource({
    params: () => {
      const m = this.m();
      return m?.subjectPerson && this.can('SCR-1101.tao')
        ? { period: m.period.id, person: m.subjectPerson.id }
        : undefined;
    },
    stream: ({ params }) =>
      this.api
        .specialCases({ periodId: params.period })
        .pipe(map((p) => p.items.filter((c) => c.person.id === params.person))),
  });
  protected readonly specialCases = computed(() =>
    this.special.hasValue() ? this.special.value() : [],
  );
  protected readonly caseLabel = specialCaseLabel;

  protected readonly step = computed(() => {
    const m = this.m();
    return m ? stepOf(m) : 0;
  });
  protected readonly present = computed(
    () => this.m()?.participants?.filter((p) => isPresent(p.status)).length ?? 0,
  );
  protected readonly draftForm = computed(
    () => this.m()?.draftForms?.find((f) => f.formCode === 'MAU_1B') ?? null,
  );

  protected readonly notice = signal<string | null>(null);
  protected readonly warn = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);

  // Góp ý (trường 6) và ý kiến tại hội nghị (trường 7)
  protected readonly kind = signal<DossierCommentKind>('UU_DIEM');
  protected readonly content = signal('');
  protected readonly contentError = signal<string | null>(null);

  // Ngoại lệ BR-11-01: lý do giữ ngày (chủ trì), dời ngày (cơ quan tổ chức)
  protected readonly reason = signal('');
  protected readonly newDate = signal('');

  /** Chế độ "Đảng viên cao tuổi": một cột, chữ lớn (NFR-11). */
  protected readonly simple = computed(() => this.theme.mode() === 'elderly');

  protected allow(k: keyof typeof actions): boolean {
    const m = this.m();
    return !!m && actions[k](m, this.can);
  }

  /** Chạy một thao tác ghi, báo lỗi theo mã, tải lại hội nghị khi xung đột phiên bản. */
  private async run<T>(op: () => Promise<T>): Promise<T | null> {
    if (this.busy()) return null;
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      return await op();
    } catch (e) {
      const p = toProblem(e);
      this.error.set(problemMessage(p));
      if (p.status === 409 || p.status === 428) this.data.reload();
      return null;
    } finally {
      this.busy.set(false);
    }
  }

  protected async sendDraft() {
    const m = this.m();
    if (!m) return;
    const r = await this.run(() => firstValueFrom(this.api.sendDraft(m)));
    if (!r) return;
    this.data.set(r);
    if (r.deadlineCheck.passed) this.notice.set('Đã gửi dự thảo tới các thành viên hội nghị.');
    else this.warn.set(msg1101(r.deadlineCheck));
  }

  protected async downloadDraft() {
    const f = this.draftForm();
    if (!f) return;
    const blob = await this.run(() => firstValueFrom(this.api.formFile(f.id)));
    if (!blob) return;
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'Du-thao-Mau-1B.pdf';
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  protected async addComment(phase: 'TRUOC_HOI_NGHI' | 'HOI_NGHI') {
    const m = this.m();
    const text = this.content().trim();
    if (!m) return;
    if (!text || text.length > 4000) {
      this.contentError.set(text ? 'Nội dung tối đa 4.000 ký tự.' : 'Nhập nội dung trước khi gửi.');
      document.getElementById('mt-content')?.focus();
      return;
    }
    this.contentError.set(null);
    const r = await this.run(() =>
      firstValueFrom(
        this.api.addComment(m.id, {
          subjectPersonId: m.subjectPerson?.id ?? '',
          kind: this.kind(),
          phase,
          content: text,
        }),
      ),
    );
    if (!r) return;
    this.content.set('');
    this.notice.set(phase === 'HOI_NGHI' ? 'Đã ghi ý kiến tại hội nghị.' : 'Đã gửi góp ý.');
    this.comments.reload();
    this.data.reload();
  }

  protected async keepDate() {
    const m = this.m();
    const r = this.reason().trim();
    if (!m) return;
    if (r.length < 10) {
      this.error.set('Lý do giữ ngày họp cần ít nhất 10 ký tự.');
      document.getElementById('mt-reason')?.focus();
      return;
    }
    const res = await this.run(() =>
      firstValueFrom(this.api.updateMeeting(m, { deadlineOverrideReason: r })),
    );
    if (!res) return;
    this.data.set(res);
    this.warn.set(null);
    this.notice.set('Đã ghi lý do giữ ngày họp; lý do sẽ được in vào biên bản.');
  }

  protected async reschedule() {
    const m = this.m();
    const v = this.newDate();
    if (!m) return;
    if (!v) {
      this.error.set('Chọn ngày giờ hội nghị mới.');
      document.getElementById('mt-date')?.focus();
      return;
    }
    const res = await this.run(() =>
      firstValueFrom(this.api.updateMeeting(m, { scheduledAt: new Date(v).toISOString() })),
    );
    if (!res) return;
    this.data.set(res);
    this.warn.set(res.deadlineCheck.passed || !res.draftSentAt ? null : msg1101(res.deadlineCheck));
    this.notice.set('Đã dời ngày hội nghị và tính lại thời hạn gửi dự thảo.');
  }

  /** "Sinh biên bản" (thư ký): xác thực bước lên, gửi điểm danh và biểu quyết, chờ job sinh xong. */
  protected async minutes(body: Parameters<DossierApi['minutes']>[1]) {
    const m = this.m();
    if (!m) return;
    if (!(await this.stepUp('Lập biên bản hội nghị cần xác thực nhiều lớp.'))) return;
    const done = await this.run(async () => {
      const { location } = await firstValueFrom(this.api.minutes(m, body));
      return firstValueFrom(this.jobs.track(location).pipe(last(undefined, null)));
    });
    if (done === null && this.error()) return;
    if (done && done.status !== 'HOAN_TAT') {
      this.error.set('Chưa sinh được biên bản. Vui lòng thử lại.');
      return;
    }
    this.notice.set('Đã ghi kết quả biểu quyết và sinh biên bản hội nghị.');
    this.data.reload();
  }

  protected async close() {
    const m = this.m();
    if (!m) return;
    const r = await this.run(() => firstValueFrom(this.api.close(m)));
    if (!r) return;
    this.data.set(r);
    this.notice.set(MSG_11_03);
    this.comments.reload();
  }
}
