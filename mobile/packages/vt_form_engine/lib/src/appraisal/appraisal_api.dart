/// Lời gọi API của phiếu tự đánh giá theo hợp đồng `contracts/openapi/modules/appraisals.yaml` và
/// `config.yaml` (`getCurrentUiForm`), quy ước baseline mục 13: ghi kèm `Idempotency-Key`, cập nhật
/// kèm `If-Match` (409 khi xung đột), lỗi RFC 9457, tác vụ dài 202 kèm `Location`.
///
/// Đây là lớp gọi tạm viết tay cho đúng năm thao tác MOB-06 cần; khi khung ứng dụng MOB-01 có lớp
/// client sinh từ hợp đồng thì [AppraisalApi] chuyển sang dùng lớp đó, giữ nguyên giao diện.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:http/http.dart' as http;

import 'appraisal_models.dart';

/// Lỗi theo RFC 9457 (`application/problem+json`).
class ApiProblem implements Exception {
  ApiProblem(this.status, {this.code, this.title, this.detail, this.fieldErrors = const {}});

  factory ApiProblem.fromResponse(http.Response r) {
    Map<String, Object?> j = const {};
    try {
      final d = jsonDecode(utf8.decode(r.bodyBytes));
      if (d is Map) j = d.cast<String, Object?>();
    } on FormatException {
      // thân không phải JSON: giữ mã HTTP
    }
    return ApiProblem(
      r.statusCode,
      code: j['code'] as String?,
      title: j['title'] as String?,
      detail: j['detail'] as String?,
      fieldErrors: {
        for (final e in (j['errors'] as List?) ?? const [])
          if (e is Map) '${e['field']}': '${e['message']}',
      },
    );
  }

  final int status;
  final String? code;
  final String? title;
  final String? detail;
  final Map<String, String> fieldErrors;

  /// Xung đột ghi đồng thời (`<MODULE>-409-VERSION-CONFLICT`); 409 khác là vi phạm trạng thái.
  bool get isVersionConflict => status == 409 && (code == null || code!.endsWith('-409-VERSION-CONFLICT'));

  @override
  String toString() => 'ApiProblem($status, $code)';
}

/// Không gọi được máy chủ (mất mạng, hết thời gian chờ): phiếu giữ ở bản nháp trên máy.
class ApiOffline implements Exception {
  ApiOffline(this.cause);
  final Object cause;
  @override
  String toString() => 'ApiOffline';
}

/// Kết quả có ETag (`row_version` trong dấu nháy kép).
class Tagged<T> {
  const Tagged(this.value, this.etag);
  final T value;
  final String? etag;
}

/// Yêu cầu ký đã được nhận (202): theo dõi job loại ký số qua `Location` (MOB-07 tiếp quản).
class SubmitAccepted {
  const SubmitAccepted(this.location, this.job);
  final String? location;
  final Map<String, Object?> job;
}

/// Sinh UUIDv7 (RFC 9562) cho `Idempotency-Key` như hợp đồng khuyến nghị.
String uuidV7([Random? random]) {
  final rnd = random ?? Random.secure();
  final ms = DateTime.now().millisecondsSinceEpoch;
  final b = List<int>.generate(16, (_) => rnd.nextInt(256));
  for (var i = 0; i < 6; i++) {
    b[i] = (ms >> (8 * (5 - i))) & 0xff;
  }
  b[6] = 0x70 | (b[6] & 0x0f);
  b[8] = 0x80 | (b[8] & 0x3f);
  final h = b.map((x) => x.toRadixString(16).padLeft(2, '0')).join();
  return '${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}';
}

typedef AccessTokenProvider = FutureOr<String?> Function();

class AppraisalApi {
  AppraisalApi({
    required this.baseUri,
    http.Client? client,
    this.accessToken,
    this.timeout = const Duration(seconds: 20),
  }) : _http = client ?? http.Client();

  /// Gốc API, ví dụ `https://<máy chủ>/api/v1/`.
  final Uri baseUri;
  final AccessTokenProvider? accessToken;
  final Duration timeout;
  final http.Client _http;

  Uri _u(String path, [Map<String, String>? q]) =>
      baseUri.resolve(path).replace(queryParameters: q == null || q.isEmpty ? null : q);

