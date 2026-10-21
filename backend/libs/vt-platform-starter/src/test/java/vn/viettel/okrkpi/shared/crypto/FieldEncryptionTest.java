package vn.viettel.okrkpi.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import vn.viettel.okrkpi.shared.crypto.DekCache.CachedDek;
import vn.viettel.okrkpi.shared.crypto.EncryptionContextHolder.RowEncryptionContext;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử đơn vị cho gói {@code vn.viettel.okrkpi.shared.crypto} (NEN-015): vector kiểm thử cố
 * định, định dạng phong bì, kiểm tra AAD, {@link DekCache}, {@link BlindIndexer}, {@link
 * OpenBaoTransitClient}, JPA {@link C2StringAttributeConverter} và đo độ trễ {@code < 50 µs}.
 */
class FieldEncryptionTest {

  private static final HexFormat HEX = HexFormat.of();
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void vectorKiemThuCoDinhKhopTuyetDoi() throws Exception {
    try (InputStream in =
        FieldEncryptionTest.class.getResourceAsStream(
            "/vn/viettel/okrkpi/shared/crypto/c2-test-vectors.json")) {
      assertThat(in).isNotNull();
      JsonNode root = MAPPER.readTree(in);

      JsonNode envNode = root.get("envelopeVector");
      UUID tenantId = UUID.fromString(envNode.get("tenantId").asText());
      UUID dekId = UUID.fromString(envNode.get("dekId").asText());
      DekPurpose purpose = DekPurpose.valueOf(envNode.get("purpose").asText());
      LocalDate validMonth = LocalDate.parse(envNode.get("validMonth").asText());
      byte[] key = HEX.parseHex(envNode.get("keyHex").asText());
      byte[] nonce = HEX.parseHex(envNode.get("nonceHex").asText());
      List<String> aadParts =
          List.of(envNode.get("aadParts").get(0).asText(), envNode.get("aadParts").get(1).asText());
      String expectedAad = envNode.get("aadUtf8").asText();
      String plaintext = envNode.get("plaintextUtf8").asText();
      String expectedEnvelopeHex = envNode.get("envelopeHex").asText();

      assertThat(new String(FieldEncryptor.buildAad(tenantId, aadParts), StandardCharsets.UTF_8))
          .isEqualTo(expectedAad);

      CryptoProvider provider = new JcaAesGcmCryptoProvider();
      DekCache cache = new DekCache();
      cache.put(dekId, tenantId, purpose, validMonth, DekStatus.ACTIVE, key);
      DekService dekService = new DekService(null, null, provider, cache);
      FieldEncryptor encryptor = new FieldEncryptor(dekService, provider);

      byte[] actualEnvelope =
          encryptor.encryptDeterministicForVector(
              tenantId, dekId, key, nonce, aadParts, plaintext.getBytes(StandardCharsets.UTF_8));
      assertThat(HEX.formatHex(actualEnvelope)).isEqualTo(expectedEnvelopeHex);
      assertThat(FieldEncryptor.extractDekId(actualEnvelope)).isEqualTo(dekId);

      String decrypted = encryptor.decryptToString(tenantId, purpose, aadParts, actualEnvelope);
      assertThat(decrypted).isEqualTo(plaintext);

      JsonNode bidxNode = root.get("blindIndexVector");
      UUID bidxTenant = UUID.fromString(bidxNode.get("tenantId").asText());
      String column = bidxNode.get("column").asText();
      byte[] rootKey = HEX.parseHex(bidxNode.get("rootKeyHex").asText());
      String rawInput = bidxNode.get("rawInput").asText();
      String expectedNormalized = bidxNode.get("normalizedInput").asText();
      String expectedBidxHex = bidxNode.get("blindIndexHex").asText();

      assertThat(BlindIndexer.normalize(rawInput)).isEqualTo(expectedNormalized);
      BlindIndexer indexer = new BlindIndexer(dekService, provider);
      byte[] bidx = indexer.computeWithRootKey(bidxTenant, rootKey, column, rawInput);
      assertThat(bidx).hasSize(BlindIndexer.BLIND_INDEX_LENGTH);
      assertThat(HEX.formatHex(bidx)).isEqualTo(expectedBidxHex);
    }
  }

