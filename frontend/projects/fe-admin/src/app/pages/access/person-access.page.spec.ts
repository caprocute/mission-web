import { PersonAccessPage } from './person-access.page';
import { has, renderAccess, text, until } from './access.spec-util';

/** WEB-058: SCR-0106 hồ sơ quyền của một người trên dữ liệu giả lập. */
describe('PersonAccessPage (SCR-0106)', () => {
  it('hiện vai trò còn và đã kết thúc, uỷ quyền đang nhận, lịch sử; có nút xuất khi có quyền', async () => {
    const { el } = await renderAccess(PersonAccessPage, { params: { personId: 'p-an' } });
    await until(el, 'h1', has('Nguyễn Văn An'));
    await until(el, 'tbody tr', has('Hết hạn'));
    expect(text(el.querySelector('tbody'))).toContain('Người duyệt thứ hai');
    await until(el, '.list li', has('nhận từ đồng chí Phạm Thu Hà'));
    await until(el, '.timeline li', has('Cấp vai trò'));
    expect(text(el.querySelector('.head'))).toContain('Xuất lịch sử (CSV)');
  });

  it('không có SCR-0106.xuat thì không có nút xuất', async () => {
    const { el } = await renderAccess(PersonAccessPage, {
      can: (k) => k === 'SCR-0106.xem',
      params: { personId: 'p-an' },
    });
    await until(el, 'h1', has('Nguyễn Văn An'));
    expect(text(el.querySelector('.head'))).not.toContain('Xuất lịch sử');
  });
});
