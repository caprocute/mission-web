import { SearchResult, SearchResultType } from '../../lib/models/notification';
import catalogFixture from '../catalog/catalog.json';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';
import sourceDocsFixture from '../source-docs/source-docs.json';
import tasksFixture from '../tasks/tasks.json';

/**
 * Giả lập tìm kiếm toàn văn SVC-16 (WEB-012): tìm trên tiêu đề và mã của nhiệm vụ, văn bản chỉ đạo,
 * danh mục công việc trong fixture, không phân biệt dấu; đoạn trích là văn bản thuần, phần khớp
 * trả bằng vị trí ký tự như máy chủ thật.
 */
interface Doc {
  type: SearchResultType;
  id: string;
  code?: string;
  title: string;
  snippet?: string;
  unitName?: string;
  periodLabel?: string;
  deepLink: string;
}

const DOCS: Doc[] = [
  ...(
    tasksFixture.tasks as {
      id: string;
      code: string;
      title: string;
      description?: string;
      unitName?: string;
    }[]
  ).map((t): Doc => ({
    type: 'TASK',
    id: t.id,
    code: t.code,
    title: t.title,
    snippet: t.description,
    unitName: t.unitName,
    periodLabel: 'Quý IV/2026',
    deepLink: `/tasks/${t.id}`,
  })),
  ...(
    sourceDocsFixture.docs as { id: string; docNumber: string; excerpt: string; issuer?: string }[]
  ).map((d): Doc => ({
    type: 'SOURCE_DOC',
    id: d.id,
    code: d.docNumber,
    title: d.excerpt,
    snippet: d.issuer,
    deepLink: `/tasks/documents/${d.id}`,
  })),
  ...(
    catalogFixture.items as { id: string; code: string; name: string; productName?: string }[]
  ).map((c): Doc => ({
    type: 'CATALOG_ITEM',
    id: c.id,
    code: c.code,
    title: c.name,
    snippet: c.productName,
    deepLink: '/tasks/catalog',
  })),
];

/** Bỏ dấu và viết thường, giữ nguyên độ dài để vị trí khớp dùng được trên chuỗi gốc. */
export function fold(s: string): string {
  return Array.from(s, (ch) =>
    ch === 'đ' || ch === 'Đ' ? 'd' : ch.normalize('NFD').charAt(0).toLowerCase(),
  ).join('');
}

function search(req: MockRequest): MockResponse {
  const q = (req.params.get('q') ?? '').trim();
  if (q.length < 2 || q.length > 200) {
    return problem(400, 'Từ khoá tìm kiếm cần từ 2 đến 200 ký tự', { code: 'SEARCH-400-QUERY' });
  }
  const type = req.params.get('type');
  const limit = Math.min(200, Math.max(1, Number(req.params.get('limit') ?? 20) || 20));
  const needle = fold(q);
  const items: SearchResult[] = [];
  for (const d of DOCS) {
    if (type && d.type !== type) continue;
    const inTitle = fold(d.title).indexOf(needle);
    const inCode = d.code ? fold(d.code).indexOf(needle) : -1;
    const inSnippet = d.snippet ? fold(d.snippet).indexOf(needle) : -1;
    if (inTitle < 0 && inCode < 0 && inSnippet < 0) continue;
    const snippet = inSnippet >= 0 ? d.snippet : d.title;
    const at = inSnippet >= 0 ? inSnippet : inTitle;
    items.push({
      type: d.type,
      id: d.id,
      code: d.code,
      title: d.title,
      snippet,
      highlights: at >= 0 ? [{ start: at, length: q.length }] : [],
      unitName: d.unitName,
      periodLabel: d.periodLabel,
      deepLink: d.deepLink,
      score: (inTitle >= 0 ? 10 : 0) + (inCode >= 0 ? 5 : 0) + (inSnippet >= 0 ? 2 : 0),
    });
  }
  items.sort((a, b) => b.score - a.score);
  return ok({ items: items.slice(0, limit), nextCursor: null });
}

export const SEARCH_MOCK: MockModule = {
  name: 'search',
  reset: () => undefined,
  routes: [{ method: 'GET', pattern: '/api/v1/search', handle: search }],
};
