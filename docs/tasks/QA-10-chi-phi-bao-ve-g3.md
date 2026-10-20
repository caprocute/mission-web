# QA-10 · PERF-14..PERF-18, PERF-25 Kịch bản kiểm chứng góp ý G3 về chi phí bảy lớp bảo vệ và mã hoá

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh QA-10` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (PERF-14, PERF-16, PERF-25 là điều kiện sang đợt 3) |
| Luồng đề xuất | B9 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | QA-08, NEN-015 |
| Truy vết | Góp ý G3, ADR-06, ADR-09, ADR-31 → TL-04 mục 7.6 → PERF-14..PERF-18, PERF-25 → SVC-05, SVC-09 ledger-service, evidence-service, esign-service → `outbox_event`, `pg-ledger` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Viết các kịch bản đo chi phí của từng lớp bảo vệ: mã hoá trường C2, mã hoá đầu cuối C3 phía client, outbox tới sổ cái, ký lô, tải lên và quét minh chứng, và chi phí toàn chuỗi khi bật dần bảy lớp. Khi xong, mỗi kịch bản cho ra bảng so sánh bật và tắt có cấu trúc, chạy rút gọn được trên kind hoặc staging, và chạy đầy đủ trên `CELL-PERF` trong QA-12.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục 7.6 "Kịch bản riêng cho góp ý G3" (dòng 1810–1824), hàng PERF-14..PERF-18, PERF-25 trong mục 7.4.
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 14 (bảy lớp bảo vệ, ngân sách độ trễ khoảng 25 ms).
- `docs/tasks/NEN-015-*.md` (cờ `vt.crypto.field-encryption.enabled`), `contracts/openapi/modules/tasks.yaml` (`updateTaskProgress`), `products.yaml` (`createEvidenceVersion`, `submitEvidenceVersion`), `dossiers.yaml` (`signDossierPackage`, `createEsignRequest`).
- `wiki/08-bao-mat-frontend.md` mục E2EE C3.

## 3. Phạm vi công việc

- PERF-14: chạy PERF-01 rút gọn 30 phút hai lần với cờ mã hoá trường bật và tắt; xuất chênh lệch p95 đọc, ghi, CPU và tỷ lệ trúng `DekCache`.
- PERF-15: bộ đo trên thiết bị bằng Playwright (Chromium, giả lập CPU chậm 4× và 6× cho điện thoại tầm trung và cấp thấp, suy luận về hệ số) đo mã hoá AES-256-GCM bằng WebCrypto cho 100 KB, 1 MB, 10 MB, bọc khoá cho 1, 5, 20 người nhận (ECDH P-256 và RSA-OAEP 2048), giải mã; tiêu chí mã hoá 1 MB ≤ 50 ms (≤ 150 ms máy cấp thấp). Tiêu chí hoàn thành đo trên giả lập; đo trên điện thoại thật để lại khi có bộ thiết bị tham chiếu, khi đó chỉ đổi cấu hình thiết bị của bộ đo.
- PERF-16: k6 phát 1.800 sự kiện mỗi giây trong 2 giờ; đo chênh p95 API ghi khi có và không có dòng outbox cho sổ cái (tiêu chí ≤ 1 ms) và độ trễ tới khi bản ghi vào chuỗi băm, niêm phong mỗi giờ và mỗi 5 phút.
- PERF-17: gửi 100 phiên ký lô, mỗi phiên ≤ 1.000 phiếu, tổng 100.000 băm; dùng bộ kết nối MySign giả lập độ trễ 1–3 s; đo thời gian hoàn tất và tỷ lệ chữ ký hợp lệ.
- PERF-18: 33 tệp mỗi giây, 1–10 MB, 10% là 10 MB, mạng giả lập 50 Mbps; đo thời gian tải lên p95, phần tăng do SHA-256 luồng (≤ 25 ms cho 10 MB) và thời gian quét ClamAV.
- PERF-25: API cập nhật tiến độ ở 600 request ghi mỗi giây, bảy bước bật dần theo mục 7.6 (tối thiểu; mTLS ztunnel; WAF Coraza; OPA; mã hoá C2; outbox sổ cái; kiểm khoá kỳ và ghi `task_state_log`), mỗi bước 15 phút; bảng phân rã p50, p95 theo lớp từ span Tempo; tiêu chí chi phí thêm bước 2–7 ≤ 10 ms p95, tổng phía máy chủ ở bước 7 ≤ 50 ms.
- Cờ bật, tắt từng lớp khai báo trong `infra/perf/profiles/g3-buoc-<n>.yaml` (values ghi đè cho môi trường thử, không dùng cho sản xuất).

## 4. Ngoài phạm vi

- Tối ưu lớp bảo vệ khi vượt ngân sách (mở task riêng); kết nối MySign thật; PERF-20 sinh phiếu (QA-11).

## 5. Tệp được phép sửa

- `infra/perf/k6/perf-14.js`, `perf-16.js`, `perf-17.js`, `perf-18.js`, `perf-25.js`; `infra/perf/thiet-bi/**` (bộ đo PERF-15); `infra/perf/profiles/g3-*.yaml`; `infra/perf/kiem-tra/g3/**`.

## 6. Tiêu chí hoàn thành

- `k6 inspect` sạch cho các tệp mới; `cd infra/perf/thiet-bi && npx playwright test` chạy được và xuất JSON kết quả.
- PERF-14 và PERF-25 rút gọn (5 phút mỗi bước, `PERF_SCALE=0.05`) chạy trên server dev hoặc kind, xuất bảng so sánh; ghi rõ lớp nào chưa bật được ở môi trường đó.
- Báo cáo nêu số đo rút gọn, không kết luận cho sản xuất.

## 7. Báo cáo

Theo AGENTS.md mục 6.

### Điểm lệch cần lưu ý

- Mục 7.6 ghi PERF-25 đo `PATCH /api/v1/tasks/{id}/progress`; hợp đồng chốt `POST` (HD-03, `updateTaskProgress`). Kịch bản theo hợp đồng.
