> Nguồn: sinh tự động bởi `data/seed/da-tenant/sinh_bang_doi_chieu.py` từ `docs/phan-quyen/ma-tran.json` và `data/seed/da-tenant/du_lieu.py`; không sửa tay. Quy tắc: YC-PT-25 (sổ tay yêu cầu).

# Bảng đối chiếu vai trò × tính năng × dữ liệu thử

Bảng này trả lời câu hỏi: với mỗi tính năng trong ma trận phân quyền, vai trò nào được dùng, bộ dữ liệu thử đa tenant đã có gì để trải nghiệm, và nếu chưa có thì vì sao và mở khoá bằng cách nào. Tài khoản gợi ý lấy theo thứ tự tenant khối Đảng (`tu.`), khối chính quyền (`cq.`), doanh nghiệp (`dn.`); mật khẩu và mã OTP thử ở `data/seed/da-tenant/TAI-KHOAN.md`. Kịch bản từng bước ở `KICH-BAN-DA-TENANT.md`.

## 1. Dữ liệu đã nạp theo tenant

| Hạng mục | Đảng bộ tỉnh Phú Lâm | Ủy ban nhân dân tỉnh Phú Lâm | Công ty TNHH MTV Cấp nước Phú Lâm |
|---|---|---|---|
| Tài khoản | 16 | 21 | 14 |
| Đơn vị | 12 | 12 | 7 |
| Bộ mục tiêu kỳ này (đã duyệt/chờ duyệt/trả lại/nháp) | 10 (7/1/1/1) | 9 (6/1/1/1) | 8 (5/1/1/1) |
| Check-in kỳ này | 13 | 11 | 10 |
| Bộ mục tiêu và check-in quý trước | 2, 10 | 2, 9 | 2, 10 |
| Nhiệm vụ (nháp/đã giao/đang làm/chờ nghiệm thu/hoàn thành/tạm dừng/huỷ) | 11 (1/3/3/1/1/1/1) | 12 (1/3/4/1/1/1/1) | 11 (1/3/3/1/1/1/1) |
| Điểm nghẽn, đề nghị điều chỉnh, nhắc việc, giao lại | 1, 4, 1, 1 | 1, 4, 1, 1 | 1, 4, 1, 1 |
| Kế hoạch quý | chờ duyệt, nháp | đã duyệt, trả lại | chờ duyệt, nháp |
| Uỷ quyền, phân quyền thêm | 2, 2 | 2, 2 | 1, 1 |
| Policy Pack mẫu | khoi-dang/quy-dinh-moi | khoi-chinh-quyen/nd335 | khoi-chinh-quyen/nd335 |

Số liệu nhiệm vụ, mục tiêu tính theo trạng thái đích trong nguồn; bộ nạp qua API (`seed-da-tenant-api.py`) in bảng thống kê thực tế (mới/đã có) sau mỗi lần chạy, kèm danh sách bước lỗi nếu có.

## 2. Tài khoản theo vai trò

| Vai trò | Khối Đảng | Khối chính quyền | Doanh nghiệp |
|---|---|---|---|
| Quản trị tenant (QT tenant) | `tu.quantri` | `cq.quantri`, `cq.quantri2` | `dn.quantri` |
| Quản trị nghiệp vụ, BA cấu hình (QT nghiệp vụ) | `tu.chuyenvientcdv` | `cq.truongphongccvc` | `dn.chuyenviennhansu` |
| Lãnh đạo, người đứng đầu (Lãnh đạo) | `tu.bithu`, `tu.truongbantc`, `tu.chanhvp`, `tu.bithuxa` | `cq.chutich`, `cq.chanhvp`, `cq.giamdocsnv`, `cq.giamdocstc`, `cq.chutichxa` | `dn.chutich`, `dn.tonggiamdoc`, `dn.giamdocxn` |
| Cán bộ quản lý trực tiếp (Quản lý) | `tu.phobithu`, `tu.phobantc`, `tu.truongphongcb`, `tu.truongphongth`, `tu.bithuchibo` | `cq.phochutich`, `cq.truongphongth`, `cq.giamdoctt`, `cq.truongphongccvc`, `cq.phogiamdocstc`, `cq.truongphongns`, `cq.truongphongcds`, `cq.chutichxa` | `dn.tonggiamdoc`, `dn.photonggiamdoc`, `dn.truongphongtchc`, `dn.truongphongkd`, `dn.truongphongkt`, `dn.giamdocxn`, `dn.totruong` |
| Cán bộ, công chức, viên chức, đảng viên (Cán bộ) | `tu.bithu`, `tu.phobithu`, `tu.truongbantc`, `tu.phobantc`, `tu.truongphongcb`, `tu.chuyenviencb`, `tu.chuyenvientcdv`, `tu.chanhvp`, `tu.truongphongth`, `tu.chuyenvienth`, `tu.chuyenvienth2`, `tu.ubkt`, `tu.bithuxa`, `tu.bithuchibo`, `tu.dangvien` | `cq.chutich`, `cq.phochutich`, `cq.chanhvp`, `cq.truongphongth`, `cq.chuyenvienth`, `cq.giamdoctt`, `cq.vienchuctt`, `cq.giamdocsnv`, `cq.truongphongccvc`, `cq.chuyenviennv`, `cq.giamdocstc`, `cq.phogiamdocstc`, `cq.truongphongns`, `cq.chuyenvienns`, `cq.chuyenvienns2`, `cq.truongphongcds`, `cq.thanhtra`, `cq.chutichxa`, `cq.congchucxa` | `dn.chutich`, `dn.tonggiamdoc`, `dn.photonggiamdoc`, `dn.truongphongtchc`, `dn.chuyenviennhansu`, `dn.truongphongkd`, `dn.nhanvienkd`, `dn.nhanvienkd2`, `dn.truongphongkt`, `dn.giamdocxn`, `dn.totruong`, `dn.congnhan` |
| Cơ quan tổ chức cán bộ (thẩm định) (Thẩm định) | `tu.truongbantc`, `tu.phobantc`, `tu.truongphongcb`, `tu.chuyenviencb` | `cq.giamdocsnv`, `cq.chuyenviennv` | `dn.truongphongtchc` |
| Cấp có thẩm quyền quyết định (Cấp quyết định) | `tu.bithu` | `cq.chutich` | `dn.chutich` |
| Người duyệt thứ hai (four-eyes) (Duyệt thứ hai) | `tu.phobithu` | `cq.phochutich` | `dn.photonggiamdoc` |
| Cơ quan kiểm tra, giám sát (Kiểm tra) | `tu.ubkt` | `cq.thanhtra` | `dn.kiemsoatvien` |

Quản trị nền tảng (AC-01), hệ thống ngoài (AC-11) và trợ lý AI (AC-12) không có tài khoản trong bộ dữ liệu: tenant thử được tạo bằng SQL, hệ thống ngoài chỉ có bản giả lập, dịch vụ trợ lý AI chưa bật trên dev.

## 3. Tính năng, vai trò và dữ liệu thử

Cột tình trạng: **Có** là dữ liệu nạp sẵn đúng cho tính năng; **Thử trực tiếp** là tính năng tạo dữ liệu mới trên nền đã có, không cần nạp trước; **Trống** là chưa có dữ liệu, kèm lý do và cách mở khoá; **Ngoài phạm vi** là tính năng của nền tảng, di động hoặc hệ thống ngoài.


