// DV-15-03 · QT-16 (BR-16-01, BR-16-02) → SVC-15 (ADR-14) · TL-03 "Phân quyền phía đọc": cấu hình Cube.
//
// - checkAuth: chỉ nhận JWT HS256 ký bằng CUBEJS_API_SECRET, có tenant (UUID) và org_path; analytics-api (DV-15-04)
//   cấp token ngắn hạn này từ token Keycloak của người xem. Thiếu ngữ cảnh thì từ chối (403).
// - queryRewrite: thêm bộ lọc tenant cho mọi cube trong truy vấn và bộ lọc nhánh cây tổ chức (org_path bằng, hoặc là
//   tiền tố, của đường dẫn đơn vị trên cây Đảng hoặc cây chuyên môn) cho mọi cube gắn đơn vị.
// - driverFactory: mỗi tenant một driver ClickHouse đặt SQL_tenant_id, để row policy của kho (V009) lọc thêm một lớp;
//   pre-aggregation của mỗi tenant nằm trong CSDL riêng <db>_pa_<tenant>.
// - checkSqlAuth, canSwitchSqlUser (DV-15-05): SQL API (cổng Postgres wire) chỉ cho tài khoản kỹ thuật của Superset
//   đăng nhập; tài khoản này không có ngữ cảnh an ninh nên chỉ đọc được danh mục, mọi truy vấn số liệu bị từ chối.
//   Superset nhúng gắn điều kiện RLS `__user = '<JWT>'` từ guest token; Cube đổi người dùng sang JWT đó (HS256 ký bằng
//   CUBEJS_API_SECRET, cùng dạng token REST) và áp đúng queryRewrite như REST.
// Chỉ các cube, measure, dimension trong model/ được truy vấn; không có SQL tự do (BR-16-02).

const ClickHouseDriver = require('@cubejs-backend/clickhouse-driver');
const jwt = require('jsonwebtoken');
const sqlstring = require('sqlstring');
const { CubejsHandlerError } = require('@cubejs-backend/api-gateway');

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const ORG_PATH = /^(\/[a-z0-9][a-z0-9._-]*)+$/;
const DB = process.env.CUBEJS_DB_NAME;
// Cube gắn đơn vị: phải lọc theo nhánh cây tổ chức. Cube khác chỉ lọc theo tenant.
const SCOPED = new Set(['org_unit', 'task', 'okr', 'okr_checkin', 'appraisal', 'score', 'rating', 'evidence']);
const TENANT_ONLY = new Set(['period', 'flow', 'subject_group', 'data_freshness']);

// Driver ClickHouse của một tenant. Hai điểm khác driver gốc:
// - SQL_tenant_id nằm trong cấu hình mặc định của client, nên áp cho cả câu lệnh command (DDL, dựng pre-aggregation),
//   không chỉ cho truy vấn đọc;
// - câu lệnh DDL, ghi (dựng pre-aggregation bằng CREATE TABLE ... AS SELECT, thêm chỉ mục bằng ALTER TABLE) chạy bằng
//   command: ClickHouse 25.8 trả thân rỗng cho các câu lệnh này, driver gốc gửi qua query định dạng JSON nên lỗi
//   "Unexpected end of JSON input".
class TenantClickHouseDriver extends ClickHouseDriver {
  constructor(tenant) {
    super({});
    this.tenant = tenant;
    if (tenant) {
      this.config.clickhouseSettings.SQL_tenant_id = tenant;
      this.client = this.createClient(Number(process.env.CUBEJS_DB_MAX_POOL || 8));
    }
  }

  guard() {
    if (!this.tenant) throw new ForbiddenError('Thiếu ngữ cảnh an ninh: tenant');
  }

  async command(sql) {
    this.guard();
    return super.command(sql);
  }

  async query(sql, values, options) {
    this.guard();
    if (/^\s*(ALTER|CREATE|DROP|INSERT|TRUNCATE|RENAME)\b/i.test(sql)) {
      await this.command(sqlstring.format(sql, values || []));
      return [];
    }
    return super.query(sql, values, options);
  }
}

class ForbiddenError extends CubejsHandlerError {
  constructor(message) {
    super(403, 'Forbidden', message);
  }
}

function tenantOf(securityContext) {
  const tenant = securityContext && securityContext.tenant;
  if (typeof tenant !== 'string' || !UUID.test(tenant)) {
    throw new ForbiddenError('Thiếu ngữ cảnh an ninh: tenant');
  }
  return tenant;
}

function verifyToken(token) {
  try {
    return jwt.verify(token, process.env.CUBEJS_API_SECRET, { algorithms: ['HS256'] });
  } catch (e) {
    throw new ForbiddenError('Token không hợp lệ');
  }
}

function tenantOrMeta(securityContext) {
  const tenant = securityContext && securityContext.tenant;
  return typeof tenant === 'string' && UUID.test(tenant) ? tenant : 'META';
}