  @Test
  void maHoaGiaiMaVaTuChoiKhiSaiAadHoacSuaBanMa() {
    UUID tenantA = UUID.fromString("01950000-0000-7000-8000-000000000001");
    UUID tenantB = UUID.fromString("01950000-0000-7000-8000-000000000002");
    UUID dekId = UUID.fromString("01950000-0000-7000-8000-0000000000aa");
    LocalDate month = LocalDate.of(2026, 10, 1);

    CryptoProvider provider = new JcaAesGcmCryptoProvider();
    byte[] rawKey = provider.generateKey();
    Clock fixedClock = Clock.fixed(Instant.parse("2026-10-15T08:00:00Z"), ZoneOffset.UTC);
    DekCache cache = new DekCache(100, Duration.ofMinutes(15), fixedClock);
    cache.put(dekId, tenantA, DekPurpose.NHAN_XET_DANH_GIA, month, DekStatus.ACTIVE, rawKey);
    cache.put(dekId, tenantB, DekPurpose.NHAN_XET_DANH_GIA, month, DekStatus.ACTIVE, rawKey);

    DekService dekService = new DekService(null, null, provider, cache, fixedClock);
    FieldEncryptor encryptor = new FieldEncryptor(dekService, provider);

    List<String> aadRow1 = List.of("appraisal.self_review.comment_enc", "row-001");
    List<String> aadRow2 = List.of("appraisal.self_review.comment_enc", "row-002");
    List<String> aadOtherCol = List.of("appraisal.manager_review.comment_enc", "row-001");

    EncryptedField encrypted =
        encryptor.encryptField(
            tenantA,
            DekPurpose.NHAN_XET_DANH_GIA,
            aadRow1,
            "Nhận xét mật C2".getBytes(StandardCharsets.UTF_8));
    assertThat(encrypted.dekId()).isEqualTo(dekId);
    byte[] envelope = encrypted.ciphertext();
    assertThat(envelope[0]).isEqualTo(CryptoProvider.VERSION_1);
    assertThat(FieldEncryptor.extractDekId(envelope)).isEqualTo(dekId);

    // Giải mã đúng AAD thành công
    assertThat(encryptor.decryptToString(tenantA, DekPurpose.NHAN_XET_DANH_GIA, aadRow1, envelope))
        .isEqualTo("Nhận xét mật C2");
    assertThat(encryptor.decryptToString(tenantA, aadRow1, envelope)).isEqualTo("Nhận xét mật C2");

    // Chép bản mã sang dòng khác -> thất bại xác thực AAD
    assertThatThrownBy(() -> encryptor.decryptToString(tenantA, aadRow2, envelope))
        .isInstanceOf(CryptoException.class);

    // Chép bản mã sang cột khác -> thất bại xác thực AAD
    assertThatThrownBy(() -> encryptor.decryptToString(tenantA, aadOtherCol, envelope))
        .isInstanceOf(CryptoException.class);

    // Chép bản mã sang tenant khác -> thất bại xác thực AAD
    assertThatThrownBy(() -> encryptor.decryptToString(tenantB, aadRow1, envelope))
        .isInstanceOf(CryptoException.class);

    // Giải mã sai mục đích DEK -> thất bại
    assertThatThrownBy(
            () -> encryptor.decryptToString(tenantA, DekPurpose.KHIEU_NAI, aadRow1, envelope))
        .isInstanceOf(CryptoException.class)
        .hasMessageContaining("Mục đích của DEK");

    // Đảo 1 bit ở phần thẻ xác thực cuối bản mã -> thất bại
    byte[] tampered = envelope.clone();
    tampered[tampered.length - 1] ^= 0x01;
    assertThatThrownBy(() -> encryptor.decrypt(tenantA, aadRow1, tampered))
        .isInstanceOf(CryptoException.class);

    // Sai phiên bản bản mã -> thất bại
    byte[] badVersion = envelope.clone();
    badVersion[0] = 0x02;
    assertThatThrownBy(() -> encryptor.decrypt(tenantA, aadRow1, badVersion))
        .isInstanceOf(CryptoException.class)
        .hasMessageContaining("Phiên bản");

    // Bản mã bị cắt ngắn -> thất bại
    assertThatThrownBy(() -> encryptor.decrypt(tenantA, aadRow1, new byte[20]))
        .isInstanceOf(CryptoException.class);
  }

