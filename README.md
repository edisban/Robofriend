# RoboFriend — an AI companion that remembers

RoboFriend is a full-stack AI chat app that talks like a friend, remembers your life, and keeps your
reminders and lists. Chat in Greek or English, by text or voice.

## Demo

https://github.com/user-attachments/assets/1f5f3eb1-01d7-4df6-9432-7f546a30ce33

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4-6DB33F?logo=springboot&logoColor=white)
![React](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=black)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16_+_pgvector-4169E1?logo=postgresql&logoColor=white)
![FastAPI](https://img.shields.io/badge/FastAPI-embeddings-009688?logo=fastapi&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)

## Features

- **Streaming chat** — replies appear word by word (Server-Sent Events), with regenerate, edit and delete.
- **Memory** — remembers facts with the date it learned them (semantic search with pgvector); stats like
  "squat max" are kept up to date instead of duplicated.
- **Reminders** — "remind me tomorrow at 9 to call mom" becomes a reminder in your timezone; when it's due,
  RoboFriend sends you a chat message.
- **Lists** — "add milk and eggs to my shopping list", then "what's on my list?"; also to-do, uni, work lists.
- **Daily check-in** — the first time you open the app each day, RoboFriend messages you if something
  matters today (an exam, a birthday, a follow-up).
- **Voice mode** — speak and listen, with an animated Siri-style wave.
- **Greek and English** — replies in the language of each message.
- **Glassmorphism UI** — animated intro, light/dark themes, mobile layout.

## Architecture

```
Browser (React)  →  nginx  →  Spring Boot API  →  Groq API (LLM, streamed)
                                     │       →  Embedding service (Python, text → vector; no database)
                                     ↓
                              PostgreSQL + pgvector
```

| Part | Tech | Role |
| --- | --- | --- |
| `bot-front/` | React 19, TypeScript, Vite, MUI | The app; served by nginx, which also proxies `/api` |
| `bot-back/` | Spring Boot 4, Java 17, Spring Security, JPA, Flyway | API, auth, prompt building, streaming, reminders; the only service that uses the database |
| `ai-memory-service/` | Python, FastAPI, sentence-transformers | Stateless: turns text into 384-dim embeddings (multilingual model) |
| PostgreSQL | 16 + pgvector | All data; memories are `vector(384)` with an HNSW index |

**Memory search.** The backend asks the embedding service for vectors and stores them itself. It finds a
user's closest facts with pgvector's cosine distance (`<=>`). A user has relatively few facts, so the
planner reads them through the `user_id` index and ranks them exactly. The HNSW index takes over if one
user's facts make up a large share of the table; the search turns on `hnsw.iterative_scan` so the user
filter doesn't cut the results short. A fact saved while the embedding service is down is kept and gets
its vector in the background. When the embedding model changes, all facts are embedded again.

How a message flows: the browser sends it to `POST /api/chat/stream`; the backend loads related memories,
stats, reminders, lists and recent messages, builds one prompt and streams Groq's answer back as SSE.
The model's output ends with a hidden JSON part (after `###META###`) listing facts to remember, a reminder
and list changes, which the backend saves.

### Why one model call with a hidden JSON part

The reply and the data to save come from **one** streamed call. The backend shows the text before the
marker as it arrives and never sends what follows it.

That JSON is model output, and the model can be talked into writing anything, so it's treated as untrusted:

- Only known fields with the right types are read. Lists and strings have size limits.
- Reminders need a real date with a UTC offset, from now to two years ahead.
- If the marker appears more than once, only the last block counts.
- Anything the user wrote (their message, the history, memories, list items) has the marker broken up
  before it goes into the prompt.
- A bad block is ignored and the chat goes on.

At worst, someone who tricks the model can change only their own facts, reminders and lists.

Alternatives we looked at:

| Option | Gain | Why not (yet) |
| --- | --- | --- |
| Tool calling (`tools` in the Groq API) | The data comes in its own channel, with typed arguments | Mixing streamed text with tool calls differs between models, and the client gets more complex. This is the first thing to switch to if the marker causes trouble. |
| Structured outputs / JSON mode | The reply is always valid JSON | The whole answer becomes JSON, so the text can't be streamed word by word |
| A second extraction call | Clean separation; a smaller, cheaper model could do it | Twice the calls (cost, rate limits) and more delay before a reminder is saved |

## Run it

Requirements: Docker with Docker Compose, and a [Groq](https://console.groq.com) API key.

1. Create `.env` in the project root (see `.env.example`):

   ```
   GROQ_API_KEY=your_groq_api_key
   JWT_SECRET=<output of: openssl rand -base64 48>
   MEMORY_SERVICE_TOKEN=any-shared-secret
   ```

2. Start everything:

   ```
   docker compose up -d --build
   ```

3. Open **http://localhost:8081**.

The backend API is also reachable at `http://localhost:8080` and PostgreSQL at `localhost:5434`.

### Frontend development

With the Docker stack running, `cd bot-front && npm install && npm run dev` serves the app with hot reload
on http://localhost:5174; it proxies `/api` to the backend on port 8080.

## Security

- Passwords hashed with BCrypt; sign-up requires 8–72 characters; failed logins are rate-limited.
- Sessions live in **HttpOnly, Secure, SameSite=Strict cookies**, so page scripts never see a token:
  - `rf_access`: a JWT valid for 15 minutes
  - `rf_refresh`: a random token valid for 30 days, stored hashed, **rotated on every use**; reusing an old one
    ends all of that user's sessions
- CSRF protection on every state-changing request (`XSRF-TOKEN` cookie echoed as `X-XSRF-TOKEN`).
- Every endpoint takes the user from the session, never from the request, so users can't see each other's data.
- The backend refuses to start without a strong `JWT_SECRET`.
- Personal content is never written to the INFO logs.

## Database

Flyway migrations in `bot-back/src/main/resources/db/migration` create and change the schema; Hibernate only
validates it.

| Table | Holds |
| --- | --- |
| `users` | accounts (BCrypt password hashes) |
| `chat_messages` | the conversation |
| `memory_vault` | remembered facts with their embeddings (`vector(384)`) and the date learned |
| `embedding_meta` | which embedding model made the stored vectors |
| `reminders` | reminders (PENDING → SENT → DONE) |
| `list_items` | shopping / to-do / other list entries |
| `refresh_tokens` | sign-in sessions (hashed tokens) |

## Tests

```
cd bot-back && ./mvnw test
```

Unit tests, web-layer security tests (`@WebMvcTest`) and integration tests against a real PostgreSQL in
Docker (Testcontainers; skipped when Docker isn't running). CI runs them on every push, then lints and
builds the frontend.

Health check: `GET /actuator/health` (includes the database).
