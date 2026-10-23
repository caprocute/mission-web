-- vt-claims (TL-04 mục 6.8, DV-01-01): kiểm JWT người dùng ở cổng và gắn ngữ cảnh tenant.
--  * Chế độ verify (mặc định của chart): tự kiểm chữ ký JWT bằng JWKS lấy từ discovery của realm. APISIX 3.13
--    openid-connect dùng lua-resty-openidc chỉ nhận thuật toán RS*/HS*, nên không kiểm được ES256 mà realm Mission
--    dùng (TL-01 SVC-01); xem wiki 14 mục DV-01-01. Kiểm alg nằm trong danh sách cho phép, kid có trong JWKS, chữ ký,
--    iss trùng issuer của discovery, exp và nbf (lệch giờ cho phép leeway giây).
--  * Không verify: đọc JWT mà openid-connect (độ ưu tiên cao hơn) đã kiểm chữ ký.
-- Sau đó từ chối token có claim cell khác cell của cổng (403), đăng ký biến vt_tenant, vt_sub làm khoá giới hạn,
-- ghi đè X-Tenant-Id gửi lên upstream bằng claim tenant (bỏ giá trị client tự gửi). Mọi lỗi do cổng sinh trả
-- Problem Details RFC 9457, kể cả 401 do openid-connect sinh.
-- Cookie định tuyến (DV-01-04): cookie `mission_route` do SVC-03 hoặc APISIX lớp ngoài ký HMAC-SHA256. Có cookie thì
-- phải đúng chữ ký (sửa một byte là 403), cùng tenant với token (403) và cùng cell với cổng (421 tenant đã chuyển).
-- Cookie hết hạn bị bỏ qua như không có; request không có cookie (ứng dụng di động, client nội bộ) vẫn qua.
local core = require("apisix.core")
local http = require("resty.http")
local pkey = require("resty.openssl.pkey")
local hmac = require("resty.openssl.hmac")
local bit = require("bit")
local ngx = ngx

local schema = {
    type = "object",
    properties = {
        required_cell = { type = "string", minLength = 1 },
        cell_claim = { type = "string", default = "cell" },
        tenant_claim = { type = "string", default = "tenant_id" },
        tenant_header = { type = "string", default = "X-Tenant-Id" },
        verify = { type = "boolean", default = false },
        discovery = { type = "string" },
        algorithms = { type = "array", items = { type = "string", enum = { "ES256", "ES384", "RS256" } },
                       default = { "ES256" } },
        jwks_ttl = { type = "integer", minimum = 10, default = 300 },
        leeway = { type = "integer", minimum = 0, default = 30 },
        route_cookie = {
            type = "object",
            properties = {
                cell_code = { type = "string", minLength = 1 },
                key_env = { type = "string", default = "VT_ROUTE_COOKIE_KEY" },
                key_previous_env = { type = "string", default = "VT_ROUTE_COOKIE_KEY_PREVIOUS" },
            },
            required = { "cell_code" },
        },
    },
    required = { "required_cell" },
}

local _M = {
    version = 0.2,
    priority = 2550, -- sau openid-connect (2599), trước limit-count (1002), limit-req (1001)
    name = "vt-claims",
    schema = schema,
}

local DIGEST = { ES256 = "sha256", ES384 = "sha384", RS256 = "sha256" }
local RAW_LEN = { ES256 = 64, ES384 = 96 }

core.ctx.register_var("vt_tenant", function(ctx)
    return ctx.vt_tenant
end)
-- vt_kind: nhãn kind (read, write, async) của metric APISIX cho SLI (TL-04 mục 10.4). Tuyến trả 202 khai nhãn
-- vt.io/kind: async; còn lại GET, HEAD là read, phương thức khác là write. Biến đăng ký toàn cục nên dùng được
-- ở plugin prometheus của mọi tuyến, kể cả tuyến không gắn vt-claims.
core.ctx.register_var("vt_kind", function(ctx)
    local route = ctx.matched_route and ctx.matched_route.value
    if route and route.labels and route.labels["vt.io/kind"] == "async" then return "async" end
    local m = ctx.var.request_method
    if m == "GET" or m == "HEAD" then return "read" end
    return "write"
end)
core.ctx.register_var("vt_sub", function(ctx)
    local c = ctx.vt_claims
    return c and c.sub and tostring(c.sub) or nil
end)

local function b64url_decode(s)
    s = s:gsub("-", "+"):gsub("_", "/")
    local pad = #s % 4
    if pad > 0 then s = s .. string.rep("=", 4 - pad) end
    return ngx.decode_base64(s)
end

