# VH-07 · NFR-08 Hồ sơ đề xuất cấp độ 4 theo TCVN 11930:2017

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh VH-07` để có prompt đầy đủ.

> **Cập nhật 24/10/2026 (wiki 14, mục "Quyết định điều chỉnh phạm vi của chủ dự án"):** ứng dụng di động (SVC-18, MOB-01..10) và đo tải, diễn tập DR (QA-08..QA-12, QA-14, QA-15) đã bỏ khỏi phạm vi; các chỗ nhắc tới chúng trong thẻ này chỉ còn giá trị lịch sử.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 (khung hồ sơ lập từ đợt 2) |
| Luồng đề xuất | B11 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | QA-13, INF-19 |
| Truy vết | NFR-08 → TL-04 mục 9.7 "Tuân thủ TCVN 11930:2017 cấp độ 4" → SEC-02..SEC-59 theo bảng ánh xạ, DR-14, OPS-18, OPS-22 → bằng chứng từ QA-13, QA-14, QA-15, INF-19 |
| Trạng thái | Chưa bắt đầu (làm với bản giả lập; nối hệ thật khi có môi trường) |

## 1. Mục tiêu

Lập hồ sơ đề xuất cấp độ 4 cho hệ thống: thuyết minh phân loại, bảng ánh xạ yêu cầu TCVN 11930 tới biện pháp kỹ thuật và mã SEC, DR, OPS, tám yêu cầu bổ sung đặc trưng cấp độ 4 với bằng chứng thật. Khi xong, mỗi dòng yêu cầu có bằng chứng truy được (cấu hình trong Git, báo cáo kiểm thử, biên bản diễn tập) hoặc được đánh dấu rõ là còn thiếu kèm kế hoạch.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục 9.7 (dòng 3140–3171: bảng nhóm yêu cầu, bảng tám yêu cầu cấp độ 4, SOC và SIEM SEC-43, dữ liệu cá nhân SEC-52), bảng SEC mục 9.2 (dòng 2519), mục 9.8 (tiêu chí kiểm thử bảo mật).
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` (NFR-08, phân lớp dữ liệu).
- `docs/tasks/QA-13-asvs-zap.md`, `INF-19-bao-mat-nen-tang.md`, `INF-13-openbao-pki-hsm.md`, `QA-14`, `QA-15` (biên bản diễn tập).

## 3. Phạm vi công việc

- `docs/attt/cap-do-4/thuyet-minh.md` (vị trí suy luận): mô tả hệ thống, phạm vi, căn cứ phân loại (Nghị định 85/2016/NĐ-CP, Thông tư 12/2022/TT-BTTTT theo TL-04), sơ đồ vùng mạng ở mức đủ cho hồ sơ (không chứa địa chỉ thật).
- `docs/attt/cap-do-4/anh-xa-yeu-cau.md`: chín nhóm yêu cầu TCVN 11930 × tiêu chí × mã SEC, DR, OPS theo bảng TL-04; mỗi dòng có cột bằng chứng (đường dẫn tệp cấu hình, báo cáo, biên bản) và trạng thái.
- Tám yêu cầu bổ sung cấp độ 4 (MFA bắt buộc, khoá theo khách hàng trong HSM, mTLS, PAM ghi phiên, SOC 24/7, sao lưu 3-2-1 bất biến, SBOM, build có chữ ký): mỗi yêu cầu một trang bằng chứng theo cột "Bằng chứng" của TL-04.
- Thu bằng chứng tự động: script `docs/attt/cap-do-4/thu-bang-chung.sh` xuất danh sách ảnh đã ký, SBOM theo phiên bản, nhật ký từ chối Kyverno, cấu hình PeerAuthentication, tỷ lệ người dùng có MFA (từ môi trường thử).
- Phần đóng góp cho hồ sơ đánh giá tác động dữ liệu cá nhân (SEC-52): mô tả biện pháp kỹ thuật ở chương 8 và 9, tác vụ `anonymize` cho môi trường phi sản xuất.
- Danh sách thiếu hụt: yêu cầu chưa có bằng chứng, nguyên nhân (mua sắm, chưa triển khai), người chịu trách nhiệm, hạn.

## 4. Ngoài phạm vi

- Thẩm định và cấp phép của cơ quan có thẩm quyền; kiểm thử xâm nhập độc lập (SEC-41); mua sắm HSM, PAM, dịch vụ SOC.
- Nối bằng chứng của HSM, PAM và dịch vụ SOC thật vào hồ sơ khi có; trong task này, các hạng mục đó lấy bằng chứng từ bản giả lập (SoftHSM, PAM giả lập) và ghi rõ là bằng chứng giả lập.

## 5. Tệp được phép sửa

- `docs/attt/cap-do-4/**`.

## 6. Tiêu chí hoàn thành

- Mọi dòng của hai bảng TL-04 mục 9.7 có mặt trong `anh-xa-yeu-cau.md` với bằng chứng hoặc trạng thái thiếu; script kiểm `python docs/attt/cap-do-4/kiem-lien-ket.py` xác nhận mọi đường dẫn bằng chứng tồn tại.
- `thu-bang-chung.sh` chạy trên staging hoặc kind và sinh thư mục bằng chứng có ngày.
- Không chứa địa chỉ máy chủ, tài khoản, khoá; bằng chứng chứa thông tin nhạy cảm lưu ngoài repo, chỉ ghi tham chiếu.

## 7. Báo cáo

Theo AGENTS.md mục 6.
