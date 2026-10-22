import {
  pluginShortName,
  pluginWidgetAllowed,
  remoteEntryFilesSafe,
  sriSha256,
  validPluginDescriptor,
} from './plugin-contract';

/** WEB-051: hợp đồng plugin chuyên ngành (ADR-05 lớp 4). */
describe('plugin-contract', () => {
  it('lấy tên ngành từ tên plugin, từ chối tên sai dạng', () => {
    expect(pluginShortName('fe-plugin-giaoduc')).toBe('giaoduc');
    expect(pluginShortName('fe-task')).toBeNull();
    expect(pluginShortName('fe-plugin-../x')).toBeNull();
    expect(pluginShortName('fe-plugin-Yte')).toBeNull();
  });

  it('widget plugin phải mang tiền tố ngành, không thay được component lõi', () => {
    expect(pluginWidgetAllowed('giaoduc', 'giaoduc.widget.tienDo')).toBe(true);
    expect(pluginWidgetAllowed('giaoduc', 'vt.widget.myTasks')).toBe(false);
    expect(pluginWidgetAllowed('giaoduc', 'yte.widget.x')).toBe(false);
    expect(pluginWidgetAllowed('giaoduc', 'giaoduc.layout.grid')).toBe(false);
  });

  it('băm SRI sha256 dạng base64', async () => {
    // sha256("abc") theo FIPS 180-2
    expect(await sriSha256('abc')).toBe('sha256-ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=');
  });

  it('remoteEntry chỉ được trỏ tệp trong thư mục plugin', () => {
    const ok = {
      exposes: [{ outFileName: 'plugin-AB12.js' }],
      shared: [{ outFileName: 'x/y.js' }],
    };
    expect(remoteEntryFilesSafe(ok)).toBe(true);
    for (const bad of [
      'https://evil/x.js',
      '//evil/x.js',
      '/abs.js',
      '../x.js',
      'a/../../x.js',
      'a\\b.js',
      '',
    ]) {
      expect(remoteEntryFilesSafe({ exposes: [{ outFileName: bad }] })).toBe(false);
    }
    expect(remoteEntryFilesSafe({ exposes: 'x' })).toBe(false);
    expect(remoteEntryFilesSafe(null)).toBe(false);
  });

  it('mô tả plugin phải đúng tên trong UI Pack', () => {
    const p = {
      name: 'fe-plugin-giaoduc',
      title: 'Giáo dục',
      icon: 'pi pi-book',
      routes: async () => [],
    };
    expect(validPluginDescriptor(p, 'fe-plugin-giaoduc')).toBe(true);
    expect(validPluginDescriptor(p, 'fe-plugin-yte')).toBe(false);
    expect(validPluginDescriptor({ ...p, icon: 'pi pi-book" onclick' }, 'fe-plugin-giaoduc')).toBe(
      false,
    );
    expect(validPluginDescriptor({ ...p, routes: undefined }, 'fe-plugin-giaoduc')).toBe(false);
    expect(validPluginDescriptor({ ...p, title: '' }, 'fe-plugin-giaoduc')).toBe(false);
  });
});
