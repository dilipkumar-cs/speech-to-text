# 🎙️ Speech to Text — Real-time Multilingual Transcription

Speak in **Hindi, Kannada, English, or any mix of them (Hinglish, Kanglish)** and watch your words appear **as you speak**.
Every recording is stored in **PostgreSQL** and managed from a **history dashboard** with full CRUD, search and
text-to-speech playback.

**Stack:** Java 17+ · Spring Boot 3.5 (Web, WebSocket, Data JPA, Flyway) · React 18 + TypeScript (Vite) · PostgreSQL 16 · Gemini Live API

> **Live demo:** https://speech-to-text-zvwq.onrender.com  
> _Hosted on Render's free tier: the first request after a period of inactivity can take about a minute while the server wakes up. Allow microphone access when prompted._

---

## Features

| | |
|---|---|
| **Real-time transcription** | Mic audio is streamed in 100 ms chunks over a WebSocket. Interim words appear live (in grey) and are replaced by finalised text. |
| **Multilingual / code-switching** | The Gemini Live transcription model auto-detects language **per utterance**, so a sentence like _"मुझे कल meeting attend करनी है"_ or _"ನಾಳೆ office ಗೆ ಹೋಗಬೇಕು"_ is transcribed as spoken. |
| **Secure by design** | The browser never sees the Gemini API key. It streams to our Spring Boot backend, which relays the audio to Gemini. |
| **Auto-save** | The transcript is saved when you stop, and also if the browser disconnects, Gemini ends the session, the 9½-minute limit is hit, or the server restarts. The UI never stays stuck on "saving": it gives up after 15 s with a clear message. |
| **History dashboard (CRUD)** | List (newest first, paginated), full-text search, view, create manually, edit, and delete (single or multi-select bulk delete) with confirmation. Shows stats, detected language, source, word count and duration. |
| **Language detection** | Each transcript is tagged with its languages, e.g. `hi`, `kn`, `en`, `hi+en`, `kn+en`, `hi+kn+en` (script detection for Devanagari / Kannada plus romanised Hindi and Kannada word lists). |
| **Text to speech** | 🔊 reads any transcription aloud with the browser's built-in speech synthesis (Hindi, Kannada or Indian-English voice). |

## Architecture

```
┌─────────────── Browser (React) ───────────────┐
│ Mic ─► AudioWorklet (16 kHz PCM16, 100 ms) ───┼──WebSocket /ws/transcribe──┐
│ Live transcript ◄─ {"type":"transcript",...} ◄┼────────────────────────────┤
│ History dashboard ◄──── REST /api/transcriptions ───────────┐              │
└───────────────────────────────────────────────┘             │              │
                                                     ┌────────▼──────────────▼───────────┐
                                                     │ Spring Boot                       │
                                                     │  TranscriptionController (CRUD)   │
                                                     │  LiveTranscriptionWebSocketHandler│
                                                     │      │  LiveTranscriber (interface)│
                                                     │      ▼                            │
                                                     │  GeminiLiveTranscriber ───────────┼──► Gemini Live API
                                                     │  TranscriptionService ──► JPA ────┼──► PostgreSQL (Flyway)
                                                     └───────────────────────────────────┘
```

* **`LiveTranscriber`** is an interface: Gemini Live is one implementation. A self-hosted engine
  (e.g. faster-whisper or Vosk) can be added without touching the WebSocket or persistence code.
* **`GeminiLiveSession`** uses the JDK `java.net.http.WebSocket`, serialises sends, buffers audio until the setup is
  acknowledged, and parses `interimInputTranscription` / `inputTranscription` messages.
* **`TranscriptAccumulator`** merges final segments and the current interim hypothesis (VERBATIM appends, SMART replaces).

### Backend package structure (layered)

```
backend/src/main/java/com/stt
├── SpeechToTextApplication.java
├── config/          SttProperties (typed config), WebConfig (CORS, WebSocket registration)
├── controller/      TranscriptionController (REST CRUD), AppInfoController, SpaForwardingController
├── service/         TranscriptionService (interface)
│   └── impl/        TranscriptionServiceImpl (business logic, transactions)
├── repository/      TranscriptionRepository (Spring Data JPA – DAO layer)
├── entity/          Transcription (JPA entity), TranscriptionSource (enum)
├── dto/             TranscriptionRequest, TranscriptionResponse, PageResponse, StatsResponse, BulkDeleteResponse, AppInfoResponse
├── mapper/          TranscriptionMapper (entity → DTO, entities never leave the service layer)
├── exception/       TranscriptionNotFoundException, GlobalExceptionHandler (RFC 9457 problem details)
├── websocket/       LiveTranscriptionWebSocketHandler (real-time audio endpoint)
├── client/          LiveTranscriber (speech engine abstraction)
│   └── gemini/      GeminiLiveTranscriber, GeminiLiveSession (Gemini Live API client)
└── util/            LanguageDetector, TranscriptAccumulator
```

