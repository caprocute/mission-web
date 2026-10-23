package vn.viettel.okrkpi.esign.infrastructure.provider.hsm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import vn.viettel.okrkpi.esign.application.seal.LedgerSealService;
import vn.viettel.okrkpi.esign.application.sign.Hashes;
import vn.viettel.okrkpi.esign.infrastructure.tsa.TsaClient;
import vn.viettel.okrkpi.esign.infrastructure.tsa.TsaMockServer;
import vn.viettel.okrkpi.esign.spi.SignRequest;
import vn.viettel.okrkpi.esign.spi.SignStatus;
import vn.viettel.okrkpi.esign.spi.SignatureFormat;
import vn.viettel.okrkpi.esign.spi.SignatureProviderException;
import vn.viettel.okrkpi.esign.spi.SignedItem;
import vn.viettel.okrkpi.shared.error.BusinessException;

/**
 * Ký qua PKCS#11 với SoftHSM2 thật (DV-10-03): ký ECDSA P-256 kiểm được bằng khoá công khai, khoá
 * không trích xuất được, nhật ký không chứa PIN, readiness DOWN khi HSM dừng, CAdES-B-T của chữ ký
 * tổ chức và niêm phong sổ cái kèm dấu thời gian kiểm bằng {@code openssl ts -verify}. Bỏ qua khi
 * máy build không cài {@code softhsm2}.
 */
@ExtendWith(OutputCaptureExtension.class)
class HsmPkcs11SignerTest {

  static TsaMockServer tsaServer;

  /**
   * Một token cho cả lớp: thư viện PKCS#11 chỉ khởi tạo một lần trong tiến trình và không thấy
   * token tạo sau đó. Trạng thái đăng nhập cũng chung cho cả tiến trình (chuẩn PKCS#11).
   */
  static SoftHsm.Token token;

  HsmProperties properties;
  HsmPkcs11Signer signer;
  SimpleMeterRegistry meters;

  @BeforeAll
  static void startTsa() throws Exception {
    tsaServer = new TsaMockServer();
    if (SoftHsm.available()) {
      token = SoftHsm.newToken();
    }
  }

  @AfterAll
  static void stopTsa() {
    tsaServer.close();
  }

  @BeforeEach
  void open() throws Exception {
    assumeTrue(token != null, "máy build không cài softhsm2");
    properties = token.properties(true);
    meters = new SimpleMeterRegistry();
    signer = new HsmPkcs11Signer(properties, meters, Clock.systemUTC());
    signer.ensureKey(properties.orgKeyAlias(), "CN=Mission thu nghiem,O=Mission dev");
    signer.ensureKey(properties.sealKeyAlias(), "CN=Mission niem phong,O=Mission dev");
  }

  @AfterEach
  void close() {
    if (signer != null) {
      signer.close();
    }
  }

