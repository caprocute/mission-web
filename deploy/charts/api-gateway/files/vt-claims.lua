-- vt-claims (TL-04 mục 6.8): đọc JWT mà openid-connect đã kiểm chữ ký qua JWKS (độ ưu tiên cao hơn nên chạy
-- trước), từ chối token có claim cell khác cell của cổng, và đăng ký biến vt_tenant, vt_sub làm khoá giới hạn.
local core = require("apisix.core")
local ngx = ngx

local schema = {
    type = "object",
    properties = {
        required_cell = { type = "string", minLength = 1 },
        cell_claim = { type = "string", default = "cell" },
    },
    required = { "required_cell" },
}

local _M = {
    version = 0.1,
    priority = 2550, -- sau openid-connect (2599), trước limit-count (1002), limit-req (1001)
    name = "vt-claims",
    schema = schema,
}

local TENANT_CLAIMS = { "tenant_id", "tenant" }

local function claim_tenant(ctx)
    local c = ctx.vt_claims
    if not c then return nil end
    for _, k in ipairs(TENANT_CLAIMS) do
        if c[k] ~= nil then return tostring(c[k]) end
    end
    return nil
end

core.ctx.register_var("vt_tenant", claim_tenant)
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

function _M.check_schema(conf)
    return core.schema.check(schema, conf)
end

function _M.rewrite(conf, ctx)
    local auth = core.request.header(ctx, "Authorization")
    local token = auth and auth:match("^[Bb]earer%s+(%S+)$")
    local payload = token and token:match("^[^.]+%.([^.]+)%.")
    local raw = payload and b64url_decode(payload)
    local claims = raw and core.json.decode(raw)
    if type(claims) ~= "table" then
        return problem(401, "Phiên đăng nhập không hợp lệ")
    end
    if tostring(claims[conf.cell_claim] or "") ~= conf.required_cell then
        core.log.warn("vt-claims: tu choi token cell=", tostring(claims[conf.cell_claim]))
        return problem(403, "Phiên đăng nhập không thuộc cụm này")
    end
    ctx.vt_claims = claims
end

return _M
