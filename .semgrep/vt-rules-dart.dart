void mau(HttpClient c, SharedPreferences p, String accessToken, String ten) {
  // ruleid: vt-dart-tls-bypass
  c.badCertificateCallback = (cert, host, port) => true;
  // ruleid: vt-dart-plain-storage-token
  p.setString('refresh_token', accessToken);
  // ok: vt-dart-plain-storage-token
  p.setString('ngon_ngu', 'vi');
  // ruleid: vt-dart-print-secret
  print(accessToken);
  // ok: vt-dart-print-secret
  print(ten);
}
