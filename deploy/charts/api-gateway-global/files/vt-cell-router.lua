-- vt-cell-router (DV-01-04, ADR-07, TL-01 "Định tuyến tới cell", TL-04 "GeoDNS và định tuyến tenant tới cell"):
-- plugin của APISIX lớp ngoài ở vùng điều phối toàn cục. Theo Host của request:
--  * tên miền riêng của tenant: tra SVC-03 `GET /api/v1/registry/resolve?host=` rồi chuyển tiếp tới cổng API của cell;
--  * tên miền chung (app.danhgia.vn): khám phá bằng `?tenant=` (mã đơn vị hoặc mã tenant), trả 307 tới URL của cell.
-- Kết quả đệm trong lua_shared_dict `vt-cell-cache` (mặc định 300 giây, theo Cache-Control của hợp đồng). Cookie định
-- tuyến `mission_route` ký HMAC-SHA256 hạn 5 phút, cùng định dạng với gói routecookie của SVC-03
-- (`v1.<b64url JSON {t,c,exp}>.<b64url HMAC>`): cookie hợp lệ và cell đã biết thì đi thẳng, không tra registry.
-- 421 của registry hoặc của cell được chuyển nguyên vẹn kèm Location và xoá mục đệm; registry lỗi thì dùng mục đệm đã
-- quá hạn tươi nhưng còn trong cửa sổ stale_ttl, không có thì 503 + Retry-After: 30.
-- TL-04 mục 11.4: tenant `TAM_NGUNG` vẫn đọc được, lệnh ghi (khác GET, HEAD, OPTIONS) bị chặn ở đây với 403 Problem
-- Details mang `tenantStatus`; đường dẫn trong write_exempt_prefixes (biểu mẫu đăng nhập Keycloak) không bị chặn, vì
-- Keycloak tự chặn đăng nhập của tenant tạm ngừng và quản trị nền tảng vẫn phải vào được. Trạng thái lấy từ cùng mục
-- đệm phân giải, nên có hiệu lực chậm nhất sau cache_ttl; dịch vụ ở cell vẫn chặn ghi ngay (TenantCellRoutingFilter).
local core = require("apisix.core")
local upstream = require("apisix.upstream")
local http = require("resty.http")
local hmac = require("resty.openssl.hmac")
local bit = require("bit")
local ngx = ngx

local COOKIE = "mission_route"
local COOKIE_TTL = 300

local schema = {
    type = "object",
    properties = {
        registry_url = { type = "string", minLength = 1 },
        common_hosts = { type = "array", items = { type = "string" }, default = {} },
        cache_ttl = { type = "integer", minimum = 1, default = 300 },
        discovery_ttl = { type = "integer", minimum = 1, default = 300 },
        stale_ttl = { type = "integer", minimum = 0, default = 3600 },
        negative_ttl = { type = "integer", minimum = 0, default = 30 },
        registry_timeout_ms = { type = "integer", minimum = 50, default = 1000 },
        token_url = { type = "string" },
        client_id = { type = "string" },
        client_secret_env = { type = "string", default = "VT_REGISTRY_CLIENT_SECRET" },
        key_env = { type = "string", default = "VT_ROUTE_COOKIE_KEY" },
        key_previous_env = { type = "string", default = "VT_ROUTE_COOKIE_KEY_PREVIOUS" },
        cookie_domain = { type = "string" },
        cells = { type = "object", additionalProperties = { type = "string" }, default = {} },
        write_exempt_prefixes = { type = "array", items = { type = "string" }, default = { "/realms/" } },
    },
    required = { "registry_url" },
}

local _M = { version = 0.1, priority = 2580, name = "vt-cell-router", schema = schema }

local dict = ngx.shared["vt-cell-cache"]

local function b64u(s) return (ngx.encode_base64(s):gsub("+", "-"):gsub("/", "_"):gsub("=", "")) end
local function unb64u(s)
    s = s:gsub("-", "+"):gsub("_", "/")
    local pad = #s % 4
    if pad > 0 then s = s .. string.rep("=", 4 - pad) end
    return ngx.decode_base64(s)
end

