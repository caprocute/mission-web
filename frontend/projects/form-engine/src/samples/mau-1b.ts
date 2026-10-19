import { JSONSchema7 } from '../lib/json-schema';
import { FormRule, FormUi } from '../lib/form-rule';

/**
 * MẪU THỬ phiếu tự đánh giá Mẫu 1B (WEB-010) để kiểm form engine, KHÔNG phải schema nghiệp vụ
 * chính thức: schema thật nằm trong Policy Pack (`policy://.../forms/mau-1b`). Trường theo bảng
 * trường SCR-0901 (TL-02, trường 2, 9 đến 12) và ví dụ UI Pack, quy tắc R-1B-01, R-1B-02 của TL-01
 * chương 7. Tên chín tiêu chí chung là tên giữ chỗ (danh mục thật theo Policy Pack và khối đối tượng).
 */
export const MAU_1B_SCHEMA: JSONSchema7 = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  type: 'object',
  required: ['ketQuaNhiemVu', 'diemTuCham', 'mucTuDeXuat', 'uuDiem', 'hanChe', 'huongKhacPhuc'],
  properties: {
    tieuChiChung: {
      type: 'object',
      title: 'Tiêu chí chung',
      properties: Object.fromEntries(
        Array.from({ length: 9 }, (_, i) => [
          `tc${i + 1}`,
          { type: 'boolean', title: `Tiêu chí chung số ${i + 1}`, default: true },
        ]),
      ),
    },
    ketQuaNhiemVu: {
      type: 'string',
      title: 'Kết quả thực hiện nhiệm vụ',
      description: 'Tóm tắt kết quả chính trong quý.',
      maxLength: 2000,
    },
    diemTuCham: {
      type: 'number',
      title: 'Điểm tự chấm',
      minimum: 0,
      maximum: 100,
      multipleOf: 0.1,
    },
    mucTuDeXuat: {
      type: 'string',
      title: 'Mức tự đề xuất',
      'x-catalog': 'muc-xep-loai',
      oneOf: [
        { const: 'HTXSNV', title: 'Hoàn thành xuất sắc nhiệm vụ' },
        { const: 'HTTNV', title: 'Hoàn thành tốt nhiệm vụ' },
        { const: 'HTNV', title: 'Hoàn thành nhiệm vụ' },
        { const: 'KHTNV', title: 'Không hoàn thành nhiệm vụ' },
      ],
    } as JSONSchema7,
    giaiPhapDotPha: {
      type: 'string',
      title: 'Giải pháp đột phá',
      description: 'Bắt buộc khi đề xuất mức Hoàn thành xuất sắc nhiệm vụ.',
      maxLength: 2000,
    },
    giaiTrinh: {
      type: 'string',
      title: 'Giải trình khác mức tham khảo',
      description: 'Bắt buộc, từ 20 ký tự, khi mức tự đề xuất khác mức hệ thống tham khảo.',
      maxLength: 2000,
    },
    uuDiem: { type: 'string', title: 'Ưu điểm', maxLength: 2000 },
    hanChe: { type: 'string', title: 'Hạn chế', maxLength: 2000 },
    huongKhacPhuc: { type: 'string', title: 'Hướng khắc phục', maxLength: 2000 },
  },
};

export const MAU_1B_UI: FormUi = {
  sections: [
    { title: 'Tiêu chí chung', fields: ['tieuChiChung'] },
    { title: 'Kết quả thực hiện nhiệm vụ', fields: ['ketQuaNhiemVu', 'diemTuCham'] },
    { title: 'Tự đề xuất mức', fields: ['mucTuDeXuat', 'giaiPhapDotPha', 'giaiTrinh'] },
    { title: 'Ưu điểm, hạn chế và hướng khắc phục', fields: ['uuDiem', 'hanChe', 'huongKhacPhuc'] },
  ],
  widgets: {
    ketQuaNhiemVu: 'vt.input.textarea',
    diemTuCham: 'vt.input.score',
    mucTuDeXuat: 'vt.input.catalog',
    giaiPhapDotPha: 'vt.input.textarea',
    giaiTrinh: 'vt.input.textarea',
    uuDiem: 'vt.input.textarea',
    hanChe: 'vt.input.textarea',
    huongKhacPhuc: 'vt.input.textarea',
  },
};

export const MAU_1B_RULES: FormRule[] = [
  {
    id: 'R-1B-01',
    when: 'true',
    assert: 'diemTuCham >= 0.0 && diemTuCham <= 100.0',
    message: 'Điểm tự chấm phải nằm trong khoảng 0 đến 100',
    field: 'diemTuCham',
  },
  {
    id: 'R-1B-02',
    when: "mucTuDeXuat == 'HTXSNV'",
    assert: 'size(giaiPhapDotPha) >= 50',
    message: 'Đề xuất mức xuất sắc phải mô tả giải pháp đột phá được kiểm chứng (ít nhất 50 ký tự)',
    field: 'giaiPhapDotPha',
  },
  {
    id: 'R-1B-03',
    when: 'mucTuDeXuat != null && mucTuDeXuat != ctx.mucThamKhao',
    assert: 'size(giaiTrinh) >= 20',
    // MSG-09-02 (TL-02 SCR-0901), kèm độ dài tối thiểu của trường 11
    message:
      'Mức tự đề xuất khác mức hệ thống tham khảo. Đồng chí cần ghi giải trình (ít nhất 20 ký tự) trước khi nộp (QĐ Điều 16).',
    field: 'giaiTrinh',
  },
  {
    id: 'R-1B-04',
    when: 'true',
    assert: 'workdays(ctx.ngayMoPhieu, ctx.ngayNop) <= ctx.soNgayLamViecDuocPhep',
    message: 'Đã quá hạn nộp phiếu tự đánh giá theo lịch làm việc của đơn vị.',
    // hàm workdays theo config.holiday_calendar chỉ có ở máy chủ (TL-01)
    serverOnly: true,
  },
];

/** Ngữ cảnh mẫu: mức hệ thống tham khảo (trường 8) và tham số hạn nộp. */
export const MAU_1B_CONTEXT = {
  mucThamKhao: 'HTTNV',
  ngayMoPhieu: '2027-01-04',
  ngayNop: '2027-01-06',
  soNgayLamViecDuocPhep: 3,
};
