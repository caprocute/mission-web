import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  PackVersionRef,
  PolicyApi,
  PolicyPackVersion,
  PolicyPackVersionSummary,
  PolicyTestRunSummary,
  isPolicyVersionEditable,
} from '@vt/api-client';
import { map, switchMap, takeWhile, tap, timer } from 'rxjs';
import { errorText } from '../../studio-ui';

/** Tên hiển thị của quy tắc không có nhãn trong `manifest.labels`. */
export const RULE_TITLE: Record<string, string> = {
  kpi_nhiem_vu: 'Điểm KPI của nhiệm vụ',
  nhom: 'KPI nhóm nhiệm vụ',
  diem: 'Tổng hợp điểm',
  muc_cuoi: 'Mức cuối cùng',
  nguoi_dung_dau: 'Quy tắc người đứng đầu',
};

const POLL_MS = 1500;

/**
 * Phiên bản Policy Pack đang mở trong Config Studio (SCR-1501 … SCR-1504): gói, phiên bản kèm ETag,
 * lần chạy kiểm thử gần nhất và thao tác chạy bộ test case vàng (202, theo dõi qua danh sách lần
 * chạy). Cung cấp ở khung gói, các tab dùng chung.
 */
@Injectable()
export class PolicyWorkspace {
  private readonly api = inject(PolicyApi);
  private readonly destroyRef = inject(DestroyRef);

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
  readonly runError = signal<string | null>(null);
  private readonly starting = signal(false);

  /** Đang chờ saga phát hành chạy xong sau khi ký phê duyệt (202). */
  readonly publishing = signal(false);

  /**
   * Theo dõi phiên bản sau khi ký phê duyệt tới khi rời `CHO_PHE_DUYET` (EVT-13; dự phòng cho SSE).
   * Gọi `done` với phiên bản mới.
   */
  waitPublished(done: (v: PolicyPackVersion) => void) {
    const ref = this.ref();
    if (!ref) return;
    this.publishing.set(true);
    timer(POLL_MS, POLL_MS)
      .pipe(
        switchMap(() => this.api.version(ref)),
        takeWhile((v) => v.value.status === 'CHO_PHE_DUYET', true),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (v) => {
          if (v.value.status === 'CHO_PHE_DUYET') return;
          this.version.set(v);
          this.pack.reload();
          this.publishing.set(false);
          done(v.value);
        },
        error: (e) => {
          this.publishing.set(false);
          this.runError.set(errorText(e, 'Không theo dõi được tiến trình phát hành.'));
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
    if (!ref || !etag) return;
    const base = this.published()?.version;
    this.starting.set(true);
    this.runError.set(null);
    this.api
      .runTests(ref, etag, {
        propertyDatasets: 10000,
        ...(base ? { compareWithVersion: base } : {}),
      })
      .pipe(
        tap(() => this.version.reload()),
        switchMap(() => timer(0, POLL_MS)),
        switchMap(() => this.api.testRuns(ref, 1)),
        map((l) => l.items[0] ?? null),
        tap((run) => this.lastRun.set(run)),
        takeWhile((run) => run?.result === 'DANG_CHAY', true),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        complete: () => {
          this.starting.set(false);
          this.runsChanged.update((n) => n + 1);
          this.version.reload();
          this.pack.reload();
        },
        error: (e) => {
          this.starting.set(false);
          this.runError.set(errorText(e, 'Không chạy được kiểm thử.'));
        },
      });
  }
}
