package vn.viettel.okrkpi.esign.infrastructure.provider.hsm;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.Security;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Pkcs11Signer} trên SunPKCS11 của JDK (không thêm thư viện): mở token bằng PIN, ký {@code
 * CKM_ECDSA} trên băm SHA-256 (định dạng P1363 {@code r||s} do HSM trả) rồi chuyển sang DER. Số
 * lệnh ký đồng thời bị chặn bởi semaphore cỡ {@code maxSessions} (pool phiên có giới hạn: SunPKCS11
 * giữ phiên tái dùng bên trong, semaphore bảo đảm không mở quá giới hạn của HSM).
 *
 * <p>Chỉ số: {@code esign_hsm_signatures_total} (tốc độ là số chữ ký mỗi giây), {@code
 * esign_hsm_sign_seconds} (độ trễ ký), {@code esign_hsm_errors_total}. Thông báo lỗi và nhật ký
 * không chứa PIN hay giá trị khoá.
 */
public class HsmPkcs11Signer implements Pkcs11Signer, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(HsmPkcs11Signer.class);

  /** Băm cố định cho lệnh ký thử của readiness. */
  static final byte[] PROBE_DIGEST = new byte[32];

  private final HsmProperties properties;
  private final Provider provider;
  private final KeyStore keyStore;
  private final Semaphore sessions;
  private final Clock clock;
  private final Counter signatures;
  private final Counter errors;
  private final Timer latency;
  private volatile boolean closed;

  public HsmPkcs11Signer(HsmProperties properties, MeterRegistry meters, Clock clock) {
    this.properties = properties;
    this.clock = clock;
    this.sessions = new Semaphore(properties.maxSessions(), true);
    this.signatures =
        Counter.builder("esign_hsm_signatures")
            .description("Số chữ ký HSM đã tạo")
            .register(meters);
    this.errors =
        Counter.builder("esign_hsm_errors").description("Số lệnh ký HSM lỗi").register(meters);
    this.latency =
        Timer.builder("esign_hsm_sign").description("Độ trễ một lệnh ký HSM").register(meters);
    try {
      Provider base = Security.getProvider("SunPKCS11");
      if (base == null) {
        throw new IllegalStateException("JDK không có SunPKCS11");
      }
      this.provider = base.configure("--" + properties.sunPkcs11Config());
      this.keyStore = KeyStore.getInstance("PKCS11", provider);
      char[] pin = properties.pin() == null ? null : properties.pin().toCharArray();
      try {
        keyStore.load(null, pin);
      } finally {
        if (pin != null) {
          Arrays.fill(pin, '\0');
        }
      }
    } catch (GeneralSecurityException | IOException | RuntimeException e) {
      // Không đưa thông điệp gốc ra ngoài: có thư viện in cả đường dẫn cấu hình và mã lỗi đăng
      // nhập.
      throw new IllegalStateException(
          "Không mở được token HSM qua PKCS#11 (" + e.getClass().getSimpleName() + ")", e);
    }
    log.info("Đã mở token HSM qua PKCS#11: {}", properties);
  }

  @Override
  public List<X509Certificate> chain(String keyAlias) {
    try {
      Certificate[] chain = keyStore.getCertificateChain(keyAlias);
      if (chain == null || chain.length == 0) {
        throw new IllegalArgumentException("Không có chứng thư cho khoá " + keyAlias);
      }
      List<X509Certificate> out = new ArrayList<>(chain.length);
      for (Certificate c : chain) {
        out.add((X509Certificate) c);
      }
      return out;
    } catch (GeneralSecurityException e) {
      throw new HsmUnavailableException("Không đọc được chứng thư từ HSM", e);
    }
  }

  @Override
  public byte[] sign(String keyAlias, byte[] digest, String algorithm) {
    if (!SHA256_WITH_ECDSA.equals(algorithm)) {
      throw new IllegalArgumentException("Thuật toán ký chưa hỗ trợ: " + algorithm);
    }
    if (digest == null || digest.length != 32) {
      throw new IllegalArgumentException("Băm SHA-256 phải dài 32 byte");
    }
    if (closed) {
      throw new HsmUnavailableException("Phiên HSM đã đóng", null);
    }
    PrivateKey key = privateKey(keyAlias);
    boolean acquired;
    try {
      acquired = sessions.tryAcquire(properties.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HsmUnavailableException("Bị ngắt khi chờ phiên HSM", e);
    }
    if (!acquired) {
      errors.increment();
      throw new HsmUnavailableException("Hết phiên HSM trong thời gian chờ", null);
    }
    long start = System.nanoTime();
    try {
      Signature s = Signature.getInstance("NONEwithECDSAinP1363Format", provider);
      s.initSign(key);
      s.update(digest);
      byte[] der = toDer(s.sign());
      signatures.increment();
      return der;
    } catch (GeneralSecurityException | RuntimeException e) {
      errors.increment();
      throw new HsmUnavailableException(
          "HSM không ký được (" + e.getClass().getSimpleName() + ")", e);
    } finally {
      latency.record(Duration.ofNanos(System.nanoTime() - start));
      sessions.release();
    }
  }

  /** Ký thử một băm cố định bằng khoá chữ ký tổ chức và kiểm lại bằng khoá công khai. */
  public boolean probe() {
    try {
      byte[] signature = sign(properties.orgKeyAlias(), PROBE_DIGEST, SHA256_WITH_ECDSA);
      Signature verify = Signature.getInstance("NONEwithECDSA");
      verify.initVerify(chain(properties.orgKeyAlias()).get(0).getPublicKey());
      verify.update(PROBE_DIGEST);
      return verify.verify(signature);
    } catch (GeneralSecurityException | RuntimeException e) {
      log.warn("Ký thử HSM thất bại: {}", e.getClass().getSimpleName());
      return false;
    }
  }

  /**
   * Chỉ dev và kiểm thử: tạo cặp khoá P-256 trong token (không trích xuất được) cùng chứng thư tự
   * ký khi nhãn {@code alias} chưa có. Production nạp khoá và chứng thư theo quy trình nghi lễ
   * khoá.
   */
  public void ensureKey(String alias, String subjectDn) {
    try {
      if (keyStore.containsAlias(alias)) {
        return;
      }
      KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", provider);
      generator.initialize(new ECGenParameterSpec("secp256r1"));
      KeyPair pair = generator.generateKeyPair();
      Instant now = clock.instant();
      X509v3CertificateBuilder builder =
          new JcaX509v3CertificateBuilder(
              new X500Name(subjectDn),
              new BigInteger(64, new java.security.SecureRandom()).abs().add(BigInteger.ONE),
              Date.from(now.minus(Duration.ofMinutes(5))),
              Date.from(now.plus(Duration.ofDays(3650))),
              new X500Name(subjectDn),
              pair.getPublic());
      ContentSigner signer = new HsmContentSigner(provider, pair.getPrivate());
      X509Certificate cert =
          new JcaX509CertificateConverter().getCertificate(builder.build(signer));
      keyStore.setKeyEntry(alias, pair.getPrivate(), null, new Certificate[] {cert});
      log.info("Đã tạo khoá {} trong token HSM (chỉ dev)", alias);
    } catch (GeneralSecurityException e) {
      throw new HsmUnavailableException("Không tạo được khoá trong HSM", e);
    }
  }

  /** Khoá riêng (đối tượng tham chiếu tới HSM, không chứa giá trị khoá). */
  PrivateKey privateKey(String alias) {
    try {
      if (!(keyStore.getKey(alias, null) instanceof PrivateKey key)) {
        throw new IllegalArgumentException("Không có khoá " + alias + " trong HSM");
      }
      return key;
    } catch (GeneralSecurityException e) {
      throw new HsmUnavailableException("Không đọc được khoá từ HSM", e);
    }
  }

  @Override
  public void close() {
    closed = true;
    if (provider instanceof java.security.AuthProvider auth) {
      try {
        auth.logout();
      } catch (javax.security.auth.login.LoginException e) {
        log.debug("Đăng xuất token HSM lỗi: {}", e.getClass().getSimpleName());
      }
    }
  }

  /** {@code r||s} (P1363) sang {@code ECDSA-Sig-Value} DER. */
  static byte[] toDer(byte[] p1363) {
    if (p1363.length % 2 != 0) {
      throw new IllegalArgumentException("Chữ ký P1363 sai độ dài");
    }
    int half = p1363.length / 2;
    ASN1EncodableVector v = new ASN1EncodableVector();
    v.add(new ASN1Integer(new BigInteger(1, Arrays.copyOfRange(p1363, 0, half))));
    v.add(new ASN1Integer(new BigInteger(1, Arrays.copyOfRange(p1363, half, p1363.length))));
    try {
      return new DERSequence(v).getEncoded(ASN1Encoding.DER);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
