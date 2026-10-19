import { Injectable } from '@angular/core';

/**
 * Thông báo chuyển sang trang kế tiếp sau khi lưu nháp hoặc gửi khiếu nại mới (trang `new` chuyển sang
 * `:id` nên thành phần được tạo lại). Chỉ giữ trong bộ nhớ, đọc một lần; không chứa nội dung C2.
 */
@Injectable({ providedIn: 'root' })
export class ComplaintFlash {
  private text: string | null = null;
  set(text: string) {
    this.text = text;
  }
  take(): string | null {
    const t = this.text;
    this.text = null;
    return t;
  }
}