  @Test
  void dekCacheGioiHan15PhutXoaMangKhoaKhiDayRaVaChanDekDestroyed() {
    UUID tenant = UUID.fromString("01950000-0000-7000-8000-000000000001");
    UUID dek1 = UUID.fromString("01950000-0000-7000-8000-000000000001");
    UUID dek2 = UUID.fromString("01950000-0000-7000-8000-000000000002");
    UUID dek3 = UUID.fromString("01950000-0000-7000-8000-000000000003");
    LocalDate month = LocalDate.of(2026, 10, 1);

    MutableClock clock = new MutableClock(Instant.parse("2026-10-15T00:00:00Z"));
    CryptoProvider provider = new JcaAesGcmCryptoProvider();
    DekCache cache = new DekCache(2, Duration.ofMinutes(15), clock);

    byte[] k1 = provider.generateKey();
    byte[] k2 = provider.generateKey();
    byte[] k3 = provider.generateKey();

    cache.put(dek1, tenant, DekPurpose.NHAN_XET_DANH_GIA, month, DekStatus.ACTIVE, k1);
    cache.put(dek2, tenant, DekPurpose.KHIEU_NAI, month, DekStatus.DECRYPT_ONLY, k2);
    assertThat(cache.size()).isEqualTo(2);

    // DECRYPT_ONLY tìm được theo ID nhưng không trả về cho getActive
    assertThat(cache.getById(tenant, dek2)).isPresent();
    assertThat(cache.getActive(tenant, DekPurpose.KHIEU_NAI, month)).isEmpty();

    // Thêm mục thứ 3 vượt maxSize=2 -> đẩy mục LRU (dek1) và ghi đè 0 lên mảng khoá
    cache.put(dek3, tenant, DekPurpose.GOP_Y_KIEM_DIEM, month, DekStatus.ACTIVE, k3);
    assertThat(cache.size()).isEqualTo(2);
    assertThat(cache.getById(tenant, dek1)).isEmpty();
    assertThat(cache.wipedEntryCount()).isEqualTo(1);
    assertThat(cache.wasLastEvictedBufferZeroed()).isTrue();

    // Sau 15 phút -> hết hạn TTL và ghi đè 0 lên các mục hết hạn
    clock.advance(Duration.ofMinutes(15));
    assertThat(cache.getById(tenant, dek3)).isEmpty();
    assertThat(cache.size()).isZero();
    assertThat(cache.wipedEntryCount()).isEqualTo(3);
    assertThat(cache.wasLastEvictedBufferZeroed()).isTrue();

    // Không cho phép đưa DEK DESTROYED vào cache
    assertThatThrownBy(
            () ->
                cache.put(
                    dek1, tenant, DekPurpose.NHAN_XET_DANH_GIA, month, DekStatus.DESTROYED, k1))
        .isInstanceOf(DekDestroyedException.class)
        .satisfies(
            ex -> {
              DekDestroyedException dde = (DekDestroyedException) ex;
              assertThat(dde.tenantId()).isEqualTo(tenant);
              assertThat(dde.dekId()).isEqualTo(dek1);
            });

    // toString() của DekCache và CachedDek che khoá
    cache.put(dek1, tenant, DekPurpose.NHAN_XET_DANH_GIA, month, DekStatus.ACTIVE, k1);
    CachedDek cached = cache.getById(tenant, dek1).orElseThrow();
    assertThat(cached.toString()).contains("[REDACTED]").doesNotContain(HEX.formatHex(k1));
    assertThat(cache.toString()).contains("[REDACTED]");
    cache.invalidateTenant(tenant);
    assertThat(cache.size()).isZero();
  }

