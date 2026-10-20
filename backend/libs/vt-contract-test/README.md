# `vt-contract-test` — Kiểm thử tuân thủ hợp đồng OpenAPI 3.1 (ADR-32 · NEN-006)

Thư viện cung cấp lớp kiểm thử nền `ContractConformanceTest` và động cơ `OpenApiConformanceEngine` để bảo đảm mọi controller trong `mission-core` (`SVC-05`) và microservice độc lập (`SVC-07`, `SVC-10`, `SVC-11`, `SVC-13`…) không lệch khỏi hợp đồng chuẩn `contracts/openapi/modules/*.yaml`.

## 1. Bốn lớp kiểm chứng tự động

1. **Đối chiếu tài liệu thực tế bằng `openapi-diff-core`**:
   - Khởi chạy lát cắt WebMvc của mô-đun và lấy tài liệu OpenAPI thực tế từ `/v3/api-docs` (springdoc).
   - Lọc hợp đồng chuẩn theo `x-mission.owner` (ví dụ `SVC-05`, `SVC-07`, `SVC-13`) và chạy `OpenApiCompare.fromSpecifications(expected, actual)`.
   - Báo đỏ kèm thông điệp tiếng Việt chỉ rõ vị trí lệch khi thiếu endpoint (`MISSING_ENDPOINT`), thừa endpoint ngoài hợp đồng (`EXTRA_ENDPOINT`), hoặc thay đổi không tương thích (`INCOMPATIBLE_CHANGE`: thiếu mã phản hồi `400`/`404`/`409`/`202`, thiếu/đổi tên trường phản hồi, đổi kiểu dữ liệu trường).
2. **Kiểm quy ước mục 13 baseline (`BASELINE_CONVENTION`)**:
   - Đường dẫn gốc bắt đầu bằng `/api/v1`.
   - Thao tác ghi (`POST`, `PUT`, `PATCH`, `DELETE`) bắt buộc có header `Idempotency-Key`.
   - Thao tác cập nhật có `If-Match` bắt buộc khai báo mã phản hồi `409`.
   - Thao tác danh sách có `limit` giới hạn `maximum <= 200`.
   - Phản hồi lỗi (`4xx`, `default`) bắt buộc dùng `application/problem+json` (RFC 9457).
3. **So khớp `examples` trong hợp đồng với schema**:
   - Tự động kiểm chứng mọi `examples` 2xx khai báo trong `contracts/openapi/modules/*.yaml` khớp với schema của chính phản hồi đó.
4. **So khớp phản hồi JSON thực tế từ `MockMvc` (`RESPONSE_SCHEMA`)**:
   - Phương thức `assertResponseConforms(MvcResult, pathTemplate, HttpMethod)` kiểm tra JSON thực tế trả về từ controller không thiếu trường `required`, không chứa trường lạ/đổi tên, đúng kiểu dữ liệu, tập giá trị `enum` và ngưỡng `minimum`/`maximum`.

## 2. Cách sử dụng trong mô-đun nghiệp vụ

```java
@SpringBootTest
@AutoConfigureMockMvc
class MyModuleContractTest extends ContractConformanceTest {

  @Autowired private MockMvc mockMvc;

  @Override
  protected ContractModule contractModule() {
    return ContractModule.JOBS;
  }

  @Override
  protected String ownerServiceCode() {
    return "SVC-05";
  }

  @Test
  void shouldConformToOpenApiContract() throws Exception {
    assertOpenApiConformance(mockMvc);
  }
}
```
