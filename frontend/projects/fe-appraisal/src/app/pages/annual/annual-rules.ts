import { AnnualRatingRow, Period, ProblemDetails } from '@vt/api-client';

/**
 * Quy tắc trình bày của SCR-1001 Xếp loại năm. Mức năm, cờ liên thông và câu giải thích do máy chủ tính
 * theo bảng quyết định năm (BR-10-01 đến BR-10-06); trình duyệt chỉ đổi chúng thành câu nghiệp vụ, không
 * hiện mã mức, mã lý do chặn hay tên tham số (YC-PT-10).
 */

/** Cách tính điểm năm theo tham số `nam.cach_tinh` (BR-10-02). */
export const YEAR_METHOD_LABEL: Record<string, string> = {
  TRUNG_BINH_QUY: 'trung bình điểm bốn quý đã quyết định',
  TRUNG_BINH_TRONG_SO: 'trung bình có trọng số điểm bốn quý',
  TOAN_BO_NHIEM_VU_NAM: 'tính trên toàn bộ nhiệm vụ của năm',
};

export const QUARTER_LABEL = ['Quý I', 'Quý II', 'Quý III', 'Quý IV'];

export type LinkageTone = 'bad' | 'warn' | '';

/** Cột "Quy tắc liên thông" (trường 8): câu ngắn và mức nhấn của dòng. */
export function linkageOf(r: AnnualRatingRow): { text: string; tone: LinkageTone } {
  if (r.reassignFlag)
    return {
      text: 'Hai quý liên tiếp không hoàn thành nhiệm vụ: đề xuất bố trí lại (QĐ khoản 15.6)',
      tone: 'bad',
    };
  if (r.blockReason)
    return {
      text: 'Có quý không hoàn thành nhiệm vụ: không xếp loại xuất sắc năm (QĐ khoản 15.5)',
      tone: 'warn',
    };
  if (r.linkageNote) return { text: r.linkageNote, tone: 'warn' };
  return { text: '', tone: '' };
}

/** "Chuyển thẩm định năm" chỉ hiện khi có hồ sơ năm đã nộp, đang chờ thẩm định. */
export function hasAwaitingAppraisal(rows: AnnualRatingRow[]): boolean {
  return rows.some((r) => r.profileStatus === 'CHO_THAM_DINH');
}

/** Kỳ năm đang xem: kỳ ghi trên đường dẫn, nếu không có thì kỳ năm mới nhất. */
export function annualPeriodOf(periods: Period[], wanted?: string | null): Period | null {
  if (wanted) return periods.find((p) => p.id === wanted) ?? null;
  return periods.find((p) => p.kind === 'NAM') ?? null;
}

/** Lỗi máy chủ của thao tác "Lập đề xuất bố trí lại" viết thành câu nghiệp vụ. */
export function reassignProblemMessage(p: ProblemDetails): string {
  switch (p.code) {
    case 'APPRAISAL-409-NO-REASSIGN-FLAG':
      return 'Hồ sơ không thuộc trường hợp hai quý liên tiếp không hoàn thành nhiệm vụ.';
    case 'APPRAISAL-409-REASSIGN-EXISTS':
      return 'Đề xuất bố trí lại của hồ sơ này đã được lập.';
    case 'APPRAISAL-409-VERSION-CONFLICT':
      return 'Hồ sơ vừa được cập nhật. Danh sách đã được tải lại, vui lòng thực hiện lại.';
  }
  if (p.status === 403) return 'Đồng chí không có quyền lập đề xuất bố trí lại cho hồ sơ này.';
  if (p.status === 422 && p.errors?.length) return p.errors.map((e) => e.message).join(' ');
  return [p.title, p.detail].filter(Boolean).join(' ');
}
