# Ảnh thử INF-04: ba biến thể dựng bằng --build-arg (bản ổn định 1, bản ổn định 2, bản lỗi 5%).
# Kéo từ bản sao mirror.gcr.io của ảnh chính thức (Docker Hub và ECR Public hay giới hạn lượt kéo).
FROM mirror.gcr.io/library/python:3.13-alpine
ARG TI_LE_LOI=0
ARG PHIEN_BAN=1
ENV TI_LE_LOI=${TI_LE_LOI} PHIEN_BAN=${PHIEN_BAN} PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY app.py /app/app.py
USER 10001
ENTRYPOINT ["python3", "/app/app.py"]
