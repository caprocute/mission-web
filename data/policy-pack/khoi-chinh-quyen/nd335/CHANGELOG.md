# Nhật ký thay đổi – Bộ quy định đánh giá luồng Nhà nước (`khoi-chinh-quyen/nd335`)


### Bản giả lập (20/10/2026)

Theo quyết định của người dùng ngày 20/10/2026, gói này là bản giả lập (`metadata.gia_lap: true`) dùng để phát triển và kiểm thử, chưa phải nội dung chính thức. Khi cơ quan có thẩm quyền ban hành công thức, ngưỡng, tham số hay mẫu biểu chính thức, chỉ phát hành phiên bản mới của gói với `gia_lap: false`, không sửa mã.
## 1.4.0 – bản nháp (chưa phê duyệt)

Trạng thái: `NHAP`. Bản nháp do agent soạn theo NĐ 335/2025 và bộ tài liệu thiết kế (TL-01 chương Policy Pack, TL-02b BR-09-11, BR-15-05) để cơ quan có thẩm quyền đối chiếu và duyệt. Chưa có người soạn, người duyệt, chữ ký số; chưa có bộ ca kiểm thử chuẩn.

### Định danh gói

Định danh `<khối>/<tên>` là `khoi-chinh-quyen/nd335`, lấy đúng ví dụ của TL-01 (bản ghi `wf_binding` "Policy Pack `khoi-chinh-quyen/nd335@1.4`" ở chương điều phối luồng, và UI Pack khai `requires.policyPack: khoi-chinh-quyen/nd335@>=1.2.0 <2.0.0`). Phiên bản 1.4.0 khớp ví dụ `@1.4` và nằm trong dải UI Pack yêu cầu (suy luận; TL-02b SCR-0901 nhắc "NĐ 335 v1.3" đang áp dụng).

### Nội dung

- Cơ cấu 30 điểm tiêu chí chung và 70 điểm kết quả nhiệm vụ; tổng 100 (BR-09-11, BR-15-05).
- Công thức KPI nhiệm vụ dùng chung với gói luồng Đảng (cùng dữ liệu sản phẩm, minh chứng, một lần nhập).
- Bảng xếp loại `dmn/xep-loai-nha-nuoc.dmn`, hit policy FIRST: từ 90 điểm HTXSNV, 70 đến dưới 90 HTTNV, 50 đến dưới 70 HTNV, dưới 50 KHTNV (BR-09-11).
- Kết quả lưu ảnh chụp điểm riêng, không ghi đè ảnh chụp luồng Đảng (BR-09-11).
- Làm tròn 1 chữ số, kiểu `NUA_LEN` (theo TL-02b, như gói luồng Đảng).
- Bảng chuyển trạng thái nhiệm vụ tham chiếu `khoi-dang/quy-dinh-moi@2.1.0#task_lifecycle`; tham số luồng duyệt hồ sơ ở `workflow-bindings/`.

### Điểm suy luận (chờ nghiệp vụ xác nhận)

- (suy luận) Điểm tiêu chí chung = 30 × tỷ lệ đáp ứng tiêu chí chung; TL chưa nêu cách chấm 30 điểm.
- (suy luận) 70 điểm kết quả nhiệm vụ là một nhóm gồm cả nhiệm vụ thường xuyên và then chốt; TL chưa nêu cách chia 70 điểm theo nhóm.
- (suy luận) Không có điều kiện phụ (tỷ lệ O, tỷ lệ vượt, loại trừ) trong bảng xếp loại luồng Nhà nước vì BR-09-11 chỉ nêu ngưỡng điểm.
- (suy luận) Hạn mức HTXSNV 20% áp cả cho luồng Nhà nước.
- (suy luận) Luồng duyệt hồ sơ ba cấp (tự đánh giá, thẩm định, thủ trưởng quyết định), thời hạn mỗi bước 3 ngày làm việc.
- (suy luận) Căn cứ ghi cả NĐ 233/2026 (viên chức) theo bảng căn cứ TL-02a; chưa rõ viên chức dùng chung gói này hay gói chuyên ngành riêng.
- Chưa có bảng xếp loại năm và bảng trường hợp đặc thù cho luồng Nhà nước.

### Còn nợ

- Kiểm thử nạp gói trong `backend/mission-core` (CEL bằng `vt-cel`, DMN bằng Flowable DMN, jqwik, API CORE-05-xx): chưa làm vì chưa có mô-đun và thư viện.
- Bộ ca kiểm thử chuẩn (DL-03), mẫu biểu (DL-05).
