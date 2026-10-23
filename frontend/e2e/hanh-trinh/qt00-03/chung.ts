import { ApiHeThong } from '../ho-tro/api';
import { HE_THONG, bienConThieu, cho } from '../ho-tro/moi-truong';
import { Phien, TenTaiKhoan } from '../ho-tro/phien';

/**
 * Phần dùng chung của nhóm hành trình QT-00..QT-03 (QA-04). Mỗi hành trình chạy được ở hai chế độ:
 * giả lập (`npm run e2e:hanh-trinh`, kiểm màn hình và luồng thao tác trên dữ liệu giả lập) và hệ thống
 * thật (`npm run e2e:he-thong`, kiểm thêm dữ liệu đọc lại qua API và sự kiện trên Kafka).
 */

/** Trên hệ thống thật, thiếu biến môi trường bắt buộc thì ca được ghi "Chờ". */
export function kiemMoiTruong() {
  if (!HE_THONG) return;
  const thieu = bienConThieu();
  if (thieu.length) cho(`môi trường chưa đặt ${thieu.join(', ')}`);
}

/** Client API của `user` bằng access token thật (chỉ gọi ở chế độ he-thong). */
export async function api(s: Phien, user: TenTaiKhoan): Promise<ApiHeThong> {
  return ApiHeThong.tao(await s.token(user), user);
}
