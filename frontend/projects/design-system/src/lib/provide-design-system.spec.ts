import { TestBed } from '@angular/core/testing';
import { PrimeNG } from 'primeng/config';
import { PRIMENG_VI } from './primeng-vi';
import { provideDesignSystem } from './provide-design-system';

/** Chuỗi mặc định của PrimeNG hiện bằng tiếng Việt trên mọi màn hình (AGENTS.md quy tắc 9). */
describe('provideDesignSystem', () => {
  it('đặt bản dịch tiếng Việt cho danh sách trống, phân trang và lịch', () => {
    TestBed.configureTestingModule({ providers: [provideDesignSystem()] });
    const config = TestBed.inject(PrimeNG);
    expect(config.getTranslation('emptyMessage')).toBe('Không có kết quả phù hợp');
    expect(config.getTranslation('emptyFilterMessage')).toBe('Không có kết quả phù hợp');
    expect(config.getTranslation('dateFormat')).toBe('dd/mm/yy');
    expect(config.getTranslation('aria').rowsPerPageLabel).toBe('Số dòng mỗi trang');
  });

  it('không để sót chuỗi tiếng Anh mặc định nào (chuỗi nào cũng có chữ có dấu hoặc là ký hiệu)', () => {
    const strings: string[] = [];
    const collect = (v: unknown): void => {
      if (typeof v === 'string') strings.push(v);
      else if (Array.isArray(v)) v.forEach(collect);
      else if (v && typeof v === 'object') Object.values(v).forEach(collect);
    };
    collect(PRIMENG_VI);
    const english =
      /\b(No|Not|Previous|Next|Page|Choose|Select|Clear|Apply|Cancel|Today|Close|items?|results?)\b/;
    expect(strings.filter((s) => english.test(s))).toEqual([]);
  });
});
