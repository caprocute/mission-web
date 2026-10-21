# backend – mã máy chủ Java của Mission

Thư mục này là dự án Maven đa mô-đun chứa mọi mã Java của hệ thống: khối nguyên khối theo mô-đun `mission-core`, các thư viện nền `vt-platform-*` và các microservice Java (ký số, tài liệu, tích hợp, quy trình Flowable). Khung do NEN-005 dựng theo ADR-16 và TL-01 Phụ lục B; quy định chi tiết phía máy chủ ở `docs/quy-dinh/BACKEND.md`.

## Cấu trúc

```
backend/
├── pom.xml                 POM cha: Java 21, enforcer, Spotless, JaCoCo, SpotBugs, Surefire/Failsafe, PIT, Jib, Sonar
├── platform-bom/pom.xml    BOM: nơi duy nhất ghim phiên bản phụ thuộc (TL-01 Phụ lục B)
├── mvnw, .mvn/             Maven Wrapper (Maven 3.9.11) và cấu hình thử lại khi tải phụ thuộc
├── libs/                   thư viện nền (NEN-009..NEN-016, vt-db-migration NEN-030) – chưa có
├── mission-core/           khối nguyên khối theo mô-đun MOD-01..MOD-08 (CORE-00-01) – chưa có
├── services/               microservice Java (NEN-019 trở đi) – chưa có
└── libs/vt-kernel, libs/vt-platform-starter   nhân kernel và starter nền (NEN-009, NEN-016)
```

`platform-bom` không nằm trong reactor: POM cha kế thừa nó qua `relativePath`, nên build cục bộ không cần cài BOM trước. Khi phát hành, đường ống (INF-05) chạy `./mvnw -f platform-bom deploy` trước rồi mới deploy phần còn lại; dự án Java nằm ngoài reactor import BOM với `scope=import`.

## Lệnh thường dùng

```bash
cd backend
./mvnw verify                      # biên dịch, kiểm thử đơn vị + tích hợp, định dạng, độ phủ, SpotBugs
./mvnw spotless:apply              # sửa định dạng Java (google-java-format) và POM (sortPom)
./mvnw -Pmutation verify           # thêm kiểm thử đột biến PIT, ngưỡng 70%
./mvnw -pl <mô-đun> -am verify     # chỉ một mô-đun và các mô-đun nó phụ thuộc
./mvnw -pl <mô-đun> jib:buildTar   # đóng ảnh ra target/jib-image.tar (không đẩy)
./mvnw dependency:tree -Dverbose   # xem cây phụ thuộc, tìm xung đột
```

`-Dquality.skip=true` tắt Spotless, JaCoCo check và SpotBugs để thử nhanh cục bộ; không dùng cờ này trong CI.

## Cổng chất lượng trong `verify`

| Cổng | Công cụ | Ngưỡng | Nguồn |
|---|---|---|---|
| Phiên bản công cụ | maven-enforcer-plugin | Java 21, Maven ≥ 3.9.6, hội tụ phụ thuộc, không trùng khai báo | TL-01 PL B |
| Thư viện bị cấm | enforcer `bannedDependencies` | xem mục dưới | TL-01 PL B |
| Định dạng | Spotless | google-java-format (kiểu GOOGLE), sortPom | (suy luận: chọn định dạng chuẩn phổ biến) |
| Độ phủ | JaCoCo | 80% dòng, 70% nhánh cho gói `..domain..`, `..application..` | TL-01 "Tháp kiểm thử", BACKEND.md mục 4 |
| Lỗi tĩnh, bảo mật | SpotBugs + Find Security Bugs | lỗi mức cao (`threshold=High`) làm hỏng build | BACKEND.md mục 4 |
| Kiểm thử | Surefire (`*Test`), Failsafe (`*IT`) | mọi kiểm thử phải qua | TL-01 "Đường ống CI/CD" |
| Đột biến (tuỳ chọn) | PIT, profile `mutation` | 70% (mô-đun đặt `pitest.targetClasses` về máy trạng thái, tính điểm) | TL-01 "Tháp kiểm thử" |

### Thư viện bị cấm

Theo TL-01 Phụ lục B, các nhóm sau bị cấm do giấy phép, kể cả khi là phụ thuộc bắc cầu: iText 7 (`com.itextpdf`, AGPL; dùng OpenPDF, docx4j), Camunda (`org.camunda.*`, `io.camunda*`; dùng Flowable), client Elasticsearch (`org.elasticsearch*`, `co.elastic.clients`, `spring-data-elasticsearch`; dùng OpenSearch), client Redis của Redis Ltd. (`redis.clients`, `com.redis*`; dùng Valkey qua Lettuce), client HashiCorp Vault (`com.hashicorp*`, `vault-java-driver`; dùng OpenBao, cách truy cập chốt ở NEN-015).

## Quality gate SonarQube (INF-05 cấu hình phía máy chủ)

POM cha đã khai `sonar.projectKey`, đường dẫn báo cáo JaCoCo (`target/site/jacoco/jacoco.xml`, `target/site/jacoco-it/jacoco.xml`) và loại trừ mã sinh `target/generated-sources/**`. Quality gate cần đặt trên máy chủ Sonar theo TL-01: lớp không quá 1.000 dòng, hàm không quá 100 dòng, độ phức tạp nhận thức không quá 15, độ phủ mã mới 80% dòng và 70% nhánh, không lỗi mới mức Blocker hay Critical.

## Ảnh chạy (Jib)

Jib cấu hình sẵn trong `pluginManagement` theo TL-04 "Nguyên tắc cấu hình chung": ảnh nền `gcr.io/distroless/java21-debian12:nonroot`, người dùng `10001:10001`, `-XX:MaxRAMPercentage=70`, nhãn OCI. Mặc định dùng G1 (`jib.jvm.gc`); service API (mission-core, workflow API, org-service, integration-gateway) đặt `<jib.jvm.gc>-XX:+UseZGC -XX:+ZGenerational</jib.jvm.gc>`. Jib không gắn vào vòng đời nên `verify` không bao giờ đẩy ảnh; đẩy lên Harbor và ký cosign do INF-05 làm.

## Thêm một mô-đun

1. Tạo thư mục trong `libs/`, `mission-core/` hoặc `services/`, với `pom.xml` có `<parent>` là `vn.viettel.okrkpi:mission-backend-parent` (đặt `relativePath` đúng).
2. Không ghi `<version>` cho phụ thuộc; nếu thư viện chưa có trong `platform-bom`, thêm vào BOM kèm chú thích lý do chọn phiên bản.
3. Thêm `<module>` vào POM cha (hoặc POM gom `libs/pom.xml`, `services/pom.xml` khi có).
4. Chạy `./mvnw spotless:apply` rồi `./mvnw -pl <mô-đun> -am verify`.

## Phiên bản đã chọn

Phiên bản và lý do chọn ghi trong chú thích của `platform-bom/pom.xml`. Tóm tắt: Spring Boot 3.5.16, Spring Modulith 1.4.13, Hibernate ORM 6.6.53.Final, Resilience4j 2.3.0, Flyway 11.7.2, springdoc-openapi 2.8.17, Log4j2 2.24.3, ArchUnit 1.4.2, Testcontainers 1.21.4, JUnit 5.12.2, jqwik 1.9.3, PIT 1.30.1, cel-java 0.14.0, Apache Camel 4.14.9 (LTS), Flowable 7.2.0, docx4j 11.5.14, OpenPDF 2.4.0.
