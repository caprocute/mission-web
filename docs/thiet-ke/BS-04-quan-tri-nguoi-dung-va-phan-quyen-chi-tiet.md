---
title: "Bổ sung BS-04: Quản trị người dùng và phân quyền chi tiết ngay trong ứng dụng – ADR-34"
subtitle: "Nền tảng đánh giá cán bộ theo OKR và KPI · Bổ sung cho TL-01, TL-02a, TL-03 và bộ hợp đồng OpenAPI · Phiên bản 0.2 · 24/10/2026"
---

# Mục đích và phạm vi

Tài liệu này ghi quyết định kiến trúc ADR-34 và đặc tả phần thiết kế còn thiếu để quản trị đơn vị tự quản lý tài khoản cán bộ và phân quyền chi tiết ngay trong Mission, không phụ thuộc hệ thống quản lý nhân sự bên ngoài. Bối cảnh trực tiếp là yêu cầu của anh Hiếu ngày 24/10/2026: "không cần tính năng đồng bộ người dùng về nữa, tạm thời biến nó thành on/off, tập trung phát triển; bổ sung tính năng quản lý người dùng, phân quyền chi tiết ngay trên hệ thống".

Bộ TL hiện coi nguồn nhân sự là hệ thống ngoài: tài khoản cán bộ sinh ra từ đồng bộ CSDL cán bộ, công chức, viên chức qua NDXP, CSDL đảng viên, HRM hoặc SCIM của IdP cơ quan (QT-01 bước 7–8, SCR-0104, DV-04-06, DV-04-07, DV-13-06), còn quản trị tenant chỉ cấp vai trò theo đơn vị (SCR-0105 đến SCR-0107, WEB-058). Khi chưa nối được hệ ngoài, quản trị không có cách nào tạo, sửa hay khoá tài khoản ngoài thao tác trực tiếp trên Keycloak và CSDL, trái với nguyên tắc mọi thay đổi định danh và quyền phải có kiểm soát, có xác thực bước lên và có vết kiểm toán. Ma trận phân quyền cũng chỉ có mười hai vai trò cố định sinh từ `docs/phan-quyen/ma-tran.json`, nên một tenant không thể giao cho một nhóm cán bộ "chỉ một phần" quyền của một vai trò.

Giống BS-01 đến BS-03, tài liệu được viết thành tệp bổ sung riêng và chưa ghép vào các tệp .docx đã phát hành. Mục cuối nêu vị trí đưa vào TL khi phát hành phiên bản tiếp theo.

# ADR-34: Định danh và phân quyền quản trị ngay trong Mission; đồng bộ ngoài là tính năng tuỳ chọn

**Bối cảnh.** Đợt triển khai hiện tại chưa có môi trường thử của HRM, CSDL cán bộ hay IdP cơ quan; dữ liệu thử nạp bằng script. Đồng bộ ngoài vẫn có giá trị cho giai đoạn đưa vào sử dụng diện rộng, nên không xoá mã đã làm. Ngược lại, quản trị trong ứng dụng là điều kiện để mọi tenant dùng được hệ thống ngay, kể cả tenant nhỏ không có hệ nhân sự điện tử.

**Quyết định.**

