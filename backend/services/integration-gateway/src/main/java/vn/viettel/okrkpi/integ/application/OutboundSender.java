package vn.viettel.okrkpi.integ.application;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.integ.domain.Connector;

/**
 * SPI adapter chiều ra của một kết nối: gửi thông điệp; trả mã xác nhận khi bên nhận xác nhận ngay,
 * rỗng khi bên nhận sẽ xác nhận sau (gọi {@link OutboundPipeline#acknowledge}).
 */
public interface OutboundSender {

  String connectorCode();

  Optional<String> send(Connector connector, UUID messageId, String messageType, byte[] payload)
      throws IOException;
}
