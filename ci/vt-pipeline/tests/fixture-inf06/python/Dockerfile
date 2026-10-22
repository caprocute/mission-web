FROM mirror.gcr.io/library/python:3.12-slim@sha256:cb33f7f771ecec2ef1225cf39b159591a040391bd73e4c01f00fdfc4c47611d2
WORKDIR /app
COPY requirements.txt app.py ./
RUN pip install --no-cache-dir --require-hashes -r requirements.txt
USER 10001:10001
ENTRYPOINT ["python", "-c", "import app; print(app.xep_loai(95))"]
