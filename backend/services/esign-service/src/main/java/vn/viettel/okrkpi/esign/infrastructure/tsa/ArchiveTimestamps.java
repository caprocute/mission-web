package vn.viettel.okrkpi.esign.infrastructure.tsa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.time.Instant;
import java.util.Calendar;
import java.util.Collection;
import java.util.Optional;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.ExternalSigningSupport;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.tsp.TSPException;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.util.Selector;
import vn.viettel.okrkpi.esign.application.seal.TimestampAuthority;
import vn.viettel.okrkpi.esign.application.sign.Hashes;
import vn.viettel.okrkpi.esign.infrastructure.provider.mysign.PdfSignatures;
import vn.viettel.okrkpi.esign.spi.SignatureProviderException;

/**
 * Dấu thời gian lưu trữ cho PDF đã ký (PAdES-B-LTA, ETSI EN 319 142-1): đọc hạn chứng thư TSA của
 * dấu thời gian mới nhất trong tệp và thêm một dấu thời gian tài liệu ({@code ETSI.RFC3161}) mới
 * bằng lưu gia tăng, giữ nguyên mọi chữ ký cũ.
 */
public final class ArchiveTimestamps {

  /** Vùng chừa cho token (byte), đủ cho token kèm hai chứng thư. */
  static final int TOKEN_SIZE = 16_384;

  static final org.apache.pdfbox.cos.COSName RFC3161 =
      org.apache.pdfbox.cos.COSName.getPDFName("ETSI.RFC3161");

  private ArchiveTimestamps() {}

  /** Hạn muộn nhất của chứng thư TSA trong các dấu thời gian của tệp; rỗng khi tệp không có. */
  public static Optional<Instant> latestTsaExpiry(byte[] pdf) {
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      Instant latest = null;
      for (PDSignature sig : doc.getSignatureDictionaries()) {
        for (TimeStampToken token : tokens(sig)) {
          Instant expiry = tsaExpiry(token);
          if (latest == null || expiry.isAfter(latest)) {
            latest = expiry;
          }
        }
      }
      return Optional.ofNullable(latest);
    } catch (IOException e) {
      throw new SignatureProviderException("Tệp PDF không đọc được: " + e.getMessage(), true, e);
    }
  }

  /** Tệp mới có thêm dấu thời gian tài liệu trên toàn bộ nội dung hiện có. */
  public static byte[] addDocumentTimestamp(byte[] pdf, TimestampAuthority tsa, Calendar signDate) {
    byte[] prepared;
    byte[] digest;
    try (PDDocument doc = Loader.loadPDF(pdf);
        SignatureOptions options = new SignatureOptions()) {
      PDSignature signature = new PDSignature();
      signature.setType(org.apache.pdfbox.cos.COSName.DOC_TIME_STAMP);
      signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
      signature.setSubFilter(RFC3161);
      signature.setSignDate(signDate);
      options.setPreferredSignatureSize(TOKEN_SIZE);
      doc.addSignature(signature, options);
      ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length + TOKEN_SIZE * 2 + 4096);
      ExternalSigningSupport external = doc.saveIncrementalForExternalSigning(out);
      try (InputStream content = external.getContent()) {
        digest = Hashes.sha256(content.readAllBytes());
      }
      external.setSignature(new byte[0]);
      prepared = out.toByteArray();
    } catch (IOException e) {
      throw new SignatureProviderException("Tệp PDF không đọc được: " + e.getMessage(), true, e);
    }
    return PdfSignatures.embed(prepared, tsa.timestampDigest(digest));
  }

  private static java.util.List<TimeStampToken> tokens(PDSignature sig) throws IOException {
    byte[] contents = sig.getContents();
    java.util.List<TimeStampToken> out = new java.util.ArrayList<>();
    if (contents == null || contents.length == 0) {
      return out;
    }
    try {
      CMSSignedData cms = new CMSSignedData(contents);
      if (RFC3161.getName().equals(sig.getSubFilter())) {
        out.add(new TimeStampToken(cms));
        return out;
      }
      for (SignerInformation info : cms.getSignerInfos().getSigners()) {
        AttributeTable unsigned = info.getUnsignedAttributes();
        if (unsigned == null) {
          continue;
        }
        org.bouncycastle.asn1.ASN1EncodableVector all =
            unsigned.getAll(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken);
        for (int i = 0; i < all.size(); i++) {
          Attribute a = Attribute.getInstance(all.get(i));
          out.add(
              new TimeStampToken(
                  new CMSSignedData(
                      a.getAttrValues().getObjectAt(0).toASN1Primitive().getEncoded())));
        }
      }
    } catch (CMSException | TSPException e) {
      // Vùng chữ ký không đọc được thì coi như không có dấu thời gian: job sẽ thêm dấu mới.
      return out;
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Instant tsaExpiry(TimeStampToken token) {
    Collection<X509CertificateHolder> certs =
        token.getCertificates().getMatches((Selector<X509CertificateHolder>) token.getSID());
    if (certs.isEmpty()) {
      // Không kèm chứng thư TSA: không biết hạn, coi như đã hết để gia hạn.
      return Instant.EPOCH;
    }
    try {
      return new JcaX509CertificateConverter()
          .getCertificate(certs.iterator().next())
          .getNotAfter()
          .toInstant();
    } catch (CertificateException e) {
      return Instant.EPOCH;
    }
  }
}