### SCR-0001 Đăng nhập (SSO, VNeID, MFA)

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Đăng nhập bằng tài khoản cơ quan, VNeID hoặc chứng thư số, xác thực MFA | QT tenant, QT nghiệp vụ, Lãnh đạo, Quản lý, Cán bộ, Thẩm định, Cấp quyết định, Duyệt thứ hai, Kiểm tra | — | Thử trực tiếp | 51 tài khoản, mật khẩu và mã OTP thử dùng chung (TAI-KHOAN.md) | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Chọn phân công (đơn vị, vai trò) sau đăng nhập | QT tenant, QT nghiệp vụ, Lãnh đạo, Quản lý, Cán bộ, Thẩm định, Cấp quyết định, Duyệt thứ hai, Kiểm tra | — | Có | Nhiều tài khoản có hơn một vai trò (ví dụ bí thư vừa là lãnh đạo vừa là cấp quyết định) | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Báo cáo truy cập bất thường (thu hồi phiên, đổi mật khẩu) | QT tenant, QT nghiệp vụ, Lãnh đạo, Quản lý, Cán bộ, Thẩm định, Cấp quyết định, Duyệt thứ hai, Kiểm tra | — | Thử trực tiếp | 51 tài khoản, mật khẩu và mã OTP thử dùng chung (TAI-KHOAN.md) | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Đăng ký, thu hồi khoá mã hoá đầu cuối của thiết bị hoặc chứng thư số | QT tenant, QT nghiệp vụ, Lãnh đạo, Quản lý, Cán bộ, Thẩm định, Cấp quyết định, Duyệt thứ hai, Kiểm tra | — | Trống | Khoá mã hoá đầu cuối gắn với thiết bị người dùng, không giả lập được; thử trực tiếp khi có chứng thư. | — |

### SCR-0002 Trang chủ "Việc của tôi hôm nay"

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem trang chủ "Việc của tôi hôm nay" (bố cục theo vai trò) | — | QT tenant, QT nghiệp vụ, Lãnh đạo, Quản lý, Cán bộ, Thẩm định, Cấp quyết định, Duyệt thứ hai, Kiểm tra | Có | Việc được giao, việc đang làm, mục tiêu chờ duyệt, đề nghị chờ quyết định, thông báo nhắc việc | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Đổi phân công (ngữ cảnh) trên thanh trên | QT tenant, QT nghiệp vụ, Lãnh đạo, Quản lý, Cán bộ, Thẩm định, Cấp quyết định, Duyệt thứ hai, Kiểm tra | — | Có | Việc được giao, việc đang làm, mục tiêu chờ duyệt, đề nghị chờ quyết định, thông báo nhắc việc | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Cập nhật nhanh tiến độ, mở check-in, nộp sản phẩm từ trang chủ | Quản lý, Cán bộ | — | Có | Mỗi tenant có 3–4 nhiệm vụ đang thực hiện của cán bộ | `tu.truongphongth`, `cq.truongphongth`, `dn.truongphongkd` |
| Đánh dấu thông báo đã đọc | QT tenant, QT nghiệp vụ, Lãnh đạo, Quản lý, Cán bộ, Thẩm định, Cấp quyết định, Duyệt thứ hai, Kiểm tra | — | Có | Việc được giao, việc đang làm, mục tiêu chờ duyệt, đề nghị chờ quyết định, thông báo nhắc việc | `tu.quantri`, `cq.quantri`, `dn.quantri` |

### SCR-0101 Danh sách tenant

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem, lọc danh sách tenant và tải theo cell | — | — | Ngoài phạm vi | Vai trò quản trị nền tảng, mẫu luồng nội bộ nền tảng: tenant thử tạo bằng SQL, không có tài khoản AC-01. | — |
| Xuất danh sách tenant (tác vụ 202) | — | — | Ngoài phạm vi | Vai trò quản trị nền tảng, mẫu luồng nội bộ nền tảng: tenant thử tạo bằng SQL, không có tài khoản AC-01. | — |

### SCR-0102 Khởi tạo tenant (wizard)

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Khởi tạo tenant bước 1, 2, 3 và khởi chạy saga cấp phát | — | — | Ngoài phạm vi | Vai trò quản trị nền tảng, mẫu luồng nội bộ nền tảng: tenant thử tạo bằng SQL, không có tài khoản AC-01. | — |
| Khai báo IdP, kiểm tra kết nối, nhập tổ chức ban đầu (bước 4, 5) | QT tenant | — | Ngoài phạm vi | Vai trò quản trị nền tảng, mẫu luồng nội bộ nền tảng: tenant thử tạo bằng SQL, không có tài khoản AC-01. | — |
| Kích hoạt tenant (bước 6, chuyển `DANG_VAN_HANH`) | — | — | Ngoài phạm vi | Vai trò quản trị nền tảng, mẫu luồng nội bộ nền tảng: tenant thử tạo bằng SQL, không có tài khoản AC-01. | — |
| Huỷ yêu cầu khởi tạo ở `KHOI_TAO` | — | — | Ngoài phạm vi | Vai trò quản trị nền tảng, mẫu luồng nội bộ nền tảng: tenant thử tạo bằng SQL, không có tài khoản AC-01. | — |

### SCR-0103 Cây tổ chức có lịch sử

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem cây tổ chức hiện tại và tại một ngày trong quá khứ | — | QT tenant, Thẩm định, Kiểm tra | Có | Cây 12/12/7 đơn vị, ba cấp, có cấp xã và chi bộ thôn (khối Đảng) | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Nhập cây tổ chức từ Excel | QT tenant | — | Thử trực tiếp | Cây đã có; nhập Excel để thêm đơn vị mới | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Thay đổi tổ chức (thành lập, sáp nhập, chia tách, giải thể) và sửa đơn vị | QT tenant | — | Thử trực tiếp | Cây tổ chức thử chỉ có bản hiện hành, chưa có lịch sử thay đổi; thử thay đổi tổ chức để tạo lịch sử. | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Thay khoá phục hồi của tổ chức, ghi biên bản khôi phục và nạp khoá bọc cho thiết bị mới khi mất khoá | QT tenant | — | Trống | Khoá mã hoá đầu cuối gắn với thiết bị người dùng, không giả lập được; thử trực tiếp khi có chứng thư. | — |
| Xem nhật ký khôi phục khoá mã hoá đầu cuối | — | QT tenant, Kiểm tra | Trống | Khoá mã hoá đầu cuối gắn với thiết bị người dùng, không giả lập được; thử trực tiếp khi có chứng thư. | — |

### SCR-0104 Đồng bộ và đối soát nhân sự

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem lần đồng bộ và danh sách lệch nhân sự | — | QT tenant, Kiểm tra | Trống | Phụ thuộc hệ thống ngoài (văn phòng điện tử, CSDL cán bộ công chức viên chức) chỉ có bản giả lập; không có thông điệp đến. | — |
| Đồng bộ ngay, lịch đồng bộ, chạy lại thông điệp lỗi | QT tenant | — | Trống | Phụ thuộc hệ thống ngoài (văn phòng điện tử, CSDL cán bộ công chức viên chức) chỉ có bản giả lập; không có thông điệp đến. | — |
| Xử lý lệch: lấy theo nguồn, giữ nội bộ, ánh xạ thủ công | QT tenant | — | Trống | Phụ thuộc hệ thống ngoài (văn phòng điện tử, CSDL cán bộ công chức viên chức) chỉ có bản giả lập; không có thông điệp đến. | — |
| Giao cán bộ tổ chức đơn vị kiểm tra dòng lệch | QT tenant | Thẩm định | Trống | Phụ thuộc hệ thống ngoài (văn phòng điện tử, CSDL cán bộ công chức viên chức) chỉ có bản giả lập; không có thông điệp đến. | — |

