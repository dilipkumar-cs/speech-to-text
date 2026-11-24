# 🎙️ Speech to Text — Real-time Multilingual Transcription

Speak in **Hindi, Kannada, English, or any mix of them (Hinglish, Kanglish)** and watch your words appear **as you speak**.
Every recording is stored in **PostgreSQL** and managed from a **history dashboard** with full CRUD, search and
text-to-speech playback.

**Stack:** Java 21 · Spring Boot 3.5 (Web, WebSocket, Data JPA, Flyway) · React 18 + TypeScript (Vite) · PostgreSQL 16 · Gemini Live API

> **Live demo:** _<add deployed URL>_

---

## Features

| | |
|---|---|
| **Real-time transcription** | Mic audio is streamed in 100 ms chunks over a WebSocket. Interim words appear live (in grey) and are replaced by finalised text. |
| **Multilingual / code-switching** | The Gemini Live transcription model auto-detects language **per utterance**, so a sentence like _"मुझे कल meeting attend करनी है"_ or _"ನಾಳೆ office ಗೆ ಹೋಗಬೇಕು"_ is transcribed as spoken. |
| **Secure by design** | The browser never sees the Gemini API key. It streams to our Spring Boot backend, which relays the audio to Gemini. |
| **Auto-save** | When you stop (or if the browser disconnects or the 10-minute session limit is hit) the transcript is saved automatically. |
| **History dashboard (CRUD)** | List (newest first, paginated), full-text search, view, create manually, edit, and delete with confirmation. Shows stats, detected language, source, word count and duration. |
| **Language detection** | Each transcript is tagged with its languages, e.g. `hi`, `kn`, `en`, `hi+en`, `kn+en`, `hi+kn+en` (script detection for Devanagari / Kannada plus romanised Hindi and Kannada word lists). |
| **Text to speech** | 🔊 reads any transcription aloud with the browser's built-in speech synthesis (Hindi, Kannada or Indian-English voice). |
| **Fallback engine** | With no Gemini key the app still works: it falls back to the browser Web Speech API (`hi-IN`, `kn-IN` or `en-IN`), then saves through the same REST API. |

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
├── dto/             TranscriptionRequest, TranscriptionResponse, PageResponse, StatsResponse, AppInfoResponse
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
| server → client | `{"type":"ready"}` · `{"type":"transcript","final":"…","interim":"…"}` · `{"type":"saved","transcription":{…}}` · `{"type":"error","message":"…"}` |

### REST API

| Method | Path | Description |
|---|---|---|
| GET | `/api/transcriptions?q=&page=0&size=10` | List / search (newest first) |
| GET | `/api/transcriptions/{id}` | Get one |
| POST | `/api/transcriptions` | Create `{title?, content, source?, durationSeconds?}` |
| PUT | `/api/transcriptions/{id}` | Update `{title?, content}` |
| DELETE | `/api/transcriptions/{id}` | Delete |
| GET | `/api/transcriptions/stats` | Totals |
| GET | `/api/info` | Engine availability |

Errors use RFC 9457 problem-details JSON.

## Run locally

**Prerequisites:** Java 21+, Maven, Node 20+, PostgreSQL. You also need a free Gemini API key from <https://aistudio.google.com/apikey>.

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

All configuration is in environment variables; see [`.env.example`](.env.example).

## Tests

```bash
cd backend && mvn test
```

29 tests cover the CRUD lifecycle, service-layer unit tests (Mockito), validation, pagination and search, language detection, transcript merging,
Gemini message parsing and setup, and an **end-to-end WebSocket test**: stream audio → receive live transcripts →
stop → transcript persisted. It uses a fake engine and an H2 database.

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
* The browser fallback engine needs Chrome or Edge (Web Speech API).
