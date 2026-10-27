import { contrastChecks, onPrimaryOf } from './ui-pack-tokens';

const color = (v: Record<string, string>) => ({
  color: Object.fromEntries(Object.entries(v).map(([k, x]) => [k, { $type: 'color', $value: x }])),
});

describe('onPrimaryOf', () => {
  it('dùng màu chữ của gói khi có', () => {
    expect(onPrimaryOf(color({ primary: '#EE0033', onPrimary: '#FFFF00' }))).toBe('#FFFF00');
    expect(onPrimaryOf(color({ primary: '#EE0033', onPrimaryStrong: '#FFFFFF' }))).toBe('#FFFFFF');
  });

  it('gói chưa khai thì chọn trắng hay đen có tương phản cao hơn', () => {
    expect(onPrimaryOf(color({ primary: '#0A5FA8' }))).toBe('#FFFFFF');
    expect(onPrimaryOf(color({ primary: '#FFE066' }))).toBe('#000000');
  });

  it('kiểm tra tương phản xét đúng màu mà ô xem trước hiện', () => {
    const [onPrimary] = contrastChecks(color({ primary: '#0A5FA8', text: '#111111' }));
    expect(onPrimary.fg).toBe('#FFFFFF');
    expect(onPrimary.ok).toBe(true);
  });
});