  @Test
  void blindIndexerChuanHoaNfcKhoangTrangChuThuongVaPhanLapTheoCotTenant() {
    UUID tenantA = UUID.fromString("01950000-0000-7000-8000-000000000001");
    UUID tenantB = UUID.fromString("01950000-0000-7000-8000-000000000002");
    CryptoProvider provider = new JcaAesGcmCryptoProvider();
    DekCache cache = new DekCache();
    cache.put(
        UUID.randomUUID(),
        tenantA,
        DekPurpose.BLIND_INDEX,
        DekService.BLIND_INDEX_ANCHOR_MONTH,
        DekStatus.ACTIVE,
        provider.generateKey());
    cache.put(
        UUID.randomUUID(),
        tenantB,
        DekPurpose.BLIND_INDEX,
        DekService.BLIND_INDEX_ANCHOR_MONTH,
        DekStatus.ACTIVE,
        provider.generateKey());

    DekService dekService = new DekService(null, null, provider, cache);
    BlindIndexer indexer = new BlindIndexer(dekService, provider);

    String nfcInput = "  Nguyễn   Văn   An \t\n ";
    String nfdInput = Normalizer.normalize("nguyễn văn an", Normalizer.Form.NFD);

    byte[] idx1 = indexer.compute(tenantA, "identity.user_profile.full_name", nfcInput);
    byte[] idx2 = indexer.compute(tenantA, "identity.user_profile.full_name", nfdInput);
    assertThat(idx1).hasSize(16).isEqualTo(idx2);

    // Khác cột -> chỉ mục mù khác nhau dù cùng bản rõ
    byte[] idxDifferentCol = indexer.compute(tenantA, "appraisal.complaint.author_name", nfcInput);
    assertThat(idxDifferentCol).hasSize(16).isNotEqualTo(idx1);

    // Khác tenant -> chỉ mục mù khác nhau
    byte[] idxDifferentTenant =
        indexer.compute(tenantB, "identity.user_profile.full_name", nfcInput);
    assertThat(idxDifferentTenant).hasSize(16).isNotEqualTo(idx1);
  }

  @Test
  void jpaC2StringAttributeConverterMaHoaVaGiaiMaQuaEncryptionContext() {
    UUID tenantId = UUID.fromString("01950000-0000-7000-8000-000000000001");
    UUID dekId = UUID.fromString("01950000-0000-7000-8000-0000000000aa");
    CryptoProvider provider = new JcaAesGcmCryptoProvider();
    DekCache cache = new DekCache();
    cache.put(
        dekId,
        tenantId,
        DekPurpose.NHAN_XET_DANH_GIA,
        LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1),
        DekStatus.ACTIVE,
        provider.generateKey());
    DekService dekService = new DekService(null, null, provider, cache);
    FieldEncryptor encryptor = new FieldEncryptor(dekService, provider);
    EncryptionContextHolder.registerFieldEncryptor(encryptor);

    C2StringAttributeConverter converter = new C2StringAttributeConverter();
    assertThat(converter.convertToDatabaseColumn(null)).isNull();
    assertThat(converter.convertToEntityAttribute(null)).isNull();

    TenantContext.run(
        new TenantId(tenantId),
        () ->
            EncryptionContextHolder.runWithRow(
                "row-jpa-123",
                DekPurpose.NHAN_XET_DANH_GIA,
                "appraisal.self_review.comment_enc",
                () -> {
                  byte[] dbBytes =
                      converter.convertToDatabaseColumn("Nội dung tự đánh giá C2 qua JPA");
                  assertThat(dbBytes).isNotNull();
                  assertThat(FieldEncryptor.extractDekId(dbBytes)).isEqualTo(dekId);

                  String restored = converter.convertToEntityAttribute(dbBytes);
                  assertThat(restored).isEqualTo("Nội dung tự đánh giá C2 qua JPA");
                }));

