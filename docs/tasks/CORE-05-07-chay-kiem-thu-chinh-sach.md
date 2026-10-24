# CORE-05-07 · QT-15/SCR-1503 Chạy kiểm thử Policy Pack: cel-java, cel-js, kiểm thử thuộc tính

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-05-07` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | L (nên tách: a. chạy bộ vàng trên cel-java và DMN, lưu kết quả; b. đối chiếu cel-js; c. kiểm thử thuộc tính và so sánh tác động) |
| Phụ thuộc | CORE-05-04, CORE-05-05, CORE-05-06 |
| Truy vết | QT-15 → SCR-1503 → `POST /api/v1/policies/packs/{id}/versions/{v}/test-runs` (`runPolicyTests`, 202 job `TEST_RUN`), `GET .../test-runs` (`listPolicyTestRuns`), `GET .../test-runs/{runId}` (`getPolicyTestRun`) → MOD-05 → `policy.test_run`, `policy.test_case_result`, `policy.policy_pack_version` (`golden_pass_rate`, `last_test_run_id`) → BR-15-07, BR-08-11, MSG-15-06, MSG-15-07, lỗi `POLICY-409-TEST-RUNNING`; ADR-19 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Chạy kiểm thử một phiên bản Policy Pack như một tác vụ dài: toàn bộ ca vàng `DA_DUYET` trên cel-java và DMN, đối chiếu kết quả với cel-js, cộng kiểm thử thuộc tính 10.000 bộ dữ liệu và ca biên tự sinh; đạt 100% và hai engine khớp thì phiên bản lên `DAT_KIEM_THU`, ngược lại về `NHAP`. Khi xong, SCR-1503 hiển thị kết quả từng ca, ca lệch hai engine và báo cáo thuộc tính, và không phiên bản nào được gửi duyệt khi chưa đạt.

## 2. Đầu vào bắt buộc đọc

- `TL-02b-quy-trinh.md` QT-15 "Mô tả các bước" (dòng 1315), "Quy tắc nghiệp vụ" (BR-15-07), "SCR-1503" (dòng 1495), MSG-15-06, MSG-15-07 (dòng 1537–1538).
- `TL-01-thiet-ke-kien-truc.md` "Bộ ca mẫu vàng" (dòng 2482, gồm ca biên và bất biến thuộc tính), "Phát hành, ký số và vòng đời" (dòng 2511, các bước `DANG_KIEM_THU`).
- `TL-03-thiet-ke-csdl.md` "Bảng policy.test_run và policy.test_case_result (bổ sung)" (dòng 1829).
- `contracts/openapi/modules/policies.yaml` nhóm `/test-runs`, schema `PolicyTestRunCreate`; `contracts/openapi/modules/jobs.yaml`.
- Bản trích `extract/tl01-xuyen-suot.md` X-PP-05, X-PP-06, X-PP-07.

## 3. Phạm vi công việc

- `runPolicyTests` (Idempotency-Key, `If-Match`): chuyển `NHAP` → `DANG_KIEM_THU`, tạo job 202; lần chạy đang dở cho cùng phiên bản trả `POLICY-409-TEST-RUNNING`.
- Bộ chạy cel-java: biên dịch quy tắc một lần mỗi lần chạy bằng `vt-cel`, đánh giá DMN bằng Flowable DMN, so từng ca với `expected` theo làm tròn `scale: 2, HALF_UP`, ghi `policy.test_case_result` (`passed`, `actual`, `diff`) và tổng `test_run` (`engine` `CEL_JAVA`).
- Đối chiếu cel-js: hợp đồng ghi lần chạy dùng cả cel-java và cel-js tại máy chủ. Điểm lệch cần lưu ý: lõi Java không chạy được cel-js trực tiếp; chọn chạy bộ ca cel-js trong ảnh container Node dùng chung `@vt/form-engine` (gọi như job phụ) hoặc engine JavaScript nhúng, nêu lý do trong PR để người phụ trách duyệt và ghi `wiki/14`. Ca khác nhau ghi `engine_match = false` và cảnh báo MSG-15-07.
- Kiểm thử thuộc tính bằng jqwik với số bộ theo `propertyDatasets` (mặc định 10.000): điểm ∈ [0; 100], C ≥ 0, tăng `so_luong_duyet` không làm giảm mức, `ty_le_O < 1` không bao giờ HTXSNV; ca biên 59,995 và 89,995, kế hoạch bằng 0; kết quả lưu `property_result_json`.
- Gói con kế thừa: chạy thêm ca vàng của gói cha trên gói con, không ca nào cho mức cao hơn (TL-01 X-PP-11).
- Khi có `compareWithVersion`: so sánh tác động trên bộ ca và trả phân bố chênh lệch mức (X-PP-07, phần dữ liệu hồ sơ thật giả danh hoá là việc của scoring-service, ghi rõ).
- Kết thúc: đạt 100% và khớp hai engine thì `DAT_KIEM_THU`, `golden_pass_rate = 1`, `last_test_run_id`; ngược lại về `NHAP` với MSG-15-06.

## 4. Ngoài phạm vi

- Chạy lại mẫu 0,1% hồ sơ kỳ đã khoá kiểm tất định (scoring-service, BR-08-11).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/policy/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/policy/**`
- `backend/services/cel-js-runner/**` (chỉ khi chọn ảnh container Node riêng; tên suy luận)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; kiểm thử tích hợp Testcontainers: bộ 50 ca đạt thì `DAT_KIEM_THU`; một ca sai thì về `NHAP`; một ca lệch engine thì về `NHAP` với `engine_match = false`.
- Kiểm thử thuộc tính 10.000 bộ chạy dưới 2 phút trên máy kiểm thử.
- Kiểm thử hợp đồng ba thao tác khớp `policies.yaml`.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