local function problem(status, title)
    core.response.set_header("Content-Type", "application/problem+json")
    return status, { type = "about:blank", title = title, status = status }
end

-- Bộ đệm JWKS theo worker: { issuer, keys = {kid -> pkey}, expires, tried }.
local cache = {}

local function get_json(url)
    local res, err = http.new():request_uri(url, { method = "GET", ssl_verify = true,
                                                   headers = { Accept = "application/json" } })
    if not res then return nil, err end
    if res.status ~= 200 then return nil, "HTTP " .. res.status end
    return core.json.decode(res.body)
end

local function load_keys(conf)
    local disc, err = get_json(conf.discovery)
    if type(disc) ~= "table" or not disc.jwks_uri or not disc.issuer then
        return nil, "discovery: " .. tostring(err)
    end
    local jwks, jerr = get_json(disc.jwks_uri)
    if type(jwks) ~= "table" or type(jwks.keys) ~= "table" then
        return nil, "jwks: " .. tostring(jerr)
    end
    local keys = {}
    for _, k in ipairs(jwks.keys) do
        if k.kid and (k.use == nil or k.use == "sig") and (k.kty == "EC" or k.kty == "RSA") then
            local pub = { kty = k.kty, crv = k.crv, x = k.x, y = k.y, n = k.n, e = k.e }
            local pk = pkey.new(core.json.encode(pub), { format = "JWK", type = "pu" })
            if pk then keys[k.kid] = { pkey = pk, alg = k.alg, kty = k.kty } end
        end
    end
    return { issuer = disc.issuer, keys = keys }
end

-- Khoá theo kid; kid lạ thì nạp lại JWKS (xoay khoá) nhưng không quá một lần mỗi 10 giây.
local function key_for(conf, kid)
    local now = ngx.now()
    local c = cache[conf.discovery]
    if c and c.expires > now and (c.keys[kid] or c.tried > now - 10) then
        return c, c.keys[kid]
    end
    local fresh, err = load_keys(conf)
    if not fresh then
        core.log.error("vt-claims: khong nap duoc JWKS: ", err)
        if c then c.tried = now; return c, c.keys[kid] end
        return nil, nil
    end
    fresh.expires, fresh.tried = now + conf.jwks_ttl, now
    cache[conf.discovery] = fresh
    return fresh, fresh.keys[kid]
end

local function allowed(conf, alg)
    for _, a in ipairs(conf.algorithms) do
        if a == alg then return true end
    end
    return false
end

-- Trả bảng claim khi token hợp lệ, nil khi không.
local function verified_claims(conf, token)
    local h64, p64, s64 = token:match("^([%w_-]+)%.([%w_-]+)%.([%w_-]+)$")
    if not h64 then return nil, "dang token" end
    local header = core.json.decode(b64url_decode(h64) or "")
    local claims = core.json.decode(b64url_decode(p64) or "")
    local sig = b64url_decode(s64)
    if type(header) ~= "table" or type(claims) ~= "table" or not sig then return nil, "giai ma" end
    local alg = header.alg
    if type(alg) ~= "string" or not allowed(conf, alg) then return nil, "alg " .. tostring(alg) end
    local c, k = key_for(conf, header.kid)
    if not k then return nil, "kid " .. tostring(header.kid) end
    if k.alg and k.alg ~= alg then return nil, "alg khac khoa" end
    if (alg:sub(1, 2) == "ES") ~= (k.kty == "EC") then return nil, "kty" end
    if RAW_LEN[alg] and #sig ~= RAW_LEN[alg] then return nil, "do dai chu ky" end
    local ok = k.pkey:verify(sig, h64 .. "." .. p64, DIGEST[alg], nil,
                             RAW_LEN[alg] and { ecdsa_use_raw = true } or nil)
    if not ok then return nil, "chu ky" end
    if claims.iss ~= c.issuer then return nil, "iss" end
    local now = ngx.time()
    if type(claims.exp) ~= "number" or claims.exp + conf.leeway < now then return nil, "het han" end
    if type(claims.nbf) == "number" and claims.nbf - conf.leeway > now then return nil, "nbf" end
    return claims
end

-- Cookie định tuyến: cùng định dạng gói routecookie của SVC-03 (v1.<b64url JSON {t,c,exp}>.<b64url HMAC>).
local route_keys
local function cookie_keys(rc)
    if route_keys then return route_keys end
    local out = {}
    for _, name in ipairs({ rc.key_env, rc.key_previous_env }) do
        local raw = os.getenv(name)
        local k = raw and raw ~= "" and ngx.decode_base64(raw)
        if k and #k >= 32 then out[#out + 1] = k end
    end
    route_keys = out
    return out
