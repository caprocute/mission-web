-- vt-waf-body (DV-01-02, SEC-34): APISIX chỉ chuyển thân request cho plugin Wasm khi biến
-- $wasm_process_req_body khác rỗng sau proxy_on_http_request_headers (apisix/wasm.lua), mà coraza-proxy-wasm viết
-- cho Envoy không đặt biến này, nên Coraza chỉ thấy URI và header. Plugin này đặt biến ở pha rewrite (trước pha
-- access của coraza-filter) cho thân JSON, biểu mẫu, XML không quá max_bytes, để CRS kiểm cả thân request.
local core = require("apisix.core")
local ngx = ngx

local schema = {
    type = "object",
    properties = {
        content_types = {
            type = "array",
            items = { type = "string", minLength = 1 },
            default = { "application/json", "application/x-www-form-urlencoded", "multipart/form-data",
                        "application/xml", "text/xml", "application/problem+json" },
        },
        max_bytes = { type = "integer", minimum = 1, default = 1048576 },
    },
}

local _M = {
    version = 0.1,
    priority = 8001, -- cao hơn coraza-filter (7999)
    name = "vt-waf-body",
    schema = schema,
}

function _M.check_schema(conf)
    return core.schema.check(schema, conf)
end

function _M.rewrite(conf, ctx)
    local ct = core.request.header(ctx, "Content-Type")
    if not ct then
        return
    end
    ct = ct:lower()
    local match = false
    for _, p in ipairs(conf.content_types) do
        if core.string.has_prefix(ct, p) then
            match = true
            break
        end
    end
    if not match then
        return
    end
    -- Thân lớn hơn ngưỡng không đưa vào WAF; client-control của tuyến trả 413 (tuyến JSON tối đa 1 MB).
    local len = tonumber(core.request.header(ctx, "Content-Length") or "")
    if len and len > conf.max_bytes then
        return
    end
    ngx.var.wasm_process_req_body = "1"
end

return _M
