# CORE-05-02 · QT-15 Phiên bản Policy Pack, nhân bản, máy trạng thái, kế thừa `overridable`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-05-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-05-01 |
| Truy vết | QT-15 → SCR-1501 → `GET /api/v1/policies/packs` (`listPolicyPacks`), `GET /api/v1/policies/packs/{id}` (`getPolicyPack`), `GET/POST /api/v1/policies/packs/{id}/versions` (`listPolicyPackVersions`, `createPolicyPackVersion`), `GET /api/v1/policies/packs/{id}/versions/{v}` (`getPolicyPackVersion`) → MOD-05 → `policy.policy_pack`, `policy.policy_pack_version`, `policy.policy_rule`, `policy.decision_table` → BR-15-13, lỗi `POLICY-403-NOT-OVERRIDABLE`, `POLICY-422-VERSION-EXISTS` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng domain Policy Pack của MOD-05: gói gốc toàn cục và gói kế thừa của tenant, phiên bản semver với máy trạng thái `NHAP` → `DANG_KIEM_THU` → `DAT_KIEM_THU` → `CHO_PHE_DUYET` → `DA_PHAT_HANH` → `NGUNG` theo baseline 9.1, nhân bản phiên bản làm bước 1 của QT-15, và luật kế thừa chỉ cho tenant sửa quy tắc có cờ `overridable`. Khi xong, SCR-1501 liệt kê gói, xem phiên bản và tạo nháp mới qua API thật.

## 2. Đầu vào bắt buộc đọc

- `TL-02b-quy-trinh.md` mục "QT-15. Soạn, kiểm thử và phát hành Policy Pack" (dòng 1284): "Mô tả các bước" (dòng 1315), "Trạng thái đối tượng" (dòng 1335), "Quy tắc nghiệp vụ" (dòng 1353, BR-15-13), "SCR-1501. Config Studio – công thức" (dòng 1382).
- `TL-01-thiet-ke-kien-truc.md` "Cấu trúc gói" (dòng 2386), "Phát hành, ký số và vòng đời" (dòng 2511).
- `contracts/openapi/modules/policies.yaml` các đường dẫn `/policies/packs`, `/policies/packs/{id}`, `/policies/packs/{id}/versions`, `/policies/packs/{id}/versions/{v}`.
- Bản trích `extract/tl02b-may-chu.md` QT-15 (BE-Q15-01, BE-Q15-11), `extract/tl01-xuyen-suot.md` mục 4 (X-PP-01, X-PP-08, X-PP-11).

## 3. Phạm vi công việc

- Aggregate `PolicyPack` và `PolicyPackVersion` với máy trạng thái đầy đủ; chuyển trái phép trả 409 `POLICY-409-VERSION-CONFLICT` hoặc mã tương ứng trong hợp đồng; mọi chuyển ghi outbox.
- `createPolicyPackVersion` (Idempotency-Key): nhân bản toàn bộ quy tắc, bảng quyết định, tham số và tham số luồng duyệt từ phiên bản nguồn sang phiên bản `NHAP` mới, ghi `source_version_id`, `change_note`; số phiên bản trùng trả `POLICY-422-VERSION-EXISTS`.
- Kế thừa `extends`: gói tenant chỉ sửa quy tắc của gói cha có `overridable = true` (BR-15-13, `POLICY-403-NOT-OVERRIDABLE`); kiểm "chỉ siết, không nới" theo TL-01 X-PP-11 được đánh dấu để CORE-05-07 chạy ca vàng của gói cha trên gói con.
- `listPolicyPacks`, `listPolicyPackVersions` phân trang con trỏ; `getPolicyPackVersion` trả `ETag` từ `row_version`.
- Trình kiểm lược đồ manifest gói (`apiVersion: policy.okrkpi.vn/v1`, `kind: PolicyPack`, `metadata`, `inputs`, `definitions`, `rounding`, `classification`, `quotas`) dùng khi nhập gói gốc, theo TL-01 "Cấu trúc gói".
- Quyền theo ma trận `wiki/15a` dòng SCR-1501 qua OPA.

## 4. Ngoài phạm vi

- Soạn quy tắc CEL (CORE-05-04), DMN (CORE-05-05), kiểm thử (CORE-05-07), phê duyệt và phát hành (CORE-05-09, CORE-05-10).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/policy/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/policy/**`
- `data/policy-pack/**` (gói gốc mẫu dùng trong kiểm thử, dữ liệu giả lập)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; kiểm thử đơn vị máy trạng thái; kiểm thử tích hợp Testcontainers cho nhân bản đủ quy tắc và bảng, sửa quy tắc không `overridable` bị 403.
- Kiểm thử hợp đồng năm thao tác khớp `policies.yaml`; ArchUnit xanh (`policy` chỉ phụ thuộc cổng `api` của `config`).
- Độ phủ lớp `domain`, `application` ≥ 80%.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