-- Khoá HMAC: base64 trong biến môi trường (External Secrets từ OpenBao), như ROUTING_COOKIE_KEY của SVC-03.
local keys_cache
local function keys(conf)
    if keys_cache then return keys_cache end
    local out = {}
    for _, name in ipairs({ conf.key_env, conf.key_previous_env }) do
        local raw = os.getenv(name)
        local k = raw and raw ~= "" and ngx.decode_base64(raw)
        if k and #k >= 32 then out[#out + 1] = k end
    end
    keys_cache = out
    return out
end

local function mac(key, msg)
    local h = hmac.new(key, "sha256")
    return h:final(msg)
end

-- So sánh hằng thời gian.
local function equal(a, b)
    if #a ~= #b then return false end
    local d = 0
    for i = 1, #a do d = bit.bor(d, bit.bxor(a:byte(i), b:byte(i))) end
    return d == 0
end

local function sign_cookie(conf, tenant, cell)
    local k = keys(conf)[1]
    if not k then return nil end
    local head = "v1." .. b64u(core.json.encode({ t = tenant, c = cell, exp = ngx.time() + COOKIE_TTL }))
    return head .. "." .. b64u(mac(k, head))
end

local function verify_cookie(conf, value)
    if not value then return nil end
    local v, p, s = value:match("^(v1)%.([%w_-]+)%.([%w_-]+)$")
    if not v then return nil end
    local sig, head = unb64u(s), v .. "." .. p
    local ok = false
    for _, k in ipairs(keys(conf)) do
        if sig and equal(sig, mac(k, head)) then ok = true end
    end
    if not ok then return nil end
    local c = core.json.decode(unb64u(p) or "")
    if type(c) ~= "table" or not c.t or not c.c or type(c.exp) ~= "number" or ngx.time() >= c.exp then
        return nil
    end
    return c
end

local function problem(status, title, code, extra_headers, extra)
    core.response.set_header("Content-Type", "application/problem+json")
    for k, v in pairs(extra_headers or {}) do core.response.set_header(k, v) end
    local body = { type = "about:blank", title = title, status = status, code = code }
    for k, v in pairs(extra or {}) do body[k] = v end
    return status, body
end

local READ_METHODS = { GET = true, HEAD = true, OPTIONS = true }

