# DV-13-10 · SVC-13/baseline §8.7 Gói chuyển hồ sơ cán bộ liên cell có ký số

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-13-10` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 |
| Luồng đề xuất | B5 |
| Cỡ việc | M |
| Phụ thuộc | DV-13-02, DV-10-01 |
| Truy vết | QT-01 (điều động cán bộ), QT-11 (hồ sơ số đã ký) → SVC-13 → bảng `integ.transfer_package`, `integ.connector` → sổ cái hai phía (SVC-09) → baseline mục 8 cơ chế 7 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực luồng thứ ba giữa các cell: khi cán bộ chuyển từ tenant ở cell này sang tenant ở cell khác, SVC-13 cell nguồn đóng gói hồ sơ cần mang theo (kết quả xếp loại các kỳ, hồ sơ số đã ký, nhận xét được phép chuyển), mã hoá, ký, bọc khoá bằng khoá công khai của cell nhận; cell nhận kiểm chữ ký, giải mã, nhập vào SVC-04 và MOD-06 của tenant đích. Khi xong, điều động liên cell không cần kết nối CSDL nào vượt ranh giới cell và cả hai phía có vết trong sổ cái.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 8 (cơ chế 7: giữa các cell).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Đồng bộ giữa các cell" (L4392–4404, luồng thứ ba), từ điển `integ.transfer_package` (L3943).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "Chuyển tenant giữa các cell" (L2738) để phân biệt với chuyển cả tenant; "Phong bì sự kiện" (L1285).
- Bản trích `extract/tl03-phan2.md` mục A11, B10.

## 3. Phạm vi công việc

- Viết use case đóng gói ở cell nguồn: nhận yêu cầu điều động đã duyệt (từ org-service, cơ chế nội bộ suy luận), thu thập dữ liệu được phép chuyển qua cổng nội bộ của MOD-04/MOD-06/SVC-04 dưới danh tính dịch vụ có ghi kiểm toán, dựng `manifest` (danh sách tệp, băm), tính `package_sha256`.
- Mã hoá gói bằng khoá nội dung ngẫu nhiên AES-256-GCM, bọc khoá bằng khoá công khai của cell nhận (`wrapped_key`), ký manifest qua esign-service (chữ ký tổ chức), lưu `transfer_package` `DONG_GOI`, đặt tệp vào bucket `transfer` (governance 1 năm).
- Gửi qua kênh liên cell mTLS, cập nhật `DA_GUI`; cell nhận lưu `direction = DEN`, kiểm chữ ký và băm, giải mã, chuyển trạng thái `DA_NHAN` → `DA_NHAP` khi SVC-04 và MOD-06 xác nhận nhập; sai chữ ký hoặc băm thì `TU_CHOI` có lý do và báo cell nguồn.
- Ghi sự kiện chuyển và nhận vào sổ cái cả hai phía (qua outbox, loại sự kiện kiểm toán `sys.audit.v1` hoặc sự kiện được TL quy định; không đặt mã EVT mới).
- Danh bạ khoá công khai các cell lấy từ vùng điều phối toàn cục (tenant-registry hoặc cấu hình ký), kiểm chuỗi tin cậy trước khi dùng (suy luận về nơi lưu).

## 4. Ngoài phạm vi

- Chuyển cả tenant giữa các cell (DV-03-07); use case nhập ở SVC-04 và MOD-06 (task DV-04, CORE-06 tương ứng; ghi rõ nếu còn thiếu).

## 5. Tệp được phép sửa

- `backend/services/integration-gateway/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/integration-gateway -am verify` xanh, gồm kiểm thử hai "cell" giả lập trong cùng Testcontainers (hai CSDL, hai cặp khoá): gói đi từ nguồn tới đích đạt `DA_NHAP`; sửa một byte gói thì đích `TU_CHOI`.
- Không có khoá nội dung dạng rõ trong CSDL, kho đối tượng hay nhật ký; gói trong bucket chỉ là bản mã.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki trang 14 ghi các giả định về danh bạ khoá cell.

## 7. Báo cáo

Theo AGENTS.md mục 6.
