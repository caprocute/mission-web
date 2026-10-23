import 'dart:async';

import 'package:http/http.dart' as http;

/// Một lệnh ghi gửi lên máy chủ.
class SyncRequest {
  const SyncRequest({required this.method, required this.path, required this.headers, this.body});

  final String method;

  /// Đường dẫn tương đối theo hợp đồng, ví dụ `/api/v1/okr/key-results/{id}/checkins`.
  final String path;

  /// Gồm sẵn `Idempotency-Key`, và `If-Match` khi có; lớp gửi của ứng dụng không được ghi đè hai header này.
  final Map<String, String> headers;
  final String? body;
}

class SyncResponse {
  const SyncResponse({required this.status, this.headers = const {}, this.body});

  final int status;

  /// Tên header viết thường.
  final Map<String, String> headers;
  final String? body;
}

/// Lỗi tầng vận chuyển: mất mạng, hết thời gian, kết nối bị cắt. Kết quả ở máy chủ là chưa rõ.
class TransportException implements Exception {
  const TransportException(this.message);

  final String message;

  @override
  String toString() => 'TransportException: $message';
}

/// Cách gửi một [SyncRequest]. Ứng dụng cắm bản dùng Dio dùng chung (Bearer, làm mới token một lần khi 401,
/// traceparent); bản đó phải đổi lỗi kết nối thành [TransportException].
abstract interface class RequestSender {
  Future<SyncResponse> send(SyncRequest request);
}

/// Bản gửi tối giản bằng `package:http`, dùng cho kiểm thử và công cụ dòng lệnh.
class HttpRequestSender implements RequestSender {
  HttpRequestSender(this.baseUri, {http.Client? client, this.accessToken, this.timeout = const Duration(seconds: 30)})
    : _client = client ?? http.Client();

  final Uri baseUri;
  final http.Client _client;
  final Future<String?> Function()? accessToken;
  final Duration timeout;

  @override
  Future<SyncResponse> send(SyncRequest request) async {
    final req = http.Request(request.method, baseUri.resolve(request.path))..headers.addAll(request.headers);
    final token = await accessToken?.call();
    if (token != null) req.headers['Authorization'] = 'Bearer $token';
    if (request.body != null) {
      req.headers.putIfAbsent('Content-Type', () => 'application/json');
      req.body = request.body!;
    }
    try {
      final streamed = await _client.send(req).timeout(timeout);
      final res = await http.Response.fromStream(streamed).timeout(timeout);
      return SyncResponse(status: res.statusCode, headers: res.headers, body: res.body);
    } on http.ClientException catch (e) {
      throw TransportException(e.message);
    } on TimeoutException {
      throw const TransportException('hết thời gian chờ');
    }
  }
}
