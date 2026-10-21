import {
  MOCK_LOADERS,
  MOCK_ORDER,
  MOCK_PREFIXES,
  MockLoaderKey,
  mockPrefixOf,
} from './mock-registry';

/**
 * WEB-052 pha 2: bảng MOCK_PREFIXES viết tay phải khớp tuyến thật của từng bộ giả lập, nếu không
 * một request sẽ không tìm thấy fixture (hoặc khớp nhầm bộ khác) khi bộ giả lập được nạp lười.
 */
describe('mock-registry nạp lười theo module API', () => {
  it('MOCK_PREFIXES liệt kê đúng và đủ bộ giả lập cho mỗi module API, theo thứ tự ưu tiên', async () => {
    const expected: Record<string, MockLoaderKey[]> = {};
    for (const key of MOCK_ORDER) {
      const mod = await MOCK_LOADERS[key]();
      for (const route of mod.routes) {
        expect(route.pattern.startsWith('/api/v1/')).toBe(true);
        const prefix = mockPrefixOf(route.pattern);
        // đoạn module phải là chữ cố định thì mới định tuyến lười được
        expect(prefix).toMatch(/^[a-z][a-z-]*$/);
        const list = (expected[prefix] ??= []);
        if (!list.includes(key)) list.push(key);
      }
    }
    expect(MOCK_PREFIXES).toEqual(expected);
  });

  it('mỗi bộ giả lập là một chunk nạp lười riêng', () => {
    for (const key of MOCK_ORDER) {
      expect(MOCK_LOADERS[key]).toBeTypeOf('function');
    }
    expect(MOCK_ORDER.indexOf('sourceDocs')).toBeLessThan(MOCK_ORDER.indexOf('tasks'));
  });
});
