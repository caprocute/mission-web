import { Component, computed, inject, input, linkedSignal, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { toProblem } from '@vt/api-client';
import {
  DossierApi,
  DossierAttendance,
  DossierMeeting,
  DossierMinutesRequest,
  DossierRemediationItem,
  DossierRemediationStatus,
  DossierVote,
  RATING_LEVEL_LABEL,
  REMEDIATION_STATUS_LABEL,
} from '@vt/api-client/domains';
import { VtSkeletonComponent } from '@vt/design-system';
import { firstValueFrom } from 'rxjs';
import { LEVELS, isPresent, problemMessage, voteError, voteTotal } from './meeting-rules';

/**
 * Cột phải của SCR-1101: vùng 3 "Khắc phục hạn chế kỳ trước" (trường 8, BR-11-08; thư ký cập nhật trạng
 * thái) và vùng 4 "Biểu quyết xếp loại tại hội nghị" (trường 9, BR-11-07): thư ký điểm danh, nhập số phiếu
 * theo mức rồi "Sinh biên bản"; trang cha lo xác thực bước lên và theo dõi job.
 */
@Component({
  selector: 'vt-meeting-side',
  imports: [VtSkeletonComponent],
  templateUrl: './meeting-side.panel.html',
  styleUrl: './meeting-side.panel.css',
})
export class MeetingSidePanel {
  private readonly api = inject(DossierApi);

  readonly meeting = input.required<DossierMeeting>();
  readonly canRemediate = input(false);
  readonly canMinutes = input(false);
  readonly busy = input(false);
  readonly minutes = output<DossierMinutesRequest>();

  protected readonly levels = LEVELS;
  protected readonly levelLabel = RATING_LEVEL_LABEL;
  protected readonly remLabel = REMEDIATION_STATUS_LABEL;
  protected readonly remStatuses: DossierRemediationStatus[] = [
    'MO',
    'DANG_KHAC_PHUC',
    'DA_KHAC_PHUC',
    'CHUA_KHAC_PHUC',
  ];

  protected readonly items = rxResource({
    params: () => ({ id: this.meeting().id, v: this.meeting().rowVersion }),
    stream: ({ params }) => this.api.remediationItems(params.id),
  });
  protected readonly previous = computed(() =>
    this.items.hasValue()
      ? this.items.value().items.filter((i) => i.meetingId !== this.meeting().id)
      : [],
  );
  protected readonly created = computed(() =>
    this.items.hasValue()
      ? this.items.value().items.filter((i) => i.meetingId === this.meeting().id)
      : [],
  );
  protected readonly remError = signal<string | null>(null);

  // Điểm danh: mặc định theo trạng thái trên thẻ thành phần
  protected readonly attendance = linkedSignal(() =>
    (this.meeting().participants ?? []).map((p) => ({
      personId: p.person.id,
      name: p.person.name,
      present: isPresent(p.status),
    })),
  );
  protected readonly presentCount = computed(
    () => this.attendance().filter((a) => a.present).length,
  );
  protected readonly vote = signal<DossierVote>({ HTXSNV: 0, HTTNV: 0, HTNV: 0, KHTNV: 0 });
  protected readonly voteErr = signal<string | null>(null);

  /** Tỷ lệ phiếu của mức đề xuất trên tổng phiếu, cho thanh tiến độ của vùng 4. */
  protected readonly share = computed(() => {
    const m = this.meeting();
    const v = m.vote;
    const total = voteTotal(v);
    return total && m.proposedLevel ? Math.round(((v?.[m.proposedLevel] ?? 0) / total) * 100) : 0;
  });

  protected togglePresent(i: number) {
    this.attendance.update((list) =>
      list.map((a, j) => (j === i ? { ...a, present: !a.present } : a)),
    );
  }

  protected setVote(level: keyof DossierVote, value: string) {
    const n = value === '' ? 0 : Number(value);
    this.vote.update((v) => ({ ...v, [level]: n }));
  }

  protected submit() {
    const err = voteError(this.vote(), this.presentCount());
    this.voteErr.set(err);
    if (err) {
      document.getElementById('sp-vote-HTXSNV')?.focus();
      return;
    }
    const attendance: DossierAttendance[] = this.attendance().map((a) => ({
      personId: a.personId,
      attendance: a.present ? 'CO_MAT' : 'VANG_CO_LY_DO',
    }));
    this.minutes.emit({ meetingAt: new Date().toISOString(), attendance, vote: this.vote() });
  }

  protected async setStatus(item: DossierRemediationItem, status: string) {
    this.remError.set(null);
    try {
      await firstValueFrom(
        this.api.updateRemediation(item, { status: status as DossierRemediationStatus }),
      );
    } catch (e) {
      this.remError.set(problemMessage(toProblem(e)));
    }
    this.items.reload();
  }
}