Request flow: `Controller → Service (interface) → ServiceImpl → Repository → PostgreSQL`, with DTOs at the API
boundary and the mapper converting between entity and DTO.

### WebSocket protocol (`/ws/transcribe`)

| Direction | Message |
|---|---|
| client → server | binary frames: 16 kHz mono 16-bit little-endian PCM |
| client → server | `{"type":"stop","title":"optional"}` · `{"type":"cancel"}` |
| server → client | `{"type":"ready"}` · `{"type":"transcript","final":"…","interim":"…"}` · `{"type":"saved","transcription":{…} | null}` (null = nothing to save) · `{"type":"error","message":"…"}` |

### REST API

| Method | Path | Description |
|---|---|---|
| GET | `/api/transcriptions?q=&page=0&size=10` | List / search (newest first) |
| GET | `/api/transcriptions/{id}` | Get one |
| POST | `/api/transcriptions` | Create `{title?, content, source?, durationSeconds?}` |
| PUT | `/api/transcriptions/{id}` | Update `{title?, content}` |
| DELETE | `/api/transcriptions/{id}` | Delete |
| DELETE | `/api/transcriptions?ids=1,2,3` | Bulk delete selected (max 100) → `{deleted: n}` |
| GET | `/api/transcriptions/stats` | Totals |
| GET | `/api/info` | Engine availability |

All errors use RFC 9457 problem-details JSON (`GlobalExceptionHandler` extends Spring's `ResponseEntityExceptionHandler`); validation errors also list the failing fields.

## Run locally

**Prerequisites:** Java 17+, Maven, Node 20+, PostgreSQL. You also need a free Gemini API key from <https://aistudio.google.com/apikey>.

```bash
# 1. database
createdb speech_to_text            # or: psql -U postgres -c "CREATE DATABASE speech_to_text"

# 2. backend (http://localhost:8080) – Flyway creates the table on start-up
cd backend
# copy src/main/resources/application-local.example.yml to application-local.yml (git-ignored)
# and fill in your PostgreSQL password and Gemini API key – it is loaded via the default 'local' profile
mvn spring-boot:run

# 3. frontend (http://localhost:5173, proxies /api and /ws to :8080)
cd frontend
npm install
npm run dev
```

**Or with Docker:** `GEMINI_API_KEY=your-key docker compose up --build`, then open <http://localhost:8080>.

Configuration comes from environment variables (see [`.env.example`](.env.example)). For local development, `application-local.yml` (git-ignored) can hold the same settings instead.

## Tests

```bash
cd backend  && mvn verify      # 81 tests + coverage report at backend/target/site/jacoco/index.html
cd frontend && npm test        # 103 tests (Vitest + React Testing Library)
```

**Backend (JUnit 5, Mockito, Spring Boot Test, H2)**: about 98% line and 93% branch coverage.
* Controllers: full CRUD lifecycle, bulk delete, validation errors, pagination, search, `/api/info`, SPA routes, health.
* Service and mapper: Mockito unit tests for every service method and edge case.
* Gemini client: setup ordering, audio buffering before `setupComplete`, the setup fallback, the buffer cap, fragmented text and binary frames, close and error handling, and URL-encoded API key. A mocked JDK `WebSocket` stands in for Google, so tests need no network.
* WebSocket flow end to end with a fake engine: stream → live transcripts → stop → saved. Also covered: cancel, stop without speech, an engine error or close mid-recording, browser disconnect (still saved), missing API key, the recording time limit, a database failure, and saving open recordings when the server restarts.
* Utilities: language detection (Hindi, Kannada, English, mixed), transcript merging, config defaults.

**Frontend (Vitest, jsdom, Testing Library)**
* API client (URLs, errors, problem details), text-to-speech voice selection, the PCM AudioWorklet (resampling, Int16 conversion, flush), and microphone capture including a failing or hanging shutdown.
* Live panel and recording hook: connect, stream, live text, stop and save, discard, permission errors, and timeouts, so the UI never stays stuck on "saving".
* History dashboard: list, search debounce, pagination, create, edit, single and bulk delete with selection.
* Modal, the 🔊 button, and app navigation.

## Deploy (Render, free tier)

The repository has a single multi-stage [`Dockerfile`](Dockerfile) (React build → bundled into the Spring Boot jar) and a
[`render.yaml`](render.yaml) blueprint:

1. Push to GitHub, then in Render choose **New → Blueprint** and select the repo.
2. Enter `GEMINI_API_KEY` when prompted. The PostgreSQL database and connection variables are created automatically.
3. Open the generated `https://speech-to-text-xxxx.onrender.com` URL. Microphone access needs HTTPS, which Render provides.

The same image runs on Railway, Fly.io, Google Cloud Run, or any VM. Set the `DB_*` variables and `GEMINI_API_KEY`.

## Notes and limitations

* A Gemini Live session can stream for up to 10 minutes; the app stops and saves at 9½ minutes.
* Language tags come from a heuristic; romanised Hindi/Kannada words outside the built-in lists count as English.
