# DV-13-02 · SVC-13 CloudEvents ra ngoài có ký JWS và mTLS

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-13-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | M |
| Phụ thuộc | DV-13-01; NEN-008 (AsyncAPI cho topic công bố) nên gộp trước (bổ sung) |
| Truy vết | TL-01 ch.6 → SVC-13 → bảng `integ.outbound_message`, `integ.connector` → CloudEvents 1.0 ra ngoài cho EVT-12, EVT-19 và sự kiện công bố → L11 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Chuyển sự kiện nội bộ (Avro, CloudEvents chế độ nhị phân trên header Kafka) sang CloudEvents 1.0 JSON chế độ cấu trúc cho hệ ngoài, dùng mã ngoài thay mã nội bộ, ký tải trọng bằng JWS và gửi qua kênh mTLS. Khi xong, mọi chiều ra của SVC-13 dùng một bộ chuyển đổi và ký thống nhất, bên nhận kiểm được nguồn gốc và tính toàn vẹn của từng thông điệp.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Phong bì sự kiện" (L1285–1315, ví dụ EVT-12 chia sẻ lên CSDL CBCCVC), "AsyncAPI" (L1317), "Quy ước topic" (L1279).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Phong bì sự kiện" (L4408, thuộc tính và phần mở rộng), từ điển `integ.outbound_message` (L3930).
- `00-co-so-thiet-ke-chung.md` mục 7 (quy ước sự kiện ra ngoài qua SVC-13 dùng CloudEvents JSON).
- Bản trích `extract/tl01-thanh-phan.md` mục D5, D6.

## 3. Phạm vi công việc

- Viết `CloudEventsMapper`: dựng `specversion` 1.0, `id`, `source` dạng `urn:vt-okrkpi:<cell>:<module>`, `type` dạng `vn.viettel.okrkpi.<topic>`, `subject`, `time`, `datacontenttype`, `dataschema`, phần mở rộng `traceparent`, `tenantid`, `classification`, `policyversion`; dữ liệu dùng mã ngoài lấy từ `mapping_xref` (ví dụ `subjectRef.scheme=cbccvc`).
- Từ chối gửi ra ngoài sự kiện có `classification` không được phép chia sẻ theo cấu hình bộ kết nối; không bao giờ đưa C2/C3 dạng rõ vào tải trọng.
- Ký JWS (ES256) tải trọng bằng khoá của cell lấy qua OpenBao/HSM (không đặt khoá trong cấu hình), đính chữ ký theo dạng tách (`detached`) hoặc bao (`compact`) theo yêu cầu bên nhận cấu hình ở `connector`; công bố khoá công khai dạng JWKS cho đối tác (suy luận về cách công bố).
- Thiết lập client HTTP mTLS theo chứng thư tổ chức cho từng bộ kết nối chiều ra; chứng thư và khoá lấy qua cert-manager/OpenBao.
- Viết bộ kiểm chứng phía nhận (thư viện nhỏ dùng trong kiểm thử và cấp cho đối tác) kiểm chữ ký JWS và lược đồ JSON.
- Điểm lệch cần lưu ý: TL-01 (L1287) đặt `source` dạng `urn:vt-okrkpi:<cell>:<module>`, TL-03 (L4408) dùng `urn:vt-okr:<cell>:<service>`; task theo TL-01 cho sự kiện ra ngoài (đúng phạm vi mục CloudEvents ra ngoài) và ghi vào `wiki/14`.

## 4. Ngoài phạm vi

- Nội dung từng luồng chiều ra (DV-13-06 EVT-12 sang CSDL CBCCVC, DV-13-09 bản neo).
- Cổng tài liệu đối tác phát hành AsyncAPI (NEN-008 hoặc task tài liệu đối tác).

## 5. Tệp được phép sửa

- `backend/services/integration-gateway/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/integration-gateway -am verify` xanh, gồm kiểm thử: EVT-12 mẫu chuyển thành CloudEvents JSON khớp ví dụ TL-01, chữ ký JWS kiểm được bằng khoá công khai, sửa một ký tự tải trọng thì kiểm thất bại.
- Kiểm thử mTLS với máy chủ giả lập yêu cầu chứng thư client: thiếu chứng thư thì kết nối bị từ chối, có chứng thư thì gửi được.
- Kiểm thử chặn sự kiện có dữ liệu C2 trong tải trọng ra ngoài.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