### SCR-0105 Vai trò theo đơn vị (cấp, duyệt thứ hai, thu hồi)

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem vai trò đã cấp theo đơn vị (lọc vai trò, còn hiệu lực, sắp hết hạn, chờ duyệt) | — | QT tenant, Lãnh đạo, Kiểm tra | Có | Vai trò gốc của 51 tài khoản cộng vai trò cấp thêm có thời hạn (đã duyệt và đang chờ duyệt) | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Cấp vai trò có thời hạn, đơn vị phạm vi, phạm vi và luồng | QT tenant | — | Có | Mỗi tenant có một đề nghị cấp vai trò đang chờ duyệt để xem mẫu; cấp thêm cho người khác | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Duyệt thứ hai hoặc từ chối đề nghị cấp `decider`, `second_approver`, `policy_author` | QT tenant, Lãnh đạo | — | Có | Đề nghị cấp quyền chờ duyệt: khối Đảng (uỷ ban kiểm tra), khối chính quyền (chuyên viên tổng hợp) | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Thu hồi vai trò trước hạn, lý do bắt buộc | QT tenant | — | Thử trực tiếp | Thu hồi vai trò cấp thêm đã có hiệu lực | `tu.quantri`, `cq.quantri`, `dn.quantri` |

### SCR-0106 Hồ sơ quyền của một người

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem hồ sơ quyền của một người: vai trò hiện hành, uỷ quyền đã giao và đã nhận, lịch sử | — | QT tenant, Lãnh đạo, Kiểm tra | Có | Hồ sơ quyền của người được cấp thêm vai trò, người được uỷ quyền | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Xuất lịch sử quyền của một người ra CSV | — | QT tenant, Kiểm tra | Có | Hồ sơ quyền của người được cấp thêm vai trò, người được uỷ quyền | `tu.quantri`, `cq.quantri`, `dn.quantri` |

### SCR-0107 Uỷ quyền khi vắng mặt

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem uỷ quyền khi vắng mặt đang hiệu lực và đã kết thúc của đơn vị | — | QT tenant, Lãnh đạo, Kiểm tra | Có | Uỷ quyền duyệt thay và check-in thay đang hiệu lực (2 khối Đảng, 2 chính quyền, 1 doanh nghiệp) | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Khai báo duyệt thay, nghiệm thu thay, check-in KR thay có thời hạn | Lãnh đạo | — | Có | Lãnh đạo khai thêm uỷ quyền nghiệm thu thay cho người khác | `tu.chanhvp`, `cq.chanhvp`, `dn.chutich` |
| Thu hồi uỷ quyền trước hạn | Lãnh đạo | — | Thử trực tiếp | Thu hồi uỷ quyền đang hiệu lực | `tu.chanhvp`, `cq.chanhvp`, `dn.chutich` |

### SCR-0201 Cây mục tiêu OKR

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem cây mục tiêu, bảng, bản đồ liên kết, xuất mẫu báo cáo OKR | — | Lãnh đạo, Quản lý, Cán bộ | Có | 10/9/8 bộ mục tiêu: cây tỉnh → sở, ban → phòng → cá nhân; mục tiêu cá nhân; đủ trạng thái | `tu.chanhvp`, `cq.chanhvp`, `dn.chutich` |
| Thêm mục tiêu của đơn vị (mở SCR-0202) | Quản lý | — | Có | 10/9/8 bộ mục tiêu: cây tỉnh → sở, ban → phòng → cá nhân; mục tiêu cá nhân; đủ trạng thái | `tu.truongphongth`, `cq.truongphongth`, `dn.truongphongkd` |
| Check-in KR từ cây mục tiêu | Quản lý, Cán bộ | — | Có | 10/9/8 bộ mục tiêu: cây tỉnh → sở, ban → phòng → cá nhân; mục tiêu cá nhân; đủ trạng thái | `tu.truongphongth`, `cq.truongphongth`, `dn.truongphongkd` |

### SCR-0202 Tạo/sửa O và KR

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Soạn, lưu nháp mục tiêu và KR của đơn vị | Quản lý | — | Có | Mục tiêu nháp của đơn vị chờ soạn tiếp; mục tiêu bị trả lại có ý kiến để sửa | `tu.truongphongth`, `cq.truongphongth`, `dn.truongphongkd` |
| Soạn, lưu nháp OKR cá nhân | Cán bộ | — | Có | Mục tiêu cá nhân nháp (chuyên viên tổng hợp, chuyên viên thanh tra, nhân viên nhân sự) | `tu.chuyenvienth`, `cq.chuyenvienth`, `dn.nhanvienkd` |
| Trợ lý AI soát KR và dùng gợi ý vào ô | Quản lý, Cán bộ | — | Trống | Dịch vụ trợ lý AI chưa bật trên môi trường dev; bỏ qua. | — |
| Trình phê duyệt OKR | Quản lý, Cán bộ | — | Có | Mục tiêu nháp và mục tiêu bị trả lại để trình lại | `tu.truongphongth`, `cq.truongphongth`, `dn.truongphongkd` |

### SCR-0203 Phê duyệt OKR

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem hàng chờ OKR, kết quả kiểm tra tự động, cây ở chế độ chỉ đọc | — | Lãnh đạo | Có | Mỗi tenant một bộ mục tiêu đang chờ duyệt; lịch sử bộ đã trả lại có ý kiến | `tu.chanhvp`, `cq.chanhvp`, `dn.chutich` |
| Phê duyệt OKR | Lãnh đạo | — | Có | Mỗi tenant một bộ mục tiêu đang chờ duyệt; lịch sử bộ đã trả lại có ý kiến | `tu.chanhvp`, `cq.chanhvp`, `dn.chutich` |
| Trả lại OKR để sửa | Lãnh đạo | — | Có | Mỗi tenant một bộ mục tiêu đang chờ duyệt; lịch sử bộ đã trả lại có ý kiến | `tu.chanhvp`, `cq.chanhvp`, `dn.chutich` |
| Duyệt hàng loạt các bộ OKR đủ điều kiện | Lãnh đạo | — | Có | Trình thêm các bộ nháp để có nhiều bộ chờ duyệt rồi duyệt hàng loạt | `tu.chanhvp`, `cq.chanhvp`, `dn.chutich` |

### SCR-0301 Check-in KR (desktop)

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Check-in KR: lưu nháp, gửi, lấy lại số liệu nguồn | Quản lý, Cán bộ | — | Có | Mục tiêu đã duyệt có kết quả then chốt chưa check-in và đã check-in | `tu.truongphongth`, `cq.truongphongth`, `dn.truongphongkd` |
| Xem lịch sử check-in | — | Lãnh đạo, Quản lý, Cán bộ | Có | 63 lần check-in, nhiều kết quả có 2–3 lần, có lần tự tin thấp kèm vướng mắc | `tu.chanhvp`, `cq.chanhvp`, `dn.chutich` |
| Giao quyền check-in tạm thời khi chủ sở hữu KR vắng mặt dài ngày | Lãnh đạo | — | Có | Uỷ quyền check-in thay đã có ở khối Đảng và chính quyền | `tu.chanhvp`, `cq.chanhvp`, `dn.chutich` |