    // Đổi rowId khi đọc -> giải mã thất bại vì lệch AAD
    RowEncryptionContext ctxRow1 =
        new RowEncryptionContext(
            tenantId, "row-1", DekPurpose.NHAN_XET_DANH_GIA, "appraisal.self_review.comment_enc");
    RowEncryptionContext ctxRow2 =
        new RowEncryptionContext(
            tenantId, "row-2", DekPurpose.NHAN_XET_DANH_GIA, "appraisal.self_review.comment_enc");
    final byte[][] holder = new byte[1][];
    EncryptionContextHolder.runWithRow(
        ctxRow1, () -> holder[0] = converter.convertToDatabaseColumn("Bí mật dòng 1"));
    assertThatThrownBy(
            () ->
                EncryptionContextHolder.runWithRow(
                    ctxRow2, () -> converter.convertToEntityAttribute(holder[0])))
        .isInstanceOf(CryptoException.class);
  }

  @Test
  void openBaoTransitClientXacThucTokenFileVaKubernetesAuth(@TempDir Path tempDir)
      throws Exception {
    CryptoProvider provider = new JcaAesGcmCryptoProvider();
    byte[] wrappingKek = provider.generateKey();
    AtomicInteger loginCalls = new AtomicInteger();

    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/auth/kubernetes/login",
        exchange -> {
          loginCalls.incrementAndGet();
          sendJson(
              exchange,
              200,
              "{\"auth\":{\"client_token\":\"k8s-token-123\",\"lease_duration\":600}}");
        });
    server.createContext(
        "/v1/transit/keys/",
        exchange -> {
          if (!hasValidToken(exchange)) {
            sendJson(exchange, 403, "{\"errors\":[\"permission denied\"]}");
            return;
          }
          sendJson(exchange, 204, "");
        });
    server.createContext(
        "/v1/transit/encrypt/",
        exchange -> {
          if (!hasValidToken(exchange)) {
            sendJson(exchange, 403, "{\"errors\":[\"permission denied\"]}");
            return;
          }
          JsonNode req = MAPPER.readTree(exchange.getRequestBody());
          byte[] plainDek = Base64.getDecoder().decode(req.get("plaintext").asText());
          byte[] nonce = provider.generateNonce();
          byte[] aad = "openbao-transit".getBytes(StandardCharsets.UTF_8);
          byte[] cipher = provider.encrypt(wrappingKek, nonce, aad, plainDek);
          String token =
              "vault:v1:"
                  + Base64.getEncoder().encodeToString(nonce)
                  + ":"
                  + Base64.getEncoder().encodeToString(cipher);
          sendJson(exchange, 200, "{\"data\":{\"ciphertext\":\"" + token + "\"}}");
        });
    server.createContext(
        "/v1/transit/decrypt/",
        exchange -> {
          if (!hasValidToken(exchange)) {
            sendJson(exchange, 403, "{\"errors\":[\"permission denied\"]}");
            return;
          }
          JsonNode req = MAPPER.readTree(exchange.getRequestBody());
          String[] parts = req.get("ciphertext").asText().split(":");
          byte[] nonce = Base64.getDecoder().decode(parts[2]);
          byte[] cipher = Base64.getDecoder().decode(parts[3]);
          byte[] aad = "openbao-transit".getBytes(StandardCharsets.UTF_8);
          byte[] plainDek = provider.decrypt(wrappingKek, nonce, aad, cipher);
          String b64 = Base64.getEncoder().encodeToString(plainDek);
          sendJson(exchange, 200, "{\"data\":{\"plaintext\":\"" + b64 + "\"}}");
        });
    server.start();

    try {
      URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
      Path jwtFile = tempDir.resolve("sa-jwt");
      Files.writeString(jwtFile, "eyJhbGciOiJSUzI1NiJ9.fake.jwt", StandardCharsets.UTF_8);

      CryptoProperties props = new CryptoProperties();
      props.setOpenBaoBaseUrl(baseUri);
      props.setAutoCreateKek(true);
      props.setKubernetesRole("mission-app");
      props.setServiceAccountTokenFile(jwtFile);

      OpenBaoTransitClient client = new OpenBaoTransitClient(props, MAPPER);
      UUID tenantId = UUID.fromString("01950000-0000-7000-8000-000000000001");
      byte[] originalDek = provider.generateKey();

      byte[] wrapped = client.wrapDek(tenantId, originalDek);
      assertThat(new String(wrapped, StandardCharsets.UTF_8)).startsWith("vault:v1:");

      byte[] unwrapped = client.unwrapDek(tenantId, wrapped);
      assertThat(unwrapped).isEqualTo(originalDek);
      assertThat(loginCalls.get()).isEqualTo(1);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void autoConfigurationDangKyDayDuBean() {
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CryptoAutoConfiguration.class));
    runner.run(
        ctx -> {
          assertThat(ctx).hasSingleBean(CryptoProvider.class);
          assertThat(ctx).hasSingleBean(DekCache.class);
          assertThat(ctx).hasSingleBean(OpenBaoTransitClient.class);
          assertThat(ctx).hasSingleBean(DekService.class);
          assertThat(ctx).hasSingleBean(FieldEncryptor.class);
          assertThat(ctx).hasSingleBean(BlindIndexer.class);
        });
  }

  @Test
  void doTreMaHoaCongGiaiMaKhiDekDaDemDuoi50MicroGiay() {
    UUID tenantId = UUID.fromString("01950000-0000-7000-8000-000000000001");
    UUID dekId = UUID.fromString("01950000-0000-7000-8000-0000000000aa");
    CryptoProvider provider = new JcaAesGcmCryptoProvider();
    DekCache cache = new DekCache();
    cache.put(
        dekId,
        tenantId,
        DekPurpose.NHAN_XET_DANH_GIA,
        LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1),
        DekStatus.ACTIVE,
        provider.generateKey());
    DekService dekService = new DekService(null, null, provider, cache);
    FieldEncryptor encryptor = new FieldEncryptor(dekService, provider);

    List<String> aadParts =
        List.of("appraisal.self_review.comment_enc", "01950000-0000-7000-8000-000000000099");
    byte[] sample =
        "Nhận xét cán bộ quý IV: Hoàn thành xuất sắc nhiệm vụ được giao, đảm bảo tiến độ và chất lượng."
            .getBytes(StandardCharsets.UTF_8);

    // Làm nóng JIT
    for (int i = 0; i < 2_000; i++) {
      byte[] enc = encryptor.encrypt(tenantId, DekPurpose.NHAN_XET_DANH_GIA, aadParts, sample);
      byte[] dec = encryptor.decrypt(tenantId, DekPurpose.NHAN_XET_DANH_GIA, aadParts, enc);
      assertThat(dec).hasSize(sample.length);
    }

    int iterations = 10_000;
    long startNanos = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      byte[] enc = encryptor.encrypt(tenantId, DekPurpose.NHAN_XET_DANH_GIA, aadParts, sample);
      byte[] dec = encryptor.decrypt(tenantId, DekPurpose.NHAN_XET_DANH_GIA, aadParts, enc);
      if (dec.length != sample.length) {
        throw new AssertionError("Sai độ dài bản rõ");
      }
    }
    long elapsedNanos = System.nanoTime() - startNanos;
    double avgMicros = (elapsedNanos / 1_000.0) / iterations;
    assertThat(avgMicros)
        .as(
            "Độ trễ trung bình mã hoá + giải mã khi DEK đã đệm phải < 50 µs (thực đo: %.2f µs)",
            avgMicros)
        .isLessThan(50.0);
  }

  private static boolean hasValidToken(HttpExchange exchange) {
    String token = exchange.getRequestHeaders().getFirst("X-Vault-Token");
    return "k8s-token-123".equals(token) || "file-token-123".equals(token);
  }

  private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      exchange.getResponseBody().write(bytes);
    }
    exchange.close();
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant initial) {
      this.instant = initial;
    }

    void advance(Duration delta) {
      this.instant = this.instant.plus(delta);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
