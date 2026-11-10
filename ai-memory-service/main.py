"""Embedding service: turns text into 384-dimension sentence vectors.

It is stateless and has no database access. The Spring backend owns the memory_vault table:
it stores the vectors, searches them with pgvector and re-embeds facts when the model changes.
"""

import logging
import os
from contextlib import asynccontextmanager
from typing import List, Optional

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from sentence_transformers import SentenceTransformer

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

SERVICE_TOKEN = os.getenv("MEMORY_SERVICE_TOKEN", "local-dev-token")

# Multilingual so Greek (and mixed Greek/English) memories search well.
MODEL_NAME = os.getenv("EMBEDDING_MODEL", "paraphrase-multilingual-MiniLM-L12-v2")
EMBEDDING_DIM = 384
MAX_TEXTS = 64
MAX_TEXT_LENGTH = 2000

model: Optional[SentenceTransformer] = None


@asynccontextmanager
async def lifespan(_: FastAPI):
    global model
    logger.info("Loading embedding model %s", MODEL_NAME)
    model = SentenceTransformer(MODEL_NAME)
    dimension = model.get_sentence_embedding_dimension()
    if dimension != EMBEDDING_DIM:
        raise RuntimeError(f"Model {MODEL_NAME} makes {dimension}-dim vectors; the database expects {EMBEDDING_DIM}")
    logger.info("Embedding model ready")
    yield


app = FastAPI(title="RoboFriend embedding service", version="2.0.0", lifespan=lifespan)


class EmbedRequest(BaseModel):
    texts: List[str] = Field(min_length=1, max_length=MAX_TEXTS)


class EmbedResponse(BaseModel):
    model: str
    dimension: int
    embeddings: List[List[float]]


def require_service_token(x_service_token: Optional[str] = Header(default=None)) -> None:
    if not SERVICE_TOKEN:
        raise HTTPException(status_code=503, detail="Service token is not configured")
    if x_service_token != SERVICE_TOKEN:
        raise HTTPException(status_code=401, detail="Invalid service token")


@app.post("/embed", response_model=EmbedResponse, dependencies=[Depends(require_service_token)])
def embed(request: EmbedRequest) -> EmbedResponse:
    texts = [text[:MAX_TEXT_LENGTH] for text in request.texts]
    vectors = model.encode(texts, convert_to_numpy=True)
    return EmbedResponse(model=MODEL_NAME, dimension=EMBEDDING_DIM, embeddings=vectors.tolist())


@app.get("/health")
def health():
    """Ready once the model is loaded. Only reachable inside the Docker network."""
    if model is None:
        raise HTTPException(status_code=503, detail="Model is loading")
    return {"status": "ok", "model": MODEL_NAME, "dimension": EMBEDDING_DIM}
