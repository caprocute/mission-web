# Quản trị khoá qua PAM (INF-27; SEC-42, SEC-11): đổi chính sách, cấu hình và xoay khoá Transit, xem thiết bị kiểm
# toán và trạng thái niêm phong. Token chỉ dùng được từ dải proxy PAM (token_bound_cidrs), sống 1 giờ, tối đa 4 giờ.
# Không có quyền giải mã hay lấy khoá dữ liệu: quản trị khoá không đọc được dữ liệu C2 (SEC-58). Mở niêm phong thủ
# công và tạo root token dùng mảnh khoá theo quy trình phá kính (ops/runbooks/pam-pha-kinh.md), không qua chính sách này.
# Người quản trị khoá không tự sửa được chính sách của mình và của PAM (deny ở cuối); mọi lần ghi chính sách nằm trong
# kiểm toán OpenBao, SOC đối chiếu với thay đổi trong Git (deploy/openbao).
path "sys/policies/acl"            { capabilities = ["list"] }
path "sys/policies/acl/*"          { capabilities = ["create", "read", "update", "list"] }
path "sys/audit"                   { capabilities = ["read", "sudo"] }
path "sys/seal-status"             { capabilities = ["read"] }
path "sys/key-status"              { capabilities = ["read"] }
path "sys/rotate"                  { capabilities = ["update", "sudo"] }
path "sys/mounts"                  { capabilities = ["read"] }
path "transit/keys"                { capabilities = ["list"] }
path "transit/keys/+"              { capabilities = ["read"] }
path "transit/keys/+/config"       { capabilities = ["update"] }
path "transit/keys/+/rotate"       { capabilities = ["update"] }
path "transit/decrypt/*"           { capabilities = ["deny"] }
path "transit/datakey/*"           { capabilities = ["deny"] }
path "transit/export/*"            { capabilities = ["deny"] }
path "transit/backup/*"            { capabilities = ["deny"] }
path "sys/policies/acl/quan-tri-khoa" { capabilities = ["deny"] }
path "sys/policies/acl/pam-*"      { capabilities = ["deny"] }