  Future<Map<String, String>> _headers([Map<String, String> extra = const {}]) async {
    final token = await accessToken?.call();
    return {
      'Accept': 'application/json, application/problem+json',
      if (token != null) 'Authorization': 'Bearer $token',
      ...extra,
    };
  }

  Future<http.Response> _send(Future<http.Response> Function() call) async {
    try {
      return await call().timeout(timeout);
    } on TimeoutException catch (e) {
      throw ApiOffline(e);
    } on http.ClientException catch (e) {
      throw ApiOffline(e);
    }
  }

  static Map<String, Object?> _json(http.Response r) =>
      (jsonDecode(utf8.decode(r.bodyBytes)) as Map).cast<String, Object?>();

  /// `GET /appraisals/profiles/mine` (`listMyAppraisalProfiles`).
  Future<List<AppraisalProfileRow>> listMine({String? periodId, String? flow}) async {
    final r = await _send(
      () async =>
          _http.get(_u('appraisals/profiles/mine', {'period': ?periodId, 'flow': ?flow}), headers: await _headers()),
    );
    if (r.statusCode != 200) throw ApiProblem.fromResponse(r);
    return [for (final x in (_json(r)['items'] as List?) ?? const []) AppraisalProfileRow.fromJson(x as Map)];
  }

  /// `GET /appraisals/profiles/{id}` (`getAppraisalProfile`), kèm ETag cho mọi thao tác ghi.
  Future<Tagged<AppraisalProfile>> getProfile(String id) async {
    final r = await _send(() async => _http.get(_u('appraisals/profiles/$id'), headers: await _headers()));
    if (r.statusCode != 200) throw ApiProblem.fromResponse(r);
    return Tagged(AppraisalProfile.fromJson(_json(r)), r.headers['etag']);
  }

  /// `GET /ui/forms/{key}/current` (`getCurrentUiForm`); trả `null` khi 304 (bản đang giữ vẫn đúng).
  Future<Tagged<Map<String, Object?>>?> getCurrentForm(String key, {String? ifNoneMatch}) async {
    final r = await _send(
      () async => _http.get(
        _u('ui/forms/${Uri.encodeComponent(key)}/current'),
        headers: await _headers({'If-None-Match': ?ifNoneMatch}),
      ),
    );
    if (r.statusCode == 304) return null;
    if (r.statusCode != 200) throw ApiProblem.fromResponse(r);
    return Tagged(_json(r), r.headers['etag']);
  }

  /// `PUT /appraisals/profiles/{id}/self-assessment` (`saveAppraisalSelfAssessment`).
  Future<Tagged<AppraisalProfile>> saveSelfAssessment(
    String id,
    Map<String, Object?> input, {
    required String ifMatch,
    required String idempotencyKey,
  }) async {
    final r = await _send(
      () async => _http.put(
        _u('appraisals/profiles/$id/self-assessment'),
        headers: await _headers({
          'Content-Type': 'application/json',
          'If-Match': ifMatch,
          'Idempotency-Key': idempotencyKey,
        }),
        body: jsonEncode(input),
      ),
    );
    if (r.statusCode != 200) throw ApiProblem.fromResponse(r);
    return Tagged(AppraisalProfile.fromJson(_json(r)), r.headers['etag']);
  }

  /// `POST /appraisals/profiles/{id}/submit` (`submitAppraisalSelfAssessment`): 202 khi yêu cầu ký
  /// đã tạo; hồ sơ giữ `TU_DANH_GIA` tới khi nhận EVT-18.
  Future<SubmitAccepted> submit(
    String id, {
    required String ifMatch,
    required String idempotencyKey,
    String? signProvider,
    String? formCode,
  }) async {
    final r = await _send(
      () async => _http.post(
        _u('appraisals/profiles/$id/submit'),
        headers: await _headers({
          'Content-Type': 'application/json',
          'If-Match': ifMatch,
          'Idempotency-Key': idempotencyKey,
        }),
        body: jsonEncode({'signProvider': ?signProvider, 'formCode': ?formCode}),
      ),
    );
    if (r.statusCode != 202) throw ApiProblem.fromResponse(r);
    final body = r.bodyBytes.isEmpty ? <String, Object?>{} : _json(r);
    return SubmitAccepted(r.headers['location'], body);
  }
}
