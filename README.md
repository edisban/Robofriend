# RoboFriend - Production-Ready Full-Stack AI Companion

RoboFriend is a full-stack AI companion combining conversational chat, long-term semantic memory, and natural-language reminders. It pairs a Spring Boot backend with a React frontend, backed by PostgreSQL + pgvector, with a Python ML microservice for sentence embeddings, and powered by the Groq API.

## Tech Stack

![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-6DB33F?logo=springboot&logoColor=white)
![React](https://img.shields.io/badge/React-18-61DAFB?logo=react&logoColor=black)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)
![pgvector](https://img.shields.io/badge/pgvector-enabled-336791)
![Groq API](https://img.shields.io/badge/Groq_API-LLM-4285F4?logo=google&logoColor=white)
![FastAPI](https://img.shields.io/badge/FastAPI-Python_ML-009688?logo=fastapi&logoColor=white)

## Key Features

- Context-aware AI chat with long-term memory retrieval via pgvector embeddings and the Groq API.
- Smart reminders system with local natural-language parsing (no LLM round-trip for scheduling).
- Voice conversation & speech-to-text: real-time hands-free voice interaction and speech recognition.
- Interactive audio visualizer UI: animated dynamic sound wave / waveform overlay visualizing active voice listening and AI response playback.
- Glassmorphism chat UI that feels like messaging a friend: grouped bubbles, typing indicator, quick replies, light/dark themes, mobile reminders drawer.
- Containerized full stack via Docker Compose (Postgres, backend, frontend, memory service).
- Per-user data isolation: every endpoint resolves the user from the JWT, never from the request.

## Architecture

```
┌─────────────┐     ┌──────────────┐     ┌──────────────────┐
│  React SPA  │────▶│ Spring Boot  │────▶│  PostgreSQL +    │
│  (Vite/TS)  │     │   Backend    │     │     pgvector     │
└─────────────┘     └──────┬───────┘     └────────┬─────────┘
                           │                      │
                           ▼                      ▼
                    ┌──────────────┐     ┌──────────────────┐
                    │  Groq API  │     │ Python ML Service│
                    │  (LLM/Chat)  │     │ (sentence-transf)│
                    └──────────────┘     └──────────────────┘
```

- **Backend**: Java 17, Spring Boot, Spring Data JPA, Lombok, PostgreSQL + pgvector.
- **Frontend**: React, TypeScript, Vite, Tailwind CSS / MUI, with an interactive voice overlay (audio visualizer).
- **AI & ML Integration**: Groq API for LLM responses; Python FastAPI service (`paraphrase-multilingual-MiniLM-L12-v2` via sentence-transformers, so Greek works too) generates 384-dim embeddings stored in pgvector for semantic memory search.
- **Python ML Service**: FastAPI app exposing `/embed`, `/search`, `/ingest`, `/health`. Uses `sentence-transformers` model `paraphrase-multilingual-MiniLM-L12-v2` (384 dimensions) and `pgvector` for cosine-similarity search over `memory_vault`.

## Getting Started

### Prerequisites

- Java 17
- Node.js 18+
- Python 3.10+ (for memory service)
- Docker & Docker Compose

### Environment Setup

Create a `.env` file in the project root:

```
GROQ_API_KEY=your_groq_api_key
JWT_SECRET=change-me-in-production-32-chars-min!
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5434/botdb
SPRING_DATASOURCE_USERNAME=botuser
SPRING_DATASOURCE_PASSWORD=botpass
MEMORY_SERVICE_TOKEN=local-dev-token
```

### Run with Docker Compose

```
docker compose up --build
```

Frontend: `http://localhost:8081/` — Backend API: `http://localhost:8080` — Memory service: `http://localhost:8000`.

### Run Locally

Backend:
```
cd bot-back
./mvnw spring-boot:run
```

Frontend:
```
cd bot-front
npm install
npm run dev
```

Memory service:
```
cd ai-memory-service
pip install -r requirements.txt
uvicorn main:app --port 8000
```

## Authentication

- Passwords are hashed with BCrypt; sign-up requires 8-72 characters. Failed logins are rate-limited.
- The session lives in two **HttpOnly, Secure, SameSite=Strict cookies**, so page scripts never see a token:
  - `rf_access`: a JWT valid for 15 minutes, sent with every API call
  - `rf_refresh`: a random token valid for 30 days, only sent to `/api/auth`, stored hashed in the database and **rotated on every use**; reusing an old one (a stolen copy) ends all of that user's sessions
- The app renews the access cookie automatically (`POST /api/auth/refresh`) and asks `GET /api/auth/me` who is signed in. `POST /api/auth/logout` revokes the session.
- State-changing requests carry a CSRF token (`XSRF-TOKEN` cookie echoed as `X-XSRF-TOKEN`).
- `JWT_SECRET` is required (at least 32 characters); generate one with `openssl rand -base64 48`.

## API & Database Structure

Entities:

- `User` — account identity (username, password hash).
- `ChatMessage` — conversation history (user/bot messages per user).
- `MemoryVault` — long-term memory facts with vector embeddings.
- `Reminder` — scheduled reminders with status (PENDING/DONE).

Health check: `GET /actuator/health` (includes the database). Memory service health: `GET /health`.