### SCR-0302 Check-in trên di động

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| [Di động] Check-in nhanh, nói để nhập | Quản lý, Cán bộ | — | Ngoài phạm vi | Ứng dụng di động đã bỏ khỏi phạm vi (quyết định 24/10/2026). | — |

### SCR-0401 Thư viện danh mục công việc

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem thư viện danh mục công việc | — | QT nghiệp vụ, Lãnh đạo, Quản lý, Cán bộ | Có | Danh mục công việc 6/7/7 mục theo nhóm, có điểm chuẩn | `tu.chuyenvientcdv`, `cq.truongphongccvc`, `dn.chuyenviennhansu` |
| Thêm, sửa mục, nhân bản phiên bản, nhập Excel danh mục | QT nghiệp vụ, Quản lý | — | Thử trực tiếp | Thêm mục, nhân bản phiên bản trên danh mục đã có | `tu.chuyenvientcdv`, `cq.truongphongccvc`, `dn.chuyenviennhansu` |

### SCR-0402 Lập kế hoạch quý

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem kế hoạch quý của đơn vị | — | Lãnh đạo, Quản lý, Cán bộ, Thẩm định | Có | Kế hoạch quý: nháp, chờ duyệt, trả lại, đã duyệt (mỗi tenant hai kế hoạch) | `tu.chanhvp`, `cq.chanhvp`, `dn.chutich` |
| Lập kế hoạch: sao chép quý trước, gợi ý từ OKR, thêm dòng, lưu nháp | Quản lý | — | Có | Kế hoạch nháp của Tỉnh uỷ, UBND tỉnh, Công ty để lập tiếp; kế hoạch bị trả lại để sửa | `tu.truongphongth`, `cq.truongphongth`, `dn.truongphongkd` |
| Gửi duyệt kế hoạch | Quản lý | — | Có | Kế hoạch nháp đã có dòng để gửi duyệt | `tu.truongphongth`, `cq.truongphongth`, `dn.truongphongkd` |
| Tạo điều chỉnh kế hoạch (phiên bản n+1) | Quản lý | — | Có | Kế hoạch Sở Tài chính đã duyệt kèm ký số (chỉ gồm dòng danh mục) để tạo phiên bản điều chỉnh | `tu.truongphongth`, `cq.truongphongth`, `dn.truongphongkd` |

### SCR-0403 Phê duyệt kế hoạch (so sánh phiên bản)

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| So sánh phiên bản kế hoạch | — | Lãnh đạo | Thử trực tiếp | Có sau khi tạo điều chỉnh kế hoạch (phiên bản 2) | `tu.chanhvp`, `cq.chanhvp`, `dn.chutich` |
| Trả lại kế hoạch với ý kiến | Lãnh đạo | — | Có | Kế hoạch chờ duyệt của Ban Tổ chức Tỉnh uỷ, Xí nghiệp Cấp nước số 1 | `tu.chanhvp`, `cq.chanhvp`, `dn.chutich` |
| Duyệt và ký số kế hoạch | Lãnh đạo | — | Có | Kế hoạch chờ duyệt của Ban Tổ chức, Xí nghiệp 1; duyệt kèm ký số đã chạy được trên dev (kế hoạch Sở Tài chính). Kế hoạch có dòng ngoài danh mục cần bước định điểm (BR-04-05) chưa có trên máy chủ | `tu.chanhvp`, `cq.chanhvp`, `dn.chutich` |

### SCR-0501 Giao việc

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Soạn, lưu nháp, huỷ nháp nhiệm vụ | Quản lý | — | Có | Mỗi tenant một nhiệm vụ nháp chưa giao | `tu.truongphongth`, `cq.truongphongth`, `dn.truongphongkd` |
| Giao việc (chủ trì, phối hợp, người nghiệm thu) | Quản lý | — | Có | Nhiệm vụ nháp để giao; nhiệm vụ đã giao chưa nhận để giao lại | `tu.truongphongth`, `cq.truongphongth`, `dn.truongphongkd` |
| Giao đồng loạt cho nhiều đơn vị | Quản lý | — | Thử trực tiếp | Giao cho nhiều phòng, xã trong cây tổ chức | `tu.truongphongth`, `cq.truongphongth`, `dn.truongphongkd` |
| Xem nhiệm vụ gần giống | — | Quản lý | Có | Nhiệm vụ cùng nhóm, cùng sản phẩm trong kỳ để so sánh gần giống | `tu.truongphongth`, `cq.truongphongth`, `dn.truongphongkd` |
| Tra khoá công khai người nhận, khoá ký danh bạ và khoá phục hồi để mã hoá nội dung mật | — | QT tenant, QT nghiệp vụ, Lãnh đạo, Quản lý, Cán bộ, Thẩm định, Cấp quyết định, Duyệt thứ hai, Kiểm tra | Trống | Khoá mã hoá đầu cuối gắn với thiết bị người dùng, không giả lập được; thử trực tiếp khi có chứng thư. | — |

### SCR-0502 Tạo nhiệm vụ từ văn bản chỉ đạo

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Nhận văn bản chỉ đạo từ hệ thống văn phòng điện tử | — | Quản lý | Trống | Phụ thuộc hệ thống ngoài (văn phòng điện tử, CSDL cán bộ công chức viên chức) chỉ có bản giả lập; không có thông điệp đến. | — |
| Trích nhiệm vụ từ văn bản bằng AI | — | — | Trống | Phụ thuộc hệ thống ngoài (văn phòng điện tử, CSDL cán bộ công chức viên chức) chỉ có bản giả lập; không có thông điệp đến. | — |
| Sửa, bỏ, gộp, xác nhận nhiệm vụ trích; bỏ qua văn bản | Quản lý | — | Trống | Phụ thuộc hệ thống ngoài (văn phòng điện tử, CSDL cán bộ công chức viên chức) chỉ có bản giả lập; không có thông điệp đến. | — |
| Xác nhận và giao các nhiệm vụ đã xác nhận | Quản lý | — | Trống | Phụ thuộc hệ thống ngoài (văn phòng điện tử, CSDL cán bộ công chức viên chức) chỉ có bản giả lập; không có thông điệp đến. | — |

### SCR-0503 Việc của tôi (desktop)

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem, lọc việc của tôi, xuất Excel | — | Cán bộ | Có | Việc của tôi đủ trạng thái: đã giao, đang làm, chờ nghiệm thu, hoàn thành, tạm dừng, huỷ | `tu.chuyenvienth`, `cq.chuyenvienth`, `dn.nhanvienkd` |
| Nhận việc (`DA_GIAO` → `DANG_THUC_HIEN`) | Cán bộ | — | Có | Nhiệm vụ đã giao chưa nhận (2–3 mỗi tenant) | `tu.chuyenvienth`, `cq.chuyenvienth`, `dn.nhanvienkd` |
| Cập nhật tiến độ một nhiệm vụ hoặc hàng loạt | Cán bộ | — | Có | Nhiệm vụ đang làm ở 20–60% | `tu.chuyenvienth`, `cq.chuyenvienth`, `dn.nhanvienkd` |
| Gửi đăng ký nhiệm vụ cho người quản lý | Cán bộ | — | Trống | Đăng ký nhiệm vụ cần mục danh mục gắn vị trí việc làm; dữ liệu tổ chức thử chưa có vị trí việc làm. | — |

