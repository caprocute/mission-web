> Nguồn: TL-04 mục 9.7 "Dữ liệu cá nhân (SEC-52)", chương 8 và chương 9; baseline phân lớp dữ liệu C1..C3; Nghị định 13/2023/NĐ-CP; task DL-15. Ngày đánh giá: 23/10/2026.

# Đóng góp cho hồ sơ đánh giá tác động xử lý dữ liệu cá nhân (SEC-52)

Theo Nghị định 13/2023/NĐ-CP, hệ thống xử lý dữ liệu cá nhân nhạy cảm như kết quả đánh giá, xếp loại và kỷ luật phải có hồ sơ đánh giá tác động xử lý dữ liệu cá nhân. Hồ sơ đó do đơn vị chủ quản lập. Trang này chỉ cung cấp phần mô tả biện pháp kỹ thuật, theo đúng phân công của TL-04: TL-04 đóng góp nội dung từ chương 8 và chương 9.

## 1. Phân lớp và bảo vệ theo lớp

Baseline phân dữ liệu thành ba lớp, và mỗi lớp có một cách bảo vệ riêng.

Lớp C1 là dữ liệu nội bộ thông thường, như tên nhiệm vụ hay cơ cấu tổ chức. Lớp này được bảo vệ bằng kiểm soát truy cập theo vai trò và phạm vi đơn vị (OPA, SEC-18), cách ly tenant bằng Row Level Security của PostgreSQL và mã hoá đường truyền.

Lớp C2 là dữ liệu cá nhân nhạy cảm, như nhận xét, lý do xếp loại hay thông tin liên lạc. Ngoài các biện pháp của C1, mỗi trường C2 được mã hoá phong bì bằng khoá riêng của tenant (SEC-11, xem [bằng chứng 02](bang-chung/02-khoa-hsm.md)). Trường cần tìm kiếm dùng chỉ mục mù. DBA không đọc được bản rõ nếu không qua quy trình phá kính (SEC-58), và mỗi lần đọc C2 đều ghi nhật ký kiểm toán.

Lớp C3 là nội dung mật. Lớp này chỉ xử lý theo luồng mã hoá đầu cuối (SEC-25): máy chủ chỉ giữ bản mã và bản bọc khoá cho người nhận, không giữ khoá giải mã. Phần khôi phục khoá theo tổ chức nằm ở `backend/services/org-service/src/main/java/vn/viettel/okrkpi/org/application/e2ee/RecoveryKeyService.java`.

## 2. Giảm thiểu và thời hạn lưu

Nhật ký ứng dụng và nhật ký hạ tầng được che thông tin cá nhân trước khi lưu (SEC-53), ở hai lớp: trong ứng dụng (`backend/libs/vt-platform-starter/src/main/java/vn/viettel/okrkpi/shared/observability/RedactingRewritePolicy.java`) và ở Fluent Bit (`deploy/charts/observability-cell/files/redact.lua`). Thời hạn lưu, việc xoá và khôi phục có kiểm soát được thực hiện qua thư viện `backend/libs/retention` và runbook `ops/runbooks/luu-tru-xoa-khoi-phuc.md`. Nhật ký kiểm toán được giữ 10 năm trong kho bất biến (`deploy/charts/audit-archiver/values.yaml`).

## 3. Không dùng dữ liệu thật ngoài sản xuất

Môi trường phát triển, kiểm thử, staging và môi trường tạm theo nhánh không dùng dữ liệu thật. Dữ liệu thử là dữ liệu tổng hợp do bộ sinh dữ liệu tạo ra (`infra/perf/datagen/datagen/bang.py`, `data/seed/perf/cell-s.yaml`). Khi cần dữ liệu gần thật để tái hiện lỗi, bản sao CSDL được che trên cụm cách ly bằng tác vụ `anonymize` (`infra/anonymize/README.md`, luật che ở `data/anonymize/rules.yaml`) rồi mới chuyển sang staging.

Tác vụ này thay họ tên, số định danh, ngày sinh, số thẻ đảng viên, thông tin liên lạc, tên tệp và nhận xét bằng giá trị tổng hợp giữ phân phối. Tác vụ cũng mã hoá lại trường C2 bằng khoá của staging và loại bỏ bảng chứa tải trọng sự kiện cùng bản bọc khoá E2EE. Cuối cùng, tác vụ quét lại toàn bộ để chứng minh không còn giá trị nguồn nào. Mỗi lần chạy sinh một biên bản JSON không chứa giá trị dữ liệu; biên bản này là bằng chứng cho hồ sơ. Cấu hình môi trường thử ghi rõ ràng buộc này ở `deploy/environments/staging/README.md` và `deploy/environments/pr/values.yaml`.

## 4. Trạng thái

Trạng thái: **Một phần**. Biện pháp kỹ thuật và tác vụ che đã có, kèm kiểm thử (`infra/anonymize/tests/test_danh_muc.py`). Chưa có biên bản chạy tác vụ trên một bản sao thật, vì chưa có môi trường sản xuất. Hồ sơ đánh giá tác động hoàn chỉnh thuộc đơn vị chủ quản.
