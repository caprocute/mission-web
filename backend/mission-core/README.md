# SVC-05 · `mission-core`

Khối nguyên khối theo mô-đun (*modular monolith*, ADR-02) chứa 8 mô-đun nghiệp vụ của hệ thống OKR/KPI (`MOD-01..MOD-08`: `okr`, `task`, `product`, `appraisal`, `policy`, `dossier`, `oversight`, `config`) và mô-đun bản sao tổ chức (`replica` / `org_replica`), chạy trên Java 21 (Virtual Threads) và Spring Boot 3.5 / Spring Modulith 1.4.

## Kiến trúc kết nối cơ sở dữ liệu

- **Tám `DataSource` nghiệp vụ độc lập**: mỗi mô-đun sở hữu một HikariCP pool riêng (`okrDataSource`, `taskDataSource`, `productDataSource`, `appraisalDataSource`, `policyDataSource`, `dossierDataSource`, `oversightDataSource`, `configDataSource`), một `LocalContainerEntityManagerFactoryBean` riêng và một `ModuleTenantJpaTransactionManager` riêng (kế thừa `TenantJpaTransactionManager` để gán `SET LOCAL app.current_tenant` theo RLS ADR-07/ADR-22).
- **Tương thích PgBouncer transaction pooling (ADR-24)**: mọi URL JDBC đều cưỡng bức `prepareThreshold=0` và `ApplicationName=mission-core-<module>`. Tổng `maximumPoolSize` mặc định trên 1 pod là `24` (`task=8, okr=4, appraisal=4, product=3, config=2, policy=1, dossier=1, oversight=1`), nằm dưới trần `35` kết nối/pod của TL-04.
- **Định tuyến đọc/ghi (`ReadWriteRoutingDataSource`)**: giao dịch `@Transactional(readOnly = true)` được định tuyến sang bản sao chỉ đọc (`RO`) trừ khi người dùng vừa ghi trong vòng 2 giây (`RecentWriteTracker` — bảo đảm *read-your-own-writes*) hoặc độ trễ nhân bản vượt ngưỡng `1000 ms`.
- **Kiểm tra phiên bản lược đồ khi khởi động (`MissionCoreSchemaVersionGuard`)**: đối chiếu `flyway_schema_history` của cả 9 schema (`okr`, `task`, `product`, `appraisal`, `policy`, `dossier`, `oversight`, `config`, `org_replica`) trước khi nhận lưu lượng; ứng dụng không tự chạy Flyway (`spring.flyway.enabled=false`).

## Cổng và đầu dò

- Cổng nghiệp vụ: `8080`
- Cổng quản trị / Actuator: `8081` (`/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus`)
- Tắt êm (*graceful shutdown*): `25s` (tương thích `terminationGracePeriodSeconds: 35` và `preStop: sleep 5` trên Kubernetes).
