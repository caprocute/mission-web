# mission-core (SVC-05): bí mật tĩnh của mình, người dùng PostgreSQL động, mã hoá phong bì bằng KEK theo tenant
# (TL-04 mục 9.4, SEC-10, SEC-11). Không có quyền tạo, xoay hay xoá khoá.
path "kv/data/@CELL@/mission-core/*"            { capabilities = ["read"] }
path "database/creds/pg-core-mission"            { capabilities = ["read"] }
path "transit/encrypt/kek-tenant-*"              { capabilities = ["update"] }
path "transit/decrypt/kek-tenant-*"              { capabilities = ["update"] }
path "transit/datakey/wrapped/kek-tenant-*"      { capabilities = ["update"] }
path "transit/rewrap/kek-tenant-*"               { capabilities = ["update"] }
path "sys/leases/renew"                          { capabilities = ["update"] }
