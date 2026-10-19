import { Job } from '../../lib/models/job';
import {
  CellLoad,
  TENANT_ONBOARDING_STATUSES,
  TENANT_STATUS_LABEL,
  TenancyModel,
  TenantDetail,
  TenantOverview,
  TenantStatus,
  TenantSummary,
} from '../../lib/models/tenant';
import { MAX_PAGE_LIMIT } from '../../lib/paging';
import {
  MockModule,
  MockRequest,
  MockResponse,
  ok,
  problem,
  resolveRelativeDates,
} from '../mock-router';
import fixture from './tenants.json';

/**
 * Giả lập danh bạ tenant của SVC-03 tenant-registry (QT-01, SCR-0101): tổng quan, danh sách lọc theo
 * cell, mô hình, trạng thái và từ khoá với phân trang con trỏ, tải theo cell, chi tiết, xuất danh
 * sách (202 + `Location`, tác vụ hoàn tất sau vài lần thăm dò).
 */

type StoredTenant = TenantDetail;

let tenants: StoredTenant[] | null = null;
let exports = new Map<string, { job: Job; polls: number; csv: string }>();

function load(): StoredTenant[] {
  if (tenants) return tenants;
  const named = resolveRelativeDates(structuredClone(fixture.tenants)).map(
    (t) =>
      ({
        ...t,
        rowVersion: 0,
        domains: [{ domain: t.domain, kind: 'PRIMARY', verifiedAt: '2026-01-15T08:00:00+07:00' }],
        statusLog: resolveRelativeDates(
          structuredClone(
            (fixture.statusLog as Record<string, StoredTenant['statusLog']>)[t.id] ?? [],
          ),
        ),
      }) as StoredTenant,
  );
  // tenant pool nhỏ ở CELL-SH, đủ để bảng có nhiều trang như vùng điều phối thật
  const pool = fixture.poolUnits.map((name, i): StoredTenant => {
    const code = `pool_${String(i + 1).padStart(3, '0')}`;
    const party = name.startsWith('Đảng uỷ');
    return {
      id: `t-${code}`,
      code,
      name,
      domain: `${code.replace('_', '-')}.okr.viettel.vn`,
      cellCode: 'CELL-SH',
      tenancyModel: 'pool',
      accountCount: 180 + ((i * 397) % 2600),
      policyPacks: [party ? 'Khối Đảng · Quy định mới v2.0' : 'Nhà nước · NĐ 335 v1.3'],
      uiPack: 'Mặc định v3.0',
      status: 'DANG_VAN_HANH',
      rowVersion: 0,
      sector: party ? 'DANG' : 'SU_NGHIEP',
      flows: [party ? 'DANG' : 'NHA_NUOC'],
      maxAccounts: 5000,
      dataRegion: 'PHIANAM',
      domains: [{ domain: `${code.replace('_', '-')}.okr.viettel.vn`, kind: 'PRIMARY' }],
      statusLog: [],
    };
  });
  tenants = [...named, ...pool];
  return tenants;
}

function summary(t: StoredTenant): TenantSummary {
  const { id, code, name, domain, cellCode, tenancyModel, accountCount, policyPacks, uiPack } = t;
  const { status, onboardingStep, rowVersion } = t;
  return {
    id,
    code,
    name,
    domain,
    cellCode,
    tenancyModel,
    accountCount,
    policyPacks,
    uiPack,
    status,
    onboardingStep,
    rowVersion,
  };
}

const norm = (s: string) =>
  s.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/g, 'd').replace(/Đ/g, 'D').toLowerCase();

function filtered(params: MockRequest['params']): StoredTenant[] {
  const q = norm(params.get('q')?.trim() ?? '');
  const cell = params.get('cell');
  const model = params.get('model') as TenancyModel | null;
  const status = params.get('status') as TenantStatus | null;
  return load().filter(
    (t) =>
      (!q || norm(`${t.name} ${t.code} ${t.domain}`).includes(q)) &&
      (!cell || t.cellCode === cell) &&
      (!model || t.tenancyModel === model) &&
      (!status || t.status === status),
  );
}

function list(r: MockRequest): MockResponse {
  const all = filtered(r.params);
  const limit = Math.min(Number(r.params.get('limit') ?? 8) || 8, MAX_PAGE_LIMIT);
  const start = Number(r.params.get('cursor') ?? 0) || 0;
  const items = all.slice(start, start + limit).map(summary);
  const next = start + limit < all.length ? String(start + limit) : null;
  return ok({ items, nextCursor: next, total: all.length });
}

