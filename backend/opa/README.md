# Gói chính sách OPA `okrkpi` (NEN-040)

Chính sách uỷ quyền tập trung theo ADR-21 và TL-01 "Uỷ quyền tập trung bằng ABAC": OPA chạy cùng pod với service, service hỏi qua localhost, bundle có chữ ký, OPA lỗi thì service từ chối. Quyền theo vai trò không viết tay mà sinh từ `docs/phan-quyen/ma-tran.json`, cùng nguồn với bảng quyền giao diện (`@vt/auth`) và trang `wiki/15a`, nên giao diện và máy chủ không lệch nhau.

| Tệp | Nội dung | Sửa tay |
|---|---|---|
| `okrkpi/authz.rego` | Gói `okrkpi.authz`: RBAC, ABAC cây tổ chức và phạm vi, luồng, mức dữ liệu, tách vai trò, bốn mắt, bước lên | Có |
| `okrkpi/generated/data.json` | `data.okrkpi.generated`: vai trò, chức năng, API, mức dữ liệu, ràng buộc đã chuẩn hoá | Không, sinh bằng `gen_matrix.py --rego` |
| `okrkpi/matrix_cases_test.rego` | Ca kiểm thử theo từng ô ma trận, chia theo QT | Không, sinh cùng lúc |
| `okrkpi/matrix_test.rego` | Bộ chạy ca kiểm thử ma trận (dựng đầu vào theo phạm vi của ô) | Có |
| `okrkpi/authz_test.rego` | Ca tay cho cây tổ chức, luồng, C0..C3, AAL2, bước lên, tách vai trò, bốn mắt, cờ | Có |
| `.manifest` | Gốc bundle `okrkpi`, Rego v1 | Có |
| `build-bundle.sh` | Kiểm tra, kiểm thử, đóng gói, ký | Có |
| `tools/doi-chieu-hop-dong.py` | Đối chiếu `x-mission.roles/scope` của hợp đồng với chính sách | Có |

## Sinh lại sau khi sửa ma trận

```bash
python3 docs/phan-quyen/gen_matrix.py              # xlsx, wiki/15a, TypeScript như cũ
python3 docs/phan-quyen/gen_matrix.py --rego       # chỉ backend/opa/okrkpi/generated/data.json và matrix_cases_test.rego
python3 docs/phan-quyen/gen_matrix.py --rego-check # CI: báo lệch nếu quên sinh lại
```

`--rego` tất định (khoá sắp xếp, không có thời điểm sinh). Ba mươi hai dòng `rang_buoc` của ma trận là văn bản tự do, nên `gen_matrix.py` có bảng `RANG_BUOC_MAP` chuẩn hoá từng dòng (theo STT của sheet SoD) thành quy tắc máy đọc được; bảng ghi lại `scr` và `loai` của dòng nguồn, và bộ sinh dừng với lỗi khi ma trận thêm, bớt hay đổi dòng mà bảng chưa cập nhật. Tên vai trò AAL2 (`aal2_roles`) và vai trò nhạy cảm cần duyệt thứ hai (`sensitive_roles`) được đọc từ chính văn bản quy tắc (BR-00-01 và dòng SCR-0105), không đặt danh sách trong mã.

## Hợp đồng đầu vào (cho NEN-014)

Service hỏi `POST /v1/data/okrkpi/authz/decision` với `input`:

| Trường | Kiểu | Ý nghĩa |
|---|---|---|
| `subject.kind` | `"user"` (mặc định) hoặc `"client"` | `"client"` là AC-11 qua client credentials, khớp vai trò `(client credentials)` của ma trận |
| `subject.user_id`, `subject.tenant_id` | chuỗi | Từ JWT đã xác thực lại |
| `subject.roles` | mảng chuỗi | Realm role của phân công đang chọn (tên baseline, kể cả vai trò con như `wf_template_author`) |
| `subject.unit_id` | chuỗi | Đơn vị của phân công đang chọn; thiếu thì dùng `data.org[tenant].managed_units[user_id]` |
| `subject.flows` | mảng `DANG`, `NHA_NUOC` | Luồng mà phân công cho phép |
| `subject.acr`, `subject.auth_time` | chuỗi, giây Unix | Mức xác thực và thời điểm xác thực; `acr = "aal2"` là phiên AAL2 |
| `action` | chuỗi | Khoá chức năng của ma trận, dạng `SCR-qqnn.<khoá>` (ví dụ `SCR-0703.xac-nhan`) |
| `request.module`, `request.method` | chuỗi | Module API (`/api/v1/<module>`) và phương thức HTTP; có thể gửi cùng `action`, khi đó phải qua cả hai |
| `resource.tenant_id`, `resource.owner_unit_id` | chuỗi | Tenant và đơn vị chủ quản của đối tượng |
| `resource.owner_ids`, `resource.assignee_ids` | mảng | Người sở hữu, chủ trì, phối hợp (phạm vi B) |
| `resource.designated_ids`, `resource.delegate_ids` | mảng | Người được giao, chỉ định trên chính đối tượng, và người duyệt thay (phạm vi Gn) |
| `resource.flow` | `DANG` hoặc `NHA_NUOC` | Luồng của đối tượng, nếu có |
| `resource.classification` | `C0`..`C3` | Mức dữ liệu cao nhất của phần được đọc hoặc ghi |
| `resource.recipient_ids` | mảng | Người nhận dữ liệu C3 |
| `resource.participants` | đối tượng nhóm → mảng user_id | Người đã tham gia đối tượng, do service lấy từ lịch sử bước; tên nhóm ở `data.okrkpi.generated.participants` (`initiator`, `acceptor`, `appraiser`, `decider`, `period_locker` là năm vai của TL-02a) |
| `resource.small_unit_exception` | bool | Service đặt khi Policy Pack cho phép ngoại lệ đơn vị nhỏ và người thao tác là cấp trên trực tiếp |
| `resource.granted_role` | chuỗi | Vai trò đang được cấp ở SCR-0105 |
| `resource.concluded` | bool | Cảnh báo SCR-1401 đã có kết luận |
| `context.now` | giây Unix | Nên truyền để quyết định tái lập được; thiếu thì dùng đồng hồ của OPA |