-- Tenant tạm ngừng chỉ được đọc (TL-04 mục 11.4). Trả nil nếu cho qua, hoặc (status, body) của 403.
local function block_suspended_write(conf, ctx, status)
    if status ~= "TAM_NGUNG" or READ_METHODS[ngx.req.get_method()] then return nil end
    local uri = ctx.var.uri or ""
    for _, p in ipairs(conf.write_exempt_prefixes) do
        if uri:sub(1, #p) == p then return nil end
    end
    core.response.set_header("Cache-Control", "no-store")
    return problem(403, "Đơn vị của bạn đang tạm ngừng sử dụng hệ thống. Bạn vẫn xem được dữ liệu nhưng chưa thể"
                        .. " cập nhật cho tới khi đơn vị được mở lại.", "COMMON-403-FORBIDDEN", nil,
                   { tenantStatus = status })
end

-- Token client credentials của client nội bộ gọi registry, đệm tới trước khi hết hạn 30 giây.
local function service_token(conf)
    if not conf.token_url or conf.token_url == "" then return nil end
    local cached = dict:get("tok:" .. conf.client_id)
    if cached then return cached end
    local res, err = http.new():request_uri(conf.token_url, {
        method = "POST",
        body = ngx.encode_args({ grant_type = "client_credentials", client_id = conf.client_id,
                                 client_secret = os.getenv(conf.client_secret_env) or "" }),
        headers = { ["Content-Type"] = "application/x-www-form-urlencoded" },
        ssl_verify = true,
    })
    local body = res and res.status == 200 and core.json.decode(res.body)
    if type(body) ~= "table" or not body.access_token then
        core.log.error("vt-cell-router: khong lay duoc token dich vu: ", err or (res and res.status))
        return nil
    end
    dict:set("tok:" .. conf.client_id, body.access_token, math.max((body.expires_in or 60) - 30, 5))
    return body.access_token
end

local function remember_cell(code, url)
    if code and url then dict:set("c:" .. code, url) end
end

-- Trả (mục phân giải, nil) hoặc (nil, status, title, code, headers).
local function resolve(conf, kind, value, ttl, ctx)
    local key = (kind == "host" and "h:" or "t:") .. value
    ctx.vt_cache_key = key
    local raw = dict:get(key)
    local entry = raw and core.json.decode(raw)
    if entry and ngx.now() - entry.at < entry.ttl then
        ctx.vt_cache = "hit"
        return entry
    end
    if dict:get("n:" .. key) then
        return nil, 404, "Không tìm thấy đơn vị", "REGISTRY-404"
    end
    ctx.vt_cache = "miss"
    local headers = { Accept = "application/json" }
    local tok = service_token(conf)
    if tok then headers.Authorization = "Bearer " .. tok end
    local client = http.new()
    client:set_timeout(conf.registry_timeout_ms)
    local res, err = client:request_uri(conf.registry_url .. "/api/v1/registry/resolve?" ..
                                         ngx.encode_args({ [kind] = value }),
                                         { method = "GET", headers = headers, ssl_verify = true })
    local status = res and res.status
    if status == 200 then
        local b = core.json.decode(res.body)
        if type(b) == "table" and type(b.cell) == "table" and b.cell.apiBaseUrl and b.cell.code then
            local e = { tenant = b.tenantId, cell = b.cell.code, url = b.cell.apiBaseUrl, status = b.status,
                        at = ngx.now(), ttl = math.min(ttl, tonumber(b.cacheTtlSeconds) or ttl) }
            dict:set(key, core.json.encode(e), e.ttl + conf.stale_ttl)
            remember_cell(e.cell, e.url)
            return e
        end
        core.log.error("vt-cell-router: phan hoi registry sai dinh dang")
    elseif status == 421 then
        dict:delete(key)
        return nil, 421, "Đơn vị đã chuyển sang hệ thống phục vụ khác", "REGISTRY-421-TENANT-MOVED",
               { Location = res.headers["Location"] }
    elseif status == 404 then
        if conf.negative_ttl > 0 then dict:set("n:" .. key, true, conf.negative_ttl) end
        return nil, 404, "Không tìm thấy đơn vị", "REGISTRY-404"
    elseif status == 400 or status == 422 then
        return nil, 400, "Yêu cầu không hợp lệ", "REGISTRY-400"
    else
        core.log.error("vt-cell-router: registry loi: ", err or status)
    end
    -- Registry ngừng hoặc lỗi: dùng mục đệm còn trong cửa sổ stale_ttl.
    if entry then
        ctx.vt_cache = "stale"
        return entry
    end
    return nil, 503, "Hệ thống tạm thời gián đoạn, vui lòng thử lại sau", "REGISTRY-503", { ["Retry-After"] = "30" }
end

local function parse_url(url)
    local scheme, host, port, path = url:match("^(https?)://([^/:]+):?(%d*)(.*)$")
    if not scheme then return nil end
    port = tonumber(port) or (scheme == "https" and 443 or 80)
    return { scheme = scheme, host = host, port = port, path = (path or ""):gsub("/$", "") }
end

local function proxy_to(ctx, url)
    local u = parse_url(url)
    if not u then return 502, { type = "about:blank", title = "Cấu hình cell không hợp lệ", status = 502 } end
    local node = { host = u.host, port = u.port, weight = 1 }
    local ip = core.resolver.parse_domain(u.host)
    if ip then node.domain, node.host = u.host, ip end
    local up_conf = { type = "roundrobin", nodes = { node }, scheme = u.scheme, pass_host = "node",
                      timeout = { connect = 2, send = 10, read = 10 } }
    local ok, err = upstream.check_schema(up_conf)
    if not ok then
        core.log.error("vt-cell-router: upstream sai: ", err)
        return 502, { type = "about:blank", title = "Cấu hình cell không hợp lệ", status = 502 }
    end
    up_conf.parent = ctx.matched_route
    upstream.set(ctx, "vt-cell#" .. u.scheme .. "://" .. u.host .. ":" .. u.port, ctx.conf_version, up_conf)
    if u.scheme == "https" then upstream.set_scheme(ctx, up_conf) end
    if u.path ~= "" then ctx.var.upstream_uri = u.path .. ctx.var.request_uri end
    core.request.set_header(ctx, "X-Forwarded-Host", ctx.var.host)
end

local function lower_host(ctx)
    return (ctx.var.host or ""):lower()
end

function _M.check_schema(conf)
    return core.schema.check(schema, conf)
end

function _M.access(conf, ctx)
    local host = lower_host(ctx)
    local cookie = verify_cookie(conf, ctx.var.cookie_mission_route)
    local common = false
    for _, h in ipairs(conf.common_hosts) do
        if h:lower() == host then common = true end
    end

    if common then
        local tenant = ctx.var.arg_tenant
        local target
        if tenant and tenant ~= "" then
            if #tenant > 64 then return problem(400, "Yêu cầu không hợp lệ", "REGISTRY-400") end
            local e, st, title, code, hdr = resolve(conf, "tenant", tenant, conf.discovery_ttl, ctx)
            if not e then return problem(st, title, code, hdr) end
            target, ctx.vt_set_cookie = e.url, sign_cookie(conf, e.tenant, e.cell)
        elseif cookie then
            target = dict:get("c:" .. cookie.c) or conf.cells[cookie.c]
        end
        if not target then
            return problem(400, "Vui lòng nhập mã đơn vị hoặc thư điện tử công vụ", "REGISTRY-400")
        end
        -- Bỏ tham số tenant khỏi URL chuyển hướng; đường dẫn và truy vấn khác giữ nguyên.
        local args = ngx.req.get_uri_args()
        args.tenant = nil
        local q = ngx.encode_args(args)
        local loc = target:gsub("/$", "") .. ctx.var.uri .. (q ~= "" and ("?" .. q) or "")
        if ctx.vt_set_cookie then core.response.add_header("Set-Cookie", _M.cookie_header(conf, ctx.vt_set_cookie)) end
        core.response.set_header("Location", loc)
        core.response.set_header("Cache-Control", "no-store")
        return 307
    end

    local url = cookie and (dict:get("c:" .. cookie.c) or conf.cells[cookie.c])
    local status
    if url then
        ctx.vt_cache = "cookie"
        ctx.vt_cache_key = "h:" .. host
        -- Đi thẳng bằng cookie: trạng thái tenant lấy từ mục đệm của tên miền nếu còn.
        local raw = dict:get(ctx.vt_cache_key)
        local cached = raw and core.json.decode(raw)
        status = type(cached) == "table" and cached.status or nil
    else
        local e, st, title, code, hdr = resolve(conf, "host", host, conf.cache_ttl, ctx)
        if not e then return problem(st, title, code, hdr) end
        url, status = e.url, e.status
        ctx.vt_set_cookie = sign_cookie(conf, e.tenant, e.cell)
    end
    local bs, body = block_suspended_write(conf, ctx, status)
    if bs then return bs, body end
    return proxy_to(ctx, url)
end

function _M.cookie_header(conf, value)
    local h = COOKIE .. "=" .. value .. "; Path=/; Max-Age=" .. COOKIE_TTL .. "; HttpOnly; Secure; SameSite=Lax"
    if conf.cookie_domain and conf.cookie_domain ~= "" then h = h .. "; Domain=" .. conf.cookie_domain end
    return h
end

function _M.header_filter(conf, ctx)
    if ctx.vt_cache then core.response.set_header("X-Vt-Cell-Cache", ctx.vt_cache) end
    -- Cell trả 421 (tenant đã chuyển): giữ nguyên mã và Location, xoá mục đệm và cookie định tuyến.
    if ngx.status == 421 and (ctx.var.upstream_status or "") ~= "" then
        if ctx.vt_cache_key then dict:delete(ctx.vt_cache_key) end
        core.response.add_header("Set-Cookie", COOKIE .. "=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax")
        return
    end
    if ctx.vt_set_cookie and ngx.status < 400 and ngx.status ~= 307 then
        core.response.add_header("Set-Cookie", _M.cookie_header(conf, ctx.vt_set_cookie))
    end
end

return _M
