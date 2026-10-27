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

  it('bỏ qua cặp màu gói không khai, như máy chủ; màu khai sai định dạng vẫn chưa đạt', () => {
    // Gói mặc định (data/seed/ui-pack) không khai color.danger: không được chặn "Lưu nháp"
    const checks = contrastChecks(
      color({ primary: '#EE0033', text: '#0F172A', background: '#F1F5F9' }),
    );
    expect(checks.map((c) => c.label)).toEqual(['Chữ trên nền màu chủ đạo', 'Chữ trên nền trang']);
    expect(checks.every((c) => c.ok)).toBe(true);
    const bad = contrastChecks(color({ primary: '#0A5FA8', danger: 'đỏ' }));
    expect(bad.find((c) => c.label === 'Chữ trên nền màu cảnh báo')?.ok).toBe(false);
  });
});