end

local function equal(a, b)
    if #a ~= #b then return false end
    local d = 0
    for i = 1, #a do d = bit.bor(d, bit.bxor(a:byte(i), b:byte(i))) end
    return d == 0
end

-- Trả "ok", "absent" (không có hoặc hết hạn), "bad" (sai định dạng, sai chữ ký), "tenant", "cell".
local function check_route_cookie(rc, value, tenant)
    if not value or value == "" then return "absent" end
    local v, p, s = value:match("^(v1)%.([%w_-]+)%.([%w_-]+)$")
    local sig = s and b64url_decode(s)
    if not v or not sig then return "bad" end
    local ok = false
    for _, k in ipairs(cookie_keys(rc)) do
        if equal(sig, hmac.new(k, "sha256"):final(v .. "." .. p)) then ok = true end
    end
    if not ok then return "bad" end
    local c = core.json.decode(b64url_decode(p) or "")
    if type(c) ~= "table" or not c.t or not c.c or type(c.exp) ~= "number" then return "bad" end
    if ngx.time() >= c.exp then return "absent" end
    if tenant and tostring(c.t) ~= tenant then return "tenant" end
    if tostring(c.c):lower() ~= rc.cell_code:lower() then return "cell" end
    return "ok"
end

function _M.check_schema(conf)
    local ok, err = core.schema.check(schema, conf)
    if not ok then return false, err end
    if conf.verify and not conf.discovery then
        return false, "verify cần discovery"
    end
    return true
end

function _M.rewrite(conf, ctx)
    local auth = core.request.header(ctx, "Authorization")
    local token = auth and auth:match("^[Bb]earer%s+(%S+)$")
    local claims, why
    if not token then
        why = "khong co token"
    elseif conf.verify then
        claims, why = verified_claims(conf, token)
    else
        local payload = token:match("^[^.]+%.([^.]+)%.")
        local raw = payload and b64url_decode(payload)
        claims = raw and core.json.decode(raw)
        if type(claims) ~= "table" then claims, why = nil, "giai ma" end
    end
    if not claims then
        core.log.warn("vt-claims: tu choi token: ", why)
        core.response.set_header("WWW-Authenticate", 'Bearer error="invalid_token"')
        return problem(401, "Phiên đăng nhập không hợp lệ")
    end
    if tostring(claims[conf.cell_claim] or "") ~= conf.required_cell then
        core.log.warn("vt-claims: tu choi token cell=", tostring(claims[conf.cell_claim]))
        return problem(403, "Phiên đăng nhập không thuộc cụm này")
    end
    ctx.vt_claims = claims
    local tenant = claims[conf.tenant_claim]
    ctx.vt_tenant = tenant ~= nil and tostring(tenant) or nil
    if conf.route_cookie then
        local r = check_route_cookie(conf.route_cookie, ctx.var.cookie_mission_route, ctx.vt_tenant)
        if r == "bad" or r == "tenant" then
            core.log.warn("vt-claims: tu choi cookie dinh tuyen: ", r)
            return problem(403, "Phiên làm việc không hợp lệ, vui lòng tải lại trang")
        elseif r == "cell" then
            core.response.set_header("Content-Type", "application/problem+json")
            return 421, { type = "about:blank", title = "Đơn vị đã chuyển sang hệ thống phục vụ khác", status = 421,
                          code = "REGISTRY-421-TENANT-MOVED", detail = "Vui lòng tải lại trang để tiếp tục." }
        end
    end
    -- Không tin header tenant của client: xoá rồi đặt lại từ claim (thiếu claim thì upstream không thấy header).
    core.request.set_header(ctx, conf.tenant_header, ctx.vt_tenant)
end

local UNAUTHORIZED = core.json.encode({
    type = "about:blank", title = "Phiên đăng nhập không hợp lệ", status = 401,
})

-- openid-connect trả 401 không có thân RFC 9457; chỉ viết lại khi phản hồi do cổng sinh (không qua upstream).
function _M.header_filter(conf, ctx)
    if ngx.status == 401 and (ctx.var.upstream_status or "") == "" then
        ctx.vt_problem_body = UNAUTHORIZED
        core.response.set_header("Content-Type", "application/problem+json")
        core.response.set_header("Content-Length", nil)
    end
end

function _M.body_filter(conf, ctx)
    if ctx.vt_problem_body then
        if ngx.arg[2] then
            ngx.arg[1] = ctx.vt_problem_body
        else
            ngx.arg[1] = nil
        end
    end
end

return _M
