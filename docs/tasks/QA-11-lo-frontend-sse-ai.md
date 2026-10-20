# QA-11 · PERF-19..PERF-24, PERF-26 Kịch bản tác vụ lô cuối kỳ, frontend, thời gian thực, đăng nhập, phân tích và AI

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh QA-11` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (PERF-26 ở đợt 3) |
| Luồng đề xuất | B9 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | QA-08 |
| Truy vết | NFR-03, NFR-06 → TL-04 mục 7.4 → PERF-19..PERF-24, PERF-26 → SVC-06 scoring, document-service, esign-service, SVC-17 web-frontend, notification-service, SVC-02 Keycloak, analytics, SVC-14 ai-gateway → EVT-10, EVT-17, EVT-18 |
| Trạng thái | Bị chặn bởi bên ngoài (một phần): PERF-26 cần GPU (kiểm tạm bằng mô hình giả lập) |

## 1. Mục tiêu

Viết các kịch bản còn lại của danh mục PERF: tính lại 1,2 triệu hồ sơ và sinh, ký 100.000 phiếu (NFR-06), hiệu năng frontend, 60.000 kết nối SSE, đăng nhập dồn, bảng điều hành, suy luận AI. Khi xong, danh mục PERF-01..PERF-26 có đủ mã kịch bản trong repo; PERF-21 chạy tự động mỗi bản build.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục 7.4 (dòng 1743–1776) hàng PERF-19..PERF-24, PERF-26 và đoạn "Lịch chạy"; mục 11.8 OPS-17 (saga khoá kỳ).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-05-dinh-co-he-thong.md` mục 12.2 hàng PERF-19, PERF-20, PERF-22 theo hồ sơ.
- `wiki/09-hieu-nang-va-trai-nghiem.md` (ngân sách kích thước, LCP), `wiki/06-quy-uoc-api.md` (SSE, tác vụ 202).
- `contracts/openapi/modules/config.yaml` (`createPeriodLockOrder`, `startPeriodLock`, `getPeriodLockProgress`), `dossiers.yaml` (`createDossierRenderJob`, `signDossierPackage`), `platform.yaml` (`streamNotifications`, `queryAnalytics`, `requestAiSuggestion`), `jobs.yaml` (`getJob`).

## 3. Phạm vi công việc

- PERF-19: kích hoạt tính lại 1,2 triệu hồ sơ qua saga khoá kỳ ở môi trường thử, theo dõi `getJob` và `getPeriodLockProgress`; kiểm hoàn tất ≤ 2 giờ (mục tiêu ≤ 1 giờ) và `result_hash` trùng 100% với lần tính trước (tiêu chí I6).
- PERF-20: sinh và ký 100.000 phiếu Mẫu 1A–3; kiểm hoàn tất ≤ 4 giờ, 100% phiếu có chữ ký hợp lệ và dấu thời gian (script kiểm chữ ký trên mẫu ngẫu nhiên 1% và toàn bộ siêu dữ liệu).
- PERF-21: Lighthouse CI cho shell và các remote trên mạng 4G mô phỏng; ngưỡng LCP ≤ 2,5 s, shell ≤ 250 KB gzip, dựng biểu mẫu 50 trường ≤ 50 ms; nối vào pipeline web (bước sau build).
- PERF-22: k6 mở 60.000 kết nối SSE tới `streamNotifications`, phát 500 thông báo mỗi giây; đo độ trễ phân phát p95 ≤ 2 s và nối lại ≤ 10 s khi ngắt pod.
- PERF-23: 100 đăng nhập mỗi giây và 500 làm mới token mỗi giây trên Keycloak của cell thử; OTP sinh bằng TOTP của tài khoản thử; đăng nhập p95 ≤ 1,5 s.
- PERF-24: 300 truy vấn bảng điều hành mỗi giây qua `queryAnalytics`; p95 ≤ 1 s và CPU `pg-core` không đổi so với PERF-01 (truy vấn đi vào ClickHouse).
- PERF-26: 10 yêu cầu mỗi giây, 1.500 token vào, 300 token ra qua `requestAiSuggestion`; đo thời gian tới token đầu p95 ≤ 2 s, thông lượng ≥ 3.000 token ra mỗi giây. Chưa có GPU: chạy với máy chủ mô hình giả lập (trả token theo độ trễ cấu hình) để kiểm kịch bản và ai-gateway; ghi rõ kết quả không phản ánh năng lực GPU.
- Tải theo hồ sơ cell đọc từ `infra/perf/profiles/`.

## 4. Ngoài phạm vi

- Chạy chiến dịch thật trên `CELL-PERF` (QA-12); mua và lắp GPU, triển khai vLLM thật (INF, đợt 3).
- Tối ưu khi không đạt (mở task riêng).

## 5. Tệp được phép sửa

- `infra/perf/k6/perf-19.js` .. `perf-24.js`, `perf-26.js`; `infra/perf/kiem-tra/lo-cuoi-ky/**`; `infra/perf/ai-gia-lap/**` (máy chủ mô hình giả lập, suy luận); `frontend/lighthouserc.json`; `ci/vt-pipeline/` chỉ bước gọi Lighthouse CI cho web.

## 6. Tiêu chí hoàn thành

- `k6 inspect` sạch cho mọi tệp mới; `cd frontend && npx lhci autorun` chạy được trên bản build cục bộ và in kết quả so với ngưỡng.
- PERF-22 rút gọn (2.500 kết nối, hồ sơ compact) và PERF-24 rút gọn chạy trên server dev hoặc kind; PERF-19 rút gọn 50.000 hồ sơ (hồ sơ compact) chạy hết và kiểm `result_hash` khớp.
- PERF-26 chạy với máy chủ giả lập; báo cáo ghi "Bị chặn bởi bên ngoài: GPU" cho phép đo thật.

## 7. Báo cáo

Theo AGENTS.md mục 6.
