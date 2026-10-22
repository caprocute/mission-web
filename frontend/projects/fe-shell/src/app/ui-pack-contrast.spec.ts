import { contrastRatio, mixHex } from '@vt/design-system';
import pack from '../../public/ui-packs/default/1.0.0/pack.json';

/**
 * WEB-059: kiểm tra tĩnh tương phản token của UI Pack mặc định (TL-01 "kiểm tra tĩnh UI Pack").
 * Các cặp màu lấy theo cách shell và preset dùng token: nút, liên kết, chữ phụ, mục menu đang chọn.
 */
describe('UI Pack mặc định: tương phản token', () => {
  const c = pack.tokens.color;
  const e = {
    ...c,
    ...Object.fromEntries(
      Object.entries(pack.modes.elderly.color).map(([k, v]) => [k, { $value: v.$value }]),
    ),
  };
  const v = (t: Record<string, { $value: string }>, k: string) => t[k].$value;
  const pinkActive = mixHex(v(c, 'primary'), 0.1, v(c, 'surface'));

  it('chế độ thường đạt 4,5:1', () => {
    expect(contrastRatio(v(c, 'onPrimaryStrong'), v(c, 'primaryStrong'))).toBeGreaterThanOrEqual(
      4.5,
    );
    expect(contrastRatio(v(c, 'primaryStrong'), v(c, 'surface'))).toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio(v(c, 'primaryStrong'), pinkActive)).toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio(v(c, 'textMuted'), v(c, 'background'))).toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio(v(c, 'text'), v(c, 'background'))).toBeGreaterThanOrEqual(4.5);
  });

  it('chế độ cao tuổi đạt 7:1 cho chữ thân, chữ phụ và chữ trên nút (BR-18-09)', () => {
    expect(contrastRatio(v(e, 'text'), v(e, 'background'))).toBeGreaterThanOrEqual(7);
    expect(contrastRatio(v(e, 'textMuted'), v(e, 'background'))).toBeGreaterThanOrEqual(7);
    expect(contrastRatio(v(e, 'onPrimaryStrong'), v(e, 'primaryStrong'))).toBeGreaterThanOrEqual(7);
  });

  it('màu nhận diện #EE0033 giữ nguyên, chỉ thêm bản đậm cho chữ', () => {
    expect(v(c, 'primary')).toBe('#EE0033');
    expect(contrastRatio(v(c, 'onPrimaryStrong'), v(c, 'primary'))).toBeLessThan(4.5);
  });
});
