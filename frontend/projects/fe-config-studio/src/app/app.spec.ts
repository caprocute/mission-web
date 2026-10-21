import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { ROUTES } from './config-studio.routes';

describe('fe-config-studio', () => {
  it('dựng router-outlet cho tuyến', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([])],
    }).compileComponents();
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('router-outlet')).not.toBeNull();
  });

  it('khung có các phần chính sách, giao diện, biểu mẫu, mẫu quy trình; phiên bản chính sách có bốn thẻ', () => {
    const sections = ROUTES[0].children ?? [];
    expect(sections.map((r) => r.path)).toEqual([
      '',
      'goi/:packId/:version',
      'giao-dien',
      'bieu-mau',
      'bieu-mau/:formKey/:formVersion',
      'mau-luong',
      'mau-luong/:templateId',
    ]);
    expect(sections[0].title).toBe('Chính sách đánh giá');
    expect(sections[1].children?.map((r) => r.path)).toEqual([
      '',
      'cong-thuc',
      'bang-quyet-dinh',
      'test-case',
      'luong-duyet',
    ]);
  });
});