1. **Đồng bộ ngoài thành tính năng tuỳ chọn, mặc định tắt.** Có ba công tắc nhất quán: thuộc tính `okrkpi.org.external-sync.enabled` của org-service (SVC-04), thuộc tính `okrkpi.integ.sync-run.enabled` của integration-gateway (SVC-13), cùng lấy từ một biến triển khai (`DONG_BO_NGOAI` ở dev); và cờ tính năng `org.externalSync` của MOD-08 để giao diện ẩn hay hiện SCR-0104. Khi tắt, mọi lối ghi của đồng bộ trả 404 `feature-disabled` (SCIM `/scim/v2/**`, "Đồng bộ ngay", chạy lại, lịch đồng bộ, cổng nhận lô), lịch đêm không chạy, nguồn giả lập `hr-gia-lap` không khởi động; thao tác đọc lịch sử và bảng lệch vẫn dùng được. Bước 5 của SCR-0102 không bắt chọn nguồn nhân sự. Mã đồng bộ được giữ nguyên để bật lại bằng cấu hình.
2. **Tài khoản cán bộ do quản trị tạo trong ứng dụng.** SVC-04 là chủ của tài khoản như là chủ của hồ sơ cán bộ. Tạo tài khoản là một thao tác gồm: tạo `org.person` (nguồn `QUAN_TRI`), tạo phân công chính tại đơn vị và chức danh đã chọn, tạo người dùng Keycloak có `id` bằng mã cán bộ, tên đăng nhập, thư điện tử, thuộc tính `tenant`, `unit_id`, mật khẩu tạm buộc đổi khi đăng nhập lần đầu. Khoá, mở khoá và đặt lại mật khẩu gọi Admin API của Keycloak đồng bộ trong yêu cầu, nên có hiệu lực ngay (khoá thì thu hồi mọi phiên). Bảng mới `org.user_account` lưu tên đăng nhập, trạng thái tài khoản, lý do khoá và mốc đặt lại mật khẩu; thư điện tử là dữ liệu C2 chỉ nằm ở Keycloak (nơi cần nó để gửi thư đặt lại mật khẩu), không chép sang CSDL nghiệp vụ. EVT-16 vẫn phát khi hồ sơ cán bộ đổi như cũ; consumer DV-02-06 thấy người dùng đã có thì chỉ cập nhật họ tên.
3. **Kiêm nhiệm, điều động dùng lại phân công hai trục thời gian.** Thêm, sửa, kết thúc phân công dùng các thao tác đã có trong hợp đồng `createOrgAssignment`, `updateOrgAssignment`, `endOrgAssignment` (DV-04-04, BR-01-06, BR-01-08); đợt này hiện thực ba thao tác đó ở org-service.
4. **Vai trò tuỳ chỉnh theo tenant chỉ thu hẹp vai trò hệ thống.** MOD-08 thêm bảng `config.custom_role`. Một vai trò tuỳ chỉnh có một vai trò gốc thuộc năm vai trò `leader`, `manager`, `member`, `appraiser`, `inspector` (xem mục giới hạn), một tập chức năng là tập con chức năng mà vai trò gốc có theo ma trận, và với mỗi chức năng một phạm vi không rộng hơn phạm vi của vai trò gốc. Vai trò tuỳ chỉnh kế thừa mức xác thực (AAL2), mức dữ liệu, tách vai trò, bốn mắt và duyệt thứ hai của vai trò gốc. Cấp vai trò tuỳ chỉnh cho người dùng vẫn qua `config.role_binding` (đơn vị, phạm vi `DON_VI` hoặc `DON_VI_VA_CAP_DUOI`, thời hạn), nên "phân quyền theo chức năng × thao tác × phạm vi đơn vị" là tổ hợp của vai trò tuỳ chỉnh và bản ghi cấp vai trò.
5. **Hiệu lực ngay với Keycloak, OPA và giao diện.** Mỗi vai trò tuỳ chỉnh là một realm role Keycloak mã `tc_<8 ký tự đầu mã tenant>_<mã ngắn>`; `RealmRoleSync` (CORE-08-16) đồng bộ cả vai trò tuỳ chỉnh. Định nghĩa vai trò được MOD-08 đẩy sang OPA ở `data.tenant_roles.<tenant>` bằng Data API ngay khi lưu, khi khởi động và định kỳ mỗi phút (OPA khởi động lại hay nạp lại tệp đều được phủ lại); `authz.rego` coi vai trò tuỳ chỉnh đang hiệu lực mà người dùng giữ như vai trò gốc bị thu hẹp. Giao diện nạp định nghĩa vai trò tuỳ chỉnh của chính người dùng qua `GET /config/me/custom-roles` và `PermissionService` áp cùng quy tắc, nên menu, tuyến và nút ẩn đúng theo quyền (YC-PT-21). Sửa định nghĩa có hiệu lực ngay ở máy chủ; người đang đăng nhập thấy giao diện đổi ở lần tải lại trang, cấp hay thu hồi vai trò có hiệu lực ở lần làm mới token kế tiếp như WEB-058.

**Phương án đã cân nhắc.** (a) Cho phép vai trò tuỳ chỉnh rộng hơn vai trò hệ thống: loại vì phá ma trận đã duyệt, mất bảo đảm tách vai trò và AAL2. (b) Sinh lại `generated/data.json` và bản build web cho từng tenant: loại vì vai trò thay đổi theo tenant, không theo phát hành. (c) Đưa định nghĩa vai trò vào token: loại vì token dài ra theo số chức năng và vẫn phải chờ làm mới token. (d) Để đồng bộ ngoài luôn bật nhưng bỏ qua lỗi: loại vì lịch đêm vẫn chạy, ghi lần chạy lỗi và hiện màn hình đối soát vô nghĩa.

