import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { EvidenceApi } from './evidence-api';

/** QA-05 HT-20: phiên bản minh chứng gửi và nhận tệp theo mảng `files` của hợp đồng (SCR-0701). */
describe('EvidenceApi', () => {
  it('gửi tệp trong files[] và gộp tệp đầu vào trường phẳng khi nhận', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const api = TestBed.inject(EvidenceApi);
    const http = TestBed.inject(HttpTestingController);
    const p = firstValueFrom(
      api.createVersion('e1', { source: 'TAI_LEN', objectId: 'o1', reuseJustification: 'x' }),
    );
    const req = http.expectOne('/api/v1/evidences/e1/versions');
    expect(req.request.body).toEqual({
      source: 'TAI_LEN',
      files: [{ objectId: 'o1', reuseJustification: 'x' }],
    });
    req.flush({
      versionNo: 1,
      scanStatus: 'CHO_QUET',
      files: [{ objectId: 'o1', contentSha256: 'ab', fileName: 'a.pdf', scanStatus: 'SACH' }],
    });
    const v = await p;
    expect(v.objectId).toBe('o1');
    expect(v.fileName).toBe('a.pdf');
    expect(v.scanStatus).toBe('SACH');
    http.verify();
  });
});
