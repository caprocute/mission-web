-- Lớp che thứ hai (SEC-53, TL-04 mục 10.3): thay JWT, số CCCD 12 chữ số, số điện thoại, email bằng *** trong mọi
-- chuỗi của bản ghi. Bỏ qua nhánh kubernetes (siêu dữ liệu) và trace/span (mã hex có thể chứa dãy số dài).
local BO_QUA = { kubernetes = true, trace = true, span = true }

local function che(s)
    s = s:gsub("eyJ[%w_%-]+%.[%w_%-]+%.[%w_%-]+", "***")
    s = s:gsub("[%w%._%%%+%-]+@[%w%.%-]+%.%a%a+", "***")
    s = s:gsub("%f[%d]%d%d%d%d%d%d%d%d%d%d%d%d%f[%D]", "***")
    s = s:gsub("%+84[35789]%d%d%d%d%d%d%d%d%f[%D]", "***")
    s = s:gsub("%f[%d]0[35789]%d%d%d%d%d%d%d%d%f[%D]", "***")
    return s
end

local function duyet(t)
    local doi = false
    for k, v in pairs(t) do
        if type(v) == "string" then
            local m = che(v)
            if m ~= v then t[k] = m; doi = true end
        elseif type(v) == "table" and not BO_QUA[k] then
            if duyet(v) then doi = true end
        end
    end
    return doi
end

function redact_pii(tag, timestamp, record)
    if duyet(record) then
        return 2, timestamp, record
    end
    return 0, timestamp, record
end
