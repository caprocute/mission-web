/*
 * SCR-1101 Hội nghị kiểm điểm: danh sách và "Lập hội nghị" (QT-11 bước 1–2 → SCR-1101 →
 * GET|POST /api/v1/dossiers/meetings, GET /dossiers/special-cases → MOD-06 → EVT-17; WEB-040).
 * Đường vào "Hồ sơ số › Kiểm điểm": hội nghị mà người dùng là thành phần hoặc thuộc đơn vị mình chuẩn bị.
 * Lập hội nghị (AC-07, ma trận `SCR-1101.tao`, suy luận): loại, kỳ, ngày giờ, địa điểm, người được kiểm điểm
 * (bỏ trống là kiểm điểm tập thể), chủ trì, thư ký (khác chủ trì), thành phần; khi chọn người được kiểm điểm,
 * màn hình hiện trường hợp đặc thù của người đó trong kỳ (BR-11-05).
 */
import { DatePipe } from '@angular/common';
import {
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  DossierApi,
  DossierMeetingStatus,
  DossierMeetingType,
  MEETING_ROLE_LABEL,
  MEETING_STATUS_LABEL,
  MEETING_TYPE_LABEL,
  OrgApi,
  PeriodApi,
  fieldErrors,
  toProblem,
} from '@vt/api-client';
import { firstValueFrom, map } from 'rxjs';
import { DOSSIER_CAN } from '../../dossier-permissions';
import { problemMessage, specialCaseLabel } from '../meeting/meeting-rules';
import { CreateForm, createErrors, toCreate } from './meetings-rules';

@Component({
  selector: 'vt-meetings-page',
  imports: [DatePipe, RouterLink],
  templateUrl: './meetings.page.html',
  styleUrl: './meetings.page.css',
})
export class MeetingsPage {
  private readonly api = inject(DossierApi);
  private readonly orgApi = inject(OrgApi);
  private readonly periodApi = inject(PeriodApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly injector = inject(Injector);
  protected readonly can = inject(DOSSIER_CAN);

  protected readonly statusLabel = MEETING_STATUS_LABEL;
  protected readonly roleLabel = MEETING_ROLE_LABEL;
  protected readonly typeLabel = MEETING_TYPE_LABEL;
  protected readonly statuses = Object.keys(MEETING_STATUS_LABEL) as DossierMeetingStatus[];
  protected readonly types = Object.keys(MEETING_TYPE_LABEL) as DossierMeetingType[];
  protected readonly caseLabel = specialCaseLabel;

  protected readonly status = signal<DossierMeetingStatus | ''>('');
  protected readonly list = rxResource({
    params: () => ({ status: this.status() }),
    stream: ({ params }) => this.api.meetings({ status: params.status || undefined }),
  });
  protected readonly items = computed(() => (this.list.hasValue() ? this.list.value().items : []));

  // Lập hội nghị
  protected readonly open = signal(false);
  protected readonly form = signal<CreateForm>(empty());
  protected readonly errors = signal<Record<string, string>>({});
  protected readonly formError = signal<string | null>(null);
  protected readonly busy = signal(false);
  private returnFocus: HTMLElement | null = null;
  private readonly dialog = viewChild<ElementRef<HTMLElement>>('dialog');

  protected readonly periods = rxResource({
    params: () => (this.open() ? true : undefined),
    stream: () =>
      this.periodApi.list().pipe(map((l) => l.filter((p) => p.kind === 'NAM' || p.kind === 'QUY'))),
  });
  protected readonly persons = rxResource({
    params: () => (this.open() ? true : undefined),
    stream: () => this.orgApi.persons().pipe(map((p) => p.items)),
  });
  protected readonly people = computed(() => (this.persons.hasValue() ? this.persons.value() : []));
  protected readonly cases = rxResource({
    params: () => {
      const f = this.form();
      return this.open() && f.periodId && f.subjectPersonId
        ? { period: f.periodId, person: f.subjectPersonId }
        : undefined;
    },
    stream: ({ params }) =>
      this.api
        .specialCases({ periodId: params.period })
        .pipe(map((p) => p.items.filter((c) => c.person.id === params.person))),
  });
  protected readonly subjectCases = computed(() =>
    this.cases.hasValue() ? this.cases.value() : [],
  );

  protected set<K extends keyof CreateForm>(k: K, v: CreateForm[K]) {
    this.form.update((f) => ({ ...f, [k]: v }));
  }

  protected toggleParticipant(id: string) {
    this.form.update((f) => ({
      ...f,
      participantIds: f.participantIds.includes(id)
        ? f.participantIds.filter((x) => x !== id)
        : [...f.participantIds, id],
    }));
  }

  protected openCreate(ev: Event) {
    this.returnFocus = ev.target as HTMLElement;
    this.form.set(empty());
    this.errors.set({});
    this.formError.set(null);
    this.open.set(true);
    afterNextRender(
      () => this.dialog()?.nativeElement.querySelector<HTMLElement>('select')?.focus(),
      {
        injector: this.injector,
      },
    );
  }

  protected closeCreate() {
    this.open.set(false);
    const back = this.returnFocus;
    afterNextRender(() => back?.focus(), { injector: this.injector });
  }

  protected onKey(ev: KeyboardEvent) {
    if (ev.key === 'Escape' && !this.busy()) {
      ev.stopPropagation();
      this.closeCreate();
    }
  }

  protected async create() {
    const f = this.form();
    const errs = createErrors(f);
    this.errors.set(errs);
    if (Object.keys(errs).length) {
      document.getElementById(`mc-${Object.keys(errs)[0]}`)?.focus();
      return;
    }
    const unitId = this.people().find((p) => p.id === f.chairPersonId)?.unitId ?? '';
    this.busy.set(true);
    this.formError.set(null);
    try {
      const m = await firstValueFrom(this.api.createMeeting(toCreate(f, unitId)));
      this.open.set(false);
      await this.router.navigate([m.id], { relativeTo: this.route });
    } catch (e) {
      const p = toProblem(e);
      const fe = fieldErrors(p);
      this.errors.set(fe);
      this.formError.set(Object.keys(fe).length ? null : problemMessage(p));
    } finally {
      this.busy.set(false);
    }
  }
}

function empty(): CreateForm {
  return {
    meetingType: 'KIEM_DIEM_NAM',
    periodId: '',
    scheduledAt: '',
    location: '',
    subjectPersonId: '',
    chairPersonId: '',
    secretaryPersonId: '',
    participantIds: [],
  };
}
