import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { pluginShortName, pluginWidgetAllowed, validPluginDescriptor } from '@vt/sdui-renderer';
import { App } from './app';
import { ROUTES } from './plugin-giaoduc.routes';
import { VT_PLUGIN } from '../plugin';

describe('fe-plugin-giaoduc', () => {
  it('dựng router-outlet cho tuyến', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([])],
    }).compileComponents();
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('router-outlet')).not.toBeNull();
  });

  it('mô tả VT_PLUGIN đúng hợp đồng của shell', async () => {
    expect(validPluginDescriptor(VT_PLUGIN, 'fe-plugin-giaoduc')).toBe(true);
    const short = pluginShortName(VT_PLUGIN.name);
    expect(short).toBe('giaoduc');
    for (const w of VT_PLUGIN.widgets ?? []) expect(pluginWidgetAllowed(short!, w.name)).toBe(true);
    expect(await VT_PLUGIN.routes()).toBe(ROUTES);
  });

  it('tuyến gốc có chặn quyền và tiêu đề tiếng Việt', () => {
    expect(ROUTES[0].path).toBe('');
    expect(ROUTES[0].canActivate?.length).toBe(1);
    expect(ROUTES[0].title).toBe('Chuyên môn giáo dục');
  });
});
