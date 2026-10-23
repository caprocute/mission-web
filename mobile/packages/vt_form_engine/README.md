# vt_form_engine (MOB-06)

Package Dart độc lập dựng biểu mẫu động từ JSON Schema và quy tắc CEL trên Flutter, kèm phiếu tự đánh giá (SCR-0901, QT-09). Khung ứng dụng MOB-01 và kho nháp mã hoá MOB-02 ghép vào sau qua các giao diện trong `lib/appraisal.dart`.

## Thành phần

| Thư viện | Nội dung |
|---|---|
| `package:vt_form_engine/vt_form_engine.dart` | Bộ thông dịch CEL `MissionCelEngine`, bản chuyển `CompiledForm` của mission-core, `FormDefinition` đọc phiên bản biểu mẫu (`schemaJson`, `uiJson`, `celRules`), `VtFormController` và widget `VtDynamicForm` |
| `package:vt_form_engine/appraisal.dart` | `AppraisalApi` (lớp gọi tạm), mô hình hồ sơ, `SelfAssessmentController`, `MyAppraisalsScreen`, `SelfAssessmentScreen`, giao diện `EncryptedDraftStore` và `ScreenSecurity` |

## Quy ước dùng chung với máy chủ

Máy chủ là nguồn sự thật (AGENTS.md quy tắc 6); kiểm tra trên máy chỉ để phản hồi nhanh. Package mô phỏng `CelEngine` của vt-cel: kiểm kiểu tĩnh phân biệt `int` và `double`, mã lỗi `POLICY-422-CEL-TYPE`, `MISSING-VAR`, `SANDBOX-LIMIT`, `TIMEOUT`, `EVAL-ERROR`, giới hạn 2048 ký tự, chiều sâu 24, 512 nút, 1000 vòng lặp, 50 ms, đệm 1024 chương trình, và các hàm `sum`, `min_muc`, `ngay_lam_viec`, `workdays`, `math.least`, `math.greatest`. Quy tắc biểu mẫu dùng biến `form` (bảng phẳng theo đường dẫn) và `self` như `CompiledForm.java`.

Kiểm thử đối chiếu chạy trên đúng bộ ca vàng của vt-cel và mission-core trong `data/golden/` của repo (tìm bằng cách đi ngược thư mục cha), nên khi bộ ca đổi, kiểm thử di động đổi theo.

## Vì sao tự viết bộ thông dịch CEL

Các package CEL trên pub.dev không kiểm kiểu tĩnh như cel-java (không phân biệt `int` với `double` nên không ra đúng mã lỗi), không tính `sum` và làm tròn bằng số thập phân chính xác, và một số kéo theo runtime ANTLR. Mission chỉ dùng một tập con CEL nhỏ, nên bộ thông dịch viết tay không phụ thuộc ngoài, nhỏ và kiểm soát được hành vi (YC-PT-18).

## Chạy kiểm thử

```bash
cd mobile/packages/vt_form_engine
flutter pub get
flutter analyze
flutter test
```

## Điểm lệch đã biết

- `NULLABLE_STRING` nhận `null` theo cách của web; vt-cel (Java) báo `MISSING-VAR`.
- Lỗi "no matching overload" lúc chạy là `EVAL-ERROR` như Java; web dùng `TYPE`.
- `celRules` của `mau-1b` trong mock web (`frontend/projects/api-client/src/mock/ui-forms/ui-forms.json`) dùng tên trường trần và `ctx.mucThamKhao`, không theo quy ước `form.` của `CompiledForm`; máy chủ sẽ không biên dịch được. Phiếu di động viết BR-09-12 và MSG-09-03 bằng mã.
- Số đo hiệu năng hiện chỉ có ở chế độ debug trên máy kiểm thử (đọc định nghĩa 50 trường khoảng 4–6 ms); ngưỡng 50 ms cho bản release trên trình giả lập chưa đo.

## Chưa làm

Khung ứng dụng MOB-01 (client API sinh từ hợp đồng thay `AppraisalApi`, chuỗi ARB), kho nháp mã hoá và chặn chụp màn hình thật của MOB-02, giao diện gắn minh chứng, và chạy trên trình giả lập Android/iOS.