**Hệ quả.** Thêm ba màn hình SCR-0108 đến SCR-0110, sáu quy tắc BR-01-19 đến BR-01-24, năm thông điệp MSG-01-19 đến MSG-01-23, hai bảng `org.user_account` và `config.custom_role`, không thêm sự kiện EVT mới. Tạo, sửa, ngừng vai trò tuỳ chỉnh chỉ ghi nhật ký kiểm toán của MOD-08, không phát sự kiện, vì không service nào khác cần phản ứng: OPA nhận định nghĩa qua Data API, Keycloak nhận realm role đồng bộ; còn cấp và thu hồi vai trò tuỳ chỉnh cho người dùng vẫn phát EVT-27 như vai trò hệ thống. Org-service cần quyền Admin API `manage-users` (đã có cho `setMyContext`); mission-core cần `manage-realm` để tạo realm role tuỳ chỉnh.

**Giới hạn của đợt này.** Ba vai trò nhạy cảm `decider`, `second_approver`, `policy_author` chưa làm vai trò gốc, vì gắn với bốn mắt, duyệt thứ hai và tách vai trò mà việc thu hẹp từng chức năng dễ làm hở; khi có nhu cầu thật sẽ mở sau khi bổ sung kiểm thử tách vai trò cho vai trò tuỳ chỉnh. Vai trò gốc `tenant_admin` và `platform_admin` chưa dùng được cho vai trò tuỳ chỉnh, vì org-service và tenant-registry còn kiểm quyền bằng `x-mission.roles` của hợp đồng thay cho OPA (wiki 14); quản trị tài khoản vì vậy vẫn là của `tenant_admin` trên toàn tenant. Phân quyền quản trị theo nhánh đơn vị (quản trị đơn vị cấp dưới chỉ quản lý người của nhánh mình) là bước tiếp theo, cần org-service hỏi OPA. Lớp API thô (`gen.api`) của vai trò tuỳ chỉnh kế thừa vai trò gốc; thu hẹp nằm ở lớp chức năng, vốn là lớp mà các thao tác nghiệp vụ của mission-core kiểm (`@RequiresPermission(action = "SCR-…")`). Trên cụm thật có OPA cùng pod, định nghĩa vai trò cần phát hành như một bundle thứ hai gốc `tenant_roles` (cùng cơ chế dự kiến cho `data.org`); dev và một OPA dùng chung nạp qua Data API.

**Quyết định chi tiết khi hiện thực (24/10/2026).**

1. Thao tác tạo tài khoản và đặt lại mật khẩu không phát lại phản hồi khi gặp lại cùng `Idempotency-Key`: lần gửi lại trả 409 `ORG-409-ALREADY-CREATED` hoặc `ORG-409-ALREADY-RESET` thay vì trả lại mật khẩu tạm, vì mật khẩu tạm không được lưu ở bất kỳ đâu ngoài Keycloak. Giao diện coi 409 này là "đã làm xong" và tải lại danh sách.
2. Schema `Assignment` của `listPersonAssignments` có thêm thuộc tính tuỳ chọn `rowVersion`, để màn hình SCR-0109 gửi `If-Match` khi sửa hay kết thúc phân công mà không phải gọi thêm một yêu cầu cho mỗi dòng. Phân công không còn hiệu lực trả `rowVersion` rỗng.
3. Kết thúc kiêm nhiệm từ SCR-0109 đặt ngày kết thúc là hết ngày hôm nay (`validTo` là ngày mai theo quy ước nửa mở của hai trục thời gian).
4. Chưa kiểm tổng tỷ lệ thời gian của các kiêm nhiệm không vượt 100%: giao diện gửi tỷ lệ, máy chủ lưu; quy tắc kiểm tổng để task sau khi TL-02 chốt cách tính cho biệt phái.
5. Tài khoản dịch vụ `mission-core` trên Keycloak cần thêm `view-realm` và `manage-realm` của `realm-management` để tạo realm role `tc_…`; realm dev và `infra/dev/scripts/them-client-mission-core.sh` đã cấp.

# Màn hình

