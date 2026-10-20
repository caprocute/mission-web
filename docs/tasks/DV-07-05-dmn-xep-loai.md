# DV-07-05 · QT-09/SCR-0903 DMN xếp loại, câu giải thích, snapshot có `input_hash`; luồng Đảng và Nhà nước song song

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-07-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B4 |
| Cỡ việc | L |
| Phụ thuộc | DV-07-03 |
| Truy vết | QT-09 → SCR-0903 → SVC-07 → bảng `scoring.score_result`, `scoring.score_trace` → EVT-10 (payload để MOD-04 ghi `appraisal.score_snapshot`, `appraisal.rating_explanation`); BR-09-06..BR-09-11, BR-09-16; MSG-09-10 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Từ điểm và kết quả tiêu chí chung, scoring-service chạy bảng quyết định DMN của Policy Pack để ra mức hệ thống tham khảo (HTXSNV, HTTNV, HTNV, KHTNV), hạ mức liền kề khi thiếu điều kiện và sinh câu giải thích nêu điều kiện thiếu, giá trị thực, điều khoản và phiên bản gói. Mỗi hồ sơ có kết quả riêng cho luồng Đảng (100 điểm) và luồng Nhà nước (30/70), không ghi đè nhau. Khi xong, EVT-10 mang đủ dữ liệu để MOD-04 lưu ảnh chụp có `input_hash`, và tính lại cùng đầu vào là luỹ đẳng.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-09 bảng BR (dòng 87–111: BR-09-06..BR-09-11, ví dụ câu giải thích dòng 111), SCR-0903 "Phiếu xếp loại có giải thích" (dòng 233–281), MSG-09-10.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Ngôn ngữ biểu thức CEL và bảng quyết định DMN" (dòng 2448) và ADR-19 (dòng 3572).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Schema scoring (SVC-07)" (dòng 3316–3389) và các bảng `appraisal.score_snapshot`, `appraisal.rating_explanation` (tra bằng grep trong mục schema appraisal).
- Hợp đồng: `contracts/openapi/modules/appraisals.yaml` schema điểm và giải thích (`getAppraisalScores`, `getAppraisalScoreTrace`); lược đồ Avro EVT-10.
- Wiki: `wiki/05b-quy-trinh-va-man-hinh-qt09-18.md`, `wiki/13-thuat-ngu.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Chạy DMN 1.4 hit policy FIRST của gói trên Flowable DMN: đầu vào điểm, tỷ lệ hoàn thành O, `ty_le_vuot`, giải pháp đột phá, mức tiêu chí chung, cờ loại trừ; mức cuối = `min_muc`(mức theo điểm, mức theo tiêu chí chung) (BR-09-06..BR-09-09).
- Hạ mức liền kề khi đủ điểm mà thiếu điều kiện, sinh câu giải thích từ mẫu câu của dòng DMN (điều kiện thiếu, giá trị thực, điều khoản, phiên bản gói) (BR-09-10).
- Luồng Nhà nước: thang 30 điểm tiêu chí chung + 70 điểm KPI và ngưỡng riêng; tính trên cùng dữ liệu nhưng ra kết quả riêng (BR-09-11); hồ sơ thuộc luồng nào lấy theo `policy_binding` và phân công.
- Đóng gói kết quả (điểm, mức hệ thống, giải thích, `policy_version`, `policy_content_hash`, `input_hash`, `result_hash`) vào EVT-10; luỹ đẳng: cùng `input_hash` và cùng gói thì không phát kết quả mới.
- Không có API ghi ảnh chụp ở SVC-07 hay MOD-04 (BR-09-16): ảnh chụp chỉ sinh từ EVT-10.
- Kiểm thử: ca vàng cho từng dòng DMN của gói mẫu (gồm ví dụ "Đủ điểm mức HTXSNV nhưng chưa đạt điều kiện 30%..." của TL-02b); hồ sơ hai luồng ra hai kết quả; tính lại cùng đầu vào không phát sự kiện mới.
- Gợi ý tách: (a) DMN, `min_muc`, hạ mức và câu giải thích; (b) luồng Nhà nước song song và đóng gói EVT-10 luỹ đẳng.

## 4. Ngoài phạm vi

- Lưu `score_snapshot`, `rating_explanation` ở MOD-04 (CORE-04-02); quy tắc người đứng đầu và phân bố HTXSNV khi tạo lô (CORE-04-07).

## 5. Tệp được phép sửa

- `backend/services/scoring-service/**`
- `data/golden/` (ca vàng xếp loại)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/scoring-service -am verify` chạy xanh gồm ca vàng xếp loại cho cả hai luồng; độ phủ dòng `domain` ≥ 80%.
- Câu giải thích của mọi ca vàng khớp nguyên văn kỳ vọng.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
