import { CONFLICT_TITLE, DossierForm, DossierPackage, ProblemDetails } from '@vt/api-client';
import { DossierPermissionKey } from '../../dossier-permissions';

/**
 * Quy tắc hiển thị của SCR-1102 (QT-11, WEB-041), tách khỏi component để kiểm thử. Máy chủ vẫn kiểm lại mọi
 * điều kiện (danh sách kiểm tra BR-11-09, gói đã nộp bị khoá BR-11-10, nơi nhận đã cấu hình) và trả 409, 422.
 */

const pad = (n: number) => String(n).padStart(2, '0');

/** Thời điểm dạng "15:12 ngày 24/04/2026". */
export function when(iso: string): string {
  const d = new Date(iso);
  return (
    `${pad(d.getHours())}:${pad(d.getMinutes())} ngày ` +
    `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()}`
  );
}

/** MSG-11-04: bấm ký khi danh sách kiểm tra chưa đạt. */
export const msg1104 = (n: number) =>
  `Chưa thể ký gói: ${n} mục kiểm tra chưa đạt. Xem chi tiết ở danh sách kiểm tra.`;

/** MSG-11-05: ký gói xong. */
export const msg1105 = (code: string, at: string) =>
  `Gói ${code} đã được ký số và nộp lúc ${when(at)}. Gói đã bị khoá; mọi thay đổi phải qua mở lại kỳ.`;

/** MSG-11-06: gửi gói con không thành công; không có giờ thử lại thì nói theo lịch. */
export const msg1106 = (dest: string, reason: string, retryAt?: string | null) =>
  `Gửi gói con tới ${dest} chưa thành công: ${reason}. Hệ thống sẽ tự thử lại ` +
  (retryAt ? `lúc ${when(retryAt)}.` : 'theo lịch.');

/** MSG-11-07: có tệp sinh lỗi. */
export const msg1107 = (n: number) =>
  `Có ${n} tệp sinh lỗi. Đồng chí có thể bấm "Sinh lại mẫu lỗi" sau khi bổ sung dữ liệu.`;

/** Mã băm rút gọn: 8 ký tự đầu và 8 ký tự cuối. */
export const shortHash = (h: string | null | undefined) =>
  h ? `${h.slice(0, 8)}…${h.slice(-8)}` : '—';

/** Lấy một thuộc tính của tên phân biệt X.500, ví dụ `CN` hay `O`. */
export const dnPart = (dn: string, key: string) =>
  new RegExp(`(?:^|,)\\s*${key}=([^,]+)`).exec(dn)?.[1]?.trim() ?? '';

export const isLocked = (p: DossierPackage) =>
  p.status === 'DA_KY' || p.status === 'DA_NOP' || p.status === 'DA_LUU_TRU';

const ready = (p: DossierPackage) => p.status !== 'DANG_LAP' && p.status !== 'DANG_SINH';

type Can = (k: DossierPermissionKey) => boolean;

/** Điều kiện hiện từng chức năng theo bảng chức năng của TL-02b và ma trận phân quyền (wiki/15a). */
export const actions = {
  regenerate: (p: DossierPackage, can: Can) =>
    can('SCR-1102.chuan-bi') && p.formCounts.failed > 0 && !isLocked(p),
  view: (f: DossierForm, can: Can) =>
    can('SCR-1102.chuan-bi') && (f.status === 'DA_SINH' || f.status === 'DA_KY'),
  export: (p: DossierPackage, can: Can) => can('SCR-1102.tai-goi') && ready(p),
  transfer: (p: DossierPackage, can: Can) =>
    can('SCR-1102.goi-con') &&
    isLocked(p) &&
    !!p.central?.profileCount &&
    p.central.transferStatus !== 'DA_GUI' &&
    p.central.transferStatus !== 'DANG_GUI',
  sign: (p: DossierPackage, can: Can) => can('SCR-1102.ky-goi') && p.status === 'CHO_KY',
};

/** Câu báo lỗi theo mã của `dossiers.yaml`; không hiện mã hay trạng thái HTTP lên màn hình. */
export function problemMessage(p: ProblemDetails): string {
  switch (p.code) {
    case 'DOSSIER-409-VERSION-CONFLICT':
      return CONFLICT_TITLE;
    case 'DOSSIER-409-CHECKLIST-NOT-PASSED':
      return p.detail ?? 'Chưa thể ký gói: còn mục kiểm tra chưa đạt.';
    case 'DOSSIER-409-PACKAGE-LOCKED':
      return 'Gói đã nộp và bị khoá; mọi thay đổi phải qua mở lại kỳ.';
    case 'DOSSIER-409-INVALID-STATE':
      return 'Gói chưa ở trạng thái chờ ký.';
    case 'DOSSIER-409-NO-FAILED-FORMS':
      return 'Gói không còn tệp sinh lỗi.';
    case 'DOSSIER-409-PACKAGE-NOT-READY':
      return 'Gói chưa sinh xong biểu mẫu nên chưa tải được.';
    case 'DOSSIER-409-PACKAGE-NOT-SIGNED':
      return 'Gói chưa được ký nên chưa gửi được gói con.';
    case 'DOSSIER-409-NO-CENTRAL-PROFILES':
      return 'Gói không có hồ sơ diện Trung ương quản lý.';
    case 'DOSSIER-422-DESTINATION-NOT-CONFIGURED':
      return 'Nơi nhận này chưa được cấu hình kết nối. Đề nghị chọn nơi nhận khác hoặc liên hệ quản trị.';
    case 'DOSSIER-409-FORM-NOT-RENDERED':
      return 'Biểu mẫu chưa sinh xong hoặc sinh lỗi.';
    case 'ESIGN-422-CERTIFICATE-INVALID':
      return 'Chứng thư số của đồng chí đã hết hạn hoặc bị thu hồi. Đề nghị gia hạn chứng thư rồi ký lại.';
    case 'ESIGN-422-PROVIDER-UNAVAILABLE':
      return 'Dịch vụ ký số đang gián đoạn. Vui lòng thử lại sau ít phút.';
  }
  if (p.status === 403) return 'Đồng chí không có quyền thực hiện thao tác này.';
  if (p.status === 428) return 'Dữ liệu trên màn hình đã cũ. Vui lòng tải lại rồi thực hiện lại.';
  if (p.status === 422)
    return p.errors?.[0]?.message ?? 'Dữ liệu chưa hợp lệ, vui lòng kiểm tra lại.';
  return 'Chưa thực hiện được thao tác. Vui lòng thử lại.';
}
