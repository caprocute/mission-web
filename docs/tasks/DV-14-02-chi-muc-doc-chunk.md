# DV-14-02 · SVC-14/ADR-28 Chỉ mục `doc_chunk` có ACL bằng pgvector, consumer EVT-24

@@HDR@@

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 |
| Luồng đề xuất | B7 |
| Cỡ việc | L |
| Phụ thuộc | DV-14-01 |
| Truy vết | QT-17 (BR-17-03, BR-17-07), QT-05 → SVC-14 → bảng `ai.doc_chunk` → nhận EVT-24 `integ.document.received.v1`, EVT-13 (văn bản Policy Pack, suy luận) → ADR-28 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Xây kho tri thức RAG trong pgvector: chia đoạn và nhúng văn bản quy định, hướng dẫn, danh mục công việc mẫu, phần mô tả Policy Pack và văn bản chỉ đạo của tenant, mỗi đoạn mang danh sách đơn vị được đọc (ACL), chỉ C0 và C1. Khi xong, truy xuất ngữ nghĩa trả về trong ≤ 500 ms chỉ những đoạn người hỏi được đọc, và văn bản chỉ đạo mới được lập chỉ mục trước khi trợ lý trả lời về nó.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "Kiến trúc cổng mô hình" (L3088, đoạn RAG: chia 500–800 từ có chồng lấp, giữ cấu trúc điều khoản, vector 1.024 chiều), ADR-28 (L3662).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` từ điển `ai.doc_chunk` (L3970: lọc trước bằng `WHERE` với `hnsw.iterative_scan = relaxed_order`), "Mã hoá đầu cuối cho nội dung mật" (L4168: nội dung mật không vào RAG).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-17 "Quy tắc nghiệp vụ" (L1864, BR-17-03, BR-17-07) và thao tác máy chủ (truy xuất ≤ 500 ms, lập chỉ mục văn bản mới tải lên trước khi trả lời).
- `00-co-so-thiet-ke-chung.md` mục 7 (EVT-24); bản trích `extract/tl02b-may-chu.md` QT-17 (BE-Q17-02).

## 3. Phạm vi công việc

- Viết bộ chia đoạn tiếng Việt: 500–800 từ, chồng lấp, không cắt giữa điều, khoản; chuẩn hoá NFC; lưu `chunk_no`, nguồn, `embed_model`.
- Viết client mô hình nhúng tiếng Việt tự lưu trữ (1.024 chiều) có lô và giới hạn; đổi mô hình nhúng tạo bản ghi mới theo `embed_model`, không ghi đè.
- Viết consumer EVT-24 (inbox idempotent qua NEN-018): lấy nội dung văn bản chỉ đạo qua API lõi bằng token `ai_readonly`, bỏ qua và ghi `guardrail_event` nếu phân loại là C2/C3, tính `acl_unit_ids` từ đơn vị nhận và cấp trên được phép đọc (suy luận theo ABAC cây tổ chức), chèn `doc_chunk`.
- Viết lệnh nạp kho văn bản dùng chung (quy định, hướng dẫn, danh mục mẫu) từ `data/` với `tenant_id` dùng chung và ACL mở trong tenant (suy luận cách biểu diễn tài liệu dùng chung, ghi vào báo cáo).
- Viết hàm truy xuất `retrieve(query, user_ctx, k)`: lọc trước theo `tenant_id`, `classification`, `acl_unit_ids && <đơn vị người dùng>` rồi HNSW cosine với `hnsw.iterative_scan = relaxed_order`; trả kèm mã đoạn để rào chắn đầu ra đối chiếu nguồn.
- Lập chỉ mục đồng bộ tệp người dùng vừa đính kèm vào câu hỏi (PDF/DOCX ≤ 20 MB) trước khi trả lời, với ACL chỉ người tải lên.
- Gợi ý tách nếu quá lớn: (a) chia đoạn, nhúng, nạp kho dùng chung; (b) consumer EVT-24 và truy xuất có ACL.

## 4. Ngoài phạm vi

- Chuyển sang OpenSearch k-NN khi vượt ngưỡng (ADR-28, quyết định sau).
- OCR tệp ảnh (DV-14-05).

## 5. Tệp được phép sửa

- `services-py/ai-gateway/**`
- `data/seed/ai-corpus/**` (văn bản dùng chung công khai)

## 6. Tiêu chí hoàn thành

- `cd services-py/ai-gateway && pytest` xanh, gồm kiểm thử tích hợp pgvector: người dùng đơn vị A không bao giờ nhận đoạn chỉ có ACL đơn vị B (kiểm trên 1.000 truy vấn ngẫu nhiên); đoạn C2 không bao giờ được chèn.
- Truy xuất trên 1 triệu đoạn giả lập có p95 ≤ 500 ms ở máy thử (ghi cấu hình và số đo); EVT-24 giao lại không sinh đoạn trùng.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
