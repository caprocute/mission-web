import { Component } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  FeatureFlag,
  FeatureFlagDirective,
  FeatureFlagService,
  featureFlagGuard,
  flagActive,
} from './feature-flags';

const flag = (
  flagKey: string,
  enabled: boolean,
  extra: Partial<FeatureFlag> = {},
): FeatureFlag => ({
  flagKey,
  enabled,
  rowVersion: 1,
  ...extra,
});

@Component({
  imports: [FeatureFlagDirective],
  template: `<p *vtFeature="'ai.weeklySummary'" id="ai">AI</p>`,
})
class Host {}

function setup() {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting()],
  });
  return {
    svc: TestBed.inject(FeatureFlagService),
    ctrl: TestBed.inject(HttpTestingController),
  };
}

describe('Cờ tính năng (WEB-076)', () => {
  it('cờ chỉ có hiệu lực khi bật và trong khoảng thời gian', () => {
    const now = Date.parse('2026-10-21T00:00:00Z');
    expect(flagActive(flag('a', true), now)).toBe(true);
    expect(flagActive(flag('a', false), now)).toBe(false);
    expect(flagActive(flag('a', true, { validFrom: '2026-11-01T00:00:00Z' }), now)).toBe(false);
    expect(flagActive(flag('a', true, { validTo: '2026-10-01T00:00:00Z' }), now)).toBe(false);
  });

  it('nạp bản đồ cờ hiệu lực GET /ui/feature-flags (WEB-072), lỗi thì mọi cờ tắt', async () => {
    const { svc, ctrl } = setup();
    let p = svc.load();
    const req = ctrl.expectOne('/api/v1/ui/feature-flags');
    expect(req.request.method).toBe('GET');
    req.flush({
      flags: { 'ai.weeklySummary': true, x: false },
      evaluatedAt: '2026-10-21T00:00:00Z',
    });
    await p;
    expect(svc.isEnabled('ai.weeklySummary')).toBe(true);
    expect(svc.isEnabled('x')).toBe(false);
    p = svc.load();
    ctrl
      .expectOne('/api/v1/ui/feature-flags')
      .flush(null, { status: 503, statusText: 'Service Unavailable' });
    await p;
    expect(svc.isEnabled('ai.weeklySummary')).toBe(false);
    ctrl.verify();
  });

  it('directive và guard theo cờ', async () => {
    const { svc, ctrl } = setup();
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('#ai')).toBeNull();
    const guard = featureFlagGuard('ai.weeklySummary');
    expect(TestBed.runInInjectionContext(() => guard({}, []))).toBe(false);
    const p = svc.load();
    ctrl
      .expectOne('/api/v1/ui/feature-flags')
      .flush({ flags: { 'ai.weeklySummary': true }, evaluatedAt: '2026-10-21T00:00:00Z' });
    await p;
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('#ai')).not.toBeNull();
    expect(TestBed.runInInjectionContext(() => guard({}, []))).toBe(true);
  });
});
