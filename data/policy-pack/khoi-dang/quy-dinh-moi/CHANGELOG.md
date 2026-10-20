# Nhật ký thay đổi – Bộ quy định đánh giá khối Đảng (`khoi-dang/quy-dinh-moi`)

## 2.1.0 – bản nháp (chưa phê duyệt)

Trạng thái: `NHAP`. Bản nháp do agent soạn theo dự thảo Quy định mới, QĐ 366-QĐ/TW và bộ tài liệu thiết kế (TL-01 chương Policy Pack, TL-02b QT-09, QT-10, QT-11, QT-15, TL-03 schema `policy`), để cơ quan tổ chức đối chiếu và duyệt. Chưa có người soạn, người duyệt, chữ ký số; chưa có bộ ca kiểm thử chuẩn (DL-03). Chưa được dùng để tính điểm chính thức.

### Nội dung

- Công thức điểm nhiệm vụ: KPI = (A + B + C) / 3; A = min(số lượng được nghiệm thu / kế hoạch, 1), kế hoạch bằng 0 thì A = 1; B là mức chất lượng năm mức (hoặc trừ 25% mỗi lần chỉnh sửa khi chọn tham số `TRU_25_MOI_LAN`); C = max(0, 1 − 0,05 × số ngày chậm do chủ quan). Căn cứ BR-09-01, BR-15-02, BR-15-03.
- Điểm kết quả = Σ (KPI nhóm × điểm tối đa nhóm), KPI nhóm là trung bình KPI các nhiệm vụ trong nhóm; nhóm thường xuyên 30, then chốt 70, tổng 100 (BR-09-03, BR-15-05).
- Tỷ lệ nhiệm vụ vượt mục tiêu ghi riêng, không cộng điểm (BR-09-04); ngưỡng 30%, cách so sánh và cách đếm là tham số (BR-15-04).
- Quy đổi tiêu chí chung bốn mức (BR-09-05) ở `dmn/tieu-chi-chung.dmn`.
- Bảng xếp loại quý `dmn/xep-loai-dang.dmn`, hit policy FIRST, sáu dòng đúng thứ tự TL-02b: dòng 1–3 loại trừ (dưới 60 điểm, tiêu chí chung Không đảm bảo, điều kiện loại trừ), dòng 4 HTXSNV, dòng 5 HTTNV (mức liền kề), dòng 6 HTNV (BR-09-07..BR-09-10). Mỗi dòng có `legal_ref`, điều khoản hiển thị và mẫu câu giải thích ≤ 200 ký tự.
- Quy tắc mức cuối `muc_cuoi` = `min_muc`(mức theo kết quả nhiệm vụ, mức từ tiêu chí chung) (BR-09-06), quy tắc người đứng đầu `nguoi_dung_dau` (BR-09-13), ràng buộc liên luồng mức đảng viên không cao hơn mức công chức với người giữ chức vụ (TL-01 "Hai luồng Đảng và Nhà nước").
- Xếp loại năm `dmn/xep-loai-nam-dang.dmn`: dòng chặn HTXSNV khi có quý KHTNV đứng trước dòng HTXSNV (BR-10-03), điều kiện năm tính trên số liệu cả năm (BR-10-05), tham số `nam.cach_tinh` mặc định `TRUNG_BINH_QUY` (BR-10-02), cờ bố trí lại khi hai quý liên tiếp KHTNV (BR-10-04).
- Phân nhóm triển vọng: `phan_nhom.mo_o_ky_quy = false`, bốn nhóm theo PL 5, Nhóm 1 và Nhóm 2 loại trừ người KHTNV năm hoặc có cờ bố trí lại (BR-10-07, BR-10-09).
- Hạn mức HTXSNV 20% trong phạm vi đơn vị cấp trên, chỉ đánh dấu vượt, không tự hạ mức (BR-09-17).
- Bảng 13 trường hợp đặc thù §1–§13 (BR-11-05).
- Bảng chuyển trạng thái nhiệm vụ `task_lifecycle.yaml` và tham số luồng duyệt `workflow-bindings/` (DL-04); tham số vận hành mặc định trong `policy.yaml`.

### Lựa chọn khi nguồn lệch nhau

- Làm tròn: TL-01 (ví dụ `policy.yaml`) ghi `scale: 2, HALF_UP`; TL-02b BR-09-03 và SCR-1501 ghi 1 chữ số, nửa lên. Gói chọn 1 chữ số, kiểu `NUA_LEN` theo TL-02b (đặc tả nghiệp vụ mới hơn) và theo hợp đồng `PolicyRounding`. Chỉ làm tròn điểm cuối trước khi vào bảng xếp loại; KPI nhiệm vụ, KPI nhóm không làm tròn khi tính (suy luận).
- Điều kiện HTXSNV: ví dụ DMN của TL-01 chỉ có điểm, tỷ lệ O và tỷ lệ vượt; BR-09-07 thêm giải pháp đột phá được kiểm chứng và tiêu chí chung "Thực hiện tốt". Gói theo TL-02b.
- Quy tắc người đứng đầu: BR-09-13 ghi "quy tắc riêng trong DMN", còn SCR-1502 (trường 7) và hợp đồng (`RuleKey` ví dụ `nguoi_dung_dau`) ghi là quy tắc CEL. Gói viết thành quy tắc CEL `nguoi_dung_dau` áp trước `muc_cuoi`.
- Mức tiêu chí chung vừa là điều kiện trong từng dòng DMN (TL-02b, SCR-1502 cột đầu vào) vừa được áp lại bằng `min_muc` (SCR-1502 vùng 3). Hai cách cho cùng kết quả; giữ cả hai để truy vết đúng hai chỗ của TL.
- Tên biến SCR-1501 (`nv.ngay_cham_chu_quan`, `tham_so.tru_tien_do`) và TL-01 (`ngay_lam_viec_cham`) khác nhau; gói dùng `nv.ngay_lam_viec_cham_chu_quan` và `nv.ngay_lich_cham_chu_quan` để tham số `loai_ngay` chọn được.

