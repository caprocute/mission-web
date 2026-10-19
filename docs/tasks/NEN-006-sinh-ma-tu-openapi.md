# NEN-006 · ADR-32 Sinh giao diện máy chủ từ hợp đồng OpenAPI và kiểm `openapi-diff` với springdoc

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-006` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | M |
| Phụ thuộc | NEN-005 |
| Truy vết | ADR-32, YC-PT-05, YC-PT-09 → `contracts/openapi/modules/*.yaml` (361 thao tác, 11 tệp) → SVC-05 (MOD-01..MOD-08, `/jobs`) và các microservice Java → kiểm thử hợp đồng |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Thực hiện phần máy chủ của ADR-32: giao diện controller và DTO Java được sinh từ hợp đồng OpenAPI 3.1 trong `contracts/openapi/` bằng openapi-generator (Spring, `interfaceOnly=true`), lớp hiện thực chỉ việc cài interface; đồng thời có cơ chế kiểm ngược: tài liệu springdoc lúc chạy được so với hợp đồng bằng `openapi-diff`, lệch thì build hỏng. Khi xong, mọi task CORE và DV viết API chỉ cần thêm phụ thuộc vào mô-đun hợp đồng tương ứng và một lớp kiểm thử kế thừa sẵn.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-02-hop-dong-API-truoc.md` (toàn bộ, nhất là quyết định L12–27, việc bắt buộc L16–21, mở rộng `x-mission` L23, bảng HD-01..HD-07 L50–60).
- `contracts/openapi/README.md` (quy ước đường dẫn, `operationId`, lỗi, `x-mission`, lệnh kiểm tra); `contracts/openapi/common/common.yaml`; `contracts/openapi/bundle.mjs`; `contracts/redocly.yaml`.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Hợp đồng API theo OpenAPI" (L1352–1356) và "Bảo vệ API và ứng dụng web" (L3062–3066: Jakarta Validation sinh từ OpenAPI).
- `docs/quy-dinh/BACKEND.md` mục 2.

## 3. Phạm vi công việc

- Tạo mô-đun `backend/libs/vt-api-contract/` chạy `openapi-generator-maven-plugin` (generator `spring`, `interfaceOnly=true`, `useSpringBoot3=true`, `useTags=true`, `useBeanValidation=true`, `skipDefaultInterface=true`, `openApiNullable=false`) cho từng tệp trong `contracts/openapi/modules/`, mỗi tệp một gói `vn.viettel.okrkpi.contract.<tệp>.api` và `.model` (suy luận về tên gói); tham chiếu `../common/common.yaml` được giải quyết khi sinh.
- Tách sản phẩm sinh theo đơn vị triển khai bằng classifier hoặc mô-đun con để mission-core chỉ kéo chín tệp của SVC-05 (`okr`, `tasks`, `products`, `appraisals`, `policies`, `dossiers`, `oversight`, `config`, `jobs`) và microservice chỉ kéo phần của mình; ghi bảng ánh xạ `x-mission.owner` → mô-đun trong `backend/libs/vt-api-contract/README.md`.
- Kiểm rằng kiểu sinh ra giữ đúng enum mã tiếng Việt (`DA_GIAO`, `CHO_NGHIEM_THU`…), trường camelCase, kiểu `Uuid`, `Date`, `DateTime`, `ProblemDetails`, `Job`; không sửa tệp hợp đồng để "chiều" generator, nếu vướng thì cấu hình `typeMappings`/`importMappings` hoặc ghi vào báo cáo.
- Tạo mô-đun `backend/libs/vt-contract-test/` cung cấp lớp kiểm thử cơ sở `ContractConformanceTest`: khởi động ứng dụng Spring trong kiểm thử, lấy `/v3/api-docs` (springdoc 2.8, OpenAPI 3.1), so với `contracts/dist/<tệp>.yaml` bằng `openapi-diff` (thư viện `org.openapitools.openapidiff`), thất bại khi có thay đổi không tương thích hoặc thao tác thiếu/thừa trong phạm vi owner.
- Cung cấp tiện ích kiểm phản hồi: so một phản hồi thực tế (MockMvc) với lược đồ hợp đồng và với `examples` trong hợp đồng (BS-02 L25), để task nghiệp vụ viết kiểm thử hợp đồng ngắn.
- Chứng minh trên mẫu nhỏ: một ứng dụng kiểm thử trong `vt-contract-test/src/test` cài `JobsApi` (`getJob`, `cancelJob` của `jobs.yaml`) với dữ liệu giả, chạy `ContractConformanceTest` xanh; cố ý đổi tên một trường thì kiểm thử đỏ.
- Viết script `backend/tools/contracts-bundle.sh` gọi `npm --prefix ../contracts ci && npm --prefix ../contracts run bundle` để sinh `contracts/dist/` trước khi chạy kiểm thử (Jenkins INF-05 gọi lại script này).

## 4. Ngoài phạm vi

- Sửa hợp đồng OpenAPI (YC-PT-09: sửa mã, không sửa hợp đồng); nếu hợp đồng sai thì mở task sửa hợp đồng riêng.
- Hiện thực các API nghiệp vụ: CORE-xx, DV-xx; `/jobs` thật trong mission-core: CORE-00-02.
- Pact Broker và kiểm thử hợp đồng giữa service: QA-02.

## 5. Tệp được phép sửa

- `backend/libs/vt-api-contract/**`, `backend/libs/vt-contract-test/**`
- `backend/libs/pom.xml` (tạo mới nếu chưa có), `backend/pom.xml` (chỉ thêm mô-đun)
- `backend/platform-bom/pom.xml` (chỉ thêm phiên bản openapi-generator, openapi-diff, swagger-parser)
- `backend/tools/contracts-bundle.sh`

## 6. Tiêu chí hoàn thành

- `cd backend && ./tools/contracts-bundle.sh && ./mvnw -q -pl libs/vt-api-contract,libs/vt-contract-test -am verify` chạy xanh.
- Sinh đủ interface cho 361 thao tác; báo cáo ghi số interface/phương thức sinh được theo từng tệp và đối chiếu với số thao tác đếm từ hợp đồng (script đếm đính kèm trong báo cáo).
- Kiểm thử âm: đổi tên trường trong lớp mẫu hoặc thêm thao tác ngoài hợp đồng làm `ContractConformanceTest` thất bại với thông báo dễ đọc.
- Mã sinh không vào Spotless/JaCoCo/Sonar (loại trừ `target/generated-sources`).
- Báo cáo cuối task theo AGENTS.md mục 6; thêm vào wiki trang 16 (nếu đã có) mục "API-first phía máy chủ" mô tả cách dùng hai mô-đun.

## 7. Báo cáo

Theo AGENTS.md mục 6.
