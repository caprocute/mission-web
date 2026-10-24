# DL-15 · SEC-52 Tác vụ ẩn danh hoá dữ liệu cho môi trường phi sản xuất (anonymize)

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DL-15` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (phải có trước khi staging cần dữ liệu gần thật để tái hiện lỗi và trước diễn tập UAT ở QA-16) |
| Luồng đề xuất | B11 |
| Cỡ việc | M (≤ 1 ngày cho bộ quy tắc và lõi thay thế; phần chạy trên cụm cách ly có thể tách DL-15b khi có cụm) |
| Phụ thuộc | NEN-015 (giải mã và mã hoá lại trường C2, chỉ mục mù HMAC), DL-08 (bộ sinh giá trị giả giữ phân phối); NEN-005, NEN-006 (migration và tài khoản `mig_<schema>`) (bổ sung) |
| Truy vết | SEC-52 → TL-04 mục 9.7 "Dữ liệu cá nhân (SEC-52)" (L3171) → các schema có dữ liệu cá nhân: `org` (`org.person`, `org.assignment`), `task`, `product`, `evidence`, `appraisal`, `oversight` → hồ sơ đánh giá tác động dữ liệu cá nhân (VH-07) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

TL-04 cấm môi trường phi sản xuất dùng dữ liệu thật; khi cần dữ liệu gần thật để tái hiện lỗi, dữ liệu phải được che bằng tác vụ `anonymize` trên cụm cách ly rồi mới chuyển sang staging: thay tên, số định danh, ngày sinh, nhận xét bằng dữ liệu tổng hợp giữ phân phối. Task này viết tác vụ đó cùng bộ quy tắc che theo từng cột, và cách chứng minh sau khi che không còn dữ liệu cá nhân nào sót lại, để VH-07 có biện pháp kỹ thuật cụ thể trong hồ sơ đánh giá tác động và QA-16 có dữ liệu diễn tập.

## 2. Đầu vào bắt buộc đọc

- TL-04 `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục 9.7, đoạn "Dữ liệu cá nhân (SEC-52)" (L3171); mục phân lớp dữ liệu C1–C3 và mã hoá trường; phần môi trường (dev, staging, perf).
- TL-03 `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`: phân lớp từng cột (C1, C2, C3), chỉ mục mù HMAC, mã hoá phong bì, các bảng của schema `org`, `task`, `product`, `evidence`, `appraisal`, `oversight`.
- TL-05 `/mnt/project-files/kien-truc-okr-kpi/src/TL-05-dinh-co-he-thong.md` phần môi trường phi sản xuất (L526).
- `docs/quy-dinh/BACKEND.md`; thẻ `docs/tasks/NEN-015-starter-ma-hoa-truong.md`, `DL-08-sinh-du-lieu-thu-tai.md`, `VH-07-ho-so-cap-do-4.md`, `QA-16-uat-tenant-thi-diem.md`; `wiki/08-bao-mat-frontend.md` (phân lớp dữ liệu).

## 3. Phạm vi công việc

- Bộ quy tắc che khai báo `data/anonymize/rules.yaml`: mỗi cột có dữ liệu cá nhân một luật (thay giả giữ định dạng, dịch ngày sinh trong cùng nhóm tuổi, thay văn bản tự do bằng văn bản tổng hợp cùng độ dài, giữ nguyên, xoá); kiểm tra trong CI rằng mọi cột đánh dấu C2, C3 trong migration đều có luật, cột mới không có luật thì CI đỏ (suy luận: lấy danh sách cột C2, C3 từ chú thích migration hoặc danh mục cột của NEN-015).
- Tác vụ `anonymize` bằng Python 3.12 trong `infra/anonymize/`, dùng lại bộ sinh giá trị giả của `infra/perf/datagen/` (DL-08) để giữ phân phối; ánh xạ nhất quán trong một lần chạy (cùng một người thật ánh xạ tới cùng một người giả ở mọi bảng) bằng HMAC với khoá dùng một lần, huỷ khoá khi xong.
- Trường C2: giải mã bằng khoá của môi trường nguồn qua giao diện của NEN-015, thay giá trị, mã hoá lại bằng khoá của staging và tính lại chỉ mục mù; trên đĩa của cụm cách ly không có bản rõ ngoài bộ nhớ tiến trình.
- Trường C3 (nội dung mật E2EE): máy chủ không giải mã được, nên thay toàn bộ bằng bản mã giả cùng độ dài và xoá bản bọc khoá (suy luận theo ADR-06).
- Giữ nguyên khoá chính, khoá ngoại, `period_bucket`, trạng thái và mốc thời gian nghiệp vụ để tái hiện lỗi; dịch mốc thời gian theo một độ lệch chung nếu luật yêu cầu.
- Bước kiểm sau che: quét toàn bộ bản xuất theo danh sách số định danh, số điện thoại, thư điện tử, họ tên mẫu từ dữ liệu nguồn; có trúng thì dừng, không cho chuyển sang staging; ghi biên bản lần chạy (số cột, số dòng, kết quả quét, không ghi giá trị) cho hồ sơ VH-07.
- Điểm chưa rõ cần ghi: TL-04 nêu "cụm cách ly" nhưng chưa có thẻ dựng cụm này; nhật ký kiểm toán và sổ cái băm chuỗi (`ledger`) có che hay loại bỏ khỏi bản xuất chưa được TL quy định (đề xuất loại bỏ, suy luận).

## 4. Ngoài phạm vi

- Dựng cụm cách ly và quy trình phê duyệt lấy bản sao sản xuất (hạ tầng, VH); sinh dữ liệu tổng hợp từ đầu (DL-08); viết hồ sơ đánh giá tác động (VH-07).

## 5. Tệp được phép sửa

- `infra/anonymize/**` (mới)
- `data/anonymize/**` (mới, chỉ quy tắc, không có dữ liệu)
- `infra/perf/datagen/**` (chỉ tách bộ sinh giá trị giả thành mô-đun dùng chung, không đổi hành vi)
- `wiki/11-kiem-thu-va-chat-luong.md` (mục dữ liệu thử), `wiki/08-bao-mat-frontend.md` (dòng về môi trường phi sản xuất)

## 6. Tiêu chí hoàn thành

- `cd infra/anonymize && pytest` xanh; `pip-audit` không có lỗi mức cao.
- Trên PostgreSQL 17 (Testcontainers) đã áp migration thật và nạp dữ liệu DL-08 hệ số 0,001 làm "nguồn": chạy che thành công, không vi phạm khoá ngoại, ràng buộc loại trừ, RLS; bước quét không trúng giá trị nguồn nào; phân phối nhóm tuổi, giới, khối lệch ≤ 5%.
- Kiểm thử cố ý bỏ luật một cột C2 thì CI đỏ; kiểm thử cố ý để sót một số định danh thì bước quét chặn.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
