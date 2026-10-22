package vn.viettel.okrkpi.evidence.infrastructure.scanning;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import vn.viettel.okrkpi.evidence.application.MalwareScanner;

/**
 * Máy khách {@code clamd} qua TCP với lệnh {@code zINSTREAM}: nội dung gửi theo khúc {@code <độ dài
 * 4 byte><dữ liệu>}, kết thúc bằng khúc rỗng; trả {@code stream: OK}, {@code stream: <mẫu> FOUND}
 * hoặc {@code ... ERROR}. Không ghi tệp xuống đĩa của worker.
 */
public class ClamdScanner implements MalwareScanner {

  static final int CHUNK = 64 * 1024;

  private final String host;
  private final int port;
  private final Duration timeout;

  public ClamdScanner(String host, int port, Duration timeout) {
    this.host = host;
    this.port = port;
    this.timeout = timeout;
  }

  @Override
  public Finding scan(InputStream content) {
    String reply;
    try (Socket socket = connect()) {
      DataOutputStream out = new DataOutputStream(socket.getOutputStream());
      out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
      byte[] buf = new byte[CHUNK];
      int n;
      while ((n = content.read(buf)) > 0) {
        out.writeInt(n);
        out.write(buf, 0, n);
      }
      out.writeInt(0);
      out.flush();
      reply = readReply(socket.getInputStream());
    } catch (IOException e) {
      throw new ScannerUnavailableException("Không gọi được clamd", e);
    }
    if (reply.endsWith("OK")) {
      return new Finding(false, null);
    }
    if (reply.endsWith(" FOUND")) {
      String body = reply.substring(reply.indexOf(':') + 1, reply.length() - " FOUND".length());
      return new Finding(true, body.trim());
    }
    throw new ScannerUnavailableException("clamd báo lỗi: " + reply, null);
  }

  @Override
  public String version() {
    try (Socket socket = connect()) {
      OutputStream out = socket.getOutputStream();
      out.write("zVERSION\0".getBytes(StandardCharsets.US_ASCII));
      out.flush();
      // "ClamAV 1.4.6/28157/Sun Oct 18 06:24:14 2026" → "1.4.6/28157"
      String[] parts = readReply(socket.getInputStream()).replace("ClamAV ", "").split("/");
      return parts.length >= 2 ? parts[0] + "/" + parts[1] : parts[0];
    } catch (IOException e) {
      throw new ScannerUnavailableException("Không gọi được clamd", e);
    }
  }

  @Override
  public String engine() {
    return "ClamAV";
  }

  private Socket connect() throws IOException {
    Socket socket = new Socket();
    socket.connect(new InetSocketAddress(host, port), Math.toIntExact(timeout.toMillis()));
    socket.setSoTimeout(Math.toIntExact(timeout.toMillis()));
    return socket;
  }

  private static String readReply(InputStream in) throws IOException {
    ByteArrayOutputStream reply = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) > 0) {
      reply.write(b);
      if (reply.size() > 4096) {
        break;
      }
    }
    return reply.toString(StandardCharsets.US_ASCII).trim();
  }
}