### SCR-0504 Việc của tôi (di động)

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| [Di động] Xem việc, cập nhật tiến độ, báo điểm nghẽn | Cán bộ | — | Ngoài phạm vi | Ứng dụng di động đã bỏ khỏi phạm vi (quyết định 24/10/2026). | — |

### SCR-0601 Chi tiết nhiệm vụ

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem chi tiết nhiệm vụ, tiến độ, sản phẩm, lịch sử | — | Lãnh đạo, Quản lý, Cán bộ, Thẩm định, Kiểm tra | Có | Chi tiết có nhật ký tiến độ, lịch sử trạng thái, điểm nghẽn, đề nghị điều chỉnh, nhắc việc, giao lại | `tu.chanhvp`, `cq.chanhvp`, `dn.chutich` |
| Cập nhật tiến độ | Cán bộ | — | Có | Nhiệm vụ đang làm | `tu.chuyenvienth`, `cq.chuyenvienth`, `dn.nhanvienkd` |
| Báo điểm nghẽn | Cán bộ | — | Có | Mỗi tenant một điểm nghẽn mẫu (mở, đang xử lý hoặc đã giải quyết); báo thêm trên việc khác | `tu.chuyenvienth`, `cq.chuyenvienth`, `dn.nhanvienkd` |
| Xử lý điểm nghẽn: giao người xử lý, đặt hạn, đóng | Quản lý | — | Có | Điểm nghẽn đang mở hoặc đang xử lý chờ người giao đóng | `tu.truongphongth`, `cq.truongphongth`, `dn.truongphongkd` |
| Duyệt đề nghị gia hạn, điều chỉnh (chọn nguyên nhân, chấp nhận, từ chối) | Lãnh đạo, Quản lý | — | Có | Đề nghị gia hạn, đổi số lượng đang chờ quyết định; lịch sử đề nghị đã duyệt và từ chối | `tu.chanhvp`, `cq.chanhvp`, `dn.chutich` |
| Nộp sản phẩm (mở SCR-0701) | Cán bộ | — | Thử trực tiếp | Minh chứng là tệp thật phải qua quét vi-rút của dịch vụ minh chứng, không nạp sẵn; nộp tệp bất kỳ ở kịch bản KB-X5. | `tu.chuyenvienth`, `cq.chuyenvienth`, `dn.nhanvienkd` |

### SCR-0602 Đề nghị gia hạn, điều chỉnh

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Lập, xem trước hệ quả, đính kèm, gửi đề nghị gia hạn, điều chỉnh, tạm dừng, huỷ | Cán bộ | — | Có | Nhiệm vụ đang làm chưa có đề nghị để gửi; đề nghị mẫu đủ bốn loại (gia hạn, số lượng, tạm dừng, huỷ) | `tu.chuyenvienth`, `cq.chuyenvienth`, `dn.nhanvienkd` |
| Rút lại đề nghị `CHO_DUYET` của mình | Cán bộ | — | Có | Đề nghị đang chờ duyệt của chính người chủ trì | `tu.chuyenvienth`, `cq.chuyenvienth`, `dn.nhanvienkd` |

### SCR-0701 Nộp minh chứng

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Tải tệp, chọn văn bản, lưu nháp minh chứng | Cán bộ | — | Thử trực tiếp | Minh chứng là tệp thật phải qua quét vi-rút của dịch vụ minh chứng, không nạp sẵn; nộp tệp bất kỳ ở kịch bản KB-X5. | `tu.chuyenvienth`, `cq.chuyenvienth`, `dn.nhanvienkd` |
| Nộp minh chứng để nghiệm thu | Cán bộ | — | Thử trực tiếp | Minh chứng là tệp thật phải qua quét vi-rút của dịch vụ minh chứng, không nạp sẵn; nộp tệp bất kỳ ở kịch bản KB-X5. | `tu.chuyenvienth`, `cq.chuyenvienth`, `dn.nhanvienkd` |
| Xem minh chứng đã nộp | — | Quản lý, Cán bộ, Thẩm định | Trống | Minh chứng là tệp thật phải qua quét vi-rút của dịch vụ minh chứng, không nạp sẵn; nộp tệp bất kỳ ở kịch bản KB-X5. | — |

### SCR-0702 Nộp minh chứng trên di động (OCR)

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| [Di động] Chụp ảnh, OCR, xác nhận và nộp minh chứng | Cán bộ | — | Ngoài phạm vi | Ứng dụng di động đã bỏ khỏi phạm vi (quyết định 24/10/2026). | — |

### SCR-0703 Nghiệm thu chất lượng 5 mức

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Mở minh chứng, kiểm toàn vẹn, xem trước điểm | — | Quản lý | Trống | Minh chứng là tệp thật phải qua quét vi-rút của dịch vụ minh chứng, không nạp sẵn; nộp tệp bất kỳ ở kịch bản KB-X5. | — |
| Xác nhận nghiệm thu chất lượng 5 mức | Quản lý | — | Trống | Minh chứng là tệp thật phải qua quét vi-rút của dịch vụ minh chứng, không nạp sẵn; nộp tệp bất kỳ ở kịch bản KB-X5. | — |
| Yêu cầu bổ sung hoặc từ chối minh chứng | Quản lý | — | Trống | Minh chứng là tệp thật phải qua quét vi-rút của dịch vụ minh chứng, không nạp sẵn; nộp tệp bất kỳ ở kịch bản KB-X5. | — |

### SCR-0801 Quản lý kỳ và tiến trình khoá kỳ

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem danh sách kỳ, lệnh khoá kỳ và tiến trình saga, sơ đồ BPMN | — | QT tenant, Lãnh đạo, Duyệt thứ hai, Kiểm tra | Có | Quý trước có lệnh khoá đã duyệt chưa khởi chạy; quý hiện hành đang mở | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Tạo kỳ, lịch ngày nghỉ và làm bù | QT tenant | — | Thử trực tiếp | Hai kỳ đã có; tạo kỳ quý sau hoặc kỳ năm | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Lập lệnh khoá kỳ | QT tenant | — | Có | Lệnh khoá quý trước đã lập; lập lệnh cho kỳ khác | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Phê duyệt lệnh khoá kỳ | Lãnh đạo | — | Có | Lệnh khoá quý trước đã được lãnh đạo duyệt (xem lịch sử); duyệt lệnh mới nếu lập thêm | `tu.chanhvp`, `cq.chanhvp`, `dn.chutich` |
| Khởi chạy saga khoá kỳ | QT tenant | — | Thử trực tiếp | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Tạm dừng sau bước hiện tại, chạy lại hồ sơ lỗi | QT tenant | — | Thử trực tiếp | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Đề nghị huỷ và bù trừ toàn bộ saga | QT tenant | — | Thử trực tiếp | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Duyệt thứ hai huỷ và bù trừ | Duyệt thứ hai | — | Thử trực tiếp | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | `tu.phobithu`, `cq.phochutich`, `dn.photonggiamdoc` |

### SCR-0901 Phiếu tự đánh giá

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem kết quả hệ thống tính, vết tính, xem trước Mẫu 1B, chuyển luồng | — | Quản lý, Cán bộ | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |
| Lưu nháp phiếu tự đánh giá | Cán bộ | — | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |
| Ký số và nộp phiếu tự đánh giá | Cán bộ | — | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |

