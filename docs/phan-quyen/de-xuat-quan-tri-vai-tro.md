# Đề xuất bổ sung thiết kế: quản trị phân công vai trò và uỷ quyền (WEB-058, pha 1)

> Trạng thái: **đã duyệt ngày 19/10/2026** (người phụ trách duyệt theo các phương án đề xuất). Kết quả duyệt và các mã đã cấp nằm ở mục 8; các mục 1 đến 7 giữ nguyên nội dung lúc trình để tra cứu, nên các "gợi ý mã" trong đó không còn là mã chính thức.

## 1. Vì sao cần bổ sung

Bộ TL đã thiết kế phần dữ liệu cho việc cấp vai trò nhưng chưa thiết kế phần người dùng thao tác. Bảng `config.role_binding` (TL-03, MOD-08) gắn một người với một vai trò hệ thống trên một đơn vị phạm vi, có phạm vi `DON_VI` hoặc `DON_VI_VA_CAP_DUOI`, giới hạn theo luồng, thời hạn hiệu lực, người cấp và người duyệt thứ hai cho ba vai trò nhạy cảm `decider`, `second_approver`, `policy_author`, với ràng buộc CHECK người duyệt khác người cấp. TL-01 coi "thay đổi quyền" là thao tác nhạy cảm phải nâng bậc xác thực và là sự kiện kiểm toán bắt buộc. TL-02 nhắc tới uỷ quyền ở bốn chỗ: duyệt OKR thay khi người duyệt vắng mặt (QT-02), giao quyền check-in KR thay khi chủ sở hữu vắng dài ngày (QT-03), chuyển người duyệt đề nghị gia hạn thay thế khi quá 2 ngày làm việc (QT-06) và nghiệm thu thay (QT-07), đều dựa trên người thay thế khai báo trong `flowable.wf_binding`. Tuy vậy không có màn hình SCR nào để tenant tạo, duyệt, thu hồi hay tra cứu các bản ghi này, không có API trong TL-01 Phụ lục C, và không có BR, MSG riêng. Nếu không bổ sung, quản trị tenant chỉ có thể cấp vai trò bằng thao tác trực tiếp trên CSDL hoặc Keycloak, trái với nguyên tắc mọi thay đổi quyền phải có người duyệt, có step-up và có vết kiểm toán.

## 2. Một điểm kiến trúc phải chốt trước: nguồn sự thật của vai trò

Hiện có hai nơi giữ vai trò mà TL chưa nói rõ quan hệ. Keycloak giữ realm role và đưa vào token (`realm_access.roles`), giao diện và cổng đọc từ đó. `config.role_binding` giữ vai trò kèm đơn vị phạm vi, thời hạn và người duyệt, OPA đọc từ đó để quyết định ABAC. Màn hình quản trị chỉ có nghĩa khi biết thao tác cấp vai trò ghi vào đâu. Có ba phương án:

| Phương án | Cách làm | Ưu điểm | Nhược điểm |
|---|---|---|---|
| A (đề xuất) | `config.role_binding` là nguồn sự thật; mỗi lần cấp, duyệt, thu hồi hoặc hết hạn, MOD-08 phát sự kiện, một consumer đồng bộ realm role tương ứng sang Keycloak qua Admin API | Một nơi có đủ phạm vi, thời hạn, người duyệt; token và OPA nhất quán; dùng lại outbox sẵn có | Thêm một luồng đồng bộ cần giám sát; token cũ còn vai trò cũ tới khi làm mới (đã chấp nhận ở wiki/15) |
| B | Keycloak là nguồn vai trò; `role_binding` chỉ bổ sung phạm vi và thời hạn | Không cần đồng bộ | Hai nơi có thể lệch; Keycloak không có khái niệm người duyệt thứ hai và phạm vi đơn vị |
| C | Token không mang vai trò nghiệp vụ, chỉ mang ngữ cảnh phân công; OPA quyết định hoàn toàn từ `role_binding`; giao diện lấy danh sách quyền qua API | Sạch nhất về ABAC | Đổi cách `@vt/auth` đang đọc vai trò từ token (WEB-054); thêm một lời gọi API khi đăng nhập và khi đổi phân công |

Đề xuất chọn A vì giữ nguyên cách giao diện và cổng đang dùng, còn mọi thông tin quản trị nằm ở một bảng có kiểm soát bốn mắt. Cũng cần chốt cách biểu diễn trạng thái "chờ duyệt": `role_binding` hiện không có cột trạng thái; đề xuất coi bản ghi vai trò nhạy cảm có `approved_by` rỗng là chưa hiệu lực (OPA và bộ đồng bộ bỏ qua), hoặc thêm cột `status` (`CHO_DUYET`, `HIEU_LUC`, `TU_CHOI`, `DA_THU_HOI`) nếu người phụ trách muốn tách rõ.

