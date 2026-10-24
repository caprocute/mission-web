# DL-08 · NFR-06/PERF-03 Bộ sinh dữ liệu thử quy mô (1,2 triệu hồ sơ một cell) cho kiểm thử hiệu năng

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DL-08` để có prompt đầy đủ.

> **Cập nhật 24/10/2026 (wiki 14, mục "Quyết định điều chỉnh phạm vi của chủ dự án"):** ứng dụng di động (SVC-18, MOB-01..10) và đo tải, diễn tập DR (QA-08..QA-12, QA-14, QA-15) đã bỏ khỏi phạm vi; các chỗ nhắc tới chúng trong thẻ này chỉ còn giá trị lịch sử.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 – lõi nghiệp vụ trên nền tảng mới (CELL-PERF phải có dữ liệu trước PERF-01..PERF-25) |
| Luồng đề xuất | B9 |
| Cỡ việc | M (≤ 1 ngày; phần tiến trình Flowable có thể tách DL-08b) |
| Phụ thuộc | CORE-04-01, CORE-02-02 (bổ sung hiển nhiên: DV-04-01 cho schema `org`, CORE-03-01 cho `product`) |
| Truy vết | NFR-06, PERF-03, PERF-13, PERF-19, PERF-20, SEC-52 → TL-04 "Môi trường và công cụ thử tải" → MOD-02, MOD-03, MOD-04, SVC-04, SVC-08, SVC-06 → bảng `org.org_unit`, `org.person`, `org.assignment`, `task.task`, `task.task_progress`, `product.evidence_version`, `evidence.object_version`, `appraisal.appraisal_profile` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Có công cụ sinh dữ liệu tổng hợp theo phân phối CELL-HCM để nạp vào CELL-PERF: 1,2 triệu tài khoản, cây tổ chức 5 cấp khoảng 12.000 đơn vị, 36 triệu nhiệm vụ trên 4 quý, 10 triệu phiên bản minh chứng và 1,2 triệu hồ sơ đánh giá, tất cả là dữ liệu giả, không có dữ liệu cá nhân thật (SEC-52). Không có bộ dữ liệu này thì không chạy được PERF-03 (đỉnh cuối kỳ), PERF-19 (tính lại 1,2 triệu hồ sơ ≤ 2 giờ) và PERF-20 (sinh, ký 100.000 phiếu ≤ 4 giờ) mà NFR-06 đòi.

## 2. Đầu vào bắt buộc đọc

- TL-04 `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md`, chương "Hiệu năng", mục "Môi trường và công cụ thử tải" (dòng 1709–1741: phân phối dữ liệu, CELL-PERF, `TestRun` k6) và "Danh mục kịch bản thử tải PERF".
- TL-03 `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`: các mục schema `org`, `task`, `product`, `evidence`, `appraisal`; quy ước `period_bucket` và phân vùng; mục "Kiểm thử hiệu năng".
- TL-05 `/mnt/project-files/kien-truc-okr-kpi/src/TL-05-dinh-co-he-thong.md`, giả định định cỡ (số tài khoản mỗi cell, hồ sơ cell).
- Baseline NFR-06; `wiki/09-hieu-nang-va-trai-nghiem.md`, `wiki/11-kiem-thu-va-chat-luong.md`.

## 3. Phạm vi công việc

- Viết bộ sinh bằng Python 3.12 trong `infra/perf/datagen/` đọc hồ sơ phân phối từ `data/seed/perf/<hồ-sơ>.yaml` (cell L, M, S, compact theo TL-05) với hệ số quy mô, để CI chạy được ở 0,1%.
- Sinh cây tổ chức 5 cấp hai trục thời gian hợp lệ (không chồng phiên bản, một phân công chính tại một thời điểm), thuộc 9 khối của DL-07; tên người, số định danh là chuỗi giả có định dạng hợp lệ nhưng không trùng người thật.
- Sinh nhiệm vụ, tiến độ, sản phẩm theo phân phối trạng thái chuẩn (`DA_GIAO`, `DANG_THUC_HIEN`, `CHO_NGHIEM_THU`, `HOAN_THANH`…), minh chứng có kích thước theo phân phối log-chuẩn trung vị 600 KB (chỉ siêu dữ liệu và băm; tuỳ chọn tạo tệp giả cho kịch bản tải tệp).
- Sinh hồ sơ đánh giá ở trạng thái phù hợp từng kịch bản (trước khoá kỳ cho PERF-19, sau quyết định cho PERF-20).
- Ghi ra tệp COPY theo `period_bucket` và nạp bằng `COPY` qua tài khoản `mig_<schema>`; cột C2 được mã hoá bằng khoá thử của môi trường perf thông qua cùng định dạng phong bì của `vt-platform-starter` (suy luận: cần giao diện mã hoá từ NEN-015, nếu chưa có thì để cột C2 là bản mã giả cùng độ dài và ghi rõ).
- Bảo đảm tất định: cùng hạt giống cho cùng dữ liệu; nạp lại không trùng khoá.
- Tách phần tạo 1,2 triệu tiến trình Flowable (gọi API khởi động tiến trình theo lô) thành DL-08b nếu vượt một ngày.

## 4. Ngoài phạm vi

- Kịch bản k6 và Gatling (QA-08, QA-09); dựng CELL-PERF (nhóm INF).
- Dữ liệu cho môi trường dev dùng chung (chỉ cần quy mô nhỏ, dùng hệ số quy mô).

## 5. Tệp được phép sửa

- `infra/perf/datagen/**`
- `data/seed/perf/**`
- `wiki/11-kiem-thu-va-chat-luong.md` (mục dữ liệu thử tải)

## 6. Tiêu chí hoàn thành

- `cd infra/perf/datagen && pytest` xanh; `pip-audit` không có lỗi mức cao.
- Chạy ở hệ số 0,001 trên PostgreSQL 17 (Testcontainers hoặc Docker Compose cục bộ) đã áp migration thật: nạp thành công, không vi phạm ràng buộc khoá ngoại, loại trừ, RLS; báo cáo thống kê phân phối lệch ≤ 5% so với hồ sơ.
- Đo thời gian sinh và nạp ở hệ số 0,01, ngoại suy và ghi thời gian dự kiến cho quy mô đầy đủ vào báo cáo.
- Quét tệp sinh ra không chứa số định danh hoặc số điện thoại có thật theo danh sách mẫu kiểm.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
