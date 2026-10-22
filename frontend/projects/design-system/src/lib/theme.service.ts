import { DOCUMENT } from '@angular/common';
import {
  CSP_NONCE,
  Injectable,
  WritableSignal,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';

export type DisplayMode = 'default' | 'elderly';

/** Tuỳ chọn hiển thị cá nhân lưu ở máy chủ (khoá `ui.display`, CNH-02). */
export interface UserDisplayPreference {
  mode: DisplayMode;
  reducedSteps: boolean;
}

/** Bản đệm cục bộ của chế độ (giá trị C1) để tải lại trang không nháy giao diện. */
const MODE_KEY = 'vt.displayMode';

/**
 * Áp token của tenant và chế độ hiển thị lên phần tử gốc.
 * Chế độ "Đảng viên cao tuổi" (NFR-11) bật bằng `data-mode="elderly"`; cờ `reducedSteps`
 * cho các màn hình biết cần chuyển sang luồng ít bước.
 *
 * CNH-05: chế độ theo người dùng trên mọi thiết bị. Thứ tự ưu tiên: tuỳ chọn máy chủ của người
 * dùng, rồi lựa chọn bằng nút chuyển nhanh trong phiên, rồi mặc định UI Pack của tenant. Trước
 * khi máy chủ trả lời, chế độ lấy từ bản đệm cục bộ để không nháy.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly doc = inject(DOCUMENT);
  private readonly nonce = inject(CSP_NONCE, { optional: true });
  private styleEl: HTMLStyleElement | null = null;

  readonly mode = signal<DisplayMode>(this.readStoredMode());
  readonly logoUrl = signal<string | null>(null);

  /** Cờ "ít bước" theo mặc định UI Pack (`modes.elderly.flags`), do `UiPackLoader` đặt. */
  private readonly packReducedSteps = signal(false);
  /** Cờ "ít bước" theo tuỳ chọn máy chủ của người dùng; `null` khi người dùng chưa lưu. */
  private readonly userReducedSteps = signal<boolean | null>(null);
  /** Người dùng đã bấm nút chuyển nhanh trong phiên này. */
  private sessionChoice = false;

  /**
   * Cờ "ít bước" đang áp: tuỳ chọn người dùng nếu có, không thì mặc định UI Pack. Ghi (`set`,
   * `update`) là ghi mặc định UI Pack, giữ tương thích với `UiPackLoader` và kiểm thử cũ.
   */
  readonly reducedSteps: WritableSignal<boolean> = Object.assign(
    computed(() => this.userReducedSteps() ?? this.packReducedSteps()),
    {
      set: (v: boolean) => this.packReducedSteps.set(v),
      update: (fn: (v: boolean) => boolean) => this.packReducedSteps.update(fn),
      asReadonly: () => this.reducedSteps,
    },
  ) as unknown as WritableSignal<boolean>;

  constructor() {
    // đặt ngay khi khởi tạo (trước lần vẽ đầu) để tải lại ở chế độ cao tuổi không nháy
    this.doc.documentElement.setAttribute('data-mode', this.mode());
    effect(() => {
      const mode = this.mode();
      this.doc.documentElement.setAttribute('data-mode', mode);
      try {
        localStorage.setItem(MODE_KEY, mode);
      } catch {
        /* trình duyệt chặn bộ nhớ cục bộ: chỉ áp trong phiên */
      }
    });
  }

  applyTenant(tenant: string, css: string): void {
    this.doc.documentElement.setAttribute('data-tenant', tenant);
    if (!this.styleEl) {
      this.styleEl = this.doc.createElement('style');
      this.styleEl.id = 'vt-tenant-tokens';
      if (this.nonce) this.styleEl.setAttribute('nonce', this.nonce);
      this.doc.head.appendChild(this.styleEl);
    }
    // textContent, không dùng innerHTML: nội dung CSS không bao giờ được hiểu là HTML
    this.styleEl.textContent = css;
  }

  toggleElderly(): void {
    this.sessionChoice = true;
    this.mode.update((m) => (m === 'elderly' ? 'default' : 'elderly'));
  }

  /**
   * Áp tuỳ chọn hiển thị đọc từ máy chủ sau đăng nhập. `null` (người dùng chưa lưu hoặc đã xoá):
   * giữ lựa chọn trong phiên nếu có, không thì về mặc định UI Pack.
   */
  applyUserPreference(pref: UserDisplayPreference | null): void {
    if (pref) {
      this.mode.set(pref.mode === 'elderly' ? 'elderly' : 'default');
      this.userReducedSteps.set(pref.reducedSteps === true);
      return;
    }
    this.userReducedSteps.set(null);
    if (!this.sessionChoice) this.mode.set('default');
  }

  /** Tuỳ chọn hiện tại để ghi lên máy chủ (cờ "ít bước" giữ giá trị đang áp). */
  currentPreference(): UserDisplayPreference {
    return { mode: this.mode(), reducedSteps: this.reducedSteps() };
  }

  private readStoredMode(): DisplayMode {
    try {
      return localStorage.getItem(MODE_KEY) === 'elderly' ? 'elderly' : 'default';
    } catch {
      return 'default';
    }
  }
}