## 3. Các chức năng đề xuất

Bảng dưới liệt kê chức năng cần có, vai trò được làm theo đề xuất, yêu cầu xác thực và vết kiểm toán. Cột "Nguồn" cho biết căn cứ trong TL; những ô không có căn cứ trực tiếp được ghi "suy luận".

| # | Chức năng | Vai trò đề xuất | Step-up | Ghi nhận | Nguồn |
|---|---|---|---|---|---|
| F1 | Xem danh sách vai trò của một đơn vị (lọc theo vai trò, còn hiệu lực, sắp hết hạn, chờ duyệt) | `tenant_admin` X/Tn; `leader` R/Đv; `inspector` R/Tn | Không | Không | TL-03 `role_binding`; vai trò xem là suy luận từ phạm vi mặc định ở wiki/15a |
| F2 | Xem vai trò và phân công của một người (phân công chính, kiêm nhiệm, điều động từ `org.assignment`; vai trò từ `role_binding`) | `tenant_admin` X/Tn; chính người đó R/B | Không | Không | TL-03 `org.assignment`, `role_binding`; QT-00 chọn phân công |
| F3 | Cấp vai trò có thời hạn và phạm vi: chọn người, `role_code` (9 giá trị của CHECK), đơn vị phạm vi, `DON_VI` hoặc `DON_VI_VA_CAP_DUOI`, luồng, `valid_from`, `valid_to`, lý do | `tenant_admin` | Có (TL-01: thay đổi quyền) | `sys.audit.v1`; sự kiện thay đổi vai trò (chờ cấp mã EVT, gợi ý EVT-27) | TL-03; TL-01 Định danh và xác thực, Nhật ký kiểm toán |
| F4 | Duyệt thứ hai khi cấp `decider`, `second_approver`, `policy_author`: xem đề nghị, phê duyệt hoặc từ chối có lý do | Người có thẩm quyền khác người cấp và khác người được cấp; đề xuất `leader` của đơn vị phạm vi hoặc một `tenant_admin` khác (chờ chốt) | Có | `sys.audit.v1`; sổ cái ghi người cấp, người duyệt | TL-03 cột `approved_by` và CHECK; wiki/15a ràng buộc 29 |
| F5 | Thu hồi vai trò trước hạn: đặt `valid_to` bằng thời điểm hiện tại, không xoá bản ghi, lý do bắt buộc | `tenant_admin` | Có | `sys.audit.v1`; sự kiện thay đổi vai trò; phiên của người bị thu hồi không cần đăng xuất ngay vì OPA từ chối theo dữ liệu mới | TL-03; wiki/15 "Cấp, thu hồi quyền" |
| F6 | Giao quyền check-in KR thay khi chủ sở hữu vắng dài ngày, có thời hạn; check-in ghi "check-in thay" | `leader` của đơn vị (đã có dòng `SCR-0301.giao-check-in-thay` trong ma trận) | Không (suy luận) | Ghi vào lịch sử check-in | TL-02 QT-03 ngoại lệ |
| F7 | Khai báo người duyệt thay, nghiệm thu thay khi vắng mặt (cấp phó được uỷ quyền), có thời hạn | `leader` cho đơn vị mình; quy tắc mặc định theo mẫu luồng do `policy_author` cấu hình ở SCR-1504 | Có (suy luận: thay đổi người có thẩm quyền duyệt) | `sys.audit.v1`; phiếu duyệt ghi "duyệt thay", "nghiệm thu thay" | TL-02 QT-02, QT-06, QT-07 ngoại lệ; TL-03 `flowable.wf_binding.params_json` |
| F8 | Xem lịch sử cấp, duyệt, thu hồi và uỷ quyền của một người hoặc một đơn vị; xuất báo cáo | `tenant_admin` R/Tn; `inspector` R/Tn | Có khi xuất | Mọi lần tra cứu được ghi (như sổ cái, BR-13-07) | TL-01 Nhật ký kiểm toán; QT-13 |

Hai chức năng F6 và F7 còn thiếu chỗ lưu dữ liệu. TL-03 chỉ có `flowable.wf_binding.params_json` cho người thay thế theo mẫu luồng, không có bảng cho uỷ quyền cá nhân có thời hạn và không có bảng cho giao quyền check-in thay. Đề xuất thêm một bảng chung `config.delegation` (người uỷ quyền, người nhận, loại uỷ quyền `DUYET_THAY`, `NGHIEM_THU_THAY`, `CHECK_IN_THAY`, đối tượng hoặc phạm vi, `valid_from`, `valid_to`, người tạo, lý do), để OPA và workflow-service đọc cùng một nguồn; phương án này cần người phụ trách TL-03 duyệt.

