# syntax=docker/dockerfile:1
# Kafka Connect của dev (Debezium 3.0) kèm plugin giả danh TenantPseudonymize (analytics/cdc/smt, DV-15-02) mà
# connector CDC phân tích dùng. Ngữ cảnh dựng là analytics/cdc/smt; plugin chỉ phụ thuộc connect-api và
# kafka-clients nên biên dịch thẳng bằng javac với thư viện có sẵn trong ảnh Connect, không cần Maven hay mạng.
ARG DEBEZIUM_IMAGE=quay.io/debezium/connect:3.0.0.Final
FROM ${DEBEZIUM_IMAGE} AS connect

FROM eclipse-temurin:21-jdk-jammy AS smt
COPY --from=connect /kafka/libs/ /kafka-libs/
COPY src/main/java/ /src/
RUN find /src -name '*.java' > /tmp/nguon.txt \
 && javac --release 17 -encoding UTF-8 -cp '/kafka-libs/*' -d /out @/tmp/nguon.txt \
 && jar cf /analytics-cdc-smt.jar -C /out .

FROM ${DEBEZIUM_IMAGE}
COPY --from=smt /analytics-cdc-smt.jar /kafka/connect/mission-analytics-cdc-smt/analytics-cdc-smt.jar
