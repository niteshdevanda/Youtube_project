# YouTube Watch Party — Java Backend

This version keeps the existing React/Vite frontend and converts the complete backend from **Python Flask + Flask-Sock** to **Java 21 + Spring Boot + Spring WebSocket**.

## Stack

- Frontend: React + Vite
- Backend: Java 21 + Spring Boot
- REST: Spring Web
- Real-time: Spring WebSocket
- JSON: Jackson
- Build/dependencies: Maven
- Video: YouTube IFrame Player API
- Storage: in-memory room state for the MVP
- Deployment: Render Docker service

## Converted backend behavior

The Java backend preserves the original WebSocket contract:

- `join_room`
- `play`
- `pause`
- `seek`
- `change_video`
- `assign_role`
- `remove_participant`
- `transfer_host`
- `request_control`
- `leave_room`

It also preserves:

- Host / Moderator / Participant roles
- Server-side permission checks
- Real-time participant updates
- YouTube playback synchronization
- Participant control requests
- Automatic Host transfer when the current Host disconnects
- `/api/health`
- `/`

## Java backend structure

```text
backend/
├── Dockerfile
├── .dockerignore
├── pom.xml
└── src/
    └── main/
        ├── java/com/grras/watchparty/
        │   ├── WatchPartyApplication.java
        │   ├── config/
        │   │   ├── CorsConfig.java
        │   │   └── WebSocketConfig.java
        │   ├── controller/
        │   │   └── HealthController.java
        │   ├── model/
        │   │   ├── Participant.java
        │   │   └── Room.java
        │   └── service/
        │       ├── WatchPartyService.java
        │       └── WatchPartyWebSocketHandler.java
        └── resources/
            └── application.properties
```

## Dependency conversion

### Old Python backend

```text
Flask
Flask-Cors
Flask-Sock
gunicorn
simple-websocket
```

### New Java backend

Maven manages these through `pom.xml`:

```text
spring-boot-starter-web
spring-boot-starter-websocket
spring-boot-starter-actuator
spring-boot-starter-test
```

The project targets **Java 21**.

## Run locally

### 1. Backend

Install:

- JDK 21
- Maven 3.9+

Then:

```bash
cd backend
mvn clean package
mvn spring-boot:run
```

Backend:

```text
http://localhost:5000
```

Health:

```text
http://localhost:5000/api/health
```

WebSocket:

```text
ws://localhost:5000/ws
```

Or run the packaged JAR:

```bash
mvn clean package
java -jar target/youtube-watch-party-backend-1.0.0.jar
```

### 2. Frontend

Open another terminal:

```bash
cd frontend
npm install
npm run dev
```

Frontend:

```text
http://localhost:5173
```

For local development, the existing frontend automatically uses:

```text
HTTP: http://localhost:5000
WebSocket: ws://localhost:5000/ws
```

## Environment variables

Backend:

```text
PORT=5000
DEFAULT_VIDEO_ID=M7lc1UVf-VE
```

Frontend:

```text
VITE_API_URL=http://localhost:5000
VITE_WS_URL=ws://localhost:5000/ws
```

For production:

```text
VITE_API_URL=https://YOUR-JAVA-BACKEND.onrender.com
VITE_WS_URL=wss://YOUR-JAVA-BACKEND.onrender.com/ws
```

These frontend variables are compiled by Vite, so configure them before building the frontend.

## WebSocket architecture

```text
React + YouTube IFrame
        |
        | WebSocket /ws
        v
Spring WebSocket Handler
        |
        v
WatchPartyService
        |
        +---- Room
        |      |
        |      +-- videoId
        |      +-- playState
        |      +-- currentTime
        |      +-- participants
        |
        +---- Participant
               |
               +-- userId
               +-- username
               +-- role
               +-- WebSocketSession
```

## Permission model

| Action | Host | Moderator | Participant |
|---|---:|---:|---:|
| Play | Yes | Yes | Request |
| Pause | Yes | Yes | Request |
| Seek | Yes | Yes | Request |
| Change video | Yes | Yes | Request |
| Assign role | Yes | No | No |
| Remove participant | Yes | No | No |
| Transfer Host | Yes | No | No |

The backend checks these permissions server-side; frontend buttons are not treated as security.

## Render deployment

The backend now uses Docker so the Java version and runtime are controlled by the project.

Render backend configuration:

```text
Root Directory: backend
Runtime: Docker
Dockerfile: backend/Dockerfile
```

The Dockerfile:

1. Uses Maven + Java 21 to build the Spring Boot JAR.
2. Uses a Java 21 runtime image.
3. Starts the JAR.
4. Exposes port 5000.

Render supplies `$PORT`; Spring reads it through:

```properties
server.port=${PORT:5000}
```

### Frontend deployment

The frontend can remain a Render Static Site:

```text
Root Directory: frontend
Build Command: npm install && npm run build
Publish Directory: dist
```

Set:

```text
VITE_API_URL=https://YOUR-JAVA-BACKEND.onrender.com
VITE_WS_URL=wss://YOUR-JAVA-BACKEND.onrender.com/ws
```

## Important MVP limitation

Room state is still stored in memory, just as in the original Python implementation.

That means:

- Restarting the Java service removes active rooms.
- Multiple backend instances do not automatically share room state.
- A production multi-instance deployment should use Redis Pub/Sub and persistent storage.

For the existing assignment/MVP, the Java conversion keeps the same architecture and WebSocket behavior.

## Frontend

The React frontend has intentionally not been rewritten. Its WebSocket message format remains compatible with the Java backend, so the existing UI can communicate with the new Java service.

## Live URL

After deployment, update this section:

```text
Live URL: https://YOUR-FRONTEND.onrender.com
Java Backend: https://YOUR-JAVA-BACKEND.onrender.com
```