function scopeOf(securityContext) {
  const tenant = tenantOf(securityContext);
  const orgPath = securityContext.org_path;
  if (typeof orgPath !== 'string' || !ORG_PATH.test(orgPath)) {
    throw new ForbiddenError('Thiếu ngữ cảnh an ninh: org_path');
  }
  return { tenant, orgPath };
}

function members(query) {
  const out = [...(query.measures || []), ...(query.dimensions || []), ...(query.segments || [])];
  for (const t of query.timeDimensions || []) out.push(t.dimension);
  const walk = (filters) => {
    for (const f of filters || []) {
      if (f.or) walk(f.or);
      else if (f.and) walk(f.and);
      else out.push(f.member || f.dimension);
    }
  };
  walk(query.filters);
  const order = Array.isArray(query.order) ? query.order.map((o) => o[0]) : Object.keys(query.order || {});
  return out.concat(order).filter(Boolean);
}

function branchFilter(orgPath) {
  const or = [];
  for (const col of ['org_unit.org_path_dang', 'org_unit.org_path_chuyen_mon']) {
    or.push({ member: col, operator: 'equals', values: [orgPath] });
    or.push({ member: col, operator: 'startsWith', values: [`${orgPath}/`] });
  }
  return { or };
}

module.exports = {
  checkAuth: (req, auth) => {
    const token = typeof auth === 'string' ? auth.replace(/^Bearer\s+/i, '') : '';
    const payload = verifyToken(token);
    const { tenant, orgPath } = scopeOf(payload);
    req.securityContext = { tenant, org_path: orgPath, sub: payload.sub };
  },

  // Đăng nhập SQL API: chỉ tài khoản kỹ thuật (mật khẩu so khớp trong Cube). Đổi người dùng qua __user gọi lại hàm
  // này với password = null: khi đó user phải là JWT hợp lệ, ngữ cảnh an ninh lấy từ JWT.
  checkSqlAuth: async (req, user, password) => {
    const sqlUser = process.env.CUBEJS_SQL_USER;
    if (password === null && user !== sqlUser) {
      const payload = verifyToken(user);
      const { tenant, orgPath } = scopeOf(payload);
      return { securityContext: { tenant, org_path: orgPath, sub: payload.sub }, skipPasswordCheck: true };
    }
    if (!sqlUser || user !== sqlUser || !process.env.CUBEJS_SQL_PASSWORD) {
      throw new ForbiddenError('Tài khoản SQL không hợp lệ');
    }
    return { password: process.env.CUBEJS_SQL_PASSWORD, securityContext: {} };
  },

  canSwitchSqlUser: async (current, newUser) => Boolean(process.env.CUBEJS_SQL_USER)
    && current === process.env.CUBEJS_SQL_USER && newUser !== current,

  queryRewrite: (query, { securityContext }) => {
    const { tenant, orgPath } = scopeOf(securityContext);
    const all = members(query);
    if (all.some((m) => m.endsWith('.tenant_id'))) throw new ForbiddenError('Không truy vấn được trường nội bộ');
    const cubes = new Set(all.map((m) => m.split('.')[0]));
    if (cubes.size === 0) throw new ForbiddenError('Truy vấn rỗng');
    const filters = [...(query.filters || [])];
    let scoped = false;
    for (const cube of cubes) {
      if (!SCOPED.has(cube) && !TENANT_ONLY.has(cube)) throw new ForbiddenError(`Không có chỉ số ${cube}`);
      filters.push({ member: `${cube}.tenant_id`, operator: 'equals', values: [tenant] });
      scoped = scoped || SCOPED.has(cube);
    }
    if (scoped) filters.push(branchFilter(orgPath));
    return { ...query, filters };
  },

  // Phiên SQL của tài khoản kỹ thuật không có tenant: chỉ dùng bản biên dịch chung để đọc danh mục (mô hình giống nhau
  // giữa các tenant); driver và pre-aggregation vẫn bắt buộc tenant nên không chạy được truy vấn số liệu.
  contextToAppId: ({ securityContext }) => `CUBE_APP_${tenantOrMeta(securityContext)}`,
  contextToOrchestratorId: ({ securityContext }) => `CUBE_APP_${tenantOrMeta(securityContext)}`,

  // Phiên kỹ thuật (META) vẫn cần một driver để Cube biết loại CSDL khi mở kết nối SQL API, nhưng driver đó từ chối
  // mọi câu lệnh.
  driverFactory: ({ securityContext }) => {
    const tenant = tenantOrMeta(securityContext);
    return new TenantClickHouseDriver(tenant === 'META' ? null : tenant);
  },

  preAggregationsSchema: ({ securityContext }) => `${DB}_pa_${tenantOrMeta(securityContext).replace(/-/g, '')}`,

  // Danh sách tenant của cell cần làm mới pre-aggregation (mỗi 5 phút theo refresh_key của model).
  scheduledRefreshContexts: async () => (process.env.CUBE_REFRESH_TENANTS || '')
    .split(',')
    .map((t) => t.trim())
    .filter((t) => UUID.test(t))
    .map((tenant) => ({ securityContext: { tenant } })),
};
