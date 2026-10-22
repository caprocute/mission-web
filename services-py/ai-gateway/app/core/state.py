"""Các thành phần dùng chung của tiến trình, dựng một lần khi khởi động."""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING, Any

from app.core.auth import JwtVerifier
from app.core.core_client import CoreClient
from app.core.opa import OpaClient
from app.core.pipeline import Gateway
from app.rag.ingest import Indexer
from app.rag.retriever import Retriever
from app.store.idempotency import IdempotencyStore
from app.store.suggestions import SuggestionStore

if TYPE_CHECKING:
    from app.assist.evidence import EvidenceOcr
    from app.assist.service import AssistService
    from app.speech.service import SpeechService


@dataclass
class State:
    verifier: JwtVerifier
    opa: OpaClient
    core: CoreClient
    gateway: Gateway
    store: SuggestionStore
    pool: Any
    retriever: Retriever
    indexer: Indexer
    idempotency: IdempotencyStore
    assist: AssistService
    evidence: EvidenceOcr
    speech: SpeechService
    ocr_clients: tuple[str, ...] = ()
