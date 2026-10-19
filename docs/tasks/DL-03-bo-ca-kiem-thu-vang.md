# DL-03 · QT-15/J6 Bộ ca kiểm thử vàng đầy đủ do nghiệp vụ ký

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DL-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 – lõi nghiệp vụ trên nền tảng mới |
| Luồng đề xuất | B11 |
| Cỡ việc | L (> 1 ngày, nên tách: xem cuối mục 3) |
| Phụ thuộc | DL-02, CORE-05-06 |
| Truy vết | J6; QT-15 (BR-15-07, BR-15-08), QT-09, QT-10 → API `POST /api/v1/policies/test-cases` (`createGoldenTestCase`), `POST /api/v1/policies/test-cases/{id}/approve` (`approveGoldenTestCase`), `POST /api/v1/policies/packs/{id}/versions/{v}/test-runs` (`runPolicyTests`) → MOD-05, SVC-07 → bảng `policy.golden_test_case`, `policy.test_run`, `policy.test_case_result` → không phát EVT (EVT-13 thuộc bước phát hành) |
| Trạng thái | Chờ người dùng chốt: cần nghiệp vụ (Ban Tổ chức) rà soát và ký số bộ ca |

## 1. Mục tiêu

Mỗi Policy Pack của DL-01, DL-02 có bộ ca mẫu vàng phủ mọi công thức, mọi dòng DMN, mọi tham số có nhiều giá trị và cả 13 trường hợp đặc thù, chạy đạt 100% trên cel-java và cho kết quả trùng với cel-js. Bộ ca là điều kiện để phiên bản gói chuyển `DAT_KIEM_THU` (BR-15-07) và là bằng chứng tiêu chí J6; agent chuẩn bị trọn bộ ca và bảng đối chiếu để nghiệp vụ rà và ký, không tự ký thay.

## 2. Đầu vào bắt buộc đọc

- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`, chương "Policy Pack…", mục "Bộ ca mẫu vàng" (dòng 2482 trở đi: định dạng `bo_ca_mau`, yêu cầu 100%, ca biên và kiểm thử thuộc tính); chương "Kiểm thử và tích hợp, triển khai liên tục" (J6).
- TL-02b `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md`: QT-15 "Quy tắc nghiệp vụ" (BR-15-07, BR-15-08), "Ngoại lệ và xử lý"; QT-09, QT-10 "Quy tắc nghiệp vụ"; QT-11 bảng 13 trường hợp.
- TL-03 mục "Schema policy (MOD-05)" (bảng `golden_test_case`: `case_code`, `input`, `expected`, `legal_ref`, `source`, `approved_by_customer`, `signature`; bảng `test_run`: `engine`, `engine_match`).
- Hợp đồng `contracts/openapi/modules/policies.yaml`; wiki `wiki/11-kiem-thu-va-chat-luong.md`.

## 3. Phạm vi công việc

- Viết tệp `tests/bo-ca-mau-<tên>.yaml` cho mỗi gói theo định dạng `bo_ca_mau` của TL-01 (`ca[].id`, `mo_ta`, `dau_vao`, `mong_doi`), mỗi ca có `legal_ref` và `source` thuộc `KHACH_HANG`, `BA`, `VAN_BAN`.
- Phủ ít nhất: mỗi dòng của mọi bảng DMN một ca; mỗi giá trị tham số `cach_tinh_chat_luong` (BR-15-02), `loai_ngay` (BR-15-03) và `nam.cach_tinh` (BR-10-02) một ca riêng; 13 trường hợp đặc thù §1–§13; ca BR-09-10 hạ mức liền kề kèm câu giải thích mong đợi; ca người đứng đầu BR-09-13; ca luồng Nhà nước BR-09-11.
- Thêm ca biên tự sinh theo TL-01: điểm 59,995 và 89,995, kế hoạch = 0, ngày chậm rất lớn, tỷ lệ O < 1.
- Viết kiểm thử thuộc tính jqwik: điểm ∈ [0; 100]; tăng `so_luong_duyet` không làm giảm mức; `ty_le_O < 1` không bao giờ cho HTXSNV.
- Dùng bộ chạy chung của `vt-cel` (NEN-041) để chạy cùng bộ ca trên cel-java và cel-js (`@vt/form-engine`), ghi kết quả `engine_match`; khác nhau dù một ca thì không đạt (BR-15-07).
- Sinh bảng đối chiếu dễ đọc cho nghiệp vụ (`data/golden/<gói>/bang-doi-chieu.csv`: mã ca, mô tả, đầu vào chính, mức mong đợi, căn cứ) để rà và ký qua `approveGoldenTestCase`; dữ liệu đều là dữ liệu giả lập, không dùng hồ sơ thật (SEC-52).
- Chuẩn bị tệp nạp ca qua `createGoldenTestCase` (trạng thái `NHAP`) và hướng dẫn ngắn cho người ký trong `data/golden/README.md`; agent không ký thay và không sửa kỳ vọng của ca đã ký (BR-15-08).
- Gợi ý tách: DL-03a ca quý luồng Đảng; DL-03b ca năm và 13 trường hợp đặc thù; DL-03c ca luồng Nhà nước và kiểm thử thuộc tính.

## 4. Ngoài phạm vi

- Sửa công thức hay bảng DMN (DL-01, DL-02); nếu ca phát hiện lỗi, ghi vào báo cáo và mở việc sửa ở task tương ứng.
- Bộ chạy test-run và quy trình ký ca ở máy chủ (CORE-05-06); phát hành gói.

## 5. Tệp được phép sửa

- `data/policy-pack/**/tests/**`
- `data/golden/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/policy/**`, `backend/mission-core/src/test/resources/policy-pack/**`
- `wiki/11-kiem-thu-va-chat-luong.md` (mục ca vàng)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; bộ chạy báo 100% ca đạt trên cel-java cho mọi gói.
- Bộ so khớp cel-java với cel-js báo `engine_match = true` cho 100% ca; kiểm thử thuộc tính chạy 10.000 bộ không vi phạm bất biến.
- Ma trận phủ (dòng DMN × ca, tham số × ca, §1–§13 × ca) được in trong báo cáo, không còn ô trống.
- Với CORE-05-06 đã gộp: nạp toàn bộ ca qua API trên Testcontainers, `runPolicyTests` trả 202 rồi `test_run.result = DAT`.
- Báo cáo nêu rõ số ca đã được nghiệp vụ ký và số ca còn chờ ký.

## 7. Báo cáo

Theo AGENTS.md mục 6.
