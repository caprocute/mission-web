import {
  AppraisalProfileRow,
  EsignCertificate,
  ProblemDetails,
  RATING_LEVELS,
  RatingLevel,
} from '@vt/api-client';

/**
 * Quy tắc và cách trình bày của SCR-0904 chạy trên trình duyệt để phản hồi tức thì; máy chủ kiểm lại khi
 * ký lô (422 lý do, người đứng đầu, phiên hiệu chỉnh; 409 số quyết định, phiên bản hồ sơ).
 */

/** Ngưỡng tỷ lệ xuất sắc của khối (`phan_bo.toi_da_htxsnv`, mặc định 20%, BR-09-17). */
export const DEFAULT_MAX_EXCELLENT = 0.2;

/** Ngưỡng áp cho danh sách đang chọn: ngưỡng chặt nhất trong các `maxExcellentShare`, vắng thì mặc định. */
export function thresholdOf(rows: { maxExcellentShare?: number }[]): number {
  const own = rows.map((r) => r.maxExcellentShare).filter((v): v is number => v !== undefined);
  return own.length ? Math.min(...own) : DEFAULT_MAX_EXCELLENT;
}
/** Một lô ký tối đa 1.000 hồ sơ; Mẫu 3 nháp sinh tối đa 200 hồ sơ mỗi lần. */
export const MAX_BATCH = 1000;
export const MAX_FORM = 200;

/** Tên mức rút gọn cho bảng và ô chọn (bỏ chữ "nhiệm vụ"), không hiện mã mức (YC-PT-10). */
export const LEVEL_SHORT: Record<RatingLevel, string> = {
  HTXSNV: 'Hoàn thành xuất sắc',
  HTTNV: 'Hoàn thành tốt',
  HTNV: 'Hoàn thành',
  KHTNV: 'Không hoàn thành',
};

export const MSG_09_14 =
  'Hồ sơ của người đứng đầu chỉ được quyết định sau khi các đơn vị trực thuộc đã có mức (QĐ khoản 14.3.3).';

/** MSG-09-13: tỷ lệ xuất sắc vượt ngưỡng của khối. */
export function msg0913(share: number, threshold: number): string {
  return (
    `Tỷ lệ Hoàn thành xuất sắc nhiệm vụ của danh sách là ${percent(share)}, vượt giới hạn ` +
    `${percent(threshold)} của khối. Đề nghị tổ chức phiên hiệu chỉnh trước khi ký.`
  );
}

/** Câu xác nhận khi tỷ lệ xuất sắc trong giới hạn. */
export function withinLimit(share: number, threshold: number): string {
  return (
    `Tỷ lệ Hoàn thành xuất sắc nhiệm vụ ${percent(share)}, trong giới hạn ${percent(threshold)} của ` +
    'khối. Không cần phiên hiệu chỉnh.'
  );
}

/** MSG-09-15: tiến độ ký lô; hồ sơ lỗi được tự thử lại. */
export function msg0915(signed: number, total: number, failed: number): string {
  return (
    `Đã ký ${signed}/${total} hồ sơ. ${failed} hồ sơ lỗi sẽ được tự thử lại; đồng chí có thể đóng ` +
    'màn hình.'
  );
}

/** MSG-09-16: lô đã ký xong. */
export function msg0916(decisionNo: string, total: number): string {
  return `Đã hoàn tất quyết định ${decisionNo}: ${total} hồ sơ đã ký số và thông báo tới cán bộ.`;
}

/** Tỷ lệ 0..1 thành phần trăm làm tròn: `0.1875` thành `19%`. */
export function percent(r: number): string {
  return `${Math.round(r * 100)}%`;
}

/** Mức đề xuất của dòng (mặc định của cột "Quyết định"): đề xuất, thiếu thì thẩm định, rồi mức hệ thống. */
export function proposedOf(r: AppraisalProfileRow): RatingLevel | null {
  return r.levels.proposed ?? r.levels.appraised ?? r.autoRatingCode ?? null;
}

export interface LevelShare {
  level: RatingLevel;
  count: number;
  share: number;
}

/** Trường 5: số người và tỷ lệ theo mức quyết định của các hồ sơ đang chọn. */
export function distribution(levels: (RatingLevel | null)[]): LevelShare[] {
  const total = levels.length;
  return RATING_LEVELS.map((level) => {
    const count = levels.filter((l) => l === level).length;
    return { level, count, share: total ? count / total : 0 };
  });
}

/**
 * BR-09-13: hồ sơ người đứng đầu đang chọn mà còn hồ sơ khác cùng kỳ, cùng luồng chờ quyết định nhưng
 * không nằm trong lô thì quyết định quá sớm. Trả mã các hồ sơ người đứng đầu bị chặn.
 */
export function headsTooEarly(
  rows: AppraisalProfileRow[],
  selected: ReadonlySet<string>,
): string[] {
  return rows
    .filter((h) => h.isHead && selected.has(h.id))
    .filter((h) =>
      rows.some(
        (r) => !r.isHead && !selected.has(r.id) && r.period.id === h.period.id && r.flow === h.flow,
      ),
    )
    .map((h) => h.id);
}

