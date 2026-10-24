# QA-02 · ADR-32 Pact Broker và kiểm thử hợp đồng frontend với API, service với service

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh QA-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0c |
| Luồng đề xuất | B9 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | NEN-006, INF-05 |
| Truy vết | ADR-32 (API-first) → TL-01 ch.18 (Pact, `can-i-deploy`) → `contracts/openapi/modules/*.yaml` → SVC-17 ↔ SVC-05; SVC-05 ↔ SVC-04, SVC-06 → tiêu chí A7, J2 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Đưa kiểm thử hợp đồng vào chuỗi phát hành: Pact Broker chạy được, frontend là bên tiêu dùng sinh pact cho các API mission-core mà `@vt/api-client` gọi, mission-core và các service xác minh pact ở phía cung cấp, và bước `pact can-i-deploy` trong pipeline chặn phát hành phá hợp đồng. Khi xong, một thay đổi ở máy chủ làm lệch một trường frontend đang dùng bị pipeline chặn trước khi gộp, bổ sung cho kiểm `openapi-diff` (ADR-32) vốn chỉ so hai bản hợp đồng.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "Tháp kiểm thử" (3124) và "Đường ống CI/CD" (3134).
- `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-02-hop-dong-API-truoc.md` (ADR-32, vai trò của kiểm thử hợp đồng).
- `contracts/openapi/README.md`, `frontend/projects/api-client/` (client sinh từ hợp đồng, WEB-064), `docs/quy-dinh/BACKEND.md` mục 2 và 4, pipeline `vt-pipeline` (INF-05) và WEB-002.

## 3. Phạm vi công việc

- Viết chart `deploy/charts/pact-broker` (Pact Broker trên PostgreSQL, sau xác thực nội bộ) cho cụm quản trị và một dịch vụ trong profile `qa` của `infra/dev/docker-compose.yml` (cổng dải 35xxx) để dùng sớm ở dev.
- Thêm bộ pact phía tiêu dùng trong frontend: mỗi nhóm thao tác chính của `@vt/api-client` mà remote đang gọi (ví dụ `listMyTasks`, `updateTaskProgress`, `createEvidence`, `listOkrObjectives`, `submitAppraisalSelfAssessment`) có tương tác mô tả đúng trường frontend thực sự đọc; dùng thư viện Pact JS (phụ thuộc mới, nêu lý do trong PR theo AGENTS.md quy tắc 1, chỉ là devDependency).
- Thêm xác minh pact phía cung cấp trong `backend/mission-core` (JUnit 5, Pact JVM), dựng trạng thái dữ liệu bằng Testcontainers; xác minh chạy trong `mvn verify -Pci`.
- Thêm pact service với service tối thiểu: mission-core là bên tiêu dùng của org-service (đọc cây tổ chức) và workflow-service (hoàn thành tác vụ duyệt), theo các thao tác có trong `contracts/openapi/modules/org.yaml` và `platform.yaml`.
- Bật bước `pact can-i-deploy --to-environment <môi trường>` trong `vt-pipeline` (INF-05) và pipeline frontend (WEB-002), cùng bước ghi nhận phiên bản đã triển khai (`record-deployment`).
- Ghi quy ước trong wiki 11: pact chỉ mô tả phần bên tiêu dùng dùng thật, không chép toàn bộ OpenAPI; hợp đồng vẫn là nguồn sự thật (ADR-32), pact là lưới an toàn bổ sung.

## 4. Ngoài phạm vi

- Pact với hệ thống ngoài qua integration-gateway (DV-13-11).
- Sinh kiểu và kiểm `api:check` của frontend (WEB-064, đã có).

## 5. Tệp được phép sửa

- `deploy/charts/pact-broker/**`, `infra/dev/docker-compose.yml` (thêm profile `qa`), `infra/dev/README.md`.
- `frontend/pact/**`, `frontend/package.json` (devDependency Pact), `backend/mission-core/src/test/java/vn/viettel/okrkpi/contract/**`, `backend/pom.xml` (phụ thuộc test Pact JVM).
- `ci/vt-pipeline/**` (bước `can-i-deploy`), `Jenkinsfile` của frontend nếu WEB-002 đã tạo; `wiki/11-kiem-thu-va-chat-luong.md`.

## 6. Tiêu chí hoàn thành

- Bộ pact frontend chạy bằng một lệnh `npm` mới trong `frontend/` và xuất pact lên Broker dev; xác minh phía mission-core chạy xanh trong `cd backend && mvn -q -pl mission-core -am verify`.
- Thử phá hợp đồng có chủ đích (đổi tên một trường trả về mà frontend đọc): xác minh phía cung cấp thất bại và `can-i-deploy` trả không cho phép; khôi phục thì trở lại cho phép.
- `helm lint`, `helm template` cho `pact-broker` không lỗi; `docker compose --profile qa config` hợp lệ.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki 11 cập nhật hàng "Hợp đồng" trong bảng tầng kiểm thử.

## 7. Báo cáo

Theo AGENTS.md mục 6.
