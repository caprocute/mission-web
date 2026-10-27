import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Job, JobAccepted, JobTracker } from '@vt/api-client';
import {
  PackVersionRef,
  PolicyApi,
  PolicyPackVersion,
  PolicyPackVersionSummary,
  PolicyTestRunSummary,
  isPolicyVersionEditable,
} from '@vt/api-client/domains';
import { last, map, switchMap, tap } from 'rxjs';
import { errorText, jobErrorText } from '../../studio-ui';

/** Tên hiển thị của quy tắc không có nhãn trong `manifest.labels`. */
export const RULE_TITLE: Record<string, string> = {
  A: 'Số lượng',
  B: 'Chất lượng',
  C: 'Tiến độ',
  kpi_A: 'Số lượng',
  kpi_B: 'Chất lượng',
  kpi_C: 'Tiến độ',
  kpi_nhiem_vu: 'Điểm KPI của nhiệm vụ',
  nhom: 'KPI nhóm nhiệm vụ',
  diem: 'Tổng hợp điểm',
  muc_cuoi: 'Mức cuối cùng',
  nguoi_dung_dau: 'Quy tắc người đứng đầu',
};

/**
 * Phiên bản Policy Pack đang mở trong Config Studio (SCR-1501 … SCR-1504): gói, phiên bản kèm ETag,
 * lần chạy kiểm thử gần nhất và thao tác chạy bộ test case vàng (202 + `Location`, theo dõi job bằng
 * `JobTracker` rồi đọc lại lần chạy). Cung cấp ở khung gói, các tab dùng chung.
 */
@Injectable()
export class PolicyWorkspace {
  private readonly api = inject(PolicyApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly jobs = inject(JobTracker);

  readonly ref = signal<PackVersionRef | null>(null);
  readonly pack = rxResource({
    params: () => this.ref()?.id,
    stream: ({ params }) => this.api.pack(params),
  });
  readonly version = rxResource({
    params: () => this.ref() ?? undefined,
    stream: ({ params }) => this.api.version(params),
  });
  readonly lastRun = rxResource({
    params: () => this.ref() ?? undefined,
    stream: ({ params }) =>
      this.api
        .testRuns(params, 1)
        .pipe(map((l) => (l.items[0] ?? null) as PolicyTestRunSummary | null)),
  });

  readonly status = computed(() => this.version.value()?.value.status);
  readonly editable = computed(() => {
    const s = this.status();
    return !!s && isPolicyVersionEditable(s);
  });
  private readonly manifest = computed(() => this.version.value()?.value.manifest);
  /** Nhãn hiển thị của khối và nhóm (`manifest.labels`). */
  readonly labels = computed(() => this.manifest()?.labels ?? {});
  readonly notes = computed(() => this.manifest()?.notes ?? {});
  /** Biến khai báo cho gợi ý và kiểm tra biến chưa biết. */
  readonly variables = computed(() => this.manifest()?.variables ?? []);
  /** Phiên bản đã phát hành mới nhất khác bản đang mở, để so sánh. */
  readonly published = computed<PolicyPackVersionSummary | null>(() => {
    const cur = this.ref()?.v;
    const list = (this.pack.value()?.versions ?? []).filter(
      (v) => v.status === 'DA_PHAT_HANH' && v.version !== cur,
    );
    return (
      list.sort((a, b) => (b.effectiveFrom ?? '').localeCompare(a.effectiveFrom ?? ''))[0] ?? null
    );
  });

  readonly running = computed(
    () => this.starting() || this.lastRun.value()?.result === 'DANG_CHAY',
  );
  /**
   * Phiên bản đang được đọc lại (sau khi lưu nháp, chạy kiểm thử…): ETag đang giữ có thể đã cũ, nên chưa
   * cho chạy kiểm thử để máy chủ không từ chối vì xung đột phiên bản (409).
   */
  readonly versionSyncing = computed(() => this.version.isLoading());
  readonly runError = signal<string | null>(null);
  private readonly starting = signal(false);

  /** Đang chờ saga phát hành chạy xong sau khi ký phê duyệt (202). */
  readonly publishing = signal(false);

  /**
   * Theo dõi saga phát hành sau khi ký phê duyệt (202 + `Location`, EVT-13) bằng `JobTracker`; job
   * xong thì đọc lại phiên bản và gọi `done` khi đã phát hành, job lỗi thì báo lỗi của job.
   */
  waitPublished(accepted: JobAccepted, done: (v: PolicyPackVersion) => void) {
    const ref = this.ref();
    if (!ref) return;
    this.publishing.set(true);
    this.jobs
      .track(accepted.location)
      .pipe(
        last(),
        switchMap((job) => {
          if (job.status !== 'HOAN_TAT') throw job;
          return this.api.version(ref);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (v) => {
          this.version.set(v);
          this.pack.reload();
          this.publishing.set(false);
          if (v.value.status === 'DA_PHAT_HANH') done(v.value);
          else this.runError.set('Phiên bản chưa được phát hành. Vui lòng tải lại sau ít phút.');
        },
        error: (e) => {
          this.publishing.set(false);
          this.version.reload();
          this.runError.set(
            isJob(e)
              ? jobErrorText(e, 'Không phát hành được phiên bản.')
              : errorText(e, 'Không theo dõi được tiến trình phát hành.'),
          );
        },
      });
  }

  label(key: string): string {
    return this.labels()[key] ?? RULE_TITLE[key] ?? key;
  }

  /** Tăng mỗi khi một lần chạy kết thúc, để thẻ Test case tải lại lịch sử. */
  readonly runsChanged = signal(0);

  /**
   * Chạy bộ test case vàng trên hai engine cùng kiểm thử thuộc tính, so sánh tác động với bản đang
   * áp dụng, rồi theo dõi tới khi có kết quả (BR-15-07).
   */
  runTests() {
    const ref = this.ref();
    const etag = this.version.value()?.etag;
    if (!ref || !etag || this.versionSyncing()) return;
    const base = this.published()?.version;
    this.starting.set(true);
    this.runError.set(null);
    this.api
      .runTests(ref, etag, {
        propertyDatasets: 10000,
        ...(base ? { compareWithVersion: base } : {}),
      })
      .pipe(
        tap(() => {
          this.version.reload();
          this.lastRun.reload();
        }),
        switchMap((accepted) => this.jobs.track(accepted.location)),
        last(),
        switchMap((job) => {
          if (job.status !== 'HOAN_TAT') throw job;
          return this.api.testRuns(ref, 1);
        }),
        map((l) => l.items[0] ?? null),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (run) => this.lastRun.set(run),
        complete: () => this.finishRun(),
        error: (e) => {
          this.finishRun();
          this.runError.set(
            isJob(e)
              ? jobErrorText(e, 'Không chạy được kiểm thử.')
              : errorText(e, 'Không chạy được kiểm thử.'),
          );
        },
      });
  }

  private finishRun() {
    this.starting.set(false);
    this.runsChanged.update((n) => n + 1);
    this.version.reload();
    this.pack.reload();
  }
}

/** Job kết thúc không thành công được ném vào nhánh lỗi để tách khỏi lỗi HTTP. */
const isJob = (e: unknown): e is Job =>
  !!e && typeof e === 'object' && 'status' in e && 'type' in e && 'id' in e;
