import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SessionService } from '@vt/auth';
import { SduiOutletComponent } from '@vt/sdui-renderer';

/** SCR-0002 Trang chủ "Việc của tôi hôm nay" (QT-00); bố cục lấy từ UI Pack của tenant (SDUI). */
@Component({
  selector: 'vt-home-page',
  imports: [SduiOutletComponent, RouterLink],
  template: `
    <header class="vt-hero-banner">
      <div>
        <span class="vt-hero-kicker">Bàn làm việc điều hành · Kỳ đánh giá 2026</span>
        <h1>Xin chào, {{ fullName() }}</h1>
        <p class="muted">
          Dưới đây là những việc đồng chí cần quan tâm hôm nay và lối tắt tới khung cấu hình nền tảng.
        </p>
      </div>
      <div class="hero-actions">
        <a routerLink="/config-studio" class="hero-btn hero-btn--primary">
          <i class="pi pi-sliders-h" aria-hidden="true"></i>
          <span>Khung cấu hình &amp; Luồng động</span>
        </a>
        <a routerLink="/admin" class="hero-btn hero-btn--ghost">
          <i class="pi pi-cog" aria-hidden="true"></i>
          <span>Quản trị nền tảng</span>
        </a>
      </div>
    </header>

    <section class="platform-strip" aria-label="Khung nền tảng cấu hình và quản trị">
      <div class="strip-head">
        <span class="strip-badge">KHUNG NỀN TẢNG LÕI</span>
        <strong>Các phân hệ nền tảng vận hành hệ thống</strong>
      </div>
      <div class="platform-cards">
        <a routerLink="/config-studio" class="p-card">
          <span class="p-icon p-icon--rose" aria-hidden="true"><i class="pi pi-sitemap"></i></span>
          <span class="p-copy">
            <strong>Chính sách &amp; Luồng quy trình động</strong>
            <small>Thiết kế quy trình phê duyệt, công thức điểm và bảng xếp loại QĐ 366-QĐ/TW</small>
          </span>
        </a>
        <a routerLink="/admin/org" class="p-card">
          <span class="p-icon p-icon--blue" aria-hidden="true"><i class="pi pi-building"></i></span>
          <span class="p-copy">
            <strong>Cây tổ chức &amp; Đồng bộ nhân sự</strong>
            <small>Quản lý cơ cấu đơn vị đa cấp và đối soát dữ liệu cán bộ</small>
          </span>
        </a>
        <a routerLink="/admin/access" class="p-card">
          <span class="p-icon p-icon--amber" aria-hidden="true"><i class="pi pi-shield"></i></span>
          <span class="p-copy">
            <strong>Phân quyền &amp; Ủy quyền xử lý</strong>
            <small>Cấp vai trò theo đơn vị và thiết lập ủy quyền duyệt thay có thời hạn</small>
          </span>
        </a>
        <a routerLink="/tasks" class="p-card">
          <span class="p-icon p-icon--emerald" aria-hidden="true"><i class="pi pi-check-square"></i></span>
          <span class="p-copy">
            <strong>Không gian công việc &amp; Mục tiêu OKR</strong>
            <small>Xử lý nhiệm vụ hằng ngày trong ≤ 3 chạm và cập nhật tiến độ kết quả</small>
          </span>
        </a>
      </div>
    </section>

    <vt-sdui-outlet layout="home">
      <p class="muted">Chưa tải được trang chủ của đơn vị. Đồng chí vui lòng tải lại trang.</p>
    </vt-sdui-outlet>
  `,
  styles: `
    .hero-actions {
      display: flex;
      gap: 10px;
      flex-wrap: wrap;
    }
    .hero-btn {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      padding: 10px 16px;
      border-radius: 10px;
      font-weight: 700;
      font-size: 0.9rem;
      text-decoration: none;
      transition: all 0.15s ease;
    }
    .hero-btn--primary {
      background: #e11d48;
      color: #ffffff;
      box-shadow: 0 8px 16px -4px rgba(225, 29, 72, 0.5);
    }
    .hero-btn--primary:hover {
      background: #be123c;
    }
    .hero-btn--ghost {
      background: rgba(255, 255, 255, 0.1);
      color: #ffffff;
      border: 1px solid rgba(255, 255, 255, 0.24);
    }
    .hero-btn--ghost:hover {
      background: rgba(255, 255, 255, 0.18);
    }
    .platform-strip {
      margin-bottom: 20px;
    }
    .strip-head {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 10px;
    }
    .strip-badge {
      font-size: 0.7rem;
      font-weight: 800;
      letter-spacing: 0.06em;
      color: #9f1239;
      background: #ffe4e6;
      padding: 2px 8px;
      border-radius: 6px;
    }
    .platform-cards {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
      gap: 12px;
    }
    .p-card {
      display: flex;
      align-items: flex-start;
      gap: 12px;
      padding: 14px 16px;
      background: #ffffff;
      border: 1px solid #e2e8f0;
      border-radius: 12px;
      text-decoration: none;
      color: inherit;
      box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
      transition: transform 0.15s ease, box-shadow 0.15s ease, border-color 0.15s ease;
    }
    .p-card:hover {
      transform: translateY(-2px);
      border-color: #fda4af;
      box-shadow: 0 10px 15px -3px rgba(15, 23, 42, 0.08);
    }
    .p-icon {
      width: 40px;
      height: 40px;
      border-radius: 10px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      font-size: 1.1rem;
      flex-shrink: 0;
    }
    .p-icon--rose { background: #ffe4e6; color: #e11d48; }
    .p-icon--blue { background: #dbeafe; color: #2563eb; }
    .p-icon--amber { background: #fef3c7; color: #d97706; }
    .p-icon--emerald { background: #d1fae5; color: #059669; }
    .p-copy {
      display: flex;
      flex-direction: column;
      gap: 3px;
    }
    .p-copy strong {
      font-size: 0.94rem;
      color: #0f172a;
    }
    .p-copy small {
      font-size: 0.8rem;
      color: #64748b;
      line-height: 1.4;
    }
  `,
})
export class HomePage {
  private readonly session = inject(SessionService);
  protected readonly fullName = computed(() => this.session.user()?.name ?? '');
}