### Điểm suy luận (chờ nghiệp vụ xác nhận)

- (suy luận) Đánh giá theo ba phạm vi `nhiem_vu` → `nhom` → `ho_so`, rồi phạm vi `xep_loai` sau DMN; các biến `nv`, `g`, `ket_qua_nhiem_vu`, `ket_qua_nhom` do engine dựng, khai báo ở `ngu_canh`. Cơ chế này cần NEN-041 và CORE-05-04 hiện thực.
- (suy luận) Tên nguồn API tổng hợp ngoài các tên đã có trong TL-01 (`task.aggregate.late_calendar_days`, `product.aggregate.revision_count`, `appraisal.aggregate.*`, `org.aggregate.*`…).
- (suy luận) Nhóm không có nhiệm vụ nào có KPI nhóm bằng 0.
- (suy luận) "Vượt mục tiêu" của một nhiệm vụ là số lượng được nghiệm thu lớn hơn kế hoạch; cách đếm "theo điểm" dùng điểm chuẩn của danh mục làm trọng số.
- (suy luận) Tham số `htxs.phep_so_sanh` (`TOI_THIEU` là ≥, `LON_HON` là >) diễn giải cụm "tối thiểu hay đúng" của BR-15-04.
- (suy luận) Tham số `tru_moi_lan_chinh_sua` = 0,25 cho phương án `TRU_25_MOI_LAN`; `ly_do_tra_lai` mới khai giá trị mặc định vì TL chưa đặt tên ba lựa chọn.
- (suy luận) Trung bình có trọng số của điểm năm dùng `nam.trong_so_quy` (mặc định bằng nhau); quý không xếp loại (trường hợp §4) không tính vào trung bình.
- (suy luận) Dòng chặn HTXSNV năm cho trường hợp mới tuyển dụng, bổ nhiệm dưới 6 tháng (§6).
- (suy luận) Khoá 13 trường hợp đặc thù (`DIEU_DONG_DUOI_2_THANG`… `NGUOI_DUNG_DAU_DON_VI_KHTNV`) do agent đặt theo ví dụ của TL-03; hiệu ứng ánh xạ vào CHECK `dossier.special_case.effect`. Thời gian công tác tối thiểu của §10 và bảng hình thức kỷ luật của §8 để trống chờ nghiệp vụ.
- (suy luận) Nội dung tiêu chí bốn nhóm triển vọng chỉ ghi tên nhóm và dẫn chiếu Phụ lục 5 vì TL chưa trích tiêu chí chi tiết.
- (suy luận) Đánh dấu `overridable` cho trọng số nhóm, ngưỡng tỷ lệ vượt, hạn mức HTXSNV và một số tham số vận hành; tenant kế thừa chỉ được siết, không được nới.
- (suy luận) Văn bản điều khoản hiển thị viết "Điều 11 của Quy định" vì cột căn cứ của TL-02b ghi "QĐ Điều 11" mà không nói rõ là QĐ 366 hay dự thảo Quy định mới.
- (suy luận) Trong `task_lifecycle.yaml`: tên trường của ngữ cảnh guard; guard của dòng `ACCEPT`, `SUBMIT`, `REWORK`, `RESUME`, `SUSPEND`, `CANCEL`; cập nhật tiến độ lần đầu phát lệnh `ACCEPT` ngầm; vai trò `leader` được tạm dừng, huỷ khi đề nghị vượt ngưỡng.
- (suy luận) Khoá `processKind` của `wf_binding` (`DANH_GIA`, `KE_HOACH_QUY`, `DUYET_OKR`, `KHIEU_NAI`, `MO_LAI_KY`), thời hạn bước của `duyet-okr`, `duyet-ke-hoach-quy`.
- `difficulty_coefficients` là giả định, chờ cơ quan tổ chức xác nhận.

### Còn nợ

- Kiểm thử nạp gói trong `backend/mission-core` (biên dịch CEL bằng `vt-cel`, kiểm thử thuộc tính jqwik, nạp DMN bằng Flowable DMN, `savePolicyRule`, `saveDecisionTable`, `evaluatePolicyVersion` trên Testcontainers): chưa làm vì chưa có mô-đun mission-core, thư viện `vt-cel` (NEN-041) và API CORE-05-xx.
- Bộ ca kiểm thử chuẩn (DL-03); mẫu biểu (DL-05).
