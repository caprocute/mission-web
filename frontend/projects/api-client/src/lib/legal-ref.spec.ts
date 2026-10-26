import { INTERNAL_CODE_PREFIXES, LegalRefPipe, hasInternalCode, legalRefText } from './legal-ref';

describe('legalRefText', () => {
  it('bỏ mã quy tắc nghiệp vụ, giữ căn cứ pháp lý thật', () => {
    expect(legalRefText('NĐ 335/2025; PL A5; BR-09-11')).toBe('NĐ 335/2025; PL A5');
    expect(legalRefText('QĐ Điều 10, Điều 11; BR-09-05; BR-09-06')).toBe('QĐ Điều 10, Điều 11');
    expect(legalRefText('QĐ Điều 11; PL 6 §12; BR-09-10')).toBe('QĐ Điều 11; PL 6 §12');
  });

  it('bỏ cả mảnh có mã quy trình, màn hình, yêu cầu kèm lời', () => {
    expect(legalRefText('QT-04 bước 9; QĐ Điều 6')).toBe('QĐ Điều 6');
    expect(legalRefText('TL-02a QT-04')).toBe('');
    expect(legalRefText('SCR-0201; YC-PT-10; NFR-03; TASK-12; DV-7; Phụ lục A3')).toBe(
      'Phụ lục A3',
    );
  });

  it('trả chuỗi rỗng khi không còn căn cứ để hiện', () => {
    expect(legalRefText('BR-06-06')).toBe('');
    expect(legalRefText('QT-06; BR-06-09')).toBe('');
    expect(legalRefText(' ; ')).toBe('');
    expect(legalRefText(null)).toBe('');
    expect(legalRefText(undefined)).toBe('');
  });

  it('không đụng số hiệu văn bản có gạch nối và chuẩn hoá khoảng trắng', () => {
    expect(legalRefText('NĐ 335/2025/NĐ-CP;  hướng dẫn của Bộ Nội vụ ')).toBe(
      'NĐ 335/2025/NĐ-CP; hướng dẫn của Bộ Nội vụ',
    );
    expect(legalRefText('QĐ 366-QĐ/TW')).toBe('QĐ 366-QĐ/TW');
    expect(legalRefText('Điều 11 Quy định 366')).toBe('Điều 11 Quy định 366');
  });

  it('nhận ra mọi tiền tố mã nội bộ', () => {
    for (const p of INTERNAL_CODE_PREFIXES) expect(hasInternalCode(`${p}-01`)).toBe(true);
    expect(hasInternalCode('SUBR-01')).toBe(false);
    expect(hasInternalCode('PL A5')).toBe(false);
  });

  it('pipe legalRef dùng cùng quy tắc', () => {
    expect(new LegalRefPipe().transform('PL A4; BR-15-04')).toBe('PL A4');
  });
});
