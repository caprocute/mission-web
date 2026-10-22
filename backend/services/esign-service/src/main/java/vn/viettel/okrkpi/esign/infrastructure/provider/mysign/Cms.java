package vn.viettel.okrkpi.esign.infrastructure.provider.mysign;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.List;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.cms.IssuerAndSerialNumber;
import org.bouncycastle.asn1.cms.SignedData;
import org.bouncycastle.asn1.cms.SignerIdentifier;
import org.bouncycastle.asn1.cms.SignerInfo;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import vn.viettel.okrkpi.esign.application.sign.Hashes;

/**
 * Dựng CMS SignedData tách rời ({@code ETSI.CAdES.detached}) quanh chữ ký thô do MySign trả về.
 * Thuộc tính ký (content-type, message-digest, signing-certificate-v2) dựng một lần lúc mở phiên và
 * lưu nguyên dạng DER, để băm gửi đi và CMS lúc nhận chữ ký khớp từng byte; không có signing-time
 * (PAdES baseline đặt thời điểm ký ở từ điển chữ ký PDF).
 */
public final class Cms {

  public static final String SHA256_OID = NISTObjectIdentifiers.id_sha256.getId();

  private static final AlgorithmIdentifier SHA256 =
      new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256, DERNull.INSTANCE);

  private Cms() {}

  /** Thuật toán ký CSC ({@code signAlgo}) theo loại khoá của chứng thư. */
  public static String signAlgo(X509Certificate signer) {
    return switch (signer.getPublicKey().getAlgorithm()) {
      case "RSA" -> PKCSObjectIdentifiers.sha256WithRSAEncryption.getId();
      case "EC" -> X9ObjectIdentifiers.ecdsa_with_SHA256.getId();
      default ->
          throw new IllegalArgumentException(
              "Loại khoá chưa hỗ trợ: " + signer.getPublicKey().getAlgorithm());
    };
  }

  /** Tập thuộc tính ký DER {@code signedAttrs}: chính là dữ liệu người ký ký (băm SHA-256). */
  public static byte[] signedAttributes(byte[] contentDigest, X509Certificate signer) {
    try {
      ESSCertIDv2 certId = new ESSCertIDv2(Hashes.sha256(signer.getEncoded()));
      ASN1EncodableVector attrs = new ASN1EncodableVector();
      attrs.add(new Attribute(CMSAttributes.contentType, new DERSet(CMSObjectIdentifiers.data)));
      attrs.add(
          new Attribute(
              CMSAttributes.messageDigest, new DERSet(new DEROctetString(contentDigest))));
      attrs.add(
          new Attribute(
              PKCSObjectIdentifiers.id_aa_signingCertificateV2,
              new DERSet(new SigningCertificateV2(new ESSCertIDv2[] {certId}))));
      return new DERSet(attrs).getEncoded(ASN1Encoding.DER);
    } catch (CertificateEncodingException | IOException e) {
      throw new IllegalStateException("Không dựng được thuộc tính ký", e);
    }
  }

  /**
   * CMS SignedData DER: chữ ký thô {@code signature} trên {@code signedAttributes}, kèm chuỗi chứng
   * thư và, nếu có, token dấu thời gian RFC 3161 trên chữ ký (thuộc tính không ký
   * signature-time-stamp, mức B-T).
   */
  public static byte[] signedData(
      byte[] signedAttributes,
      byte[] signature,
      List<X509Certificate> chain,
      byte[] timestampToken) {
    try {
      X509Certificate signer = chain.get(0);
      Certificate signerAsn1 = Certificate.getInstance(signer.getEncoded());
      ASN1ObjectIdentifier sigOid = new ASN1ObjectIdentifier(signAlgo(signer));
      AlgorithmIdentifier sigAlg =
          sigOid.equals(PKCSObjectIdentifiers.sha256WithRSAEncryption)
              ? new AlgorithmIdentifier(sigOid, DERNull.INSTANCE)
              : new AlgorithmIdentifier(sigOid);
      ASN1Set unsigned = null;
      if (timestampToken != null) {
        unsigned =
            new DERSet(
                new Attribute(
                    PKCSObjectIdentifiers.id_aa_signatureTimeStampToken,
                    new DERSet(ASN1Primitive.fromByteArray(timestampToken))));
      }
      SignerInfo info =
          new SignerInfo(
              new SignerIdentifier(
                  new IssuerAndSerialNumber(signerAsn1.getIssuer(), signer.getSerialNumber())),
              SHA256,
              ASN1Set.getInstance(signedAttributes),
              sigAlg,
              new DEROctetString(signature),
              unsigned);
      ASN1EncodableVector certs = new ASN1EncodableVector();
      for (X509Certificate c : chain) {
        certs.add(Certificate.getInstance(c.getEncoded()));
      }
      SignedData sd =
          new SignedData(
              new DERSet(SHA256),
              new ContentInfo(CMSObjectIdentifiers.data, null),
              new DERSet(certs),
              null,
              new DERSet(new ASN1Encodable[] {info}));
      return new ContentInfo(CMSObjectIdentifiers.signedData, sd).getEncoded(ASN1Encoding.DER);
    } catch (CertificateEncodingException e) {
      throw new IllegalStateException("Chứng thư không mã hoá được", e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