function cells(): CellLoad[] {
  const all = load();
  return fixture.cells.map((c) => {
    const mine = all.filter((t) => t.cellCode === c.code && t.status !== 'DA_CHAM_DUT');
    return {
      ...(c as Omit<CellLoad, 'accountCount' | 'tenantCount'>),
      accountCount: mine.reduce((s, t) => s + t.accountCount, 0),
      tenantCount: mine.length,
    };
  });
}

function overview(): MockResponse {
  const all = load();
  const onboarding = all.filter((t) => TENANT_ONBOARDING_STATUSES.includes(t.status));
  const body: TenantOverview = {
    operatingCount: all.filter((t) => t.status === 'DANG_VAN_HANH').length,
    maxTenants: fixture.maxTenants,
    accountTotal: all.reduce((s, t) => s + t.accountCount, 0),
    designAccounts: fixture.designAccounts,
    onboardingCount: onboarding.length,
    cellCount: fixture.cells.length,
    dataCenterCount: new Set(fixture.cells.map((c) => c.primaryDc)).size,
    onboarding: onboarding.map((t) => ({ id: t.id, name: t.name, step: t.onboardingStep ?? 1 })),
    todos: fixture.todos as TenantOverview['todos'],
    updatedAt: resolveRelativeDates(fixture.updatedAt),
  };
  return ok(body);
}

const csvCell = (v: string | number) => `"${String(v).replace(/"/g, '""')}"`;

function startExport(r: MockRequest): MockResponse {
  const rows = filtered(r.params);
  const csv = [
    [
      'Tenant',
      'Mã',
      'Tên miền',
      'Cell',
      'Mô hình',
      'Tài khoản',
      'Policy Pack',
      'UI Pack',
      'Trạng thái',
    ],
    ...rows.map((t) => [
      t.name,
      t.code,
      t.domain,
      t.cellCode,
      t.tenancyModel,
      t.accountCount,
      t.policyPacks.join(' + '),
      t.uiPack,
      TENANT_STATUS_LABEL[t.status],
    ]),
  ]
    .map((line) => line.map(csvCell).join(','))
    .join('\r\n');
  const id = crypto.randomUUID();
  exports.set(id, { job: { id, type: 'EXPORT', status: 'CHO', progress: 0 }, polls: 0, csv });
  return {
    status: 202,
    body: null,
    headers: { Location: `/api/v1/config/tenants/exports/${id}` },
  };
}

function exportJob(r: MockRequest): MockResponse {
  const e = exports.get(r.pathParams['jobId']);
  if (!e) return problem(404, 'Không tìm thấy tác vụ xuất');
  e.polls++;
  const id = e.job.id;
  e.job =
    e.polls >= 2
      ? {
          id,
          type: 'EXPORT',
          status: 'HOAN_TAT',
          progress: 100,
          message: 'Đã tạo tệp danh sách tenant',
          resultLocation: `/api/v1/config/tenants/exports/${id}/file`,
        }
      : { id, type: 'EXPORT', status: 'DANG_CHAY', progress: 50, message: 'Đang tổng hợp' };
  return ok(e.job);
}

function exportFile(r: MockRequest): MockResponse {
  const e = exports.get(r.pathParams['jobId']);
  if (!e || e.job.status !== 'HOAN_TAT') return problem(404, 'Tệp xuất chưa sẵn sàng');
  // BOM để Excel đọc đúng tiếng Việt có dấu
  return {
    status: 200,
    body: new Blob(['﻿' + e.csv], { type: 'text/csv;charset=utf-8' }),
    headers: { 'Content-Type': 'text/csv; charset=utf-8' },
  };
}

export const TENANTS_MOCK: MockModule = {
  name: 'config/tenants',
  reset: () => {
    tenants = null;
    exports = new Map();
  },
  routes: [
    { method: 'GET', pattern: '/api/v1/config/tenants/overview', handle: overview },
    { method: 'GET', pattern: '/api/v1/config/tenants', handle: list },
    { method: 'POST', pattern: '/api/v1/config/tenants/export', handle: startExport },
    { method: 'GET', pattern: '/api/v1/config/tenants/exports/:jobId', handle: exportJob },
    { method: 'GET', pattern: '/api/v1/config/tenants/exports/:jobId/file', handle: exportFile },
    {
      method: 'GET',
      pattern: '/api/v1/config/tenants/:id',
      handle: (r) => {
        const t = load().find((x) => x.id === r.pathParams['id']);
        return t ? ok(t) : problem(404, 'Không tìm thấy tenant');
      },
    },
    { method: 'GET', pattern: '/api/v1/config/cells', handle: () => ok({ items: cells() }) },
  ],
};
