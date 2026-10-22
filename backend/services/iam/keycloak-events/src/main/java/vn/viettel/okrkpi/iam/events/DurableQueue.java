package vn.viettel.okrkpi.iam.events;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import org.jboss.logging.Logger;

/**
 * Hàng đợi bền cục bộ giữa Keycloak và Kafka (DV-02-05). Bảng của Keycloak không được sửa nên không dùng outbox trong
 * CSDL {@code iam}; thay vào đó:
 * <ol>
 *   <li>{@link #offer} chỉ đặt thông điệp vào hàng đợi bộ nhớ có giới hạn, không chặn luồng đăng nhập;
 *   <li>một luồng nền ghi thông điệp xuống tệp nhật ký trên đĩa ({@code queue.log}, mỗi bản ghi có độ dài và CRC32,
 *       {@code force} sau mỗi đợt) rồi gửi Kafka theo lô;
 *   <li>vị trí đã gửi lưu ở {@code queue.pos}; Kafka lỗi thì giữ trên đĩa và thử lại với thời gian chờ tăng dần, khởi
 *       động lại thì gửi tiếp từ vị trí đã lưu (ít nhất một lần, consumer khử trùng bằng tiêu đề {@code id}).
 * </ol>
 * Khi đã gửi hết, tệp nhật ký được cắt về rỗng. Bản ghi dở dang cuối tệp (tiến trình dừng giữa chừng) bị bỏ khi mở lại.
 */
final class DurableQueue implements AutoCloseable {

  /** Nơi nhận lô thông điệp; ném lỗi khi chưa gửi được để giữ lại trên đĩa. */
  interface Sink {
    void send(List<Outgoing> batch) throws Exception;
  }

  private static final Logger LOG = Logger.getLogger(DurableQueue.class);
  private static final int BATCH = 500;
  private static final long MAX_BACKOFF_MS = 30_000;

  private final Path log;
  private final Path pos;
  private final Sink sink;
  private final BlockingQueue<Outgoing> memory;
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong sent = new AtomicLong();
  private final FileChannel channel;
  private final Thread worker;
  private volatile boolean running = true;
  private long readPosition;
  private long backoffMs;
  private long nextAttempt;

  DurableQueue(Path dir, Sink sink, int memoryCapacity) {
    try {
      Files.createDirectories(dir);
      this.log = dir.resolve("queue.log");
      this.pos = dir.resolve("queue.pos");
      this.sink = sink;
      this.memory = new ArrayBlockingQueue<>(memoryCapacity);
      this.channel = FileChannel.open(log, StandardOpenOption.CREATE, StandardOpenOption.READ,
          StandardOpenOption.WRITE);
      this.readPosition = Files.exists(pos) ? Long.parseLong(Files.readString(pos).trim()) : 0L;
      recover();
    } catch (IOException e) {
      throw new UncheckedIOException("Không mở được hàng đợi sự kiện tại " + dir, e);
    }
    this.worker = new Thread(this::loop, "mission-events-queue");
    this.worker.setDaemon(true);
    this.worker.start();
  }

  /** Không chặn: hàng đợi bộ nhớ đầy thì bỏ thông điệp và đếm (đăng nhập không bao giờ chờ Kafka). */
  boolean offer(Outgoing message) {
    if (memory.offer(message)) {
      return true;
    }
    if (dropped.incrementAndGet() % 1000 == 1) {
      LOG.warnf("Hàng đợi sự kiện trong bộ nhớ đầy, đã bỏ %d thông điệp", dropped.get());
    }
    return false;
  }

  long sentCount() {
    return sent.get();
  }

  long droppedCount() {
    return dropped.get();
  }

  /** Số byte còn chờ gửi trên đĩa. */
  synchronized long pendingBytes() throws IOException {
    return channel.size() - readPosition;
  }

