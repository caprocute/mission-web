import golden from '../../../../../data/golden/ui-pack-checks/tuong-phan.json';
import caoTuoi from '../../../fe-shell/public/ui-packs/cao-tuoi/1.0.0/pack.json';
import macDinh from '../../../fe-shell/public/ui-packs/default/1.0.0/pack.json';
import thuongHieuToi from '../../../fe-shell/public/ui-packs/thuong-hieu-toi/1.0.0/pack.json';
import tuongPhanCao from '../../../fe-shell/public/ui-packs/tuong-phan-cao/1.0.0/pack.json';
import tuongPhanThap from '../../../../tools/token-compiler/fixtures/tuong-phan-thap.json';
import { contrastRatio } from './contrast';
import { tokensToCss, type TokenGroup } from './design-tokens';
import {
  checkUiPackSize,
  compileUiPack,
  contrastPairs,
  formatRatio,
  mergeTokens,
  type CompilableUiPack,
} from './token-compiler';

const asPack = (p: unknown) => p as CompilableUiPack & { compiledCss?: string };

describe('token-compiler (WEB-004)', () => {
  it('chạy đúng bộ ca tương phản dùng chung với máy chủ', () => {
    for (const c of golden.cases) {
      const r = contrastRatio(c.text, c.background);
      expect(Math.round(r * 1000) / 1000, c.id).toBeCloseTo(c.ratio, 3);
      expect(r >= 4.5, c.id).toBe(c.aa);
      expect(r >= 7, c.id).toBe(c.elderly);
    }
  });

  it('suy cặp chữ và nền từ tên token như bộ kiểm máy chủ', () => {
    const pairs = contrastPairs([
      'primary',
      'onPrimary',
      'on-accent',
      'accent',
      'text',
      'text-muted',
      'textSubtle',
      'surface',
      'surface-alt',
      'background',
      'border',
      'brand.base',
      'brand.on-base',
      'brand.text',
      'online',
    ]);
    expect(pairs).toContainEqual(['onPrimary', 'primary']);
    expect(pairs).toContainEqual(['on-accent', 'accent']);
    expect(pairs).toContainEqual(['text-muted', 'surface-alt']);
    expect(pairs).toContainEqual(['textSubtle', 'background']);
    expect(pairs).toContainEqual(['brand.on-base', 'brand.base']);
    expect(pairs.some(([t]) => t === 'online' || t === 'brand.text')).toBe(false);
    expect(pairs.some(([, b]) => b === 'border')).toBe(false);
  });

  it.each([
    ['mặc định', macDinh],
    ['tương phản cao', tuongPhanCao],
    ['cao tuổi', caoTuoi],
    ['thương hiệu tối', thuongHieuToi],
  ])('bộ token %s đạt mọi kiểm tra và compiledCss khớp tokensToCss', (_name, raw) => {
    const pack = asPack(raw);
    const r = compileUiPack(pack);
    expect(r.issues).toEqual([]);
    expect(r.contrast.filter((c) => c.mode === 'elderly').length).toBeGreaterThan(0);
    expect(pack.compiledCss).toBe(r.css);
    expect(r.css).toBe(tokensToCss(pack.tokens, (pack.modes ?? {}) as Record<string, TokenGroup>));
    expect(r.css).toContain('--vt-color-on-primary-strong:#FFFFFF');
  });

  it('từ chối bộ token tương phản thấp, báo cả chế độ thường và cao tuổi', () => {
    const r = compileUiPack(asPack(tuongPhanThap));
    expect(r.issues.map((i) => i.code)).toEqual(expect.arrayContaining(['CONTRAST', 'ELDER_MODE']));
    const muted = r.issues.find(
      (i) => i.path === 'tokens.color.textMuted' && i.code === 'CONTRAST',
    );
    expect(muted?.message).toContain('chưa đạt mức AA (4,5 : 1)');
    const brand = r.issues.find((i) => i.path === 'tokens.color.onPrimaryStrong');
    expect(brand?.message).toContain('4,48 : 1');
  });

  it('kiểm cỡ chữ, bo góc, vùng chạm và chế độ cao tuổi khai báo trong $extensions', () => {
    const r = compileUiPack({
      tokens: {
        color: { text: { $value: '#595959' }, background: { $value: '#FFFFFF' } },
        font: { size: { body: { $value: '18px' } } },
        radius: { card: { $value: '1.5rem' } },
        $extensions: {
          modes: {
            elderly: {
              font: { size: { body: { $value: '16px' } } },
              flags: { minTouchTarget: '44px' },
            },
          },
        },
      } as unknown as TokenGroup,
    });
    expect(r.issues.map((i) => i.path)).toEqual([
      'tokens.font.size.body',
      'tokens.radius.card',
      'modes.elderly.font.size.body',
      'modes.elderly.flags.minTouchTarget',
    ]);
    expect(r.contrast.find((c) => c.mode === 'elderly')?.ok).toBe(true);
    expect(r.css).toContain(':root[data-tenant][data-mode="elderly"]{--vt-font-size-body:16px}');
  });

  it('giải bí danh màu và báo màu không kiểm được', () => {
    const r = compileUiPack({
      tokens: {
        color: {
          white: { $value: '#FFFFFF' },
          surface: { $value: '{color.white}' },
          text: { $value: '#000' },
          textMuted: { $value: 'gray' },
        },
      } as unknown as TokenGroup,
    });
    expect(r.contrast).toContainEqual(
      expect.objectContaining({ text: 'text', background: 'surface', ok: true }),
    );
    expect(r.issues[0].message).toContain('hãy dùng mã màu dạng #RRGGBB');
  });

  it('gộp ghi đè, ngân sách kích thước và định dạng tỉ lệ', () => {
    const merged = mergeTokens(
      { color: { text: { $type: 'color', $value: '#111' }, bg: { $value: '#fff' } } },
      { color: { text: { $value: '#000' } } },
    );
    expect(merged).toEqual({
      color: { text: { $type: 'color', $value: '#000' }, bg: { $value: '#fff' } },
    });
    expect(checkUiPackSize(30 * 1024)).toBeNull();
    expect(checkUiPackSize(30 * 1024 + 1)?.message).toBe(
      'Kích thước bộ giao diện là 31 KB, vượt ngân sách 30 KB.',
    );
    expect(formatRatio(4.478)).toBe('4,47');
  });
});
