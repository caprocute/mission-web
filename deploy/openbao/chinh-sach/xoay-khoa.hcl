# CronJob xoay khoá (SEC-12, OPS-20): đọc tuổi khoá Transit, xoay KEK theo tenant và khoá ký cosign.
# Dấu * của chính sách chỉ đứng cuối đường dẫn, và quy tắc "kek-tenant-*" sẽ thắng mọi quy tắc "+/rotate" (ký tự
# đại diện đứng sau), nên cả hai quy tắc dùng "+": đọc siêu dữ liệu khoá (không có vật liệu khoá vì exportable=false)
# và xoay. Xoay không phá dữ liệu (phiên bản cũ vẫn giải mã được); khoá nào được xoay do kịch bản CronJob chọn.
path "transit/keys/"         { capabilities = ["list"] }
path "transit/keys/+"        { capabilities = ["read"] }
path "transit/keys/+/rotate" { capabilities = ["update"] }
