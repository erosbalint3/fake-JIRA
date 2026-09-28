# FakeJIRA

A lightweight, Jira-style task tracker for small teams, built for a university course.
Version 2.0 replaces the original Android + Firebase app with a **Spring Boot** REST API and a **React** web frontend.

![Board](docs/board.png)

| Backlog & sprints | Task detail | Burndown | Mobile |
| --- | --- | --- | --- |
| ![Backlog](docs/backlog.png) | ![Task detail](docs/task-detail.png) | ![Burndown](docs/reports.png) | ![Mobile](docs/mobile.png) |

## Features

**Projects and planning**
- **Projects** each have their own members, backlog, board and task keys (`WEB-1`, `API-7`, …). The owner manages members and settings. Members can leave.
- **Sprints**: plan work in the backlog by dragging tasks into a sprint (or use the dropdown), start it with dates, and complete it. Unfinished tasks go back to the backlog.
- **Board** for the active sprint (or every task if none is running), with drag and drop, an "only my tasks" filter, due dates, labels and checklist progress on cards.
- **Burndown chart** per sprint: remaining tasks per day against the ideal line, with a hover tooltip and a table view.
- **My work**: everything assigned to you across projects, with overdue tasks first.

**Tasks**
- Assign to any project member, or accept a task yourself.
- **Due dates**, with overdue and due-soon highlighting.
- **Labels**, with autocomplete and filtering.
- **Checklists** with a progress bar.
- **Markdown** in descriptions and comments, with a live preview. Raw HTML is not rendered.
- **@mentions** in comments, with autocomplete; mentioned members get notified.
- **Attachments** (up to 10 MB each): drag and drop to upload, with image previews. Files are always served as downloads.
- **Activity history**, e.g. "changed status from To do to In progress".

**Staying up to date**
- **Live updates**: boards, backlogs, tasks and notification badges refresh by themselves when someone else changes something (server-sent events).
- **Notifications** in the app, and optionally **by email** (each user turns it on in their profile).
- **Keyboard shortcuts**: `c` to create a task, `/` to search, `g b` / `g k` / `g r` / `g m` / `g n` / `g p` to jump to board, backlog, reports, my work, notifications and projects, and `?` for help.

**Accounts**
- Register and sign in with a username or email. Passwords are hashed with BCrypt, and the API uses JWT bearer tokens.
- **Password reset** by email: single-use links, valid for one hour.
- Light and dark themes, and a responsive layout for phones.

## Tech stack

| Layer | Stack |
| --- | --- |
| Backend | Java 17+, Spring Boot 3.5 (Web, Data JPA, Security, OAuth2 Resource Server/JWT, Validation), H2 database |
| Frontend | React 19, TypeScript, Vite, React Router, react-markdown, lucide icons |

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
| `app.base-url` | `APP_BASE_URL` | `http://localhost:5173`. The public URL, used for links in emails. |
| `app.storage.dir` | `APP_STORAGE_DIR` | `./data/attachments` (`/data/attachments` in Docker) |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:h2:file:./data/fakejira` |
| `spring.mail.host` | `SPRING_MAIL_HOST` | Empty, so email is off. Also set `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` and `APP_MAIL_FROM`. For an SMTP relay without login, set `SPRING_MAIL_SMTP_AUTH=false`. |

**Email is optional.** Without SMTP, the email toggle in profiles is disabled. Password reset links are then written to the server log instead (`docker logs fakejira`), so an administrator can pass them on.

### Upgrading from 2.0

Just deploy the new version on your existing database. On first start, all existing tasks move into a project called **FakeJIRA** (key `FJ`) and keep their `FJ-<number>` keys. Every existing user becomes a member of it. Logins stay valid if `APP_JWT_SECRET` is unchanged.

## Tests

```bash
cd backend && mvn test          # API integration tests (projects, sprints, attachments, live events, email, upgrade)
cd frontend && npm run build    # type-check + production build
```

## API overview

All endpoints except register, login and password reset need an `Authorization: Bearer <token>` header.

| Method | Path | Description |
| --- | --- | --- |
| POST | `/api/auth/register`, `/api/auth/login` | Create an account or sign in; returns `{ token, user }` |
| POST | `/api/auth/forgot-password`, `/api/auth/reset-password` | Email a reset link / set a new password with the link's token |
| GET | `/api/auth/me` | Current user |
| GET / POST | `/api/projects` | My projects / create a project (`{ key, name, description }`) |
| GET / PUT / DELETE | `/api/projects/{key}` | Read, update or delete (owner) a project |
| POST / DELETE | `/api/projects/{key}/members[/{userId}]` | Add a member by username or email / remove a member or leave |
| GET | `/api/projects/{key}/labels` | Labels used in the project |
| GET / POST | `/api/projects/{key}/sprints` | List or create sprints |
| PUT / DELETE | `/api/sprints/{id}` | Edit or delete (planned only) a sprint |
| POST | `/api/sprints/{id}/start`, `/api/sprints/{id}/complete` | Start or complete a sprint |
| GET | `/api/sprints/{id}/burndown` | Burndown data points |
| GET | `/api/tasks?project=&scope=AVAILABLE\|MINE\|REPORTED\|ALL&q=&priority=&status=&label=&sprint=<id>\|backlog&assignee=<id>\|me\|none` | List and search tasks in your projects |
| POST | `/api/tasks` | Create a task (`projectKey`, `title`, `description`, `priority`, `dueDate`, `labels`, `assigneeId`, `sprintId`) |
| GET / PUT / DELETE | `/api/tasks/{id}` | Read, update, or delete (reporter or project owner) |
| PATCH | `/api/tasks/{id}/status` | Change status |
| PUT | `/api/tasks/{id}/assignee`, `/api/tasks/{id}/sprint` | Assign (`null` unassigns) / move to a sprint (`null` = backlog) |
| POST | `/api/tasks/{id}/accept`, `/api/tasks/{id}/release` | Take or give back a task |
| GET / POST | `/api/tasks/{id}/comments` | List or add comments (`@username` mentions notify) |
| GET / POST / PATCH / DELETE | `/api/tasks/{id}/checklist[/{itemId}]` | Checklist items |
| GET | `/api/tasks/{id}/activity` | Task history |
| GET / POST | `/api/tasks/{id}/attachments` | List or upload (`multipart/form-data`, field `file`) |
| GET / DELETE | `/api/attachments/{id}/content`, `/api/attachments/{id}` | Download or delete a file |
| GET | `/api/events` | Server-sent event stream of live changes |
| GET | `/api/users?q=` | Username search (for adding members) |
| GET | `/api/notifications`, `/api/notifications/unread-count` | Notifications |
| POST | `/api/notifications/{id}/read`, `/api/notifications/read-all` | Mark as read |
| GET / PUT | `/api/profile`, `/api/profile/settings`, `/api/profile/password` | Profile, email notification setting, password change |

## Contact

If you have trouble building the project, email erosbalint3@proton.me.
