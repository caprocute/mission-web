import { celSyntaxError, unknownVariables } from './cel-check';

describe('kiểm tra biểu thức ở trình duyệt (SCR-1501)', () => {
  const declared = ['nv.ke_hoach', 'nv.so_luong_duyet', 'tham_so.tru_tien_do', 'nhom'];

  it('cú pháp: ngoặc thiếu, để trống', () => {
    expect(celSyntaxError('math.least(1.0, 2.0)')).toBeNull();
    expect(celSyntaxError('(1 + 2')).toBe('sai cú pháp');
    expect(celSyntaxError('  ')).toBe('biểu thức đang để trống');
  });

  it('biến chưa khai báo, bỏ qua hàm, biến lặp và tên quy tắc', () => {
    expect(
      unknownVariables(
        'math.least(double(nv.so_luong_duyet) / double(nv.ke_hoach), 1.0)',
        declared,
        ['A'],
      ),
    ).toEqual([]);
    expect(unknownVariables('sum(nhom.map(n, n.kpi_nhom * n.diem_toi_da))', declared, [])).toEqual(
      [],
    );
    expect(unknownVariables('(A + B + C) / 3.0', declared, ['A', 'B', 'C'])).toEqual([]);
    expect(unknownVariables('nv.ngay_cham + x', declared, [])).toEqual(['nv.ngay_cham', 'x']);
    expect(unknownVariables('"nv.khong_phai_bien"', declared, [])).toEqual([]);
  });
});
