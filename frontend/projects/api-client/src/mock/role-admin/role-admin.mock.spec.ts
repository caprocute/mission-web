import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { RoleAdminApi } from '../../lib/role-admin-api';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';
import { MOCK_ME, mockSession, mockStepUp } from '../mock-session';

/** WEB-058: giả lập quản trị vai trò và uỷ quyền SCR-0105 đến SCR-0107. */
function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
    ],
  });
  return TestBed.inject(RoleAdminApi);
}

async function fails(p: Promise<unknown>): Promise<HttpErrorResponse> {
  try {
    await p;
  } catch (e) {
    return e as HttpErrorResponse;
  }
  throw new Error('Mong đợi lỗi');
}

const day = (n: number) => new Date(Date.now() + n * 86_400_000).toISOString();
const grantBody = {
  personId: 'p-tuan',
  roleCode: 'decider' as const,
  orgUnitId: 'u-btc',
  scope: 'DON_VI' as const,
  validFrom: day(1),
  validTo: day(200),
  reason: 'Quyết định giao quyết định xếp loại',
};

describe('mock role-admin – SCR-0105..SCR-0107 (WEB-058)', () => {
  beforeEach(() => {
    resetMockData();
    mockSession.reset();
  });

  it('danh sách theo đơn vị có số đếm; gồm cấp dưới khi includeSubunits', async () => {
    const api = setup();
    const btc = await firstValueFrom(api.bindings({ orgUnitId: 'u-btc' }));
    expect(btc.items.every((b) => b.orgUnit.id === 'u-btc')).toBe(true);
    expect(btc.counts.pending).toBe(2);
    expect(btc.counts.awaitingMe).toBe(1);
    expect(btc.counts.expiringSoon).toBe(1);
    const all = await firstValueFrom(api.bindings({ orgUnitId: 'u-tn', includeSubunits: true }));
    expect(all.items.length).toBeGreaterThan(btc.items.length);
    const mine = await firstValueFrom(api.bindings({ orgUnitId: 'u-btc', awaitingMe: true }));
    expect(mine.items.map((b) => b.id)).toEqual(['rb-03']);
  });

  it('ghi cần MFA gần đây (BR-01-15): 401 insufficient_user_authentication', async () => {
    const api = setup();
    const e = await fails(firstValueFrom(api.grant(grantBody)));
    expect(e.status).toBe(401);
    expect(e.headers.get('WWW-Authenticate')).toContain('acr_values="aal2"');
  });

  it('vai trò nhạy cảm chờ duyệt thứ hai; người cấp không tự duyệt được (BR-01-13, BR-01-14)', async () => {
    const api = setup();
    mockStepUp();
    const b = await firstValueFrom(api.grant(grantBody));
    expect(b.status).toBe('CHO_DUYET');
    expect(b.sensitive).toBe(true);
    const e = await fails(firstValueFrom(api.approve(b)));
    expect(e.status).toBe(403);
    expect(e.error.code).toBe('CONFIG-403-SELF-GRANT');
  });

  it('duyệt đề nghị của người khác thì vai trò có hiệu lực theo ngày bắt đầu', async () => {
    const api = setup();
    mockStepUp();
    const [pending] = (await firstValueFrom(api.bindings({ orgUnitId: 'u-btc', awaitingMe: true })))
      .items;
    const ok = await firstValueFrom(api.approve(pending));
    expect(ok.status).toBe('SAP_HIEU_LUC');
    expect(ok.approvedBy?.id).toBe(MOCK_ME.id);
    const again = await fails(firstValueFrom(api.approve(ok)));
    expect(again.error.code).toBe('CONFIG-409-NOT-PENDING');
  });

  it('không tự cấp cho mình, bắt buộc thời hạn, chặn trùng còn hiệu lực', async () => {
    const api = setup();
    mockStepUp();
    const self = await fails(firstValueFrom(api.grant({ ...grantBody, personId: MOCK_ME.id })));
    expect(self.error.code).toBe('CONFIG-403-SELF-GRANT');
    const term = await fails(
      firstValueFrom(api.grant({ ...grantBody, validTo: grantBody.validFrom })),
    );
    expect(term.status).toBe(422);
    expect(term.error.code).toBe('CONFIG-422-VALIDITY');
    const dup = await fails(
      firstValueFrom(
        api.grant({
          ...grantBody,
          personId: 'p-dung',
          roleCode: 'leader',
          scope: 'DON_VI_VA_CAP_DUOI',
        }),
      ),
    );
    expect(dup.error.code).toBe('CONFIG-409-DUPLICATE-BINDING');
  });

  it('thu hồi cần If-Match và lý do; ghi vào lịch sử của người đó', async () => {
    const api = setup();
    mockStepUp();
    const page = await firstValueFrom(api.bindings({ personId: 'p-ha' }));
    const b = page.items.find((x) => x.status === 'HIEU_LUC')!;
    const stale = await fails(
      firstValueFrom(api.revoke({ ...b, rowVersion: 99 }, 'Chuyển công tác')),
    );
    expect(stale.status).toBe(409);
    const done = await firstValueFrom(api.revoke(b, 'Chuyển công tác sang Sở Nội vụ'));
    expect(done.status).toBe('DA_THU_HOI');
    const h = await firstValueFrom(api.history({ personId: 'p-ha' }));
    expect(h.items[0].kind).toBe('THU_HOI_VAI_TRO');
    expect(h.items[0].summary).toContain('Chuyển công tác sang Sở Nội vụ');
  });

  it('uỷ quyền: không uỷ quyền lại (BR-01-17), thu hồi trước hạn', async () => {
    const api = setup();
    mockStepUp();
    const base = {
      kind: 'DUYET_THAY' as const,
      orgUnitId: 'u-btc',
      validFrom: day(0),
      validTo: day(7),
      reason: 'Vắng mặt đi công tác',
    };
    // p-chau đang nhận duyệt thay của p-dung nên không uỷ quyền tiếp cho p-binh
    const re = await fails(
      firstValueFrom(api.delegate({ ...base, delegatorId: 'p-chau', delegateId: 'p-binh' })),
    );
    expect(re.error.code).toBe('CONFIG-422-REDELEGATION');
    const self = await fails(
      firstValueFrom(api.delegate({ ...base, delegatorId: 'p-binh', delegateId: 'p-binh' })),
    );
    expect(self.error.code).toBe('CONFIG-403-SELF-GRANT');
    const d = await firstValueFrom(
      api.delegate({ ...base, delegatorId: 'p-binh', delegateId: 'p-ha' }),
    );
    expect(d.status).toBe('HIEU_LUC');
    const r = await firstValueFrom(api.revokeDelegation(d, 'Đã trở lại cơ quan'));
    expect(r.status).toBe('DA_THU_HOI');
    const list = await firstValueFrom(api.delegations({ orgUnitId: 'u-btc' }));
    expect(list.items.some((x) => x.id === d.id && x.status === 'DA_THU_HOI')).toBe(true);
  });
});
