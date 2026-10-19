import { RoleBindingsPage } from './role-bindings.page';
import { buttons, has, renderAccess, row, text, until } from './access.spec-util';

/** WEB-058: SCR-0105 trên dữ liệu giả lập: ẩn hiện theo ma trận, bốn mắt, MSG khi máy chủ trả 403. */
function press(scope: Element | null, label: string) {
  const b = [...(scope?.querySelectorAll('button') ?? [])].find((e) => text(e) === label);
  (b as HTMLButtonElement).click();
}

async function open(can?: (k: string) => boolean, me?: string) {
  const r = await renderAccess(RoleBindingsPage, { can, me });
  r.selectUnit('u-btc');
  await until(r.el, 'tbody tr', has('Lê Minh Châu'));
  return r.el;
}

describe('RoleBindingsPage (SCR-0105)', () => {
  it('AC-02 đủ quyền: thấy Cấp vai trò, Duyệt đề nghị người khác lập, không thấy Duyệt đề nghị mình lập', async () => {
    const el = await open();
    expect(text(el.querySelector('.head'))).toContain('Cấp vai trò');
    expect(text(el.querySelector('.filters'))).toContain('Chờ tôi duyệt 1');
    const chau = row(el, 'Lê Minh Châu');
    expect(text(chau)).toContain('Chờ duyệt thứ hai');
    expect(buttons(chau)).toEqual(['Duyệt', 'Từ chối']);
    const an = row(el, 'Người duyệt thứ hai');
    expect(buttons(an)).not.toContain('Duyệt');
    expect(text(an)).toContain('Chờ người khác duyệt');
    expect(buttons(row(el, 'Hoàng Văn Dũng'))).toEqual(['Thu hồi']);
    expect(el.querySelector('a[href*="/access/people/p-dung"]')).not.toBeNull();
  });

  it('AC-10 chỉ xem: không có nút cấp, duyệt, thu hồi; vẫn mở được hồ sơ quyền', async () => {
    const el = await open((k) => k === 'SCR-0105.xem' || k === 'SCR-0106.xem');
    expect(text(el.querySelector('.head'))).not.toContain('Cấp vai trò');
    expect(text(el.querySelector('.filters'))).not.toContain('Chờ tôi duyệt');
    expect(buttons(el.querySelector('tbody'))).toEqual([]);
    expect(el.querySelector('a[href*="/access/people/"]')).not.toBeNull();
  });

  it('không có SCR-0106.xem thì tên cán bộ không là liên kết', async () => {
    const el = await open((k) => k.startsWith('SCR-0105'));
    expect(el.querySelector('a[href*="/access/people/"]')).toBeNull();
  });

  it('duyệt thứ hai đề nghị của người khác: vai trò có hiệu lực', async () => {
    const el = await open();
    press(row(el, 'Lê Minh Châu'), 'Duyệt');
    const ok = await until(el, 'p-message', has('Đã duyệt vai trò'));
    expect(text(ok)).toContain('Cấp có thẩm quyền quyết định');
    await until(el, 'tbody tr', (e) => has('Lê Minh Châu')(e) && has('Sắp hiệu lực')(e));
  });

  it('máy chủ trả 403 tự duyệt: hiện MSG-01-16 dù giao diện lỡ hiện nút', async () => {
    // giao diện tưởng mình là người khác nên hiện nút Duyệt cho đề nghị do chính mình lập (rb-04)
    const el = await open(undefined, 'p-other');
    const an = row(el, 'Người duyệt thứ hai');
    press(an, 'Duyệt');
    const err = await until(el, 'p-message', has('không thể tự cấp'));
    expect(text(err)).toContain(
      'Đồng chí không thể tự cấp, tự duyệt hoặc tự thu hồi vai trò, uỷ quyền của chính mình.',
    );
  });
});