### SCR-0902 Thẩm định

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem hàng đợi, chi tiết hồ sơ, mở minh chứng (ghi nhật ký truy cập C2) | — | Thẩm định | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |
| Trả lại hồ sơ bổ sung | Thẩm định | — | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |
| Ký số kết quả thẩm định và chuyển đề xuất | Thẩm định | — | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |
| Xuất danh sách hàng đợi (không gồm trường C2) | — | Thẩm định | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |

### SCR-0903 Phiếu xếp loại có giải thích

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem phiếu xếp loại, vết tính, luồng Nhà nước | — | Quản lý, Cán bộ, Thẩm định, Cấp quyết định | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |
| Ghi mức đề xuất xếp loại | Quản lý | — | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |
| Tải Mẫu 1B (PDF/A) đã ký | — | Quản lý, Cán bộ, Thẩm định, Cấp quyết định | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |
| Gửi khiếu nại (mở SCR-1201) | Cán bộ | — | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |

### SCR-0904 Quyết định xếp loại hàng loạt và ký số

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem hồ sơ chờ quyết định, phân bố mức, xuất Mẫu 3 | — | Cấp quyết định | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |
| Đổi mức quyết định có lý do | Cấp quyết định | — | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |
| Quyết định và ký lô | Cấp quyết định | — | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |
| Theo dõi tiến độ và huỷ lô chưa hoàn tất | Cấp quyết định | — | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |

### SCR-1001 Xếp loại năm

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem tổng hợp bốn quý, mức năm và giải thích, lọc cảnh báo liên thông | — | Lãnh đạo, Thẩm định, Cấp quyết định | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |
| Chuyển thẩm định năm (mở SCR-0902 chế độ năm) | Thẩm định | — | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |
| Lập đề xuất bố trí lại | Thẩm định | — | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |

### SCR-1002 Phân nhóm triển vọng

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem danh sách phân nhóm và tiêu chí PL 5 | — | Lãnh đạo, Thẩm định | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |
| Kéo thả xếp nhóm, ghi lý do, lưu nháp | Lãnh đạo, Thẩm định | — | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |
| Trình tập thể lãnh đạo | Lãnh đạo | — | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |

### SCR-1101 Hội nghị kiểm điểm

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Tạo hội nghị kiểm điểm | Thẩm định | — | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |
| Gửi dự thảo bản kiểm điểm | Cán bộ | — | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |
| Tải dự thảo Mẫu 1B, gửi góp ý | Lãnh đạo, Quản lý, Cán bộ, Thẩm định | — | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |
| Ghi ý kiến tại hội nghị, sinh biên bản (thư ký) | Cán bộ, Thẩm định | — | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |
| Kết thúc hội nghị, khởi động ký biên bản (chủ trì) | Lãnh đạo | — | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |

### SCR-1102 Gói hồ sơ số và ký số

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Theo dõi sinh biểu mẫu, sinh lại mẫu lỗi, xem biểu mẫu | Thẩm định | Cấp quyết định | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |
| Tải gói ZIP và manifest | — | Thẩm định, Cấp quyết định | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |
| Ký số gói và nộp cấp trên | Cấp quyết định | — | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |
| Gửi gói con diện Trung ương | Thẩm định | — | Trống | Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). | — |

### SCR-1201 Gửi khiếu nại

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Soạn, lưu nháp, đính kèm khiếu nại (mã hoá nội dung) | Cán bộ | — | Trống | Chỉ có sau khi kỳ đã khoá và có kết quả xếp loại (KB-X4). | — |
| Ký số và gửi khiếu nại | Cán bộ | — | Trống | Chỉ có sau khi kỳ đã khoá và có kết quả xếp loại (KB-X4). | — |
| Rút khiếu nại, xác nhận bảo lưu ý kiến | Cán bộ | — | Trống | Chỉ có sau khi kỳ đã khoá và có kết quả xếp loại (KB-X4). | — |

### SCR-1202 Xử lý khiếu nại

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Tiếp nhận hoặc không thụ lý khiếu nại | Thẩm định | — | Trống | Chỉ có sau khi kỳ đã khoá và có kết quả xếp loại (KB-X4). | — |
| Yêu cầu giải trình; bên liên quan gửi giải trình | Quản lý, Thẩm định | — | Trống | Chỉ có sau khi kỳ đã khoá và có kết quả xếp loại (KB-X4). | — |
| Tính thử điều chỉnh, xem văn bản trả lời | — | Thẩm định | Trống | Chỉ có sau khi kỳ đã khoá và có kết quả xếp loại (KB-X4). | — |
| Ký số văn bản trả lời khiếu nại | Cấp quyết định | — | Trống | Chỉ có sau khi kỳ đã khoá và có kết quả xếp loại (KB-X4). | — |
| Tạo yêu cầu mở lại kỳ từ kết luận khiếu nại | Thẩm định | — | Trống | Chỉ có sau khi kỳ đã khoá và có kết quả xếp loại (KB-X4). | — |

### SCR-1301 Yêu cầu mở lại kỳ

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Tạo yêu cầu mở lại kỳ, tính thử ảnh hưởng | Thẩm định, Kiểm tra | — | Trống | Chỉ có sau khi kỳ đã khoá và có kết quả xếp loại (KB-X4). | — |
| Phê duyệt thứ nhất (step-up MFA, ký số) | Cấp quyết định | — | Trống | Chỉ có sau khi kỳ đã khoá và có kết quả xếp loại (KB-X4). | — |
| Phê duyệt thứ hai (step-up MFA, ký số), mở lại kỳ | Duyệt thứ hai | — | Trống | Chỉ có sau khi kỳ đã khoá và có kết quả xếp loại (KB-X4). | — |
| Từ chối yêu cầu mở lại kỳ | Cấp quyết định, Duyệt thứ hai | — | Trống | Chỉ có sau khi kỳ đã khoá và có kết quả xếp loại (KB-X4). | — |
| Huỷ yêu cầu trước khi đủ phê duyệt | Thẩm định, Kiểm tra | — | Trống | Chỉ có sau khi kỳ đã khoá và có kết quả xếp loại (KB-X4). | — |

### SCR-1302 Tra cứu sổ cái và kiểm tra toàn vẹn

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Tra cứu sổ cái, xem bản ghi, niêm phong, bản neo | — | Thẩm định, Kiểm tra | Có | Sổ cái ghi mọi thao tác đã nạp (duyệt mục tiêu, giao việc, quyết định đề nghị, cấp quyền) | `tu.chuyenviencb`, `cq.chuyenviennv`, `dn.truongphongtchc` |
| Chạy kiểm tra toàn vẹn chuỗi băm | Kiểm tra | — | Có | Sổ cái ghi mọi thao tác đã nạp (duyệt mục tiêu, giao việc, quyết định đề nghị, cấp quyền) | `tu.ubkt`, `cq.thanhtra`, `dn.kiemsoatvien` |
| Xuất bằng chứng (JSON và chữ ký) | — | Kiểm tra | Có | Sổ cái ghi mọi thao tác đã nạp (duyệt mục tiêu, giao việc, quyết định đề nghị, cấp quyền) | `tu.ubkt`, `cq.thanhtra`, `dn.kiemsoatvien` |

