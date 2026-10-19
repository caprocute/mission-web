import { DelegationsPage } from './delegations.page';
import { buttons, has, renderAccess, row, text, until } from './access.spec-util';

/** WEB-058: SCR-0107 uỷ quyền khi vắng mặt trên dữ liệu giả lập. */
describe('DelegationsPage (SCR-0107)', () => {
  it('AC-04: thấy Khai báo uỷ quyền và Thu hồi trên uỷ quyền còn hiệu lực', async () => {
    const { el, selectUnit } = await renderAccess(DelegationsPage);
    selectUnit('u-btc');
    await until(el, 'tbody tr', has('Phạm Thu Hà'));
    expect(text(el.querySelector('.head'))).toContain('Khai báo uỷ quyền');
    expect(text(row(el, 'Phạm Thu Hà'))).toContain('KR2');
    expect(buttons(row(el, 'Phạm Thu Hà'))).toEqual(['Thu hồi']);
  });

  it('AC-02 chỉ xem: không có nút khai báo, thu hồi', async () => {
    const { el, selectUnit } = await renderAccess(DelegationsPage, {
      can: (k) => k === 'SCR-0107.xem',
    });
    selectUnit('u-btc');
    await until(el, 'tbody tr', has('Phạm Thu Hà'));
    expect(text(el.querySelector('.head'))).not.toContain('Khai báo uỷ quyền');
    expect(buttons(el.querySelector('tbody'))).toEqual([]);
  });
});
