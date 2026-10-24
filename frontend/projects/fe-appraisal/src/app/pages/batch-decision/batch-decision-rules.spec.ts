import { AppraisalProfileRow } from '@vt/api-client/domains';
import {
  certificateInfo,
  certificateValid,
  checkBatch,
  distribution,
  headsTooEarly,
  msg0913,
  msg0915,
  noteOf,
  reasonError,
  signProviderOf,
} from './batch-decision-rules';

const P = { id: 'q4', label: 'Quý IV/2026', startDate: '2026-10-01', endDate: '2026-12-31' };
function row(id: string, extra: Partial<AppraisalProfileRow> = {}): AppraisalProfileRow {
  return {
    id,
    person: { id: 'p' + id, name: 'Cán bộ ' + id },
    orgUnit: { id: 'u', name: 'Phòng' },
    period: P,
    periodKind: 'QUY',
    flow: 'DANG',
    status: 'CHO_QUYET_DINH',
    levels: { proposed: 'HTTNV' },
    rowVersion: 1,
    ...extra,
  };
}

describe('Quy tắc SCR-0904 (WEB-038)', () => {
  it('phân bố mức và câu cảnh báo vượt ngưỡng, không hiện mã mức', () => {
    const d = distribution([
      'HTXSNV',
      'HTXSNV',
      'HTTNV',
      'HTTNV',
      'HTTNV',
      'HTTNV',
      'HTTNV',
      'KHTNV',
    ]);
    expect(d.map((x) => x.count)).toEqual([2, 5, 0, 1]);
    expect(d[0].share).toBe(0.25);
    const m = msg0913(0.25, 0.2);
    expect(m).toBe(
      'Tỷ lệ Hoàn thành xuất sắc nhiệm vụ của danh sách là 25%, vượt giới hạn 20% của khối. Đề nghị tổ chức phiên hiệu chỉnh trước khi ký.',
    );
    expect(m).not.toMatch(/HTXSNV/);
    expect(msg0915(36, 48, 2)).toBe(
      'Đã ký 36/48 hồ sơ. 2 hồ sơ lỗi sẽ được tự thử lại; đồng chí có thể đóng màn hình.',
    );
  });

  it('người đứng đầu chỉ quyết định khi mọi hồ sơ khác cùng kỳ, cùng luồng đã trong lô (BR-09-13)', () => {
    const rows = [row('a'), row('b'), row('h', { isHead: true })];
    expect(headsTooEarly(rows, new Set(['a', 'b', 'h']))).toEqual([]);
    expect(headsTooEarly(rows, new Set(['a', 'h']))).toEqual(['h']);
    expect(headsTooEarly(rows, new Set(['a']))).toEqual([]);
    expect(noteOf(rows[2], 'HTTNV', true)).toContain('chờ các đơn vị trực thuộc');
    expect(noteOf(rows[2], 'HTTNV', false)).toBe('Quy tắc người đứng đầu: đạt');
    expect(noteOf(row('x', { differsExplained: true }), 'HTTNV', false)).toBe(
      'Khác mức · đã giải trình',
    );
    expect(noteOf(row('y'), 'HTNV', false)).toBe('Đổi mức · đã ghi lý do');
    expect(noteOf(row('z', { totalScore: 57.8 }), 'HTTNV', false)).toBe('Dưới 60 điểm');
  });

  it('kiểm trước khi ký: số, ngày, phiên hiệu chỉnh, chứng thư; lý do 20..2.000 ký tự', () => {
    const ok = {
      selected: 48,
      decisionNo: '215-QĐ/ĐUK',
      decisionDate: '2027-01-12',
      overLimit: false,
      calibrationAcknowledged: false,
      headBlocked: false,
      certificateValid: true,
    };
    expect(checkBatch(ok)).toEqual({});
    expect(
      Object.keys(checkBatch({ ...ok, decisionNo: ' ', overLimit: true, certificateValid: false })),
    ).toEqual(['decisionNo', 'calibration', 'certificate']);
    expect(checkBatch({ ...ok, headBlocked: true })['selection']).toContain('người đứng đầu');
    expect(checkBatch({ ...ok, selected: 0 })['selection']).toBeTruthy();
    expect(reasonError('ngắn')).toBeTruthy();
    expect(reasonError('Tập thể thống nhất hạ một mức do chưa đạt nhiệm vụ then chốt.')).toBeNull();
  });

  it('chứng thư: tên người ký, nhà cung cấp, hiệu lực', () => {
    const c = {
      id: 'c',
      subjectDn: 'CN=Ngô Thanh Tâm, O=Đảng uỷ khối',
      issuerDn: 'CN=MySign CA, O=Viettel Group',
      serialNo: '1',
      notBefore: '2025-08-15T00:00:00+07:00',
      notAfter: '2027-08-14T23:59:59+07:00',
    };
    expect(certificateInfo(c)).toEqual({ holder: 'Ngô Thanh Tâm', provider: 'MySign' });
    expect(signProviderOf(c)).toBe('MYSIGN');
    expect(certificateValid(c, Date.parse('2027-01-12'))).toBe(true);
    expect(certificateValid(c, Date.parse('2027-09-01'))).toBe(false);
    expect(certificateValid({ ...c, lastOcspStatus: 'REVOKED' }, Date.parse('2027-01-12'))).toBe(
      false,
    );
  });
});
