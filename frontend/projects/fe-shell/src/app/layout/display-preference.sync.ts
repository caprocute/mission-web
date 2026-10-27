import { DestroyRef, Injectable, effect, inject, untracked } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { PreferenceApi, UserPreferenceItem } from '@vt/api-client';
import { SessionService } from '@vt/auth';
import { ThemeService, UserDisplayPreference } from '@vt/design-system';
import { switchMap } from 'rxjs';

const KEY = 'ui.display';
/** Thời gian chờ giữa các lần thử lại khi mất mạng (giây). */
const RETRY_SECONDS = [2, 4, 8, 16];

/**
 * Đồng bộ chế độ Đảng viên cao tuổi theo người dùng trên mọi thiết bị (CNH-05, NFR-11, BR-18-09):
 * sau đăng nhập đọc khoá `ui.display` (CNH-02) và áp vào `ThemeService`; nút chuyển nhanh ghi ngay
 * lên máy chủ kèm `If-Match`. Mất mạng thì giữ lựa chọn trong phiên và thử lại; xung đột phiên bản
 * thì đọc bản mới rồi ghi lại lựa chọn vừa bấm (người dùng bấm sau cùng là ý định đúng). Bấm nút khi
 * khoá chưa đọc xong thì chờ đọc xong mới ghi, để lệnh ghi mang đúng `If-Match` thay vì bị trả 428.
 */
@Injectable({ providedIn: 'root' })
export class DisplayPreferenceSync {
  private readonly api = inject(PreferenceApi);
  private readonly theme = inject(ThemeService);
  private readonly session = inject(SessionService);
  private item: UserPreferenceItem | null = null;
  private loadedFor: string | null = null;
  /** Đang đọc khoá sau đăng nhập; trong lúc này lệnh ghi phải chờ để biết `rowVersion`. */
  private loading = false;
  /** Người dùng đã bấm nút trong lúc đang đọc. */
  private pendingSave = false;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private attempt = 0;

  /** Theo dõi người dùng đăng nhập từ lần đầu khung ứng dụng cần dịch vụ này. */
  constructor() {
    inject(DestroyRef).onDestroy(() => this.clearRetry());
    effect(() => {
      const id = this.session.user()?.id ?? null;
      untracked(() => {
        if (id && id !== this.loadedFor) this.load(id);
        if (!id) this.loadedFor = null;
      });
    });
  }

  /** Nút chuyển nhanh trên thanh trên. */
  toggle(): void {
    this.theme.toggleElderly();
    this.attempt = 0;
    if (this.loading) this.pendingSave = true;
    else this.save();
  }

  private load(userId: string): void {
    this.loadedFor = userId;
    this.loading = true;
    this.api.all().subscribe({
      next: (all) => {
        this.item = all.items[KEY] ?? null;
        this.loading = false;
        // đã bấm trong lúc đọc: lựa chọn vừa bấm thắng bản trên máy chủ, ghi kèm phiên bản vừa đọc
        if (this.flushPending()) return;
        this.theme.applyUserPreference(this.preferenceOf(this.item));
      },
      // không đọc được: giữ bản đệm cục bộ, lần đăng nhập sau đọc lại
      error: () => {
        this.loadedFor = null;
        this.loading = false;
        this.flushPending();
      },
    });
  }

  private flushPending(): boolean {
    if (!this.pendingSave) return false;
    this.pendingSave = false;
    this.save();
    return true;
  }

  private save(): void {
    this.clearRetry();
    const value = { ...(this.item?.value ?? {}), ...this.theme.currentPreference() };
    this.api.put(KEY, value, this.item?.rowVersion ?? null).subscribe({
      next: (saved) => (this.item = saved),
      error: (err) => this.recover(err),
    });
  }

  private recover(err: unknown): void {
    const status = err instanceof HttpErrorResponse ? err.status : -1;
    if (status === 409 || status === 412 || status === 428) {
      // bản trên máy chủ đã đổi ở thiết bị khác: lấy phiên bản mới rồi ghi lại lựa chọn vừa bấm
      const mine = this.theme.currentPreference();
      this.api
        .all()
        .pipe(
          switchMap((all) => {
            this.item = all.items[KEY] ?? null;
            return this.api.put(
              KEY,
              { ...(this.item?.value ?? {}), ...mine },
              this.item?.rowVersion ?? null,
            );
          }),
        )
        .subscribe({ next: (saved) => (this.item = saved), error: () => this.scheduleRetry() });
      return;
    }
    if (status === 0 || status >= 500 || status === 429) this.scheduleRetry();
  }

  private scheduleRetry(): void {
    if (this.attempt >= RETRY_SECONDS.length) return;
    const wait = RETRY_SECONDS[this.attempt++] * 1000;
    this.retryTimer = setTimeout(() => this.save(), wait);
  }

  private clearRetry(): void {
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = null;
  }

  private preferenceOf(item: UserPreferenceItem | null): UserDisplayPreference | null {
    const v = item?.value as Partial<UserDisplayPreference> | undefined;
    if (!v || (v.mode !== 'elderly' && v.mode !== 'default')) return null;
    return { mode: v.mode, reducedSteps: v.reducedSteps === true };
  }
}
