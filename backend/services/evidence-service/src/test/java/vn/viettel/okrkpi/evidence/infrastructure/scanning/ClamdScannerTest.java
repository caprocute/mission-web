package vn.viettel.okrkpi.evidence.infrastructure.scanning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.evidence.application.MalwareScanner.ScannerUnavailableException;

class ClamdScannerTest {

  /** clamd giả: đọc lệnh và khúc INSTREAM rồi trả {@code reply}. */
  static String serve(String reply, AtomicReference<byte[]> received, ThrowingScan scan)
      throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      Thread t =
          new Thread(
              () -> {
                try (Socket s = server.accept()) {
                  DataInputStream in = new DataInputStream(s.getInputStream());
                  var cmd = new StringBuilder();
                  int c;
                  while ((c = in.read()) > 0) {
                    cmd.append((char) c);
                  }
                  var all = new java.io.ByteArrayOutputStream();
                  if (cmd.toString().equals("zINSTREAM")) {
                    int n;
                    while ((n = in.readInt()) > 0) {
                      all.write(in.readNBytes(n));
                    }
                  }
                  received.set(all.toByteArray());
                  s.getOutputStream().write((reply + "\0").getBytes(StandardCharsets.US_ASCII));
                } catch (IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
              });
      t.start();
      String out =
          scan.run(new ClamdScanner("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(5)));
      t.join(5000);
      return out;
    }
  }

  interface ThrowingScan {
    String run(ClamdScanner c) throws Exception;
  }

  @Test
  void guiTheoKhucVaDocKetLuan() throws Exception {
    AtomicReference<byte[]> got = new AtomicReference<>();
    byte[] body = new byte[200_000];
    body[199_999] = 7;
    String r =
        serve(
            "stream: OK",
            got,
            c -> String.valueOf(c.scan(new ByteArrayInputStream(body)).infected()));
    assertThat(r).isEqualTo("false");
    assertThat(got.get()).isEqualTo(body);

    r =
        serve(
            "stream: Eicar-Test-Signature FOUND",
            got,
            c -> c.scan(new ByteArrayInputStream(new byte[3])).signature());
    assertThat(r).isEqualTo("Eicar-Test-Signature");
  }

  @Test
  void loiVaPhienBan() throws Exception {
    AtomicReference<byte[]> got = new AtomicReference<>();
    assertThatThrownBy(
            () ->
                serve(
                    "INSTREAM size limit exceeded. ERROR",
                    got,
                    c -> String.valueOf(c.scan(new ByteArrayInputStream(new byte[3])))))
        .isInstanceOf(ScannerUnavailableException.class);
    assertThat(serve("ClamAV 1.4.6/28157/Sun Oct 18 06:24:14 2026", got, ClamdScanner::version))
        .isEqualTo("1.4.6/28157");
    assertThat(new ClamdScanner("h", 1, Duration.ofSeconds(1)).engine()).isEqualTo("ClamAV");
    int closed;
    try (ServerSocket probe = new ServerSocket(0)) {
      closed = probe.getLocalPort();
    }
    ClamdScanner down = new ClamdScanner("127.0.0.1", closed, Duration.ofSeconds(1));
    assertThatThrownBy(down::version).isInstanceOf(ScannerUnavailableException.class);
    assertThatThrownBy(() -> down.scan(new ByteArrayInputStream(new byte[1])))
        .isInstanceOf(ScannerUnavailableException.class);
  }
}
