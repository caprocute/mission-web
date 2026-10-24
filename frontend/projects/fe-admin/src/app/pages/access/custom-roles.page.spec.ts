import { CustomRolesPage } from './custom-roles.page';
import { buttons, has, renderAccess, row, text, until } from './access.spec-util';

/** WEB-078: SCR-0110 trên dữ liệu giả lập: danh sách, ẩn nút theo quyền, ngừng sử dụng vai trò. */
async function open(can?: (k: string) => boolean) {
  const r = await renderAccess(CustomRolesPage, { can });
  await until(r.el, 'tbody tr', has('Cán bộ theo dõi tiến độ'));
  return r.el;
}

describe('CustomRolesPage (SCR-0110)', () => {
  it('quản trị đơn vị: thấy vai trò, vai trò gốc, nút tạo, sửa và ngừng sử dụng', async () => {
    const el = await open();
    const r = row(el, 'Cán bộ theo dõi tiến độ');
    expect(text(r)).toContain('Đang sử dụng');
    expect(buttons(r)).toEqual(['Sửa', 'Ngừng sử dụng']);
    expect(text(el.querySelector('.head'))).toContain('Tạo vai trò');
    expect(text(el)).not.toMatch(/tc_|HIEU_LUC|SCR-/);
  });

  it('chỉ xem: không có nút tạo, sửa, ngừng', async () => {
    const el = await open((k) => k === 'SCR-0110.xem');
    expect(buttons(el.querySelector('.head'))).not.toContain('Tạo vai trò');
    expect(buttons(el.querySelector('tbody'))).toEqual([]);
  });

  it('ngừng sử dụng có lý do: vai trò chuyển sang ngừng sử dụng', async () => {
    const el = await open();
    const b = [...row(el, 'Cán bộ theo dõi tiến độ')!.querySelectorAll('button')].find(
      (e) => text(e) === 'Ngừng sử dụng',
    ) as HTMLButtonElement;
    b.click();
    const area = (await until(document.body, 'textarea')) as HTMLTextAreaElement;
    area.value = 'Tổ chức lại bộ phận theo dõi';
    area.dispatchEvent(new Event('input'));
    const confirm = (await until(
      document.body,
      '.p-dialog button',
      (e) => text(e) === 'Ngừng sử dụng' && !(e as HTMLButtonElement).disabled,
    )) as HTMLButtonElement;
    confirm.click();
    await until(el, 'p-message', has('Đã ngừng sử dụng vai trò'));
    await until(
      el,
      'tbody tr',
      (e) =>
        has('Cán bộ theo dõi tiến độ')(e) && has('Ngừng sử dụng')(e) && buttons(e).length === 0,
    );
  });
});