Kết quả `{"allow": bool, "deny": [lý do], "flags": [cờ]}`. `allow` chỉ đúng khi có quyền và `deny` rỗng. Lý do: `role_not_allowed`, `out_of_scope`, `separation_of_duties`, `four_eyes_required`, `step_up_required`, `c3_not_recipient`, `classification_denied`. Cờ là nghĩa vụ service phải làm khi cho phép: `inferred_grant` (quyền đến từ ô suy luận `*`, cần ghi vết để rà lại), `sod_small_unit_exception`, `c2_access_log`, `exclude_c2_fields`, `c2_no_identified_export`, `c2_anonymous_feedback`, `record_delegation`, `act_as_caller`, `ai_no_c2_log`, `ai_on_behalf_of_user`, `second_approval_required`, `ledger_record`.

## Dữ liệu cây tổ chức `data.org`

Không nằm trong bundle này (gốc bundle chỉ là `okrkpi`); CORE-00-03 nạp từ `org_replica.unit_closure_v` theo tenant, cập nhật khi có EVT-14 và EVT-15 (suy luận về cơ chế nạp: bundle thứ hai gốc `org` hoặc `PUT /v1/data/org/<tenant>`).

```json
{
  "<tenant_id>": {
    "subtree": { "<unit_id>": ["<unit_id>", "<đơn vị con>", "<đơn vị cháu>"] },
    "managed_units": { "<user_id>": ["<unit_id>"] }
  }
}
```

`subtree[u]` gồm chính `u` và mọi đơn vị cấp dưới hiện hành (mỗi dòng `ancestor_id → descendant_id` của bảng đóng). Thiếu dữ liệu của tenant thì phạm vi Cd thất bại (fail closed).

## Kiểm tra, đóng gói, ký

```bash
opa fmt --diff backend/opa        # rỗng
opa check --strict backend/opa
opa test backend/opa --coverage   # authz.rego 100 %
OPA=$(command -v opa) backend/opa/build-bundle.sh /tmp/okrkpi-out /duong-dan/khoa-rieng.pem
```

`build-bundle.sh` chạy `--rego-check`, `opa fmt --fail`, `opa check --strict`, `opa test --coverage --threshold 90`, rồi `opa build -b` bỏ tệp kiểm thử, công cụ, tài liệu, gắn `--revision` là commit hiện tại, và ký RS256 nếu có khoá. Khoá ký thật nằm trong OpenBao và chỉ pipeline đọc; không đặt khoá trong repo. Thử ở máy dev bằng khoá tạm sinh ngoài repo:

```bash
openssl genrsa -out /tmp/opa-key/private.pem 2048
openssl rsa -in /tmp/opa-key/private.pem -pubout -out /tmp/opa-key/public.pem
OPA=$(command -v opa) backend/opa/build-bundle.sh /tmp/okrkpi-out /tmp/opa-key/private.pem
opa build -b /tmp/okrkpi-out/okrkpi-bundle.tar.gz --verification-key /tmp/opa-key/public.pem -o /tmp/okrkpi-out/verified.tar.gz
```

## Nạp bundle vào OPA cùng pod

Container OPA cạnh service, chỉ nghe localhost, tải bundle từ kho bundle nội bộ và kiểm chữ ký bằng khoá công khai (cấu hình suy luận theo ADR-21, chi tiết triển khai thuộc INF):

```yaml
services:
  bundle-store: { url: https://bundle-store.internal }
bundles:
  okrkpi:
    service: bundle-store
    resource: bundles/okrkpi-bundle.tar.gz
    polling: { min_delay_seconds: 30, max_delay_seconds: 60 }
    signing: { keyid: okrkpi }
keys:
  okrkpi: { algorithm: RS256, key: "<khoá công khai PEM từ OpenBao>" }
decision_logs: { console: false }
```

```bash
opa run --server --addr 127.0.0.1:8181 --disable-telemetry --config-file /etc/opa/config.yaml
# thử nhanh với tệp bundle: opa run --server --addr 127.0.0.1:8181 --disable-telemetry \
#   --verification-key /tmp/opa-key/public.pem --bundle /tmp/okrkpi-out/okrkpi-bundle.tar.gz
```

OPA từ chối nạp bundle có chữ ký sai, và từ chối `PUT /v1/data/okrkpi/...` vì đường dẫn đó thuộc bundle; dữ liệu `org` nằm ngoài gốc bundle nên nạp riêng được.

## Đối chiếu hợp đồng

```bash
python3 backend/opa/tools/doi-chieu-hop-dong.py --opa $(command -v opa) [--json ket-qua.json] [--strict]
```

Với mỗi thao tác của `contracts/openapi/modules/*.yaml` và mỗi vai trò ở `x-mission.roles`, công cụ hỏi chính sách thật: vai trò có gọi được phương thức ở module đó không, và có chức năng nào của `x-mission.scr` cho vai trò thao tác ở phạm vi `x-mission.scope` không. Chỗ lệch chỉ được liệt kê; sửa hợp đồng hay ma trận cần người phụ trách quyết định.
