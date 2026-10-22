"""Cấu hình ai-gateway đọc từ biến môi trường (TL-04 values SVC-14). Không có giá trị bí mật mặc định."""

from __future__ import annotations

import os
from dataclasses import dataclass, field


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def _int(name: str, default: int) -> int:
    raw = _env(name)
    return int(raw) if raw else default


def _float(name: str, default: float) -> float:
    raw = _env(name)
    return float(raw) if raw else default


@dataclass(frozen=True)
class Settings:
    database_url: str = ""
    # Keycloak: kiểm JWT người dùng qua JWKS và đổi token (RFC 8693) sang client chỉ đọc.
    oidc_issuer: str = ""
    oidc_jwks_url: str = ""
    oidc_audience: str = ""
    oidc_token_url: str = ""
    exchange_client_id: str = "ai-gateway"
    exchange_client_secret: str = field(default="", repr=False)
    exchange_audience: str = "mission-core"
    exchange_scope: str = "ai_readonly"
    exchange_role: str = "ai_readonly"
    # OPA cùng pod (ADR-21).
    opa_url: str = "http://127.0.0.1:8181"
    opa_timeout_s: float = 0.5
    # API lõi đọc dưới danh tính người dùng.
    core_base_url: str = ""
    core_timeout_s: float = 5.0
    # Mô hình: "mock" là bản giả lập (YC-PT-13); "openai" là API tương thích OpenAI của vLLM.
    llm_mode: str = "mock"
    llm_base_url: str = ""
    llm_model: str = "viettel-llm-vi"
    llm_api_key: str = field(default="", repr=False)
    llm_timeout_s: float = 30.0
    llm_max_concurrency: int = 4
    llm_max_waiting: int = 32
    # Thời gian chờ đồng bộ trước khi chuyển sang 202 (NFR-03 cho phần nhận yêu cầu).
    llm_sync_wait_s: float = 20.0
    deferred_max: int = 256
    # Mô hình nhúng 1.024 chiều: "mock" là HashEmbedder, "openai" là /v1/embeddings tại embed_base_url.
    embed_mode: str = "mock"
    embed_base_url: str = ""
    embed_model: str = "viettel-embed-vi"
    kafka_brokers: str = ""
    cell_code: str = ""
    # OCR (DV-14-05): "mock" là bản giả lập, "viettel" là dịch vụ OCR của Viettel AI tại ocr_base_url.
    ocr_mode: str = "mock"
    ocr_base_url: str = ""
    ocr_model: str = "viettel-ocr"
    ocr_api_key: str = field(default="", repr=False)
    ocr_timeout_s: float = 30.0
    # Kho đối tượng nội bộ cho /ai/ocr (evidence-service gửi objectKey; đọc qua cổng nội bộ trong lưới mTLS).
    object_base_url: str = ""
    ocr_clients: tuple[str, ...] = ("evidence-service", "search-indexer")

    @staticmethod
    def from_env() -> Settings:
        issuer = _env("OIDC_ISSUER")
        return Settings(
            database_url=_env("DATABASE_URL"),
            oidc_issuer=issuer,
            oidc_jwks_url=_env("OIDC_JWKS_URL", f"{issuer}/protocol/openid-connect/certs" if issuer else ""),
            oidc_audience=_env("OIDC_AUDIENCE"),
            oidc_token_url=_env("OIDC_TOKEN_URL", f"{issuer}/protocol/openid-connect/token" if issuer else ""),
            exchange_client_id=_env("AI_EXCHANGE_CLIENT_ID", "ai-gateway"),
            exchange_client_secret=_env("AI_EXCHANGE_CLIENT_SECRET"),
            exchange_audience=_env("AI_EXCHANGE_AUDIENCE", "mission-core"),
            exchange_scope=_env("AI_EXCHANGE_SCOPE", "ai_readonly"),
            exchange_role=_env("AI_EXCHANGE_ROLE", "ai_readonly"),
            opa_url=_env("OPA_URL", "http://127.0.0.1:8181"),
            opa_timeout_s=_float("OPA_TIMEOUT_SECONDS", 0.5),
            core_base_url=_env("CORE_BASE_URL"),
            core_timeout_s=_float("CORE_TIMEOUT_SECONDS", 5.0),
            llm_mode=_env("LLM_MODE", "mock"),
            llm_base_url=_env("LLM_BASE_URL"),
            llm_model=_env("LLM_MODEL", "viettel-llm-vi"),
            llm_api_key=_env("LLM_API_KEY"),
            llm_timeout_s=_float("LLM_TIMEOUT_SECONDS", 30.0),
            llm_max_concurrency=_int("LLM_MAX_CONCURRENCY", 4),
            llm_max_waiting=_int("LLM_MAX_WAITING", 32),
            llm_sync_wait_s=_float("LLM_SYNC_WAIT_SECONDS", 20.0),
            deferred_max=_int("AI_DEFERRED_MAX", 256),
            embed_mode=_env("EMBED_MODE", "mock"),
            embed_base_url=_env("EMBED_BASE_URL"),
            embed_model=_env("EMBED_MODEL", "viettel-embed-vi"),
            kafka_brokers=_env("KAFKA_BROKERS"),
            cell_code=_env("CELL_CODE"),
            ocr_mode=_env("OCR_MODE", "mock"),
            ocr_base_url=_env("OCR_BASE_URL"),
            ocr_model=_env("OCR_MODEL", "viettel-ocr"),
            ocr_api_key=_env("OCR_API_KEY"),
            ocr_timeout_s=_float("OCR_TIMEOUT_SECONDS", 30.0),
            object_base_url=_env("OBJECT_BASE_URL"),
            ocr_clients=tuple(x.strip() for x in _env("OCR_CLIENTS", "evidence-service,search-indexer").split(",")
                              if x.strip()),
        )
