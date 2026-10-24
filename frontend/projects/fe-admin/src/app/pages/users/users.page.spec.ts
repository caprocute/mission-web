import { UsersPage } from './users.page';
import { has, renderAccess, row, text, until } from '../access/access.spec-util';

/** WEB-077: SCR-0108 trên dữ liệu giả lập: danh sách tài khoản, trạng thái, ẩn nút theo quyền. */
async function open(can?: (k: string) => boolean) {
  const r = await renderAccess(UsersPage, { can });
  await until(r.el, 'tbody tr', has('Hoàng Văn Dũng'));
  return r.el;
}

describe('UsersPage (SCR-0108)', () => {
  it('quản trị đơn vị: thấy danh sách, trạng thái khoá, nút thêm người dùng', async () => {
    const el = await open();
    expect(text(el.querySelector('.head'))).toContain('Thêm người dùng');
    expect(text(row(el, 'Đỗ Minh Tuấn'))).toContain('Đã khoá');
    expect(text(row(el, 'Hoàng Văn Dũng'))).toContain('Đang hoạt động');
    expect(text(el)).not.toMatch(/HOAT_DONG|KHOA\b|SCR-/);
  });

  it('chỉ xem: không có nút thêm, khoá, mở khoá', async () => {
    const el = await open((k) => k === 'SCR-0108.xem');
    expect(text(el.querySelector('.head'))).not.toContain('Thêm người dùng');
    const labels = [...el.querySelectorAll('tbody button')].map(text);
    expect(labels).not.toContain('Khoá');
    expect(labels).not.toContain('Mở khoá');
  });
});
