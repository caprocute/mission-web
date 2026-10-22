package vn.viettel.okrkpi.esign.infrastructure.provider.mysign;

import java.io.IOException;
import java.math.BigInteger;
import java.security.SecureRandom;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TSPException;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import vn.viettel.okrkpi.esign.application.sign.Hashes;
import vn.viettel.okrkpi.esign.spi.SignatureProviderException;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;

/**
 * Lấy dấu thời gian RFC 3161 cho giá trị chữ ký (PAdES-B-T). Máy chủ TSA chính thức đến từ
 * DV-10-03; tới lúc đó địa chỉ TSA là cấu hình triển khai.
 */
public class TimestampClient {

  private static final MediaType QUERY = MediaType.parseMediaType("application/timestamp-query");
  private static final MediaType REPLY = MediaType.parseMediaType("application/timestamp-reply");

  private final ResilientClient tsa;
  private final SecureRandom random = new SecureRandom();

  public TimestampClient(ResilientClient tsa) {
    this.tsa = tsa;
  }

  /** Token dấu thời gian (ContentInfo DER) trên SHA-256 của {@code signature}. */
  public byte[] timestamp(byte[] signature) {
    TimeStampRequestGenerator generator = new TimeStampRequestGenerator();
    generator.setCertReq(true);
    TimeStampRequest request =
        generator.generate(
            TSPAlgorithms.SHA256, Hashes.sha256(signature), new BigInteger(64, random));
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(QUERY);
    headers.setAccept(java.util.List.of(REPLY));
    try {
      byte[] body = tsa.exchange(HttpMethod.POST, "", request.getEncoded(), headers, byte[].class);
      TimeStampResponse response = new TimeStampResponse(body);
      response.validate(request);
      if (response.getTimeStampToken() == null) {
        throw new SignatureProviderException(
            "Máy chủ dấu thời gian từ chối: " + response.getStatusString(), false);
      }
      return response
          .getTimeStampToken()
          .toCMSSignedData()
          .toASN1Structure()
          .getEncoded(ASN1Encoding.DER);
    } catch (IOException | TSPException e) {
      throw new SignatureProviderException(
          "Dấu thời gian không hợp lệ: " + e.getMessage(), false, e);
    }
  }
}
