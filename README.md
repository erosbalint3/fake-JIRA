# FakeJIRA

A lightweight, Jira-style task tracker for small teams, built for a university course.
Version 2.0 replaces the original Android + Firebase app with a **Spring Boot** REST API and a **React** web frontend.

![Backlog](docs/backlog.png)

| My board | Task detail | Mobile |
| --- | --- | --- |
| ![Board](docs/board.png) | ![Task detail](docs/task-detail.png) | ![Mobile](docs/mobile.png) |

## Features

- **Accounts**: register and sign in with a username or email. Passwords are hashed with BCrypt and the API uses JWT bearer tokens.
- **Backlog**: shows available (unassigned) tasks, tasks you reported, or all tasks. You can search and filter by priority and status.
- **Tasks**: create, edit and delete tasks with a title, a description and a priority (low, medium, high or critical). Tasks get keys like `FJ-12`.
- **Accept and release**: accepting a task moves it from the shared backlog onto your board. Releasing it puts it back.
- **My board**: a Kanban board with To do, In progress, In review and Done columns. Move cards by drag and drop or with the arrow buttons, which also work on touch screens.
- **Comments** on every task.
- **Notifications**: you get one when someone accepts, updates, moves, comments on, releases or deletes a task you are involved in. The sidebar shows an unread badge.
- **Profile**: your task stats and a password change form.
- Light and dark themes, and a responsive layout for phones.

## Tech stack

| Layer | Stack |
| --- | --- |
| Backend | Java 17+, Spring Boot 3.5 (Web, Data JPA, Security, OAuth2 Resource Server/JWT, Validation), H2 database |
| Frontend | React 19, TypeScript, Vite, React Router, lucide icons |

## Project layout

```
backend/    Spring Boot API (Maven)
frontend/   React single-page app (Vite)
docs/       Screenshots
```

## Running locally

Prerequisites: JDK 17 or newer, Maven 3.9+, Node.js 20+.

**1. Start the backend** (on http://localhost:8080):

```bash
cd backend
mvn spring-boot:run
```

Data is stored in an H2 file database at `backend/data/`.

**2. Start the frontend** (on http://localhost:5173) in a second terminal:

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173 and create an account. The Vite dev server proxies `/api` to the backend.

### Single-jar build

You can bundle the React app into the Spring Boot jar and serve everything from one port:

```bash
cd frontend && npm install && npm run build:backend
cd ../backend && mvn package
java -jar target/fake-jira-2.0.0.jar      # http://localhost:8080
```

## Running with Docker

The multi-stage `Dockerfile` builds the frontend and the backend and produces one small JRE image. The image runs as a non-root user and stores its H2 database in the `/data` volume.

Inside the container the app listens on port 8080. The compose file publishes it only on the host's loopback interface, on port **8081** by default, so it won't clash with another service already using 8080.

```bash
cp .env.example .env
# Set APP_JWT_SECRET in .env (e.g. openssl rand -base64 48); change FAKEJIRA_PORT if 8081 is taken
docker compose up -d --build
```

Updating: `git pull && docker compose up -d --build`. Your data stays in the `fakejira-data` volume.

### Behind Caddy

See [`deploy/Caddyfile.example`](deploy/Caddyfile.example) and add one site block to your existing Caddyfile.

- **Caddy installed on the host:** use `docker compose up -d` as above and set `reverse_proxy 127.0.0.1:8081`.
- **Caddy running in Docker** (for example in another compose project): attach FakeJIRA to Caddy's network and don't publish a host port.

  ```bash
  # CADDY_NETWORK in .env = the network your Caddy container is on (see `docker network ls`)
  docker compose -f docker-compose.yml -f docker-compose.caddy.yml up -d --build
  ```

  Then use `reverse_proxy fakejira:8080` in the Caddyfile. This override needs Docker Compose v2.24 or newer.

### Configuration

| Setting | Env var | Default |
| --- | --- | --- |
| `app.jwt.secret` | `APP_JWT_SECRET` | Random on each start. Set a value of 32+ characters so logins survive restarts. |
| `app.jwt.validity` | `APP_JWT_VALIDITY` | `12h` |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:h2:file:./data/fakejira` |

## Tests

```bash
cd backend && mvn test          # API integration tests
cd frontend && npm run build    # type-check + production build
```

## API overview

All endpoints except register and login need an `Authorization: Bearer <token>` header.

| Method | Path | Description |
| --- | --- | --- |
| POST | `/api/auth/register` | Create an account, returns `{ token, user }` |
| POST | `/api/auth/login` | Sign in with `{ login, password }` |
| GET | `/api/auth/me` | Current user |
| GET | `/api/tasks?scope=AVAILABLE\|MINE\|REPORTED\|ALL&q=&priority=&status=` | List and search tasks |
| POST | `/api/tasks` | Create a task |
| GET / PUT / DELETE | `/api/tasks/{id}` | Read, update (reporter or assignee), or delete (reporter only) |
| PATCH | `/api/tasks/{id}/status` | Change status |
| POST | `/api/tasks/{id}/accept`, `/api/tasks/{id}/release` | Take or give back a task |
| GET / POST | `/api/tasks/{id}/comments` | List or add comments |
| GET | `/api/notifications` | Notifications plus unread count |
| POST | `/api/notifications/{id}/read`, `/api/notifications/read-all` | Mark as read |
| GET | `/api/profile` | Profile and stats |
| PUT | `/api/profile/password` | Change password |

## Contact

If you have trouble building the project, email erosbalint3@proton.me.
