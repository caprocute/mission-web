import { DEFAULT_RUNTIME_CONFIG } from '@vt/api-client';
import {
  absolutize,
  appRootOf,
  embedManifest,
  embedRuntimeConfig,
  normalizeRoute,
  pageRedirectUri,
  tenantAttr,
} from './embed-config';

const ROOT = 'https://okr.example.vn/';

describe('Cấu hình chế độ nhúng (WEB-013)', () => {
  it('gốc ứng dụng là thư mục cha của /embed/', () => {
    expect(appRootOf('https://okr.example.vn/embed/')).toBe(ROOT);
  });

  it('đổi địa chỉ tương đối sang tuyệt đối theo gốc ứng dụng, giữ địa chỉ tuyệt đối', () => {
    expect(absolutize('/api', ROOT)).toBe('https://okr.example.vn/api');
    expect(absolutize('https://api.g4.example.vn/', ROOT)).toBe('https://api.g4.example.vn');
    expect(absolutize('', ROOT)).toBe('');
  });

  it('cấu hình nhúng: API và UI Pack tuyệt đối, tenant theo thuộc tính, trả về trang của cổng', () => {
    const cfg = embedRuntimeConfig(DEFAULT_RUNTIME_CONFIG, {
      root: ROOT,
      pageUrl: 'https://cong.example.vn/van-phong/okr?ma=1#tab',
      tenant: 'so-tu-phap',
    });
    expect(cfg.apiBaseUrl).toBe('https://okr.example.vn/api');
    expect(cfg.uiPackBaseUrl).toBe('https://okr.example.vn/ui-packs');
    expect(cfg.tenant).toBe('so-tu-phap');
    expect(cfg.auth.redirectUri).toBe('https://cong.example.vn/van-phong/okr');
    expect(cfg.auth.clientId).toBe(DEFAULT_RUNTIME_CONFIG.auth.clientId);
  });

  it('không có thuộc tính tenant thì giữ tenant của config.json; giá trị lạ bị bỏ qua', () => {
    expect(tenantAttr(' so-noi-vu ')).toBe('so-noi-vu');
    expect(tenantAttr('a"b')).toBeUndefined();
    expect(tenantAttr(null)).toBeUndefined();
    const cfg = embedRuntimeConfig(DEFAULT_RUNTIME_CONFIG, {
      root: ROOT,
      pageUrl: 'https://c.vn/',
    });
    expect(cfg.tenant).toBe(DEFAULT_RUNTIME_CONFIG.tenant);
  });

  it('manifest remote đổi sang địa chỉ tuyệt đối', () => {
    expect(
      embedManifest(
        {
          feTask: '/remotes/fe-task/remoteEntry.json',
          feOkr: 'https://cdn.example.vn/fe-okr/remoteEntry.json',
        },
        ROOT,
      ),
    ).toEqual({
      feTask: 'https://okr.example.vn/remotes/fe-task/remoteEntry.json',
      feOkr: 'https://cdn.example.vn/fe-okr/remoteEntry.json',
    });
  });

  it('pageRedirectUri bỏ truy vấn (mã đăng nhập cũ) và phần sau dấu #', () => {
    expect(pageRedirectUri('https://c.vn/a/b?code=x&state=y#z')).toBe('https://c.vn/a/b');
  });

  it('thuộc tính route chỉ nhận đường dẫn nội bộ, mặc định "Việc của tôi"', () => {
    expect(normalizeRoute('tasks')).toBe('/tasks');
    expect(normalizeRoute('/tasks/123')).toBe('/tasks/123');
    expect(normalizeRoute(undefined)).toBe('/tasks');
    expect(normalizeRoute('javascript:alert(1)')).toBe('/tasks');
    expect(normalizeRoute('https://ngoai.vn')).toBe('/tasks');
    expect(normalizeRoute('//ngoai.vn')).toBe('/ngoai.vn');
  });
});
