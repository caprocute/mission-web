import { Job } from '../../lib/models/job';
import {
  CELL_LOAD_THRESHOLD,
  CellLoad,
  ConfigTenant,
  RegistryTenant,
  TENANT_ONBOARDING_STATUSES,
  TENANT_STATUS_LABEL,
  TENANCY_MODEL_LABEL,
  TenancyModel,
  TenantExportRequest,
  TenantRow,
  TenantStats,
  TenantStatus,
} from '../../lib/models/tenant';
import { MAX_PAGE_LIMIT } from '../../lib/paging';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';
import fixture from './tenants.json';
import { StoredTenant, load, resetTenantStore } from './tenant-store';
import { ONBOARDING_ROUTES } from './onboarding.mock';

/**
 * Giả lập danh bạ tenant (QT-01, SCR-0101) theo `config.yaml` và `platform.yaml`: danh sách lọc theo
 * cell, mô hình, trạng thái và từ khoá với phân trang con trỏ, kèm `summary` (bốn chỉ số và tải theo
 * cell); chi tiết `ConfigTenant`; bản ghi danh bạ `RegistryTenant`; xuất danh sách (202 + `Location`,
 * tác vụ hoàn tất sau vài lần thăm dò).
 */

let exports = new Map<string, { job: Job; polls: number; csv: string }>();

function row(t: StoredTenant): TenantRow {
  const { id, code, name, domain, cellCode, tenancyModel, accountCount, policyPacks, uiPack } = t;
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
    status: t.status,
    onboardingStep: TENANT_ONBOARDING_STATUSES.includes(t.status) ? (t.onboardingStep ?? 1) : null,
  };
}

/** Id phiên bản gói giả lập, ổn định theo tên gói. */
const packId = (name: string) =>
  `0192e1c4-5d6e-7f80-9a1b-${[...name]
    .reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 1e12, 7)
    .toString()
    .padStart(12, '0')}`;

/** Tách "tên vX.Y" của UI Pack thành `{ name, version }` theo `ConfigTenant.uiPack`. */
function uiPackRef(p: string): ConfigTenant['uiPack'] {
  if (!p) return undefined;
  const m = /^(.*) v(\d+(?:\.\d+)*)$/.exec(p);
  return m ? { name: m[1], version: m[2] } : { name: p, version: '' };
}

function detail(t: StoredTenant): ConfigTenant {
  const bindings = t.policyPacks.map((p) => {
    const m = /^(.*) v(\d+(?:\.\d+)*)$/.exec(p);
    return {
      flow: p.startsWith('Khối Đảng') ? ('DANG' as const) : ('NHA_NUOC' as const),
      packVersionId: packId(p),
      packCode: m ? m[1] : p,
      version: m ? m[2] : '',
    };
  });
  return {
    id: t.id,
    code: t.code,
    name: t.name,
    sector: t.sector as ConfigTenant['sector'],
    onboardingCode: t.onboarding?.onboardingCode,
    contractRef: t.contractRef,
    contractValidTo: t.contractValidTo,
    flows: t.flows,
    expectedAccounts: t.expectedAccounts,
    maxAccounts: t.maxAccounts,
    accountCount: t.accountCount,
    tenancyModel: t.tenancyModel,
    cellCode: t.cellCode,
    domain: t.domain,
    dataRegion: t.dataRegion,
    policyBindings: bindings,
    uiPack: uiPackRef(t.uiPack),
    statusLog: t.statusLog.map((l, i) => ({
      id: `${t.id}-log-${t.statusLog.length - i}`,
      fromStatus: l.from ?? null,
      toStatus: l.to,
      reason: l.reason ?? '',
      changedAt: l.at,
    })),
    status: t.status,
    onboardingStep: TENANT_ONBOARDING_STATUSES.includes(t.status) ? (t.onboardingStep ?? 1) : null,
    rowVersion: t.rowVersion,
  };
}

function registry(t: StoredTenant): RegistryTenant {
  return {
    id: t.id,
    code: t.code,
    name: t.name,
    sector: t.sector as RegistryTenant['sector'],
    tenancyModel: t.tenancyModel.toUpperCase() as RegistryTenant['tenancyModel'],
    status: t.status,
    flows: t.flows,
    maxAccounts: t.maxAccounts,
    expectedAccounts: t.expectedAccounts,
    accountCount: t.accountCount,
    contractRef: t.contractRef,
    contractValidTo: t.contractValidTo,
    onboardingCode: t.onboarding?.onboardingCode,
    currentCell: {
      id: `cell-${t.cellCode}`,
      code: t.cellCode,
      databaseName: `td_${t.code}`,
      validFrom: t.statusLog.at(-1)?.at ?? '2026-01-15T08:00:00+07:00',
    },
    domains: t.domains.map((d) => ({ ...d, verifiedAt: d.verifiedAt ?? null })),
    rowVersion: t.rowVersion,
  };
}

const norm = (s: string) =>
  s.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/g, 'd').replace(/Đ/g, 'D').toLowerCase();