  private void loop() {
    List<Outgoing> drained = new ArrayList<>();
    while (running || !memory.isEmpty()) {
      try {
        Outgoing first = memory.poll(200, TimeUnit.MILLISECONDS);
        if (first != null) {
          drained.add(first);
          memory.drainTo(drained, BATCH * 4);
          append(drained);
          drained.clear();
        }
        if (running) {
          ship();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException | IOException e) {
        LOG.warnf("Lỗi hàng đợi sự kiện: %s", e.getClass().getSimpleName());
      }
    }
  }

  private synchronized void append(List<Outgoing> messages) throws IOException {
    channel.position(channel.size());
    for (Outgoing m : messages) {
      byte[] data = m.encode();
      CRC32 crc = new CRC32();
      crc.update(data);
      ByteBuffer buf = ByteBuffer.allocate(8 + data.length);
      buf.putInt(data.length).putInt((int) crc.getValue()).put(data).flip();
      while (buf.hasRemaining()) {
        channel.write(buf);
      }
    }
    channel.force(false);
  }

  private synchronized void ship() throws IOException {
    long now = System.currentTimeMillis();
    if (now < nextAttempt) {
      return;
    }
    while (true) {
      List<Outgoing> batch = new ArrayList<>();
      long end = read(readPosition, batch, BATCH);
      if (batch.isEmpty()) {
        compact();
        return;
      }
      try {
        sink.send(batch);
      } catch (Exception e) {
        backoffMs = backoffMs == 0 ? 500 : Math.min(MAX_BACKOFF_MS, backoffMs * 2);
        nextAttempt = System.currentTimeMillis() + backoffMs;
        LOG.debugf("Chưa gửi được %d sự kiện, thử lại sau %d ms: %s", batch.size(), backoffMs,
            e.getClass().getSimpleName());
        return;
      }
      backoffMs = 0;
      sent.addAndGet(batch.size());
      readPosition = end;
      savePosition();
    }
  }

  /** Đọc tối đa {@code max} bản ghi từ {@code from}; trả vị trí sau bản ghi cuối đọc được. */
  private long read(long from, List<Outgoing> out, int max) throws IOException {
    long position = from;
    long size = channel.size();
    ByteBuffer head = ByteBuffer.allocate(8);
    while (out.size() < max && position + 8 <= size) {
      head.clear();
      channel.read(head, position);
      head.flip();
      int length = head.getInt();
      int crc = head.getInt();
      if (length < 0 || position + 8 + length > size) {
        break;
      }
      ByteBuffer body = ByteBuffer.allocate(length);
      channel.read(body, position + 8);
      CRC32 check = new CRC32();
      check.update(body.array());
      if ((int) check.getValue() != crc) {
        break;
      }
      out.add(Outgoing.decode(body.array()));
      position += 8 + length;
    }
    return position;
  }

  /** Bỏ bản ghi dở dang cuối tệp sau khi tiến trình dừng đột ngột. */
  private void recover() throws IOException {
    long size = channel.size();
    if (readPosition > size) {
      readPosition = 0;
    }
    long end = readPosition;
    List<Outgoing> scratch = new ArrayList<>();
    while (true) {
      scratch.clear();
      long next = read(end, scratch, BATCH);
      if (next == end) {
        break;
      }
      end = next;
    }
    if (end < size) {
      LOG.warnf("Bỏ %d byte dở dang cuối hàng đợi sự kiện", size - end);
      channel.truncate(end);
    }
  }

  private void compact() throws IOException {
    if (readPosition > 0 && readPosition == channel.size()) {
      channel.truncate(0);
      channel.force(false);
      readPosition = 0;
      savePosition();
    }
  }

  private void savePosition() throws IOException {
    Path tmp = pos.resolveSibling("queue.pos.tmp");
    Files.writeString(tmp, Long.toString(readPosition), StandardCharsets.US_ASCII);
    Files.move(tmp, pos, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  /** Dừng luồng nền sau khi đã ghi hết hàng đợi bộ nhớ xuống đĩa (không chờ Kafka). */
  @Override
  public void close() {
    running = false;
    try {
      worker.join(5_000);
      channel.close();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      LOG.warnf("Đóng hàng đợi sự kiện lỗi: %s", e.getClass().getSimpleName());
    }
  }
}
