# `vt-testkit` — Bộ công cụ kiểm thử tích hợp dùng chung (`NEN-020`)

Thư viện `vn.viettel.okrkpi:vt-testkit` cung cấp các lớp hỗ trợ Testcontainers dùng chung và bộ sinh JWT thử nghiệm cho toàn bộ mô-đun backend (`mission-core` và các microservice Java).

## 1. Thành phần chính

| Lớp / Annotation | Chức năng |
|---|---|
| `MissionTestImages` | Hằng số phiên bản ảnh container khớp 1-1 với `infra/dev/docker-compose.yml` và cấu hình cổng động (`MissionDevEnv`). |
| `PostgresContainerSupport` | Khởi chạy PostgreSQL 17 + `pg_partman`, tự động tạo 21 schema cùng bộ 4 tài khoản `mig_<schema>`, `svc_<schema>`, `ro_<schema>`, `cdc_<schema>` (`NOBYPASSRLS`, khoá timeout `5s/10s/2s`) và hỗ trợ PgBouncer chế độ `transaction`. |
| `KafkaContainerSupport` | Khởi chạy Kafka 3.9 KRaft, Apicurio Registry 3.x, Debezium Connect 3.x và cung cấp danh mục 26 topic nghiệp vụ + topic kỹ thuật + mẫu `.retry.<consumer>` / `.dlq.<consumer>`. |
| `ValkeyContainerSupport` | Khởi chạy Valkey 8 (`valkey/valkey:8-alpine`) kèm đăng ký thuộc tính Spring (`spring.data.redis.*`). |
| `KeycloakContainerSupport` | Khởi chạy Keycloak 26 nạp sẵn `infra/dev/keycloak/realm-mission.json`. |
| `SecurityContainerSupport` | Khởi chạy OPA 1.x (`/v1/data/okrkpi/authz/decision`) và OpenBao 2.x chế độ dev kèm khoá Transit KEK (`kek-default`, `kek-tenant-demo`, `blind-index-default`). |
| `TestJwtFactory` | Sinh JWT ký bằng RSA-2048 (`RS256`) kèm JWKS nội bộ đầy đủ claim `sub`, `tenant_id`, `org_path`, `roles`, `scope`, `acr`, `auth_time`, `jti` cho mọi vai trò AC mà không cần bật Keycloak. |
| `@IntegrationTest` | Meta-annotation gộp `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@ActiveProfiles("test")`, `@Testcontainers(disabledWithoutDocker = true)`. |

## 2. Bật tái sử dụng container trên máy trạm (`withReuse(true)`)

Mọi container trong `vt-testkit` đều bật sẵn `.withReuse(true)` để giảm thời gian chạy các lớp `*IT` liên tiếp. Để kích hoạt tính năng này trên máy cá nhân, thêm dòng sau vào `~/.testcontainers.properties`:

```properties
testcontainers.reuse.enable=true
```

## 3. Ví dụ sử dụng trong kiểm thử tích hợp (`*IT.java`)

```java
@IntegrationTest
class ScoringServiceIT {

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    PostgresContainerSupport.registerProperties(registry, "scoring");
    ValkeyContainerSupport.registerProperties(registry);
  }

  @Test
  void shouldAuthenticateWithTestJwt() {
    String token = TestJwtFactory.defaultInstance()
        .issueToken(TestJwtFactory.claimsForRole("CAN_BO"));
    assertThat(token).isNotBlank();
  }
}
```
