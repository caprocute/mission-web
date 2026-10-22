# Ảnh máy chủ Git thử (INF-04): ảnh alpine/git (bản sao mirror.gcr.io, ghim digest) cộng tệp chạy Go dựng sẵn.
FROM mirror.gcr.io/alpine/git@sha256:03228d7ded483b0b3e55e1de7fa660d70d3e1bb432fa7021d29e192640de11aa
COPY kho-git /usr/local/bin/kho-git
USER 10001
ENTRYPOINT ["/usr/local/bin/kho-git"]