interface Filter {
  q?: string | null;
  cell?: string | null;
  model?: TenancyModel | null;
  status?: TenantStatus | null;
}

function filtered(f: Filter): StoredTenant[] {
  const q = norm(f.q?.trim() ?? '');
  const { cell, model, status } = f;
  return load().filter(
    (t) =>
      (!q || norm(`${t.name} ${t.code} ${t.domain}`).includes(q)) &&
      (!cell || t.cellCode === cell) &&
      (!model || t.tenancyModel === model) &&
      (!status || t.status === status),
  );
}

const paramFilter = (p: MockRequest['params']): Filter => ({
  q: p.get('q'),
  cell: p.get('cell'),
  model: p.get('model') as TenancyModel | null,
  status: p.get('status') as TenantStatus | null,
});

function list(r: MockRequest): MockResponse {
  const all = filtered(paramFilter(r.params));
  const limit = Math.min(Number(r.params.get('limit') ?? 8) || 8, MAX_PAGE_LIMIT);
  const start = Number(r.params.get('cursor') ?? 0) || 0;
  const items = all.slice(start, start + limit).map(row);
  const next = start + limit < all.length ? String(start + limit) : null;
  return ok({ items, nextCursor: next, summary: stats() });
}

function cells(): CellLoad[] {
  const all = load();
  return fixture.cells.map((c) => {
    const accountCount = all
      .filter((t) => t.cellCode === c.code && t.status !== 'DA_CHAM_DUT')
      .reduce((s, t) => s + t.accountCount, 0);
    const ratio = accountCount / c.capacityAccounts;
    return {
      cellCode: c.code,
      accountCount,
      capacityAccounts: c.capacityAccounts,
      loadPercent: Math.round(ratio * 1000) / 10,
      overThreshold: ratio > CELL_LOAD_THRESHOLD,
    };
  });
}

/** Chỉ số toàn vùng điều phối, không theo bộ lọc của trang. */
function stats(): TenantStats {
  const all = load();
  return {
    activeTenants: all.filter((t) => t.status === 'DANG_VAN_HANH').length,
    tenantLimit: fixture.maxTenants,
    totalAccounts: all.reduce((s, t) => s + t.accountCount, 0),
    accountLimit: fixture.designAccounts,
    onboardingTenants: all.filter((t) => TENANT_ONBOARDING_STATUSES.includes(t.status)).length,
    cellCount: fixture.cells.length,
    cellLoads: cells(),
  };
}

const csvCell = (v: string | number) => `"${String(v).replace(/"/g, '""')}"`;

function startExport(r: MockRequest): MockResponse {
  const rows = filtered((r.body ?? {}) as TenantExportRequest);
  const csv = [
    [
      'Đơn vị triển khai',
      'Mã',
      'Tên miền',
      'Cụm máy chủ',
      'Mức cô lập',
      'Tài khoản',
      'Bộ quy định đánh giá',
      'Bộ giao diện',
      'Trạng thái',
    ],
    ...rows.map((t) => [
      t.name,
      t.code,
      t.domain,
      t.cellCode,
      TENANCY_MODEL_LABEL[t.tenancyModel],
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
  if (!e) return problem(404, 'Không tìm thấy yêu cầu xuất danh sách.');
  e.polls++;
  const id = e.job.id;
  e.job =
    e.polls >= 2
      ? {
          id,
          type: 'EXPORT',
          status: 'HOAN_TAT',
          progress: 100,
          message: 'Đã tạo tệp danh sách đơn vị triển khai',
          resultLocation: `/api/v1/config/tenants/exports/${id}/file`,
        }
      : { id, type: 'EXPORT', status: 'DANG_CHAY', progress: 50, message: 'Đang tổng hợp dữ liệu' };
  return ok(e.job);
}

function exportFile(r: MockRequest): MockResponse {
  const e = exports.get(r.pathParams['jobId']);
  if (!e || e.job.status !== 'HOAN_TAT') return problem(404, 'Tệp xuất chưa sẵn sàng.');
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
    resetTenantStore();
    exports = new Map();
  },
  routes: [
    // tuyến cụ thể của SCR-0102 đứng trước `/config/tenants/:id`
    ...ONBOARDING_ROUTES,
    { method: 'GET', pattern: '/api/v1/config/tenants', handle: list },
    { method: 'POST', pattern: '/api/v1/config/tenants/export', handle: startExport },
    { method: 'GET', pattern: '/api/v1/config/tenants/exports/:jobId', handle: exportJob },
    { method: 'GET', pattern: '/api/v1/config/tenants/exports/:jobId/file', handle: exportFile },
    {
      method: 'GET',
      pattern: '/api/v1/config/tenants/:id',
      handle: (r) => {
        const t = load().find((x) => x.id === r.pathParams['id']);
        if (!t) return problem(404, 'Không tìm thấy đơn vị triển khai.');
        return ok(detail(t));
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/registry/tenants/:id',
      handle: (r) => {
        const t = load().find((x) => x.id === r.pathParams['id']);
        return t ? ok(registry(t)) : problem(404, 'Không tìm thấy đơn vị triển khai.');
      },
    },
  ],
};
