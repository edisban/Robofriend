# RoboFriend: an AI companion that remembers

RoboFriend is a full-stack AI chat app that talks like a friend. It remembers what you tell it and keeps
your reminders and lists. You can chat in Greek or English, by text or by voice.

https://github.com/user-attachments/assets/1f5f3eb1-01d7-4df6-9432-7f546a30ce33

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4-6DB33F?logo=springboot&logoColor=white)
![React](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=black)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16_+_pgvector-4169E1?logo=postgresql&logoColor=white)
![FastAPI](https://img.shields.io/badge/FastAPI-embeddings-009688?logo=fastapi&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)

## Features

- **Streaming chat.** Replies appear word by word. You can regenerate a reply, edit your last message or
  delete messages. The chat opens on the latest 50 messages and loads older ones as you scroll up.
- **Memory.** RoboFriend remembers facts and the date it learned them, and finds the relevant ones by
  meaning. Values that change over time, like a squat max, are updated instead of duplicated.
- **Reminders.** "Remind me tomorrow at 9 to call mom" creates a reminder in your timezone. When it's
  due, RoboFriend sends you a message.
- **Lists.** "Add milk and eggs to my shopping list", then "what's on my list?"
- **Daily check-in.** The first time you open the app each day, RoboFriend messages you if something
  matters that day, such as an exam, a follow-up or your birthday.
- **Profile.** Set the name RoboFriend uses for you and your birthday (it wishes you a happy birthday on
  the day), or delete your account and all its data.
- **Voice mode**, **light and dark themes**, and a **mobile layout**.

## Architecture

```
Browser (React) → nginx → Spring Boot API → Groq (LLM, streamed)
                                 │        → Embedding service (Python, text → vector)
                                 ↓
                        PostgreSQL + pgvector
```

| Folder | Tech | What it does |
| --- | --- | --- |
| `bot-front/` | React 19, TypeScript, Vite, MUI | The app. nginx serves it and passes `/api` to the backend. |
| `bot-back/` | Spring Boot 4, Java 17, Spring Security, JPA, Flyway | API, sign-in, prompts, streaming, reminders. The only service that uses the database. |
| `ai-memory-service/` | Python, FastAPI, sentence-transformers | Turns text into 384-dimension vectors. Stateless; it has no database. |

**How a message is handled:** the backend gathers related memories (pgvector cosine search), reminders,
lists and recent messages. It builds one prompt and streams Groq's reply to the browser as Server-Sent
Events. After a `###META###` marker, the model also writes a JSON block with facts, reminders and list
changes to save. That block is never shown to the user and is checked strictly before anything is saved.

**Reliability:**
- Each due reminder is claimed with one atomic database update, so it is sent once even with several
  backend instances.
- If the embedding service is down, chat keeps working without memory search. New facts are saved anyway
  and get their vectors in the background.
- If the AI can't be reached, the user gets a short friendly message instead of an error.

## Run it

You need Docker and a [Groq](https://console.groq.com) API key.

1. Create a `.env` file in the project folder (see `.env.example`):

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

The API is also on `localhost:8080` (health check: `/actuator/health`) and PostgreSQL on `localhost:5434`.
The database schema is managed by Flyway migrations in `bot-back/src/main/resources/db/migration`.

**Frontend with hot reload:** with the Docker stack running, run `cd bot-front && npm install && npm run dev`
and open http://localhost:5174.

## Tests

| Command | What it runs |
| --- | --- |
| `cd bot-back && ./mvnw test` | Unit, web-layer and integration tests against a real PostgreSQL (Testcontainers, needs Docker) |
| `cd bot-front && npm test` | Unit tests (Vitest, React Testing Library) |
| `cd bot-front && npm run test:e2e` | Browser tests (Playwright) with a faked API |

GitHub Actions runs all three on every push.

## Security

- Passwords are hashed with Argon2id. Accounts created before the switch keep their BCrypt hash until
  they next sign in, when it is upgraded.
- Sessions use HttpOnly, Secure, SameSite=Strict cookies: a 15-minute access token and a 30-day refresh
  token that is replaced every time it's used.
- Every request that changes data needs a CSRF token.
- Sign-in, account deletion and AI replies are rate-limited. Deleting an account needs the password.
- The model's hidden JSON is validated before anything is saved, and text written by users can't add a
  fake `###META###` marker.
