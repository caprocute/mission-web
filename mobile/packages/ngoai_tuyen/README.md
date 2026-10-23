# ngoai_tuyen: lưu ngoại tuyến và hàng đợi đồng bộ (MOB-02)

> Nguồn: TL-01 "SVC-18 mobile-app"; TL-02a "Thiết bị di động", SCR-0302 (MSG-03-06, MSG-03-07); TL-03 bảng `idempotency_key` (48 giờ) và phân loại C0..C3; `contracts/openapi/common/common.yaml` (`Idempotency-Key`, `If-Match`, `Retry-After`); `docs/quy-dinh/DI-DONG.md`. Task: MOB-02.

Package Dart thuần, không phụ thuộc Flutter, để kiểm thử được bằng `dart test` trên máy dựng. Ứng dụng `mobile/` (MOB-01) thêm dependency path tới package này và cắm ba phần phụ thuộc nền tảng.

## Thành phần

`OfflineDatabase` là CSDL SQLite mã hoá bằng SQLCipher. Mỗi cặp người dùng và tenant có một tệp riêng. Tên tệp là băm của cặp đó nên định danh không lộ trên đĩa. Khoá là 256 bit ngẫu nhiên (`Random.secure`), sinh lần đầu và cất trong Android Keystore hoặc iOS Keychain qua giao diện `SecureKeyStore`. Khoá được nạp dạng khoá thô `x'..'`, không qua KDF.

Khi mở CSDL, package làm ba việc bảo vệ:

- Nó kiểm `PRAGMA cipher_version` và từ chối chạy khi thư viện nạp được không phải SQLCipher, để không bao giờ lưu dữ liệu dạng rõ vì cấu hình sai.
- Nó đặt `temp_store = MEMORY` để bảng tạm không ra đĩa.
- Khi khoá đã mất, nó xoá tệp cũ rồi tạo CSDL rỗng mới.

Tệp có ba bảng:

- `drafts`: bản nháp.
- `cached_views`: dữ liệu để xem khi mất mạng, như danh sách việc và KR cần check-in.
- `sync_queue`: các lệnh ghi đang chờ gửi.

Dữ liệu C3 bị từ chối ở mọi điểm ghi. `destroy()` xoá tệp, tệp nhật ký và khoá; gọi hàm này khi đăng xuất hoặc đổi tenant.

`SyncEngine` là hàng đợi đồng bộ. `enqueue` được gọi đúng lúc người dùng bấm gửi. Khoá chống ghi trùng UUIDv7 sinh tại thời điểm đó và đi theo mục qua mọi lần gửi lại. Thứ tự gửi là FIFO trong cùng `resourceKey`; các tài nguyên khác nhau không chặn nhau. Mỗi loại phản hồi được xử lý như sau:

| Phản hồi | Xử lý |
|---|---|
| 2xx, kể cả phản hồi phát lại của khoá đã dùng | Xoá mục |
| 409, 422, 4xx khác | Giữ mục ở trạng thái chờ người dùng xem lại, không tự gửi lại. Người dùng sửa rồi gọi `resubmit` để gửi như một ý định ghi mới, với khoá mới |
| 401 | Dừng lượt và phát `SignInRequired`. Lớp gửi của ứng dụng đã thử làm mới token một lần |
| 408, 429, 5xx, mất mạng | Thử lại lùi dần từ 2 giây, gấp đôi mỗi lần, trần 15 phút, dao động tới 20%. Tôn trọng `Retry-After`. Sau 50 lần thì chuyển sang chờ xem lại |

Máy chủ chỉ giữ khoá 48 giờ. Vì vậy, khi một mục có lần gửi chưa rõ kết quả cũ hơn 47 giờ, hàng đợi không tự gửi lại mục đó mà chuyển sang chờ xem lại, vì gửi lại lúc này có thể tạo bản ghi trùng.

Mục chờ được giữ 7 ngày. Hàng đợi báo `ExpiringSoon` trước một ngày, và báo `Discarded` khi huỷ. Các giá trị này nằm trong `SyncPolicy` và là đề xuất của task (suy luận).

Thông báo đi qua `notices`. `SavedLocally` là MSG-03-06, `SentPending` là MSG-03-07. Câu chữ chuẩn của TL-02 có sẵn cho check-in; với loại thao tác khác, màn hình tự đặt câu chữ. Mã MSG chỉ dùng trong mã nguồn và nhật ký. Hàng đợi không ghi thân lệnh hay `detail` của lỗi RFC 9457 vào nhật ký, vì đó có thể là dữ liệu C2; nó chỉ giữ `code`.

## Cắm vào ứng dụng Flutter

1. **SQLCipher trên Android, iOS.** `pubspec.yaml` của `mobile/` khai báo đoạn dưới đây. Lựa chọn này dùng bản SQLCipher community đóng sẵn của `package:sqlite3` 3.x, nạp qua build hook, không cần platform channel; bản Android có liên kết OpenSSL. Hai phương án đã cân nhắc và không chọn: `sqflite_sqlcipher` đi qua platform channel và chậm hơn; `sqlcipher_flutter_libs` đã được thay bằng hook trong `sqlite3` 3.x.

   ```yaml
   hooks:
     user_defines:
       sqlite3:
         source: sqlcipher
   ```

2. **`SecureKeyStore`.** Viết bản hiện thực bằng `flutter_secure_storage`, dùng cùng cấu hình với `lib/core/auth/token_store.dart`. Trên iOS dùng `KeychainAccessibility.first_unlock_this_device` để khoá không đi theo bản sao lưu iCloud (suy luận).
3. **`RequestSender`.** Viết bản hiện thực trên Dio dùng chung của `lib/core/network/`, với hai yêu cầu. Interceptor không được ghi đè `Idempotency-Key` và `If-Match` có sẵn trong request. `DioException` loại kết nối hoặc hết thời gian phải được đổi thành `TransportException`.
4. **`NetworkMonitor`.** Viết bản hiện thực bằng `connectivity_plus`, rồi gọi `engine.start()`.
5. **Đồng bộ nền.** Đăng ký `workmanager` (Android WorkManager, iOS BGTaskScheduler) để gọi `engine.runOnce()` với ràng buộc có mạng (suy luận, TL không chỉ định thư viện).

## Kiểm thử

```bash
sudo apt-get install libsqlcipher-dev   # máy dựng Linux; pubspec của package nạp libsqlcipher.so của hệ điều hành
dart pub get && dart analyze && dart test
```

Có ba tệp kiểm thử:

- `offline_database_test.dart`: tệp thô không chứa chuỗi đánh dấu đã ghi; kết nối không có khoá và kết nối sai khoá đều không đọc được; mỗi cặp người dùng và tenant tách tệp; `destroy` xoá tệp và khoá.
- `sync_engine_test.dart`: gửi lại ba lần với cùng một khoá; 409 không tự gửi lại; 422 rồi gửi lại với khoá mới; FIFO; 401; `Retry-After`; giới hạn số lần thử; cửa sổ 47 giờ; huỷ sau 7 ngày; câu chữ MSG-03-06 và MSG-03-07.
- `integration_test.dart`: chạy với máy chủ HTTP giả lập có kho idempotency. Ngắt mạng, nhập ba lần, rồi bật mạng; phản hồi đầu tiên bị cắt; máy chủ ghi đúng ba bản ghi.