| Mã | Tên | Tác nhân | Nội dung chính | Đường dẫn |
|---|---|---|---|---|
| SCR-0108 | Người dùng | AC-02 (thao tác), AC-10 (xem) | Danh sách tài khoản theo đơn vị (gồm đơn vị cấp dưới), tìm theo tên hoặc tên đăng nhập, lọc trạng thái; thêm người dùng; khoá, mở khoá | `/admin/users` |
| SCR-0109 | Hồ sơ người dùng | AC-02, AC-10 (xem) | Thông tin tài khoản, sửa họ tên, thư điện tử; phân công công tác (chính, kiêm nhiệm, biệt phái) thêm, sửa, kết thúc; vai trò đang giữ và cấp vai trò (dùng SCR-0105); đặt lại mật khẩu; lịch sử | `/admin/users/:id` |
| SCR-0110 | Vai trò và quyền | AC-02 (thao tác), AC-10 (xem) | Danh sách vai trò hệ thống (chỉ xem) và vai trò tuỳ chỉnh; ma trận chức năng × vai trò theo nhóm nghiệp vụ; tạo vai trò tuỳ chỉnh từ vai trò gốc, bật tắt từng chức năng, chọn phạm vi; ngừng sử dụng | `/admin/roles` |

Ba màn hình viết mã như SCR-0105 (màn hình quản trị, không qua SDUI), theo YC-PT-10, YC-PT-21, YC-PT-24: nhãn nghiệp vụ, không hiện mã chức năng, mã vai trò kỹ thuật hay mã trạng thái thô; chế độ người cao tuổi rút gọn bước.

# Quy tắc nghiệp vụ

| Mã | Quy tắc | Kiểm soát |
|---|---|---|
| BR-01-19 | Tên đăng nhập duy nhất trong cell, 3 đến 64 ký tự chữ thường không dấu, số, dấu chấm, gạch dưới, gạch nối, bắt đầu bằng chữ; không đổi sau khi tạo | org-service, Keycloak |
| BR-01-20 | Tài khoản mới bắt buộc có đơn vị công tác chính và chức danh; được cấp mật khẩu tạm và phải đổi mật khẩu ở lần đăng nhập đầu | org-service |
| BR-01-21 | Khoá tài khoản cần lý do (5 đến 500 ký tự), thu hồi ngay mọi phiên đăng nhập; không tự khoá chính mình; mở khoá cần lý do | org-service, Keycloak |
| BR-01-22 | Tạo tài khoản, khoá, mở khoá, đặt lại mật khẩu và sửa vai trò tuỳ chỉnh cần phiên AAL2 còn mới (bước lên, BR-01-15); mật khẩu tạm chỉ trả một lần trong phản hồi, không ghi nhật ký | org-service, mission-core |
| BR-01-23 | Vai trò tuỳ chỉnh chỉ thu hẹp vai trò gốc: chức năng là tập con, phạm vi từng chức năng không rộng hơn (thứ tự N > Tn > Cd > Đv > B; Gn chỉ giữ nguyên); kế thừa AAL2, mức dữ liệu, tách vai trò, bốn mắt và duyệt thứ hai của vai trò gốc | mission-core, OPA |
| BR-01-24 | Sửa hay ngừng vai trò tuỳ chỉnh có hiệu lực ngay với mọi người đang giữ; vai trò đã ngừng không cấp mới được và mọi bản ghi cấp còn hiệu lực bị thu hồi với lý do "Vai trò đã ngừng sử dụng" | mission-core |

# Thông điệp

| Mã | Nội dung |
|---|---|
| MSG-01-19 | Đã tạo tài khoản cho đồng chí {họ tên}. Mật khẩu tạm thời chỉ hiển thị một lần, đồng chí đó phải đổi mật khẩu khi đăng nhập lần đầu. |
| MSG-01-20 | Tên đăng nhập này đã được sử dụng. Vui lòng chọn tên khác. |
| MSG-01-21 | Đã khoá tài khoản. Người dùng đã được đăng xuất khỏi mọi thiết bị. |
| MSG-01-22 | Đã đặt lại mật khẩu. Mật khẩu tạm thời chỉ hiển thị một lần. |
| MSG-01-23 | Đã lưu vai trò. Thay đổi được áp dụng ngay cho những người đang giữ vai trò này. |

# API (hợp đồng trước, ADR-32)

**org.yaml (SVC-04).** `GET /org/users` (danh sách tài khoản theo đơn vị, `includeSubunits`, `q`, `status`, phân trang con trỏ), `POST /org/users` (tạo, 201, `Idempotency-Key`, AAL2), `GET /org/users/{id}`, `PATCH /org/users/{id}` (`If-Match`), `POST /org/users/{id}/lock`, `POST /org/users/{id}/unlock`, `POST /org/users/{id}/reset-password` (AAL2), `GET /org/job-titles` (danh mục chức danh cho hộp chọn); hiện thực `createOrgAssignment`, `updateOrgAssignment`, `endOrgAssignment` đã có trong hợp đồng.