## 4. Ràng buộc nghiệp vụ đề xuất (chờ cấp mã BR, MSG)

| Gợi ý mã | Quy tắc | Kiểm soát | Thông báo khi vi phạm |
|---|---|---|---|
| BR-01-11 | Không ai được tự cấp, tự duyệt hay tự thu hồi vai trò cho chính mình | CSDL CHECK `approved_by <> granted_by`, OPA kiểm thêm người được cấp | MSG chờ cấp mã (gợi ý MSG-01-16) |
| BR-01-12 | Vai trò `decider`, `second_approver`, `policy_author` chỉ có hiệu lực sau khi người duyệt thứ hai phê duyệt | OPA bỏ qua bản ghi chưa duyệt; bộ đồng bộ Keycloak chưa gán | Gợi ý MSG-01-17 |
| BR-01-13 | Mọi thao tác cấp, duyệt, thu hồi vai trò và khai báo uỷ quyền yêu cầu phiên `acr=aal2` gần đây | mission-core trả 401 `insufficient_user_authentication` | Theo luồng step-up chung |
| BR-01-14 | Vai trò và uỷ quyền phải có thời hạn; hết `valid_to` tự hết hiệu lực, không cần thao tác | OPA so thời điểm; job nhắc trước 7 ngày (suy luận) | Thông báo cho người được cấp và người cấp |
| BR-01-15 | Uỷ quyền không được chuyển tiếp: người nhận uỷ quyền không uỷ quyền lại | OPA | Gợi ý MSG-01-18 |
| BR-01-16 | Người nhận uỷ quyền duyệt thay vẫn chịu ràng buộc tách vai trò của hồ sơ (không duyệt hồ sơ mình đã tham gia) | OPA dùng lịch sử hồ sơ như mọi bước duyệt | Dùng lại MSG của QT tương ứng |

## 5. Màn hình đề xuất (chờ cấp mã SCR)

Đề xuất gom tám chức năng vào ba màn hình trong remote `fe-admin`, đúng nhóm QT-01 "tổ chức và nhân sự" của tuyến `/admin`:

1. **Vai trò theo đơn vị** (gợi ý SCR-0105): cây đơn vị bên trái như SCR-0103, bảng vai trò của đơn vị đang chọn bên phải với cột người, vai trò, phạm vi, luồng, hiệu lực, người cấp, người duyệt, trạng thái; nút "Cấp vai trò" mở ngăn kéo biểu mẫu F3, nút "Thu hồi" trên từng dòng (F5); thẻ "Chờ tôi duyệt" cho F4. Chức năng F1, F3, F4, F5.
2. **Hồ sơ quyền của một người** (gợi ý SCR-0106): phân công và vai trò hiện hành, lịch sử cấp, duyệt, thu hồi, uỷ quyền đã nhận và đã giao; mở từ SCR-0103 hoặc SCR-0104. Chức năng F2, F8.
3. **Uỷ quyền khi vắng mặt** (gợi ý SCR-0107): lãnh đạo khai báo người duyệt thay, nghiệm thu thay, check-in thay có thời hạn cho đơn vị mình; danh sách uỷ quyền đang hiệu lực. Chức năng F6, F7.

Mọi màn hình theo khung của WEB-016: dữ liệu qua `@vt/api-client`, nút gắn khoá quyền bằng `*vtCan`, lỗi 403 hiện MSG, 401 chạy step-up, chế độ Đảng viên cao tuổi.

## 6. API đề xuất (chờ bổ sung vào TL-01 Phụ lục C)

Theo quy ước mục 13 baseline, thuộc module `config` (MOD-08): `GET /api/v1/config/role-bindings?orgUnitId=&role=&status=&cursor=`, `POST /api/v1/config/role-bindings` (kèm `Idempotency-Key`), `POST /api/v1/config/role-bindings/{id}/approve` và `/reject`, `POST /api/v1/config/role-bindings/{id}/revoke` (kèm `If-Match`), `GET /api/v1/config/role-bindings/history?personId=`; với uỷ quyền nếu chọn bảng `config.delegation`: `GET`, `POST /api/v1/config/delegations`, `POST /api/v1/config/delegations/{id}/revoke`.

## 7. Điểm cần người phụ trách quyết định

1. Nguồn sự thật của vai trò: phương án A, B hay C ở mục 2 (đề xuất A).
2. Trạng thái chờ duyệt: dùng `approved_by` rỗng hay thêm cột `status` vào `config.role_binding`.
3. Người duyệt thứ hai khi cấp vai trò nhạy cảm: `leader` của đơn vị phạm vi hay một `tenant_admin` khác.
4. Có thêm bảng `config.delegation` cho uỷ quyền cá nhân và check-in thay hay không.
5. Cấp mã chính thức cho ba màn hình, các BR, MSG, sự kiện thay đổi vai trò và các API ở mục 6.