### SCR-1401 Trung tâm cảnh báo

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem, lọc cảnh báo, xem chuỗi bằng chứng, mở sổ cái | — | Thẩm định, Kiểm tra | Trống | Cảnh báo do luật phát hiện sinh ra từ dữ liệu thật (check-in nhảy vọt, OKR chép nhau); dữ liệu nạp đã có check-in nhảy hơn 50% nên có thể xuất hiện, chưa xác nhận trên dev. | — |
| Xác nhận hợp lệ hoặc giao kiểm tra | Thẩm định, Kiểm tra | — | Trống | Cảnh báo do luật phát hiện sinh ra từ dữ liệu thật (check-in nhảy vọt, OKR chép nhau); dữ liệu nạp đã có check-in nhảy hơn 50% nên có thể xuất hiện, chưa xác nhận trên dev. | — |
| Kết luận kiểm tra (có vi phạm thì mở SCR-1301) | Kiểm tra | — | Trống | Cảnh báo do luật phát hiện sinh ra từ dữ liệu thật (check-in nhảy vọt, OKR chép nhau); dữ liệu nạp đã có check-in nhảy hơn 50% nên có thể xuất hiện, chưa xác nhận trên dev. | — |
| Cấu hình ngưỡng phát hiện | QT nghiệp vụ | — | Thử trực tiếp | Cấu hình ngưỡng của tenant | `tu.chuyenvientcdv`, `cq.truongphongccvc`, `dn.chuyenviennhansu` |

### SCR-1501 Config Studio – công thức

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Soạn công thức, lưu nháp, xem trước, so sánh phiên bản | QT nghiệp vụ | QT tenant | Có | Policy Pack mẫu bản nháp của từng tenant (khối Đảng: Quy định mới; chính quyền, doanh nghiệp: NĐ 335) | `tu.chuyenvientcdv`, `cq.truongphongccvc`, `dn.chuyenviennhansu` |
| Gửi phê duyệt phát hành Policy Pack | QT nghiệp vụ | — | Có | Policy Pack mẫu bản nháp của từng tenant (khối Đảng: Quy định mới; chính quyền, doanh nghiệp: NĐ 335) | `tu.chuyenvientcdv`, `cq.truongphongccvc`, `dn.chuyenviennhansu` |

### SCR-1502 Bảng quyết định xếp loại

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Soạn bảng quyết định DMN, lưu nháp, xuất DMN XML | QT nghiệp vụ | — | Có | Ba bảng quyết định xếp loại nhập từ gói mẫu | `tu.chuyenvientcdv`, `cq.truongphongccvc`, `dn.chuyenviennhansu` |

### SCR-1503 Chạy test case vàng

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Thêm ca kiểm thử, chạy lại bộ test case vàng | QT nghiệp vụ | — | Có | Bộ ca mẫu đã duyệt của gói (63 ca khối Đảng, bộ ca NĐ 335 cho hai tenant còn lại) | `tu.chuyenvientcdv`, `cq.truongphongccvc`, `dn.chuyenviennhansu` |
| Xem kết quả test khi phê duyệt | — | Cấp quyết định | Có | Có sau khi quản trị nghiệp vụ gửi phê duyệt phát hành (KB-X4) | `tu.bithu`, `cq.chutich`, `dn.chutich` |
| Ký số phê duyệt phát hành Policy Pack | Cấp quyết định | — | Có | Có sau khi gửi phê duyệt; ký số cần xác thực bước lên trên dev | `tu.bithu`, `cq.chutich`, `dn.chutich` |

### SCR-1504 Cấu hình luồng duyệt (mẫu + tham số)

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Chọn mẫu luồng, mô phỏng, lưu tham số luồng duyệt | QT nghiệp vụ | — | Trống | Tenant thử chưa gắn mẫu luồng duyệt; tiến trình theo mẫu chỉ xuất hiện khi gắn mẫu ở màn hình cấu hình luồng duyệt. | — |

### SCR-1505 Thư viện và trình soạn mẫu luồng (nội bộ nền tảng, BS-01)

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem thư viện mẫu luồng: phiên bản, trạng thái, số tenant đang dùng, số test | — | Duyệt thứ hai | Ngoài phạm vi | Vai trò quản trị nền tảng, mẫu luồng nội bộ nền tảng: tenant thử tạo bằng SQL, không có tài khoản AC-01. | — |
| Soạn, sửa mẫu luồng bằng bpmn-js, khai báo tham số; linter bpmnlint chạy ngay trên trình duyệt | — | — | Ngoài phạm vi | Vai trò quản trị nền tảng, mẫu luồng nội bộ nền tảng: tenant thử tạo bằng SQL, không có tài khoản AC-01. | — |
| Chạy bộ test kịch bản và thử tải rút gọn trên engine kiểm thử riêng | — | — | Ngoài phạm vi | Vai trò quản trị nền tảng, mẫu luồng nội bộ nền tảng: tenant thử tạo bằng SQL, không có tài khoản AC-01. | — |
| Đề nghị phát hành: tạo merge request vào repo mẫu luồng kèm BPMN, tham số, test | — | — | Ngoài phạm vi | Vai trò quản trị nền tảng, mẫu luồng nội bộ nền tảng: tenant thử tạo bằng SQL, không có tài khoản AC-01. | — |
| Phê duyệt phát hành mẫu luồng (bốn mắt), ký số gói mẫu | Duyệt thứ hai | — | Ngoài phạm vi | Vai trò quản trị nền tảng, mẫu luồng nội bộ nền tảng: tenant thử tạo bằng SQL, không có tài khoản AC-01. | — |
| Theo dõi saga triển khai cuốn chiếu lên engine từng cell, bù trừ về phiên bản trước | — | — | Ngoài phạm vi | Vai trò quản trị nền tảng, mẫu luồng nội bộ nền tảng: tenant thử tạo bằng SQL, không có tài khoản AC-01. | — |

### SCR-1506 Giám sát tiến trình theo mẫu (BS-01)

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem tiến trình đang chạy theo mẫu, phát hiện tiến trình kẹt | — | QT tenant | Trống | Tenant thử chưa gắn mẫu luồng duyệt; tiến trình theo mẫu chỉ xuất hiện khi gắn mẫu ở màn hình cấu hình luồng duyệt. | — |
| Chuyển người xử lý của user task khi cán bộ nghỉ hoặc chuyển công tác | QT tenant | — | Trống | Tenant thử chưa gắn mẫu luồng duyệt; tiến trình theo mẫu chỉ xuất hiện khi gắn mẫu ở màn hình cấu hình luồng duyệt. | — |
| Gia hạn thời hạn của một bước | QT tenant | — | Trống | Tenant thử chưa gắn mẫu luồng duyệt; tiến trình theo mẫu chỉ xuất hiện khi gắn mẫu ở màn hình cấu hình luồng duyệt. | — |
| Xem sơ đồ tiến trình với bước hiện tại | — | QT tenant | Trống | Tenant thử chưa gắn mẫu luồng duyệt; tiến trình theo mẫu chỉ xuất hiện khi gắn mẫu ở màn hình cấu hình luồng duyệt. | — |

### SCR-1601 Bảng điều hành lãnh đạo

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem bảng điều hành, so với quý trước, xuất báo cáo | — | Lãnh đạo, Quản lý, Kiểm tra | Có | Bảng điều hành tổng hợp từ 34 nhiệm vụ, 27 bộ mục tiêu, 63 check-in; có kỳ trước để so sánh | `tu.chanhvp`, `cq.chanhvp`, `dn.chutich` |
| Nhắc người chủ trì việc chậm | Lãnh đạo, Quản lý | — | Có | Đã có một lần nhắc việc mỗi tenant; nhắc thêm trên việc chậm khác | `tu.chanhvp`, `cq.chanhvp`, `dn.chutich` |
| Giao lại nhiệm vụ trong phạm vi | Lãnh đạo, Quản lý | — | Có | Đã có một lần giao lại mỗi tenant; giao lại việc đã giao chưa nhận | `tu.chanhvp`, `cq.chanhvp`, `dn.chutich` |
| Xem dữ liệu AI đã dùng cho tóm tắt | — | Lãnh đạo, Quản lý | Trống | Dịch vụ trợ lý AI chưa bật trên môi trường dev; bỏ qua. | — |