/** Cột "Ghi chú" của một dòng, theo thứ tự ưu tiên. */
export function noteOf(
  r: AppraisalProfileRow,
  decided: RatingLevel | null,
  headBlocked: boolean,
): string {
  if (r.isHead)
    return headBlocked
      ? 'Người đứng đầu: chờ các đơn vị trực thuộc có mức'
      : 'Quy tắc người đứng đầu: đạt';
  if (decided && decided !== proposedOf(r)) return 'Đổi mức · đã ghi lý do';
  if (r.totalScore !== null && r.totalScore !== undefined && r.totalScore < 60)
    return 'Dưới 60 điểm';
  if (r.differsExplained) return 'Khác mức · đã giải trình';
  return '';
}

/** Lý do đổi mức: 20 đến 2.000 ký tự (BR-09-12); `null` khi hợp lệ. */
export function reasonError(reason: string): string | null {
  const n = reason.trim().length;
  return n < 20 || n > 2000 ? 'Ghi lý do đổi mức, từ 20 đến 2.000 ký tự.' : null;
}

export interface BatchForm {
  selected: number;
  decisionNo: string;
  decisionDate: string;
  overLimit: boolean;
  calibrationAcknowledged: boolean;
  headBlocked: boolean;
  certificateValid: boolean;
}

/** Kiểm trước khi ký lô (trường 2, 5, 6, 7); khoá của kết quả là tên trường để đưa tiêu điểm tới. */
export function checkBatch(f: BatchForm): Record<string, string> {
  const e: Record<string, string> = {};
  if (f.selected < 1) e['selection'] = 'Chọn ít nhất một hồ sơ để quyết định.';
  else if (f.selected > MAX_BATCH) e['selection'] = 'Mỗi lô ký tối đa 1.000 hồ sơ.';
  if (f.headBlocked) e['selection'] = MSG_09_14;
  if (!f.decisionNo.trim()) e['decisionNo'] = 'Nhập số quyết định.';
  else if (f.decisionNo.trim().length > 64) e['decisionNo'] = 'Số quyết định tối đa 64 ký tự.';
  if (!f.decisionDate) e['decisionDate'] = 'Chọn ngày quyết định.';
  if (f.overLimit && !f.calibrationAcknowledged)
    e['calibration'] = 'Xác nhận đã tổ chức phiên hiệu chỉnh trước khi ký.';
  if (!f.certificateValid)
    e['certificate'] =
      'Chứng thư ký số đã hết hạn hoặc chưa đăng ký. Vui lòng gia hạn trước khi ký.';
  return e;
}

/** Tên người ký và nhà cung cấp lấy từ chứng thư (trường 7). */
export function certificateInfo(c: EsignCertificate): { holder: string; provider: string } {
  const cn = (dn: string) => /CN=([^,]+)/.exec(dn)?.[1]?.trim() ?? dn;
  const issuer = cn(c.issuerDn);
  const provider = /mysign/i.test(issuer)
    ? 'MySign'
    : /viettel/i.test(issuer)
      ? 'Viettel-CA'
      : /cơ yếu|co yeu/i.test(issuer)
        ? 'Ban Cơ yếu Chính phủ'
        : issuer;
  return { holder: cn(c.subjectDn), provider };
}

/** Nhà cung cấp gửi kèm yêu cầu ký lô, suy từ tổ chức cấp chứng thư. */
export function signProviderOf(
  c: EsignCertificate,
): 'MYSIGN' | 'VIETTEL_CA' | 'CA_CHINH_PHU' | 'BAN_CO_YEU' {
  const issuer = c.issuerDn;
  if (/mysign/i.test(issuer)) return 'MYSIGN';
  if (/viettel/i.test(issuer)) return 'VIETTEL_CA';
  if (/cơ yếu|co yeu/i.test(issuer)) return 'BAN_CO_YEU';
  return 'CA_CHINH_PHU';
}

/** Chứng thư còn hiệu lực tại ngày `today` (`YYYY-MM-DD`, giờ Việt Nam) và chưa bị thu hồi. */
export function certificateValid(
  c: EsignCertificate | null | undefined,
  now = Date.now(),
): boolean {
  return !!c && Date.parse(c.notAfter) > now && c.lastOcspStatus !== 'REVOKED';
}

/** Lỗi máy chủ của thao tác ký lô viết thành câu cho người quyết định. */
export function batchProblemMessage(p: ProblemDetails): string {
  switch (p.code) {
    case 'APPRAISAL-409-DECISION-NO-DUPLICATE':
      return 'Số quyết định này đã được dùng trong năm. Vui lòng kiểm tra lại số quyết định.';
    case 'APPRAISAL-422-HEAD-TOO-EARLY':
      return MSG_09_14;
    case 'APPRAISAL-422-CALIBRATION-REQUIRED':
      return p.title ?? 'Tỷ lệ Hoàn thành xuất sắc nhiệm vụ vượt giới hạn của khối.';
    case 'APPRAISAL-409-VERSION-CONFLICT':
      return (
        (p.errors?.map((e) => e.message).join(' ') ?? '') +
        ' Danh sách đã được tải lại, vui lòng kiểm tra trước khi ký.'
      ).trim();
    case 'APPRAISAL-409-COMPLAINT-OPEN':
      return 'Còn khiếu nại quý chưa có kết luận, chưa quyết định được xếp loại năm.';
    case 'APPRAISAL-409-BATCH-COMPLETED':
      return 'Lô đã ký xong nên không huỷ được.';
  }
  if (p.status === 403) return 'Đồng chí không có quyền quyết định các hồ sơ này.';
  if (p.status === 422 && p.errors?.length) return p.errors.map((e) => e.message).join(' ');
  return [p.title, p.detail].filter(Boolean).join(' ');
}
