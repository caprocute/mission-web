# QA-06 · QT-08..QT-10 Hành trình đầu cuối: khoá kỳ, tự đánh giá, thẩm định, quyết định ký số, xếp loại năm

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh QA-06` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B9 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | QA-05, DV-06-06, CORE-04-10 |
| Truy vết | QT-08 → SCR-0801; QT-09 → SCR-0901..SCR-0904; QT-10 → SCR-1001, SCR-1002 → API `/api/v1/periods*`, `/api/v1/appraisals*`, `/api/v1/esign/*` → MOD-04, MOD-08, SVC-06, SVC-07, SVC-10, SVC-11 → bảng `config.period`, `appraisal.appraisal_profile` → EVT-09..EVT-12, EVT-17..EVT-19, EVT-26 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực các hành trình đầu cuối nhóm QT-08..QT-10 trên hệ thống thật: khoá kỳ bằng saga chốt kỳ có hai người, tự đánh giá, thẩm định, đề xuất, quyết định xếp loại hàng loạt có ký số, và xếp loại năm từ bốn quý. Khi xong, một kỳ thử đi trọn từ `MO` tới `DA_KHOA`, hồ sơ đánh giá của cán bộ thử đi từ `TU_DANH_GIA` tới `DA_KHOA` qua giao diện, và nhánh huỷ khoá trước bước ký được kiểm.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` mục QT-08; `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` mục QT-09, QT-10.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` đoạn OPS-17 trong "Quy trình định kỳ khác" (3855–3873: các bước saga khoá kỳ, vai trò AC-02, AC-04, AC-09).
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 9.1 (trạng thái kỳ, hồ sơ đánh giá, mức xếp loại).
- `contracts/openapi/modules/config.yaml` (`createPeriodLockOrder`, `approvePeriodLockOrder`, `startPeriodLock`, `getPeriodLockProgress`, `requestPeriodLockCancel`, `approvePeriodLockCancel`), `appraisals.yaml` (`saveAppraisalSelfAssessment`, `submitAppraisalSelfAssessment`, `appraiseAppraisalProfile`, `returnAppraisalProfile`, `proposeAppraisalRating`, `createAppraisalBatchDecision`, `getAppraisalBatchDecision`, `listAnnualAppraisalRatings`, `getAnnualAppraisalExplanation`), `dossiers.yaml` (`createEsignRequest`, `getEsignRequest`).

## 3. Phạm vi công việc

- Hành trình khoá kỳ: AC-02 lập lệnh khoá có MFA, AC-04 duyệt, khởi động saga; theo dõi tiến độ trên SCR-0801 qua các bước kiểm tiền điều kiện, `DANG_KHOA` (EVT-09), tính điểm (EVT-10), sinh biểu mẫu (EVT-17), ký lô (EVT-18), `DA_KHOA` và niêm phong (EVT-19).
- Hành trình huỷ khoá: trước khi bước ký lô xong, yêu cầu huỷ, AC-09 duyệt, kỳ về `MO` và EVT-26 `config.period.unlocked.v1`; sau bước ký, giao diện không cho huỷ mà chỉ cho thử lại.
- Hành trình tự đánh giá và thẩm định: AC-06 lập phiếu tự đánh giá, gửi; AC-07 thẩm định, trả lại một lần, AC-06 sửa gửi lại, AC-07 thẩm định đạt, đề xuất mức; kiểm EVT-11 ở từng bước và trạng thái `TU_DANH_GIA` → `CHO_THAM_DINH` → `DA_THAM_DINH` → `CHO_QUYET_DINH`.
- Hành trình quyết định hàng loạt: AC-08 chọn nhiều hồ sơ, quyết định, ký số (môi trường dev dùng bộ ký giả lập của esign-service), kiểm `DA_QUYET_DINH` → `DA_KY_SO`, EVT-12, EVT-18; phiếu xếp loại có giải thích hiển thị nguồn điểm.
- Hành trình xếp loại năm: sau bốn kỳ quý dựng bằng dữ liệu khởi tạo, AC-07 xem bảng xếp loại năm, giải thích, đề xuất phân nhóm triển vọng.
- Ca phủ định: người đã thẩm định không được quyết định cùng hồ sơ (tách vai trò); bước cần AAL2 thiếu step-up bị từ chối.
- Gợi ý tách: (a) QT-08; (b) QT-09; (c) QT-10.

## 4. Ngoài phạm vi

- Hiệu năng tính lại và ký lô quy mô (PERF-19, PERF-20, PERF-17; QA-10, QA-11).
- Ký số với nhà cung cấp thật (MySign) — chỉ kiểm ở môi trường có kết nối thật, ghi trong báo cáo.

## 5. Tệp được phép sửa

- `frontend/e2e/hanh-trinh/qt08-10/**`, `frontend/e2e/hanh-trinh/danh-muc.json` (trạng thái hành trình), `data/seed/e2e/**`.

## 6. Tiêu chí hoàn thành

- `E2E_MODE=he-thong npm run e2e:he-thong -- --grep "QT-(08|09|10)"` xanh trên server dev, hai lần liên tiếp, không lỗi console.
- Mọi EVT nêu ở mục 3 được kiểm thấy; trạng thái kỳ và hồ sơ đúng baseline mục 9.1 ở từng bước.
- Saga khoá kỳ trên dữ liệu thử hoàn tất trong thời gian ghi được trong báo cáo; nhánh huỷ khoá đúng điều kiện.
- Báo cáo cuối task theo AGENTS.md mục 6, kèm bảng hành trình × kết quả.

## 7. Báo cáo

Theo AGENTS.md mục 6.
