// Ca kiểm thử cho .semgrep/vt-rules.yml (INF-05). Chạy: semgrep --test .semgrep/
// Chú thích "rule-id" (ca dương) đánh dấu dòng kế tiếp PHẢI bị bắt; chú thích "ok" (ca âm) đánh dấu
// dòng kế tiếp KHÔNG được bắt; cú pháp chú thích theo tài liệu `semgrep --test`. Tệp này chỉ dùng cho kiểm thử quy tắc, không biên dịch, không nằm trong backend/.
package vn.vt.mission.semgreptest;

import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509TrustManager;
import javax.persistence.EntityManager;
import javax.servlet.http.HttpServletRequest;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

class SqlCases {
  private static final String BASE = "SELECT id, title FROM task.task";

  void jdbc(Statement stmt, Connection conn, String unitId, String sort) throws Exception {
    // ruleid: vt-sql-string-concat
    stmt.executeQuery("SELECT * FROM task.task WHERE unit_id = '" + unitId + "'");
    // ruleid: vt-sql-string-concat
    stmt.execute("DELETE FROM task.task WHERE id = " + unitId);
    // ruleid: vt-sql-string-concat
    stmt.executeUpdate("UPDATE task.task SET title = 'x' WHERE id = " + unitId);
    // ruleid: vt-sql-string-concat
    PreparedStatement bad = conn.prepareStatement("SELECT * FROM task.task ORDER BY " + sort);

    // ok: vt-sql-string-concat
    PreparedStatement ps = conn.prepareStatement("SELECT * FROM task.task WHERE unit_id = ?");
    ps.setString(1, unitId);
    // ok: vt-sql-string-concat
    stmt.executeQuery("SELECT * FROM task.task " + "WHERE deleted = false");
  }

  void jpa(EntityManager em, String title) {
    // ruleid: vt-sql-string-concat
    em.createNativeQuery("SELECT * FROM task.task WHERE title = '" + title + "'", Object.class);
    // ruleid: vt-sql-string-concat
    em.createQuery("FROM Task t WHERE t.title = '" + title + "'");
    // ok: vt-sql-string-concat
    em.createNativeQuery("SELECT * FROM task.task WHERE title = :title").setParameter("title", title);
  }

  List<?> spring(JdbcTemplate jdbc, String unitId, String sort, StringBuilder sb) {
    // ruleid: vt-sql-string-concat
    sb.append("ORDER BY " + sort);
    // ok: vt-sql-string-concat
    sb.append("ORDER BY created_at DESC");
    // ruleid: vt-sql-string-concat
    jdbc.update("DELETE FROM task.task WHERE unit_id = " + unitId);
    // ok: vt-sql-string-concat
    jdbc.update("DELETE FROM task.task WHERE unit_id = ?", unitId);
    // ruleid: vt-sql-string-concat
    return jdbc.query("SELECT * FROM task.task WHERE unit_id = " + unitId, (rs, i) -> rs.getString(1));
  }

  void viaVariable(Statement stmt, String unitId) throws Exception {
    String sql = BASE + " WHERE unit_id = " + unitId;
    // ok: vt-sql-string-concat
    String label = "Đơn vị " + unitId;
    // ruleid: vt-sql-string-concat
    String q = "SELECT * FROM task.task WHERE unit_id = " + unitId;
    stmt.executeQuery(q);
    // ruleid: vt-sql-string-concat
    String f = String.format("SELECT * FROM task.task WHERE id = %s", unitId);
    stmt.execute(f);
  }
}

class LogCases {
  private static final Logger log = LoggerFactory.getLogger(LogCases.class);

  @PostMapping("/api/v1/tasks")
  ResponseEntity<String> create(@RequestBody TaskInput input, HttpServletRequest request, Jwt jwt) {
    // ruleid: vt-log-request-body
    log.info("Tạo nhiệm vụ: {}", input);
    // ok: vt-log-request-body
    log.info("Tạo nhiệm vụ cho đơn vị {}", input.unitId());
    // ruleid: vt-log-secret-token
    log.debug("Authorization = {}", request.getHeader("Authorization"));
    // ruleid: vt-log-secret-token
    log.debug("JWT {}", jwt.getTokenValue());
    // ok: vt-log-secret-token
    log.info("Người gọi {} với jti {}", jwt.getSubject(), jwt.getId());
    ResponseEntity<String> resp = ResponseEntity.ok("x");
    // ruleid: vt-log-request-body
    log.warn("Phản hồi {}", resp.getBody());
    return resp;
  }

