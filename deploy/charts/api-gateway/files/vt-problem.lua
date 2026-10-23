-- vt-problem (DV-01-02): phản hồi lỗi do chính cổng sinh ra (limit-conn 503 khi cắt tải theo lớp, client-control
-- 413 khi thân quá lớn) được ghi lại thành thân RFC 9457 application/problem+json kèm Retry-After. Phản hồi đã đi
-- qua upstream, hoặc đã là problem+json (fault-injection của cắt tải mức 3, response-rewrite của 429), giữ nguyên.
local core = require("apisix.core")
local ngx = ngx

local schema = {
    type = "object",
    properties = {
        -- Khoá là mã trạng thái dạng chuỗi ("503", "413").
        problems = {
            type = "object",
            additionalProperties = {
                type = "object",
                properties = {
                    type = { type = "string", minLength = 1 },
                    title = { type = "string", minLength = 1 },
                    detail = { type = "string" },
                    retry_after = { type = "integer", minimum = 0 },
                },
                required = { "type", "title" },
            },
        },
    },
    required = { "problems" },
}

local _M = {
    version = 0.1,
    priority = 896, -- chỉ chạy ở header_filter, body_filter; không trùng response-rewrite (899)
    name = "vt-problem",
    schema = schema,
}

function _M.check_schema(conf)
    return core.schema.check(schema, conf)
end

function _M.header_filter(conf, ctx)
    local status = ngx.status
    local p = conf.problems[tostring(status)]
    if not p or (ngx.var.upstream_status or "") ~= "" then
        return
    end
    local ct = ngx.header["Content-Type"] or ""
    if core.string.has_prefix(ct, "application/problem+json") then
        return
    end
    ctx.vt_problem = core.json.encode({ type = p.type, title = p.title, status = status, detail = p.detail })
    ngx.header["Content-Type"] = "application/problem+json"
    ngx.header["Content-Length"] = nil
    if p.retry_after and not ngx.header["Retry-After"] then
        ngx.header["Retry-After"] = tostring(p.retry_after)
    end
end

function _M.body_filter(conf, ctx)
    if not ctx.vt_problem then
        return
    end
    if ngx.arg[2] then
        ngx.arg[1] = ctx.vt_problem
    else
        ngx.arg[1] = ""
    end
end

return _M
