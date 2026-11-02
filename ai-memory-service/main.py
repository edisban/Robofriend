import os
import logging
from datetime import datetime
from typing import List, Optional

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel
import numpy as np
from sentence_transformers import SentenceTransformer
import psycopg2
from pgvector.psycopg2 import register_vector

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="AI Memory Service", version="1.0.0")

DB_HOST = os.getenv("DB_HOST", "localhost")
DB_PORT = os.getenv("DB_PORT", "5434")
DB_NAME = os.getenv("DB_NAME", "botdb")
DB_USER = os.getenv("DB_USER", "botuser")
DB_PASSWORD = os.getenv("DB_PASSWORD", "botpass")
SERVICE_TOKEN = os.getenv("MEMORY_SERVICE_TOKEN", "local-dev-token")

# Multilingual so Greek (and mixed Greek/English) memories search well.
MODEL_NAME = os.getenv("EMBEDDING_MODEL", "paraphrase-multilingual-MiniLM-L12-v2")
EMBEDDING_DIM = 384

model = None


class SearchRequest(BaseModel):
    query: str
    user_id: Optional[int] = None
    limit: Optional[int] = 5


class SearchResult(BaseModel):
    id: int
    user_id: int
    fact: str
    category: str
    created_at: datetime
    similarity: float


def require_service_token(x_service_token: Optional[str] = Header(default=None)) -> None:
    if not SERVICE_TOKEN:
        raise HTTPException(status_code=503, detail="Service token is not configured")
    if x_service_token != SERVICE_TOKEN:
        raise HTTPException(status_code=401, detail="Invalid service token")


def get_db_connection():
    conn = psycopg2.connect(
        host=DB_HOST, port=DB_PORT, dbname=DB_NAME, user=DB_USER, password=DB_PASSWORD
    )
    register_vector(conn)
    return conn


def encode(text: str) -> list:
    return model.encode(text).tolist()


def reembed_if_model_changed(conn) -> None:
    """Vectors from a different model live in a different space, so re-encode
    every stored fact once whenever the configured model changes."""
    with conn.cursor() as cur:
        cur.execute("SELECT model FROM embedding_meta LIMIT 1")
        row = cur.fetchone()
        if row and row[0] == MODEL_NAME:
            return

        cur.execute("SELECT id, fact FROM memory_vault")
        rows = cur.fetchall()
        logger.info("Embedding model changed to %s; re-embedding %d facts", MODEL_NAME, len(rows))
        for fact_id, fact in rows:
            cur.execute(
                "UPDATE memory_vault SET embedding = %s WHERE id = %s",
                (str(encode(fact)), fact_id),
            )
        cur.execute("DELETE FROM embedding_meta")
        cur.execute("INSERT INTO embedding_meta (model) VALUES (%s)", (MODEL_NAME,))
    conn.commit()


@app.on_event("startup")
def startup_event():
    global model
    logger.info("Loading embedding model %s", MODEL_NAME)
    model = SentenceTransformer(MODEL_NAME)
    logger.info("Embedding model ready")
    try:
        with get_db_connection() as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT 1 FROM pg_extension WHERE extname='vector'")
                if not cur.fetchone():
                    raise RuntimeError("pgvector extension is not installed")
            reembed_if_model_changed(conn)
        logger.info("Database connection verified")
    except Exception as e:
        logger.error("Cannot reach the database: %s", e)
        raise


@app.post("/search", response_model=List[SearchResult], dependencies=[Depends(require_service_token)])
def semantic_search(request: SearchRequest):
    try:
        query_embedding = np.array(encode(request.query), dtype=np.float32)
        with get_db_connection() as conn:
            with conn.cursor() as cur:
                query = """
                    SELECT id, user_id, fact, category, created_at,
                           1 - (CAST(embedding AS vector) <=> %s::vector) AS similarity
                    FROM memory_vault
                    WHERE embedding IS NOT NULL
                      AND vector_dims(CAST(embedding AS vector)) = %s
                """
                params = [query_embedding, EMBEDDING_DIM]
                if request.user_id is not None:
                    query += " AND user_id = %s"
                    params.append(request.user_id)
                query += " ORDER BY CAST(embedding AS vector) <=> %s::vector LIMIT %s"
                params.append(query_embedding)
                params.append(request.limit or 5)
                cur.execute(query, params)
                rows = cur.fetchall()
                results = []
                for row in rows:
                    results.append(
                        SearchResult(
                            id=row[0],
                            user_id=row[1],
                            fact=row[2],
                            category=row[3],
                            created_at=row[4],
                            similarity=float(row[5]),
                        )
                    )
                return results
    except Exception as e:
        logger.exception("Search error")
        raise HTTPException(status_code=500, detail="Search failed") from e


@app.post("/ingest", dependencies=[Depends(require_service_token)])
def ingest_message(user_id: int, message: str, category: str = "General", key: Optional[str] = None):
    """Stores a fact. A `key` (e.g. "squat_max") marks a value that changes over time:
    the new fact replaces any earlier one with the same key."""
    try:
        embedding = encode(message)
        with get_db_connection() as conn:
            with conn.cursor() as cur:
                if key:
                    cur.execute(
                        "DELETE FROM memory_vault WHERE user_id = %s AND fact_key = %s",
                        (user_id, key),
                    )
                cur.execute(
                    """
                    INSERT INTO memory_vault (user_id, fact, category, embedding, fact_key)
                    VALUES (%s, %s, %s, %s, %s)
                    """,
                    (user_id, message, category, str(embedding), key or None),
                )
                conn.commit()
        return {"status": "success", "message": "Ingested"}
    except Exception as e:
        logger.exception("Ingest error")
        raise HTTPException(status_code=500, detail="Ingest failed") from e


@app.get("/health", dependencies=[Depends(require_service_token)])
def health():
    try:
        with get_db_connection() as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT 1")
                cur.fetchone()
        return {"status": "ok", "model": MODEL_NAME, "dimension": EMBEDDING_DIM, "database": "ok"}
    except Exception as e:
        logger.error("Health check failed: %s", e)
        raise HTTPException(status_code=503, detail="Database unreachable") from e
