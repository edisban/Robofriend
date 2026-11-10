-- Embeddings were stored as text and cast to vector on every search, so no index could be used.
-- Make the column a real vector(384) and index it for cosine distance.

-- Text that isn't a 384-number vector can't be converted; clear it so the backend re-embeds
-- those facts (it fills in every NULL embedding in the background). CASE keeps the cast from
-- running on text that isn't shaped like a vector.
UPDATE memory_vault
SET embedding = NULL
WHERE embedding IS NOT NULL
  AND CASE
          WHEN embedding ~ '^\s*\[\s*[-+0-9.eE]+(\s*,\s*[-+0-9.eE]+)*\s*\]\s*$'
              THEN vector_dims(embedding::vector) <> 384
          ELSE TRUE
      END;

ALTER TABLE memory_vault
    ALTER COLUMN embedding TYPE vector(384) USING embedding::vector(384);

-- Nearest-neighbour search. The search runs with hnsw.iterative_scan, so filtering by user_id
-- after the index scan still returns that user's closest facts.
CREATE INDEX idx_memory_vault_embedding ON memory_vault USING hnsw (embedding vector_cosine_ops);

-- Every other memory query is per user (profile facts, recent facts, replacing a keyed fact).
CREATE INDEX idx_memory_vault_user ON memory_vault(user_id, created_at);
