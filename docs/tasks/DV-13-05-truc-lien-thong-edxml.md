# DV-13-05 · SVC-13/L01 Trục liên thông văn bản quốc gia (EdXML, X-Road)

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-13-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B5 |
| Cỡ việc | L |
| Phụ thuộc | DV-13-01; DV-13-02 (ký, mTLS chiều ra) nên gộp trước cho chiều gửi (bổ sung) |
| Truy vết | QT-05 bước 1b, QT-11 (BR-11-11 gói con diện TW gửi EdXML) → SVC-13 → bảng `integ.connector` (`protocol` `EDXML`, `XROAD`), `integ.inbox_message`, `integ.outbound_message`, `integ.mapping_xref`, `integ.dead_letter` → EVT-24 → L01, L6 |
| Trạng thái | Chưa bắt đầu (làm với bản giả lập; nối hệ thật khi có môi trường) |

## 1. Mục tiêu

Kết nối nền tảng với Trục liên thông văn bản quốc gia theo EdXML (QĐ 28/2018/QĐ-TTg) qua X-Road security server: nhận văn bản, thông tin nhiệm vụ giữa cơ quan (phát EVT-24) và gửi văn bản ra (ví dụ gói con hồ sơ diện Trung ương tới Ban Tổ chức TW, Văn phòng TW). Khi xong, liên thông văn bản giữa cơ quan đi qua một bộ kết nối chuẩn, chạy thành Deployment riêng để đối tác chậm không ảnh hưởng bộ kết nối khác.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Các bộ kết nối" (L1417, dòng Trục liên thông: EdXML, X-Road security server, Deployment riêng), "SVC-13 integration-gateway" (L1180, co giãn và Deployment riêng), trục L dòng L6 (L3218).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-11 "Quy tắc nghiệp vụ" (L579, BR-11-11), SCR-1102 (L675).
- `contracts/openapi/modules/dossiers.yaml` phần gửi gói con (`/dossiers/packages/{id}/transfers`, mô tả "EdXML qua integration-gateway").
- Đặc tả EdXML và tài liệu kết nối X-Road của Trục (tra theo văn bản pháp lý khi cần).

## 3. Phạm vi công việc

- Viết bộ phân tích và dựng EdXML (tiêu đề, cơ quan gửi/nhận theo mã định danh cơ quan, tệp đính kèm, chữ ký số của văn bản) dùng chung cho vOffice (DV-13-03) và Trục.
- Viết adapter X-Road: gửi/nhận qua security server của đơn vị (giao thức SOAP/REST của X-Road), cấu hình mã thành viên, mã hệ thống con trong `connector` và bí mật trong OpenBao.
- Chiều vào: lưu nguyên văn, ánh xạ cơ quan nhận sang đơn vị nội bộ qua `mapping_xref`, phát EVT-24 qua translator chung.
- Chiều ra: nhận yêu cầu gửi gói con diện TW từ MOD-06 (gói đã ký, đã mã hoá theo chứng thư bên nhận), dựng EdXML, gửi, lưu `outbound_message`, chỉ coi xong khi có xác nhận nhận; thử lại theo lịch, quá hạn thì báo theo MSG-11-06 qua EVT-21.
- Đóng gói cấu hình chạy route Trục thành Deployment riêng cùng image (chỉ bật route Trục), có values mẫu.
- Gợi ý tách nếu quá lớn: (a) bộ phân tích EdXML và chiều vào; (b) chiều ra và Deployment riêng.

## 4. Ngoài phạm vi

- Tách gói con, mã hoá theo chứng thư bên nhận và ký gói ở MOD-06 (CORE-06-08); cài đặt security server X-Road (hạ tầng của đơn vị).
- Nối và kiểm thử với Trục liên thông văn bản quốc gia và security server X-Road thật khi có môi trường; khi đó chỉ đổi cấu hình kết nối, không đổi mã.

## 5. Tệp được phép sửa

- `backend/services/integration-gateway/**`
- `deploy/charts/integration-gateway/` (chỉ values cho Deployment riêng của route Trục, nếu chart đã có từ INF-02)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/integration-gateway -am verify` xanh, gồm kiểm thử phân tích và dựng EdXML với bộ tệp mẫu (khứ hồi dựng rồi phân tích cho cùng nội dung), kiểm thử X-Road giả lập cho cả hai chiều.
- Kiểm thử chứng minh gửi gói con thất bại được thử lại theo lịch và không gửi trùng khi đã có xác nhận.
- `helm template` với values Deployment riêng không lỗi (nếu có sửa chart).
- Địa chỉ Trục, security server X-Road, mã định danh thành viên và chứng thư nằm trong bản ghi `integ.connector` và giá trị triển khai, không nằm trong mã; đổi từ X-Road giả lập sang Trục thật chỉ đổi cấu hình kết nối, không đổi mã.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
