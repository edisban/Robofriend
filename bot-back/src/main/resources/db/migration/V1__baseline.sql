-- Baseline schema. Existing databases (created before Flyway) are marked as
-- already at this version; fresh databases are created from it.

-- Created by migrate.sh with superuser rights; this is a no-op when it already exists.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    last_checkin_on DATE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE chat_messages (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    sender VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_chat_messages_user_date ON chat_messages(user_id, created_at);

CREATE TABLE memory_vault (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    fact TEXT NOT NULL,
    category VARCHAR(50) DEFAULT 'General',
    fact_key VARCHAR(80),
    -- 384-dim sentence embedding, stored as text and cast to vector when searching
    embedding TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Which embedding model the stored vectors come from (the memory service re-embeds when it changes).
CREATE TABLE embedding_meta (
    model TEXT NOT NULL
);

CREATE TABLE reminders (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING',
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_reminders_user_schedule ON reminders(user_id, scheduled_at);

CREATE TABLE list_items (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    list_name VARCHAR(60) NOT NULL,
    text VARCHAR(300) NOT NULL,
    done BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    done_at TIMESTAMP WITH TIME ZONE
);
