// Quy tắc của kiểm tra nội dung giao diện (wiki/16-noi-dung-giao-dien.md). Mỗi quy tắc bắt một loại
// nội dung không được hiện cho người dùng cuối; `hint` nói cách sửa.
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const GEN = join(
  dirname(fileURLToPath(import.meta.url)),
  '../../projects/api-client/src/lib/generated',
);

/** Viết tắt nghiệp vụ được phép hiện (thuật ngữ chuẩn OKR/KPI, mức xếp loại, tên hệ thống quốc gia). */
const BUSINESS_ACRONYMS = new Set([
  'KPI',
  'OKR',
  'KR',
  'HTXSNV',
  'HTTNV',
  'HTNV',
  'KHTNV',
  'CBCCVC',
  'CSDL',
  'PDF',
  'DOCX',
  'CSV',
  'LGSP',
  'VPTW',
  'BTC',
  'TW',
  'LLVT',
  'BO',
  'NAM',
  'BA',
  'MOBILE',
  'XLSX',
  'JPG',
  'PNG',
  'HRM',
  'TLS',
  'MFA',
  'OCR',
  'AI',
  'QR',
  'CCCD',
  'URL',
  'MB',
  'KB',
  'GB',
]);

/** Giá trị enum của hợp đồng API (mã trạng thái, mã loại) không có dấu gạch dưới, ví dụ `MO`, `NHAP`, `CAO`. */
const ENUM_WORDS = (() => {
  const set = new Set();
  for (const f of readdirSync(GEN)) {
    for (const m of readFileSync(join(GEN, f), 'utf8').matchAll(/'([A-Z][A-Z0-9]+)'/g)) {
      if (!BUSINESS_ACRONYMS.has(m[1]) && m[1].length >= 2) set.add(m[1]);
    }
  }
  return [...set];
})();

export const RULES = [
  {
    id: 'ma-thiet-ke',
    re: /\b(SCR|QT|BR|MSG|ADR|EVT|MOD|SVC|NFR|SEC|OPS|PERF|DR|AC|TL|WEB|BS|YC)-\d[\w.-]*/,
    sentenceOnly: true,
    hint: 'Bỏ mã thiết kế khỏi văn bản hiển thị; giữ trong chú thích mã nguồn hoặc thuộc tính data-*.',
  },
  {
    id: 'trang-thai-tho',
    re: /\b[A-Z][A-Z0-9]*(_[A-Z0-9]+)+\b/,
    sentenceOnly: true,
    hint: 'Hiện nhãn nghiệp vụ của trạng thái/loại (ví dụ "Đã giao") thay cho mã thô.',
  },
  {
    id: 'trang-thai-tho',
    re: new RegExp(`(?<![\\p{L}\\d_-])(${ENUM_WORDS.join('|')}|OK)(?![\\p{L}\\d_-])`, 'u'),
    sentenceOnly: true,
    hint: 'Hiện nhãn nghiệp vụ của trạng thái/loại thay cho mã thô.',
  },
  {
    id: 'ten-ky-thuat',
    re: /\b([a-z]+-)+(service|registry|gateway|engine|worker)\b|\bfe-[a-z-]+\b|\b[a-z]+\.[a-z]+(_[a-z]+)+\b|\b[a-z]+(_[a-z0-9]+)+\b|\b[a-z]+\.[a-z_]+\.[a-z_.]+\.v\d\b/,
    sentenceOnly: true,
    hint: 'Không hiện tên service, tên bảng, tên trường hay tên sự kiện; mô tả bằng lời nghiệp vụ.',
  },
  {
    id: 'thuat-ngu-ky-thuat',
    re: /(?<![\p{L}-])(saga|Saga|worker|superseded|endpoint|payload|JSON|BPMN|API|HTTP|UUID|JWKS|JWT|SCIM|IdP|OIDC|SSE|CDN|DMN|CEL|Kafka|Flowable|Keycloak|OPA|Rego|tenant|Tenant|Policy Pack|UI Pack|Config Studio|remote|Remote|mock|Mock|cell|Cell|silo|bridge|pool|null|undefined|NaN|console)(?![\p{L}-])/u,
    sentenceOnly: true,
    hint: 'Thay thuật ngữ kỹ thuật bằng từ nghiệp vụ theo bảng ở wiki/16 (ví dụ "tenant" → "đơn vị triển khai").',
  },
  {
    id: 'ma-loi-http',
    re: /\b(HTTP|mã lỗi|lỗi|status)\s*[1-5]\d\d\b/i,
    hint: 'Không hiện mã HTTP; nói điều đã xảy ra và người dùng cần làm gì.',
  },
  {
    id: 'ma-dinh-danh-noi-bo',
    re: /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/,
    sentenceOnly: false,
    hint: 'Không hiện mã định danh nội bộ (UUID); hiện tên hoặc mã nghiệp vụ.',
  },
  {
    id: 'ghi-chu-giai-phap',
    re: /\(suy luận\)|\bTODO\b|\bFIXME\b|Lorem ipsum|theo kế hoạch phát triển|đang được phát triển|đã có khung|giai đoạn sau|dữ liệu mẫu|giả lập/i,
    hint: 'Không in ghi chú giải pháp, tiến độ phát triển hay ghi chú dữ liệu mẫu lên giao diện.',
  },
  {
    id: 'tieng-anh-mac-dinh',
    re: /\b(No results found|No available options|No selected item|items selected|Search results are available|No file chosen|Rows per page|(Previous|Next|First|Last) Page|Choose (Date|Month|Year)|Enter a password|Loading\.\.\.)\b/,
    hint: 'Chuỗi mặc định tiếng Anh của thư viện giao diện; đặt bản dịch tiếng Việt (PRIMENG_VI trong @vt/design-system) hoặc truyền nhãn tiếng Việt cho component.',
  },
  {
    id: 'xung-ho',
    re: /\b(anh\/chị|Anh\/chị|anh chị|Anh chị|bạn hãy|Bạn hãy)\b/,
    hint: 'Xưng hô thống nhất "đồng chí" (wiki/16).',
  },
  {
    id: 'dang-tai-tron',
    // YC-PT-28: vùng dữ liệu đang tải hiện khung xương, không in chữ "Đang tải…"; tiến độ gửi hay
    // nhận tệp ("Đang tải lên…", "Đang tải xuống…") là trạng thái thao tác nên được phép
    re: /Đang tải(?! lên| xuống| tệp)[^…]*(…|\.\.\.)/,
    allow: [/bản mới nhất để so sánh/],
    hint: 'Vùng dữ liệu đang tải dùng <vt-skeleton> của @vt/design-system với bố cục gần nội dung sắp hiện, không in chữ "Đang tải…".',
  },
];
