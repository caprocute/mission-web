# NEN-005 · ADR-16 Khung Maven đa mô-đun `backend/`: POM cha, BOM phiên bản, Java 21, cổng chất lượng

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-005` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | M |
| Phụ thuộc | NEN-001 |
| Truy vết | ADR-16, ADR-01 → TL-01 Phụ lục B (danh mục công nghệ), chương "Kiểm thử và tích hợp, triển khai liên tục" → SVC-04..SVC-08, SVC-10, SVC-11, SVC-13 (các service Java) |
| Trạng thái | Hoàn thành (20/10/2026, đã gộp vào `main`: `backend/pom.xml`, `backend/vt-bom/`, `backend/vt-build-verification/`) |

## 1. Mục tiêu

Dựng thư mục `backend/` thành một dự án Maven đa mô-đun mà mọi mã Java của hệ thống (mission-core, thư viện nền, microservice Java) dùng chung: POM cha khoá Java 21 và các plugin cổng chất lượng, một BOM ghim phiên bản đúng TL-01 Phụ lục B, Maven Wrapper để chạy giống nhau ở máy agent và Jenkins. Khi xong, `cd backend && ./mvnw -q verify` chạy xanh trên một mô-đun mẫu nhỏ và chứng minh định dạng mã, độ phủ, quét bảo mật và đóng gói ảnh đều hoạt động; các task NEN-006 trở đi chỉ việc thêm mô-đun.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` Phụ lục B "Danh mục công nghệ" (L3702–3758); mục "Công nghệ và phiên bản" của SVC-05 (L385–387); chương "Kiểm thử và tích hợp, triển khai liên tục" (L3122–3138: "Tháp kiểm thử", "Đường ống CI/CD"); ADR-16 (L3542).
- `docs/quy-dinh/BACKEND.md` mục 1 (stack), mục 4 (độ phủ 80% lớp `domain`, `application`; SpotBugs + Find Security Bugs).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục "Nguyên tắc cấu hình chung" (L835–849: JRE 21 distroless, không root, `MaxRAMPercentage=70`).

## 3. Phạm vi công việc

- Tạo `backend/pom.xml` (POM cha, `groupId` `vn.viettel.okrkpi`, đóng gói `pom`) với `maven.compiler.release=21`, mã hoá UTF-8, khối `<modules>` để trống chỗ cho `libs/`, `mission-core/`, `services/`, và Maven Wrapper (`backend/mvnw`, `backend/.mvn/wrapper/`).
- Tạo BOM `backend/platform-bom/pom.xml` ghim phiên bản theo Phụ lục B: Spring Boot 3.5.x (import `spring-boot-dependencies`), Spring Modulith 1.4.x, Hibernate 6.6, Resilience4j 2.3, Flyway 11, springdoc-openapi 2.8, Log4j2 2.24, ArchUnit 1.4, Testcontainers, JUnit 5, jqwik, PIT, cel-java, Apache Camel 4.x LTS, Flowable 7.x, docx4j 11, OpenPDF 2.x; chọn bản vá mới nhất còn hỗ trợ và ghi lý do chọn vào chú thích POM.
- Cấu hình `maven-enforcer-plugin`: Java 21, Maven tối thiểu, hội tụ phụ thuộc, cấm phụ thuộc trái giấy phép mà Phụ lục B đã loại (iText 7, Camunda, Elasticsearch client, Redis client của Redis Ltd., HashiCorp Vault client) (suy luận về cách thực thi bằng `bannedDependencies`).
- Cấu hình plugin chất lượng trong `<pluginManagement>` và bật mặc định: Spotless (định dạng Java và POM, `spotless:check` ở `verify`), JaCoCo (báo cáo XML cho Sonar; ngưỡng 80% dòng cho gói `..domain..` và `..application..`, 70% nhánh theo TL-01 "Tháp kiểm thử"), SpotBugs + Find Security Bugs (lỗi mức cao làm hỏng build), Surefire/Failsafe tách kiểm thử đơn vị và tích hợp (`*IT.java`).
- Cấu hình PIT trong profile `-Pmutation` (ngưỡng đột biến 70% áp cho lớp máy trạng thái và tính điểm theo TL-01) để task nghiệp vụ bật khi cần.
- Cấu hình Jib trong `<pluginManagement>`: ảnh nền JRE 21 distroless, người dùng không root (UID 10001 theo TL-04), `-XX:MaxRAMPercentage=70`, nhãn OCI, không đẩy ảnh trong `verify` (chỉ `jib:buildTar` khi gọi tường minh).
- Thêm thuộc tính Sonar (`sonar.projectKey`, đường dẫn báo cáo JaCoCo, loại trừ mã sinh `target/generated-sources/**`) và ghi trong `backend/README.md` các ngưỡng quality gate TL-01 (lớp ≤ 1.000 dòng, hàm ≤ 100 dòng, độ phức tạp nhận thức ≤ 15) để INF-05 cấu hình phía máy chủ Sonar.
- Tạo mô-đun mẫu `backend/tools/build-smoke/` (một lớp, một kiểm thử) chỉ để chứng minh chuỗi plugin chạy; ghi rõ trong README rằng mô-đun này được xoá khi NEN-009 có mô-đun thật (suy luận).
- Viết `backend/README.md` ngắn: cấu trúc thư mục, lệnh build, cách thêm mô-đun, nơi đặt phiên bản; thêm `backend/.gitignore` cho `target/`.

## 4. Ngoài phạm vi

- Sinh mã từ OpenAPI: NEN-006; Avro: NEN-007; thư viện nền: NEN-009..NEN-016.
- Pipeline Jenkins dùng chung, đẩy ảnh lên Harbor, ký cosign, SBOM: INF-05.
- Khung ứng dụng mission-core: CORE-00-01.

## 5. Tệp được phép sửa

- `backend/pom.xml`, `backend/platform-bom/**`, `backend/tools/build-smoke/**`
- `backend/mvnw`, `backend/mvnw.cmd`, `backend/.mvn/**`
- `backend/README.md`, `backend/.gitignore`
- `wiki/16-kien-truc-mission-core-va-microservice.md` (chỉ mục liên quan tới task này; tạo trang nếu chưa có, theo quy tắc cập nhật wiki)

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q verify` chạy xanh; chạy lại sau khi cố ý làm sai định dạng một tệp thì `spotless:check` báo lỗi (ghi kết quả cả hai lần vào báo cáo).
- `./mvnw -q -Pmutation verify` chạy được PIT trên mô-đun mẫu.
- `./mvnw -q -pl tools/build-smoke jib:buildTar` tạo được tệp ảnh; `docker load` rồi `docker inspect` cho thấy người dùng không phải root.
- Thêm thử một phụ thuộc bị cấm (ví dụ `com.itextpdf:itext7-core`) thì enforcer chặn build; gỡ ra trước khi gộp.
- `./mvnw -q dependency:tree` không có xung đột phiên bản; báo cáo liệt kê phiên bản đã chọn cho từng mục Phụ lục B.
- Báo cáo cuối task theo AGENTS.md mục 6; cập nhật `wiki/16-...` (nếu đã có) hoặc ghi vào báo cáo để NEN-002 đưa vào wiki.

## 7. Báo cáo

Theo AGENTS.md mục 6.