  void filter(HttpServletRequest request, String body, String accessToken, String refreshToken, Credentials cred)
      throws Exception {
    // ruleid: vt-log-request-body
    log.debug("Body: {}", request.getReader());
    // ruleid: vt-log-request-body
    log.trace("Raw body {}", body);
    // ruleid: vt-log-secret-token
    log.info("Token mới: {}", accessToken);
    // ruleid: vt-log-secret-token
    log.error("Làm mới thất bại, refresh = " + refreshToken);
    // ruleid: vt-log-secret-token
    log.warn("Đăng nhập {} {}", cred.getUsername(), cred.getPassword());
    // ok: vt-log-secret-token
    log.info("Đăng nhập của {}", cred.getUsername());
    // ok: vt-log-secret-token
    log.info("Độ dài token {}", accessToken.length());
    // ok: vt-log-request-body
    log.debug("Kích thước body {}", body.length());
  }

  record TaskInput(String unitId, String title) {}

  interface Credentials {
    String getUsername();

    String getPassword();
  }
}

class CryptoCases {
  void ciphers(byte[] key) throws Exception {
    // ruleid: vt-crypto-weak-cipher
    Cipher.getInstance("DES/CBC/PKCS5Padding");
    // ruleid: vt-crypto-weak-cipher
    Cipher.getInstance("DESede");
    // ruleid: vt-crypto-weak-cipher
    Cipher.getInstance("RC4");
    // ruleid: vt-crypto-weak-cipher
    Cipher.getInstance("AES/ECB/PKCS5Padding");
    // ruleid: vt-crypto-weak-cipher
    Cipher.getInstance("AES");
    // ruleid: vt-crypto-weak-cipher
    SecretKeyFactory.getInstance("PBEWithMD5AndDES");
    // ruleid: vt-crypto-weak-cipher
    KeyGenerator.getInstance("DES");
    // ruleid: vt-crypto-weak-cipher
    new SecretKeySpec(key, "DESede");
    // ok: vt-crypto-weak-cipher
    Cipher.getInstance("AES/GCM/NoPadding");
    // ok: vt-crypto-weak-cipher
    Cipher.getInstance("ChaCha20-Poly1305");
    // ok: vt-crypto-weak-cipher
    new SecretKeySpec(key, "AES");
    // ok: vt-crypto-weak-cipher
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
  }

  void hashes(byte[] data) throws Exception {
    // ruleid: vt-crypto-weak-hash
    MessageDigest.getInstance("MD5");
    // ruleid: vt-crypto-weak-hash
    MessageDigest.getInstance("SHA-1");
    // ruleid: vt-crypto-weak-hash
    Signature.getInstance("SHA1withRSA");
    // ruleid: vt-crypto-weak-hash
    Signature.getInstance("MD5withRSA");
    // ruleid: vt-crypto-weak-hash
    Mac.getInstance("HmacMD5");
    // ruleid: vt-crypto-weak-hash
    DigestUtils.md5Hex(data);
    // ruleid: vt-crypto-weak-hash
    DigestUtils.sha1Hex(data);
    // ok: vt-crypto-weak-hash
    MessageDigest.getInstance("SHA-256");
    // ok: vt-crypto-weak-hash
    Signature.getInstance("SHA256withECDSA");
    // ok: vt-crypto-weak-hash
    Signature.getInstance("Ed25519");
    // ok: vt-crypto-weak-hash
    DigestUtils.sha256Hex(data);
  }
}

class TrustAllManager implements X509TrustManager {
  // ruleid: vt-tls-trust-all
  public void checkClientTrusted(X509Certificate[] chain, String authType) {}

  // ruleid: vt-tls-trust-all
  public void checkServerTrusted(X509Certificate[] chain, String authType) {}

  public X509Certificate[] getAcceptedIssuers() {
    return new X509Certificate[0];
  }
}

class StrictTrustManager implements X509TrustManager {
  private final X509TrustManager delegate;

  StrictTrustManager(X509TrustManager delegate) {
    this.delegate = delegate;
  }

  // ok: vt-tls-trust-all
  public void checkClientTrusted(X509Certificate[] chain, String authType)
      throws java.security.cert.CertificateException {
    delegate.checkClientTrusted(chain, authType);
  }

  // ok: vt-tls-trust-all
  public void checkServerTrusted(X509Certificate[] chain, String authType)
      throws java.security.cert.CertificateException {
    delegate.checkServerTrusted(chain, authType);
  }

  public X509Certificate[] getAcceptedIssuers() {
    return delegate.getAcceptedIssuers();
  }
}

class HostnameCases {
  void configure(HttpsURLConnection conn, List<String> allowed) {
    // ruleid: vt-tls-trust-all
    conn.setHostnameVerifier((host, session) -> true);
    // ok: vt-tls-trust-all
    conn.setHostnameVerifier((host, session) -> allowed.contains(host));
    // ruleid: vt-tls-trust-all
    var factory = io.netty.handler.ssl.util.InsecureTrustManagerFactory.INSTANCE;
    // ruleid: vt-tls-trust-all
    var noop = org.apache.http.conn.ssl.NoopHostnameVerifier.INSTANCE;
  }

  static class AllowAll implements HostnameVerifier {
    // ruleid: vt-tls-trust-all
    public boolean verify(String host, SSLSession session) { return true; }
  }
}