  @Test
  void kyEcdsaP256KiemDuocBangKhoaCongKhai() throws Exception {
    byte[] data = "ho-so-danh-gia".getBytes(StandardCharsets.UTF_8);
    byte[] signature =
        signer.sign(properties.orgKeyAlias(), Hashes.sha256(data), Pkcs11Signer.SHA256_WITH_ECDSA);
    X509Certificate cert = signer.chain(properties.orgKeyAlias()).get(0);
    Signature verify = Signature.getInstance("SHA256withECDSA");
    verify.initVerify(cert.getPublicKey());
    verify.update(data);
    assertThat(verify.verify(signature)).isTrue();
    assertThat(cert.getPublicKey().getAlgorithm()).isEqualTo("EC");
    assertThat(meters.counter("esign_hsm_signatures").count()).isEqualTo(1);
    // Khoá đã có thì không tạo lại.
    signer.ensureKey(properties.orgKeyAlias(), "CN=khac");
    assertThat(signer.chain(properties.orgKeyAlias()).get(0)).isEqualTo(cert);
    assertThatThrownBy(() -> signer.sign(properties.orgKeyAlias(), new byte[32], "SHA1withRSA"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                signer.sign(properties.orgKeyAlias(), new byte[20], Pkcs11Signer.SHA256_WITH_ECDSA))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> signer.sign("khong-co", new byte[32], Pkcs11Signer.SHA256_WITH_ECDSA))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> signer.chain("khong-co")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void khoaRiengKhongTrichXuatDuoc() throws Exception {
    assertThat(signer.privateKey(properties.orgKeyAlias()).getEncoded()).isNull();
    assumeTrue(SoftHsm.onPath("pkcs11-tool"), "máy build không có pkcs11-tool");
    String objects =
        SoftHsm.run(
            "pkcs11-tool",
            "--module",
            properties.library(),
            "--slot",
            Integer.toString(token.slot()),
            "--login",
            "--pin",
            token.pin(),
            "--list-objects",
            "--type",
            "privkey");
    assertThat(objects).contains("never extractable").contains("sensitive");
  }

  @Test
  void nhatKyVaThongBaoLoiKhongChuaPin(CapturedOutput output) {
    signer.probe();
    // Đăng xuất để lần mở với PIN sai thực sự đăng nhập lại.
    signer.close();
    assertThat(properties.toString()).doesNotContain(token.pin());
    HsmProperties wrong =
        new HsmProperties(
            true,
            properties.library(),
            token.slot(),
            null,
            "SaiPin" + token.pin(),
            null,
            null,
            null,
            null,
            false);
    assertThatThrownBy(() -> new HsmPkcs11Signer(wrong, meters, Clock.systemUTC()))
        .isInstanceOf(IllegalStateException.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain(wrong.pin()));
    // Nhật ký của bộ ký chỉ định dạng HsmProperties.toString() và tên lớp ngoại lệ; khi có
    // backend ghi nhật ký trên classpath thì kiểm thêm cả đầu ra.
    assertThat(output.getAll()).doesNotContain(token.pin()).doesNotContain(wrong.pin());
  }

  @Test
  void readinessDownKhiHsmDung() {
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("signer", signer);
    HsmHealthIndicator health =
        new HsmHealthIndicator(beans.getBeanProvider(HsmPkcs11Signer.class));
    assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    signer.close();
    assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
    assertThatThrownBy(
            () ->
                signer.sign(properties.orgKeyAlias(), new byte[32], Pkcs11Signer.SHA256_WITH_ECDSA))
        .isInstanceOf(HsmUnavailableException.class);
    HsmHealthIndicator disabled =
        new HsmHealthIndicator(
            new StaticListableBeanFactory().getBeanProvider(HsmPkcs11Signer.class));
    assertThat(disabled.health().getStatus()).isEqualTo(Status.UP);
    assertThat(disabled.health().getDetails()).containsEntry("hsm", "disabled");
  }

  @Test
  void chuKyToChucCadesBtKemDauThoiGian() throws Exception {
    HsmPkcs11Provider provider =
        new HsmPkcs11Provider(
            signer, properties.orgKeyAlias(), tsaServer.client(), Clock.systemUTC());
    assertThat(provider.id()).isEqualTo("hsm");
    assertThat(provider.supportedFormats()).containsExactly(SignatureFormat.CADES_B_T);
    byte[] content = "quyet-dinh".getBytes(StandardCharsets.UTF_8);
    SignRequest request = request(SignatureFormat.CADES_B_T, content);
    String session = provider.begin(request).sessionId();
    assertThat(provider.poll(session)).isEqualTo(SignStatus.SIGNED);
    List<SignedItem> items = provider.collect(session);
    assertThat(items).hasSize(1);
    CMSSignedData cms =
        new CMSSignedData(new CMSProcessableByteArray(content), items.get(0).signatureValue());
    SignerInformation info = cms.getSignerInfos().getSigners().iterator().next();
    X509Certificate cert = signer.chain(properties.orgKeyAlias()).get(0);
    assertThat(info.verify(new JcaSimpleSignerInfoVerifierBuilder().build(cert))).isTrue();
    assertThat(
            info.getUnsignedAttributes().get(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken))
        .isNotNull();
    assertThat(items.get(0).certificate().serialNo())
        .isEqualTo(cert.getSerialNumber().toString(16));
    assertThat(provider.poll(session)).isEqualTo(SignStatus.EXPIRED);
    assertThatThrownBy(() -> provider.collect(session))
        .isInstanceOf(SignatureProviderException.class);
    assertThatThrownBy(() -> provider.begin(request(SignatureFormat.PADES_B_LTA, content)))
        .isInstanceOf(SignatureProviderException.class);
    signer.close();
    HsmPkcs11Provider noTsa =
        new HsmPkcs11Provider(signer, properties.orgKeyAlias(), null, Clock.systemUTC());
    assertThatThrownBy(() -> noTsa.begin(request)).isInstanceOf(SignatureProviderException.class);
  }

  @Test
  void niemPhongSoCaiKiemDuocBangOpenssl(@TempDir Path dir) throws Exception {
    TsaClient tsa = tsaServer.client();
    LedgerSealService service =
        new LedgerSealService(
            new HsmSealSigner(signer, properties.sealKeyAlias()),
            tsa,
            Set.of("ledger-service"),
            Clock.systemUTC());
    String root = HexFormat.of().formatHex(Hashes.sha256("lo-su-kien".getBytes()));
    LedgerSealService.Result r =
        service.seal(
            new LedgerSealService.Command(
                "ledger-service", LocalDate.of(2026, 10, 23), root, null, 128));
    X509Certificate seal = signer.chain(properties.sealKeyAlias()).get(0);
    Signature verify = Signature.getInstance("SHA256withECDSA");
    verify.initVerify(seal.getPublicKey());
    verify.update(HexFormat.of().parseHex(root));
    assertThat(verify.verify(r.signature())).isTrue();
    assertThat(r.certificateSerial()).isEqualTo(seal.getSerialNumber().toString(16));
    assertThat(r.certificateSha256()).isEqualTo(Hashes.sha256(seal.getEncoded()));
    assertThat(r.signedAt()).isBefore(Instant.now().plusSeconds(1));
    assumeTrue(SoftHsm.onPath("openssl"), "máy build không có openssl");
    Path tok = Files.write(dir.resolve("seal.tst"), r.timestampToken());
    Path ca = Files.writeString(dir.resolve("ca.pem"), pem(tsaServer.root));
    String out =
        SoftHsm.run(
            "openssl",
            "ts",
            "-verify",
            "-digest",
            root,
            "-in",
            tok.toString(),
            "-token_in",
            "-CAfile",
            ca.toString());
    assertThat(out).contains("Verification: OK");
    assertThatThrownBy(
            () ->
                service.seal(
                    new LedgerSealService.Command(
                        "okr-core", LocalDate.of(2026, 10, 23), root, null, 1)))
        .isInstanceOf(BusinessException.class);
  }

  static SignRequest request(SignatureFormat format, byte[] content) {
    return new SignRequest(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        List.of(new SignRequest.Document("doc-1", Hashes.sha256(content), format)),
        Instant.now().plusSeconds(120));
  }

  static String pem(X509Certificate c) throws Exception {
    return "-----BEGIN CERTIFICATE-----\n"
        + java.util.Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(c.getEncoded())
        + "\n-----END CERTIFICATE-----\n";
  }
}
