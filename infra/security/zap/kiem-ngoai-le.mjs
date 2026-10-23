// QA-13 · Đối chiếu báo cáo SARIF của ZAP với ngoai-le.yaml theo tiêu chí TL-04 mục 9.8: 0 cảnh báo High, mọi
// cảnh báo Medium có giải trình. Ngoại lệ hết hạn xem lại hoặc thiếu người duyệt bị coi như không có.
//
//   node infra/security/zap/kiem-ngoai-le.mjs <zap.sarif> [ngoai-le.yaml] [api|web]
//
// Mã thoát: 0 đạt; 1 còn High hoặc Medium chưa giải trình; 2 lỗi đầu vào. Chỉ dùng thư viện chuẩn Node.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const [tepSarif, tepNgoaiLe = join(dirname(fileURLToPath(import.meta.url)), 'ngoai-le.yaml'), keHoach = 'api'] =
  process.argv.slice(2);
if (!tepSarif) {
  console.error('Cách dùng: node kiem-ngoai-le.mjs <zap.sarif> [ngoai-le.yaml] [api|web]');
  process.exit(2);
}

/**
 * Đọc tập con YAML của ngoai-le.yaml: khoá `ngoaiLe:` chứa danh sách ánh xạ một cấp, giá trị vô hướng trên một dòng.
 * Không cần thư viện YAML ở máy chạy pipeline; tệp có định dạng khác thì báo lỗi thay vì đoán.
 */
export function docNgoaiLe(text) {
  const ds = [];
  let trong = false;
  let cur = null;
  for (const [i, dongGoc] of text.split(/\r?\n/).entries()) {
    const dong = dongGoc.replace(/\s+#.*$/, '').replace(/^#.*$/, '');
    if (!dong.trim()) continue;
    if (/^ngoaiLe:\s*(\[\])?\s*$/.test(dong)) {
      trong = true;
      continue;
    }
    if (!trong) throw new Error(`dòng ${i + 1}: mong đợi khoá ngoaiLe:`);
    const m = dong.match(/^\s{2}(-\s+|\s{2})([A-Za-z]+):\s*(.*)$/);
    if (!m) throw new Error(`dòng ${i + 1}: không đúng định dạng "  - khoa: giá trị" hoặc "    khoa: giá trị"`);
    if (m[1].startsWith('-')) {
      cur = {};
      ds.push(cur);
    } else if (!cur) {
      throw new Error(`dòng ${i + 1}: thuộc tính trước mục đầu tiên`);
    }
    cur[m[2]] = m[3].replace(/^(['"])(.*)\1$/, '$2');
  }
  return ds;
}

const MUC = { error: 'High', warning: 'Medium', note: 'Low', none: 'Informational' };
const homNay = new Date().toISOString().slice(0, 10);

function hopLe(n) {
  const thieu = ['ruleId', 'giaiTrinh', 'nguoiDuyet', 'ngayXemLai'].filter((k) => !n[k]);
  if (thieu.length) return `thiếu ${thieu.join(', ')}`;
  if (n.ngayXemLai < homNay) return `đã quá ngày xem lại ${n.ngayXemLai}`;
  return null;
}

let sarif;
let ngoaiLe;
try {
  sarif = JSON.parse(readFileSync(tepSarif, 'utf8'));
  ngoaiLe = docNgoaiLe(readFileSync(tepNgoaiLe, 'utf8'));
} catch (e) {
  console.error(`Không đọc được đầu vào: ${e.message}`);
  process.exit(2);
}

const ketQua = (sarif.runs ?? []).flatMap((r) => r.results ?? []);
const loi = [];
const daGiaiTrinh = [];
for (const n of ngoaiLe) {
  const v = hopLe(n);
  if (v) loi.push(`Ngoại lệ ${n.ruleId ?? '?'} không hợp lệ: ${v}`);
}
for (const kq of ketQua) {
  const muc = MUC[kq.level ?? 'warning'] ?? 'Medium';
  if (muc !== 'High' && muc !== 'Medium') continue;
  const url = kq.locations?.[0]?.physicalLocation?.artifactLocation?.uri ?? '';
  const ruleId = String(kq.ruleId ?? '').replace(/-\d+$/, '');
  const khop = ngoaiLe.find((n) => !hopLe(n) && String(n.ruleId) === ruleId
    && (!n.keHoach || n.keHoach === keHoach || n.keHoach === 'ca-hai')
    && (!n.urlRegex || new RegExp(n.urlRegex).test(url))
    && (muc !== 'High' || n.trangThai === 'da-duyet'));
  const tom = (kq.message?.text ?? '').split(/[.\n]/)[0].slice(0, 100);
  const moTa = `${muc} ${ruleId} ${tom} ${url}`.trim();
  if (khop) daGiaiTrinh.push(`${moTa} → ${khop.giaiTrinh}${khop.trangThai === 'da-duyet' ? '' : ' (chờ duyệt)'}`);
  else loi.push(`${moTa}: chưa có giải trình trong ngoai-le.yaml`);
}

const dem = ketQua.reduce((m, kq) => ((m[MUC[kq.level ?? 'warning']] = (m[MUC[kq.level ?? 'warning']] ?? 0) + 1), m), {});
console.log(`ZAP (${keHoach}): ${ketQua.length} kết quả: ${Object.entries(dem).map(([k, v]) => `${k} ${v}`).join(', ') || 'không có'}`);
for (const d of daGiaiTrinh) console.log(`  đã giải trình: ${d}`);
for (const l of loi) console.log(`  CHẶN: ${l}`);
process.exit(loi.length ? 1 : 0);