**config.yaml (MOD-08).** `GET /config/permission-catalog` (danh mục chức năng theo nhóm nghiệp vụ, phạm vi tối đa của từng vai trò hệ thống, sinh từ ma trận), `GET /config/custom-roles`, `POST /config/custom-roles` (AAL2), `GET /config/custom-roles/{id}`, `PUT /config/custom-roles/{id}` (`If-Match`, AAL2), `POST /config/custom-roles/{id}/retire` (AAL2), `GET /config/me/custom-roles` (định nghĩa vai trò tuỳ chỉnh mà người gọi đang giữ, mọi vai trò người dùng). `RoleCode` của `role-bindings` mở rộng nhận mã vai trò tuỳ chỉnh dạng `tc_…`.

# Dữ liệu

**`org.user_account`** (SVC-04, RLS theo tenant): `person_id` (khoá chính cùng `tenant_id`), `username` (duy nhất theo tenant, C1), `status` (`HOAT_DONG`, `KHOA`), `locked_at`, `locked_by`, `lock_reason`, `password_reset_at`, `password_reset_by`, `source` (`QUAN_TRI`, `DONG_BO`, `NAP_SAN`), cột chung. Tài khoản đã có trên Keycloak trước khi có bảng (dữ liệu thử nạp sẵn) được ghi bù vào bảng ở lần đọc đầu.

**`config.custom_role`** (MOD-08, RLS theo tenant): `id`, `code` (duy nhất toàn cell, `tc_…`), `name`, `description`, `base_role`, `permissions` (jsonb, khoá chức năng → phạm vi), `status` (`HIEU_LUC`, `NGUNG`), `retired_at`, `retired_by`, cột chung. Ràng buộc CHECK `ck_role_binding__role_code` của `config.role_binding` nới thành chín vai trò hệ thống hoặc mã bắt đầu `tc_`.

# Ma trận phân quyền

Khoá chức năng mới trong `docs/phan-quyen/ma-tran.json`: `SCR-0108.xem`, `SCR-0108.them`, `SCR-0108.khoa`, `SCR-0109.xem`, `SCR-0109.sua`, `SCR-0109.phan-cong`, `SCR-0109.dat-lai-mat-khau`, `SCR-0110.xem`, `SCR-0110.sua`. Ô của AC-02 là X/Tn (xem là R/Tn), AC-10 là R/Tn cho các khoá `xem`; các vai trò khác `-`. Dòng API `org` của AC-02 thêm `PATCH`. Sinh lại bằng `python3 docs/phan-quyen/gen_matrix.py` (Markdown, TypeScript, dữ liệu OPA).

# Truy vết và task

QT-01 → SCR-0108, SCR-0109 → `/org/users…`, `/org/assignments…` → SVC-04 → `org.user_account`, `org.person`, `org.assignment`, Keycloak; QT-01 → SCR-0110 → `/config/custom-roles…`, `/config/permission-catalog` → MOD-08 → `config.custom_role`, `config.role_binding`, EVT-27, OPA `data.tenant_roles`.

Task mới trong `docs/tasks/danh-muc-he-thong.tsv`: DV-04-09 (quản trị tài khoản trong org-service), DV-04-10 (công tắc đồng bộ ngoài), CORE-08-18 (vai trò tuỳ chỉnh, danh mục quyền, đẩy dữ liệu OPA), NEN-045 (chính sách OPA cho vai trò tuỳ chỉnh), WEB-077 (SCR-0108, SCR-0109), WEB-078 (SCR-0110 và `PermissionService` áp vai trò tuỳ chỉnh), WEB-079 (ẩn đồng bộ ngoài theo cờ). Các task đồng bộ đã gộp (DV-04-06, DV-04-07, DV-13-06, DV-13-07, WEB-019) giữ trạng thái và ghi chú "tính năng đang tắt theo ADR-34".

# Vị trí đưa vào TL ở phiên bản sau

TL-01: thêm ADR-34 vào Phụ lục A, bổ sung mục SVC-04 (quản trị tài khoản) và mục "Uỷ quyền tập trung" (vai trò tuỳ chỉnh, `data.tenant_roles`). TL-02a: QT-01 thêm nhánh "quản trị tạo tài khoản", đặc tả SCR-0108 đến SCR-0110, BR-01-19 đến BR-01-24, MSG-01-19 đến MSG-01-23. TL-03: hai bảng mới và CHECK đã nới. Baseline mục 15: thêm ADR-34 và các mã trên.