Khi các điểm trên được chốt, pha 2 của WEB-058 thêm các dòng chức năng vào `docs/phan-quyen/ma-tran.json`, sinh lại wiki/15a và bảng quyền, rồi hiện thực ba màn hình trên dữ liệu giả lập.

## 8. Kết quả duyệt (19/10/2026) và mã đã cấp

Người phụ trách duyệt toàn bộ đề xuất theo phương án mặc định. Bốn quyết định được chốt như sau. Thứ nhất, nguồn sự thật của vai trò là `config.role_binding` (phương án A): mỗi lần cấp, duyệt, từ chối, thu hồi hoặc hết hạn, MOD-08 phát EVT-27 qua outbox và một consumer đồng bộ realm role sang Keycloak. Thứ hai, trạng thái chờ duyệt được biểu diễn bằng `approved_by` rỗng, không thêm cột `status`; trạng thái hiển thị (`CHO_DUYET`, `SAP_HIEU_LUC`, `HIEU_LUC`, `HET_HAN`, `DA_THU_HOI`, `TU_CHOI`) do máy chủ suy ra từ các cột. Để từ chối và thu hồi có vết, bảng được bổ sung các cột `reason`, `granted_at`, `approved_at`, `ended_by`, `ended_at`, `end_kind` (`THU_HOI`, `TU_CHOI`) và `end_reason`. Thứ ba, người duyệt thứ hai có thể là một `tenant_admin` khác hoặc `leader` của đơn vị phạm vi, miễn khác người cấp và khác người được cấp. Thứ tư, thêm bảng `config.delegation` dùng chung cho duyệt thay, nghiệm thu thay và check-in KR thay.

Khi cấp mã, hai mã BR gợi ý đầu tiên (BR-01-11, BR-01-12) đã trùng với quy tắc có sẵn của QT-01 trong TL-02, nên toàn bộ dải BR được dời lên hai số. Bảng dưới đối chiếu mã gợi ý ở mục 4 với mã chính thức.

| Gợi ý ở mục 4 | Mã chính thức | Quy tắc |
|---|---|---|
| BR-01-11 | BR-01-13 | Không tự cấp, tự duyệt, tự thu hồi vai trò hoặc uỷ quyền của chính mình (MSG-01-16, mã lỗi `CONFIG-403-SELF-GRANT`) |
| BR-01-12 | BR-01-14 | Vai trò nhạy cảm chỉ hiệu lực sau duyệt thứ hai (MSG-01-17) |
| BR-01-13 | BR-01-15 | Thao tác thay đổi quyền cần phiên `aal2` trong 15 phút |
| BR-01-14 | BR-01-16 | Vai trò và uỷ quyền bắt buộc có thời hạn (`CONFIG-422-VALIDITY`) |
| BR-01-15 | BR-01-17 | Không uỷ quyền lại (MSG-01-18, `CONFIG-422-REDELEGATION`) |
| BR-01-16 | BR-01-18 | Người nhận uỷ quyền vẫn chịu phân tách nhiệm vụ của hồ sơ |

Ba màn hình nhận mã SCR-0105 "Vai trò theo đơn vị", SCR-0106 "Hồ sơ quyền của một người" và SCR-0107 "Uỷ quyền khi vắng mặt"; sự kiện thay đổi vai trò là EVT-27. Hợp đồng API nằm trong `contracts/openapi/modules/config.yaml` (thẻ "Vai trò và uỷ quyền"): `GET`, `POST /config/role-bindings`, `GET /config/role-bindings/{id}`, `POST /config/role-bindings/{id}/approve`, `/reject`, `/revoke`, `GET /config/access-history`, `GET /config/access-history/export`, `GET`, `POST /config/delegations` và `POST /config/delegations/{id}/revoke`. Lịch sử thay đổi quyền dùng đường `/config/access-history` thay cho `/config/role-bindings/history` ở mục 6 vì gộp cả sự kiện uỷ quyền. Các dòng chức năng `SCR-0105.*`, `SCR-0106.*`, `SCR-0107.*` đã thêm vào `ma-tran.json`; wiki/15a và bảng quyền được sinh lại.

Một điểm còn để ngỏ: hộp khai báo uỷ quyền check-in KR thay chưa cho chọn KR cụ thể vì MOD-01 chưa có API tra KR theo người sở hữu; để trống đối tượng nghĩa là mọi KR của người uỷ quyền tại đơn vị. Điểm này được ghi ở wiki/14.