### SCR-1602 Trình dựng dashboard

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Dựng, xem trước, lưu dashboard | Lãnh đạo, Quản lý | — | Thử trực tiếp | Dựng dashboard từ dữ liệu nhiệm vụ, mục tiêu đã có | `tu.chanhvp`, `cq.chanhvp`, `dn.chutich` |
| Chia sẻ dashboard (`RIENG`, `DON_VI`, `TENANT`) | Lãnh đạo, Quản lý | — | Thử trực tiếp | Dựng dashboard từ dữ liệu nhiệm vụ, mục tiêu đã có | `tu.chanhvp`, `cq.chanhvp`, `dn.chutich` |

### SCR-1603 Đi xuống tới minh chứng

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Xem chuỗi truy vết từ điểm tới minh chứng và ngược lên mục tiêu | — | Lãnh đạo, Quản lý, Thẩm định, Kiểm tra | Trống | Minh chứng là tệp thật phải qua quét vi-rút của dịch vụ minh chứng, không nạp sẵn; nộp tệp bất kỳ ở kịch bản KB-X5. | — |
| Mở tệp minh chứng (ghi nhật ký truy cập) | — | Lãnh đạo, Quản lý, Thẩm định, Kiểm tra | Trống | Minh chứng là tệp thật phải qua quét vi-rút của dịch vụ minh chứng, không nạp sẵn; nộp tệp bất kỳ ở kịch bản KB-X5. | — |
| Xuất chuỗi bằng chứng JSON và PDF | — | Lãnh đạo, Quản lý, Thẩm định, Kiểm tra | Trống | Minh chứng là tệp thật phải qua quét vi-rút của dịch vụ minh chứng, không nạp sẵn; nộp tệp bất kỳ ở kịch bản KB-X5. | — |

### SCR-1701 Trợ lý AI soạn OKR và soát KR

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Hỏi trợ lý AI, nhận gợi ý OKR, cảnh báo KR mơ hồ | Quản lý, Cán bộ | — | Trống | Dịch vụ trợ lý AI chưa bật trên môi trường dev; bỏ qua. | — |
| Dùng hoặc bỏ gợi ý, lưu nháp, xem lịch sử nháp | Quản lý, Cán bộ | — | Trống | Dịch vụ trợ lý AI chưa bật trên môi trường dev; bỏ qua. | — |
| Trình phê duyệt OKR từ trợ lý | Quản lý, Cán bộ | — | Trống | Dịch vụ trợ lý AI chưa bật trên môi trường dev; bỏ qua. | — |

### SCR-1801 Cấu hình giao diện tenant

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Soạn token, bố cục, chế độ cao tuổi, thêm widget, xem trước | QT tenant | — | Thử trực tiếp | UI Pack chỉ nạp được khi máy nạp có Python 3.10 trở lên; máy chủ dev bỏ qua, quản trị tự soạn. | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Gửi duyệt UI Pack | QT tenant | — | Thử trực tiếp | UI Pack chỉ nạp được khi máy nạp có Python 3.10 trở lên; máy chủ dev bỏ qua, quản trị tự soạn. | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Duyệt và phát hành UI Pack | QT tenant | — | Thử trực tiếp | UI Pack chỉ nạp được khi máy nạp có Python 3.10 trở lên; máy chủ dev bỏ qua, quản trị tự soạn. | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Quay lui về phiên bản `THAY_THE` tương thích | QT tenant | — | Thử trực tiếp | UI Pack chỉ nạp được khi máy nạp có Python 3.10 trở lên; máy chủ dev bỏ qua, quản trị tự soạn. | `tu.quantri`, `cq.quantri`, `dn.quantri` |

### SCR-1802 Trình dựng biểu mẫu động

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| Dựng biểu mẫu, lưu nháp, chạy bộ test biểu mẫu | QT tenant, QT nghiệp vụ | — | Thử trực tiếp | Dựng biểu mẫu động mới cho tenant | `tu.quantri`, `cq.quantri`, `dn.quantri` |
| Đưa biểu mẫu vào UI Pack nháp | QT tenant | — | Thử trực tiếp | Dựng biểu mẫu động mới cho tenant | `tu.quantri`, `cq.quantri`, `dn.quantri` |

### SCR-1803 Chế độ Đảng viên cao tuổi (di động)

| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |
|---|---|---|---|---|---|
| [Di động] Chế độ cao tuổi: xác nhận sinh hoạt, tự đánh giá rút gọn, nhập giọng nói | Cán bộ | — | Ngoài phạm vi | Ứng dụng di động đã bỏ khỏi phạm vi (quyết định 24/10/2026). | — |

## 4. Tổng hợp

| Tình trạng | Số chức năng |
|---|---|
| Có | 61 |
| Thử trực tiếp | 26 |
| Trống | 76 |
| Ngoài phạm vi | 16 |

Các nhóm còn trống và cách mở khoá:

- Khoá mã hoá đầu cuối gắn với thiết bị người dùng, không giả lập được; thử trực tiếp khi có chứng thư. Màn hình: SCR-0001, SCR-0103, SCR-0501.
- Phụ thuộc hệ thống ngoài (văn phòng điện tử, CSDL cán bộ công chức viên chức) chỉ có bản giả lập; không có thông điệp đến. Màn hình: SCR-0104, SCR-0502.
- Dịch vụ trợ lý AI chưa bật trên môi trường dev; bỏ qua. Màn hình: SCR-0202, SCR-1601, SCR-1701.
- Đăng ký nhiệm vụ cần mục danh mục gắn vị trí việc làm; dữ liệu tổ chức thử chưa có vị trí việc làm. Màn hình: SCR-0503.
- Minh chứng là tệp thật phải qua quét vi-rút của dịch vụ minh chứng, không nạp sẵn; nộp tệp bất kỳ ở kịch bản KB-X5. Màn hình: SCR-0701, SCR-0703, SCR-1603.
- Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước). Màn hình: SCR-0901, SCR-0902, SCR-0903, SCR-0904, SCR-1001, SCR-1002, SCR-1101, SCR-1102.
- Chỉ có sau khi kỳ đã khoá và có kết quả xếp loại (KB-X4). Màn hình: SCR-1201, SCR-1202, SCR-1301.
- Cảnh báo do luật phát hiện sinh ra từ dữ liệu thật (check-in nhảy vọt, OKR chép nhau); dữ liệu nạp đã có check-in nhảy hơn 50% nên có thể xuất hiện, chưa xác nhận trên dev. Màn hình: SCR-1401.
- Tenant thử chưa gắn mẫu luồng duyệt; tiến trình theo mẫu chỉ xuất hiện khi gắn mẫu ở màn hình cấu hình luồng duyệt. Màn hình: SCR-1504, SCR-1506.

## Liên quan

- [Kịch bản trải nghiệm đa tenant](KICH-BAN-DA-TENANT.md)
- [Ma trận phân quyền](../../wiki/15a-ma-tran-phan-quyen.md)
- [Bộ dữ liệu thử đa tenant](../../data/seed/da-tenant/README.md)
