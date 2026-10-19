import { flattenTokens, tokensToCss, TokenGroup } from './design-tokens';

/** WEB-001: test mẫu cho biên dịch token UI Pack (TL-01 "Cấu trúc UI Pack"). */
describe('flattenTokens', () => {
  it('làm phẳng cây token thành biến --vt-*, đổi camelCase sang kebab-case', () => {
    const tokens: TokenGroup = {
      color: {
        primary: { $type: 'color', $value: '#b71c1c' },
        textMuted: { $type: 'color', $value: '#555' },
      },
      radius: { base: { $type: 'dimension', $value: 6 } },
    };
    expect(flattenTokens(tokens)).toEqual({
      '--vt-color-primary': '#b71c1c',
      '--vt-color-text-muted': '#555',
      '--vt-radius-base': '6',
    });
  });

  it('bỏ qua khoá bắt đầu bằng $, nhóm flags và token kiểu asset', () => {
    const tokens = {
      $description: 'mô tả',
      flags: { reducedSteps: { $value: true } },
      logo: { $type: 'asset', $value: 'logo.svg' },
      color: { bg: { $value: '#fff' } },
    } as unknown as TokenGroup;
    expect(flattenTokens(tokens)).toEqual({ '--vt-color-bg': '#fff' });
  });

  it('nối mảng font, đặt trong nháy kép tên có khoảng trắng; bỏ giá trị không phải chuỗi/số', () => {
    const tokens: TokenGroup = {
      font: {
        family: { $type: 'fontFamily', $value: ['Be Vietnam Pro', 'sans-serif'] },
        weird: { $value: { a: 1 } },
      },
    };
    expect(flattenTokens(tokens)).toEqual({
      '--vt-font-family': '"Be Vietnam Pro", sans-serif',
    });
  });
});

describe('tokensToCss', () => {
  it('sinh khối :root[data-tenant] và một khối cho mỗi chế độ (elderly)', () => {
    const css = tokensToCss(
      { font: { size: { $value: '14px' } } },
      { elderly: { font: { size: { $value: '18px' } } } },
    );
    expect(css.split('\n')).toEqual([
      ':root[data-tenant]{--vt-font-size:14px}',
      ':root[data-tenant][data-mode="elderly"]{--vt-font-size:18px}',
    ]);
  });

  it('không có chế độ thì chỉ có một khối', () => {
    expect(tokensToCss({ color: { a: { $value: 'red' } } })).toBe(
      ':root[data-tenant]{--vt-color-a:red}',
    );
  });
});
