# FakeJIRA

A lightweight, Jira-style task tracker for small teams, built for a university course.
Version 2.0 replaced the original Android + Firebase app with a **Spring Boot** REST API and a **React** web frontend; 3.0 added planning, team and integration features; 4.0 added security, automation, search, flow reports and PostgreSQL; 5.0 adds workflows, a wiki, service desk, forecasts, company sign-in, an optional Claude assistant, a table view, German and Spanish and much more (see [What's new in 5.0](#whats-new-in-50)).

![Board](docs/board.png)

| Backlog & bulk edit | Task detail | Roadmap | Velocity | Command palette | Mobile |
| --- | --- | --- | --- | --- | --- |
| ![Backlog](docs/backlog.png) | ![Task detail](docs/task-detail.png) | ![Roadmap](docs/roadmap.png) | ![Velocity](docs/reports.png) | ![Command palette](docs/palette.png) | ![Mobile](docs/mobile.png) |

## Features

**Projects and planning**
- **Projects** each have their own members, backlog, board and task keys (`WEB-1`, `API-7`, …). The owner manages members and settings. Members can leave.
- **Sprints**: plan work in the backlog by dragging tasks into a sprint (or use the dropdown), start it with dates, and complete it. Unfinished tasks go back to the backlog.
- **Board** for the active sprint (or every task if none is running), with drag and drop, an "only my tasks" filter, due dates, labels and checklist progress on cards.
- **Custom board columns** per project: rename, reorder, add columns (e.g. "QA" and "Code review" both mapping to In review) and set **WIP limits** — a column turns red when it holds too many cards.
- **Story points** on tasks, a **burndown** in tasks or points, and a **velocity chart** (committed vs. completed points for the last 10 sprints, with average velocity and say/do ratio).
- **Epics and roadmap**: group tasks into epics with start and due dates, see progress and a month-by-month timeline.
- **Reports**: burndown, velocity and a **time report** (hours per person and per task for any date range).
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
- **Subtasks** with progress, and **links** between tasks (blocks / relates to / duplicates). Tasks blocked by an unfinished task get a "Blocked" badge everywhere.
- **Time tracking**: log work like `1h 30m` with a date and note.
- **Watch** any task to be notified about its changes (commenters start watching automatically).

**Finding and changing things fast**
- **Command palette** (`Ctrl/⌘ K`): jump to any task by key or title, project, page or saved filter, or run an action.
- **Saved filters**: backlog filters live in the URL; save them (private or shared with the project) and they appear in the sidebar.
- **Bulk edit**: select tasks in the backlog (shift-click for ranges) and change status, assignee, sprint, priority, epic or labels at once, or delete them.
- **CSV export and import** from project settings.

**Staying up to date**
- **Live updates**: boards, backlogs, tasks and notification badges refresh by themselves when someone else changes something (server-sent events).
- **Notifications** in the app, **by email** (instantly, or as a **daily or weekly digest**) and as **browser push notifications**.
- **Installable app (PWA)**: add FakeJIRA to your phone's home screen or install it from the desktop browser.
- **GitHub integration**: mention task keys like `WEB-12` in commits, branches or pull requests and they appear on the task; optionally a merged pull request moves its tasks to Done.
- **Keyboard shortcuts**: `c` to create a task, `/` to search, `Ctrl/⌘ K` for the command palette, `g b` / `g k` / `g o` / `g r` / `g m` / `g n` / `g p` to jump to board, backlog, roadmap, reports, my work, notifications and projects, and `?` for help.

**Accounts**
- Register and sign in with a username or email. Passwords are hashed with BCrypt, and the API uses JWT bearer tokens.
- **Password reset** by email: single-use links, valid for one hour.
- **Profile pictures and display names.**
- **Project roles**: owner, member and **viewer** (read-only; viewers can still comment and watch).
- **Sign-up control**: open, **admin approval** or **invite-only**, switchable on the Admin page. Admins and project owners create invite links.
- **Rate limiting** of sign-in, sign-up and password reset.
- **Admin page**: sign-up mode, pending accounts, admins, invites and **backups**.
- Light and dark themes, and a responsive layout for phones.

## What's new in 5.0

**Work management**
- **Workflows** on top of board columns: required fields per column (assignee, points, due date, estimate, resolution, approvals, custom fields) and optional allowed transitions — the board asks for what is missing.
- **Resolutions**, **components** with leads, **approvals**, per-type checklists, **helpers**, and an **archive**.
- Start dates and estimates, a task **timeline** (Gantt) with dependencies, the critical path, drag-to-reschedule, optional automatic rescheduling, and a **per-person view** that flags overlapping work.
- Sprint goals, **capacity planning**, a cross-project **portfolio** with risk flags, and quarterly **goals / OKRs**.

**Working together**
- **Live co-editing** of descriptions with presence, **inline comments** on passages, a **project wiki** with history, **meeting notes** whose action items become tasks, a **stand-up** view, **polls** and a decision log, **kudos**.
- A **guest** role for clients, **internal comments** that guests and viewers never see, and **custom project roles** (named permission sets).

**Personal productivity**
- A **Today** list, a **timer** that logs time, **reminders**, **private notes**, inbox **triage** (done, snooze), **notification rules** with quiet hours, and keyboard navigation in lists (`j`/`k`, `e`, `a`, `s`, `x`).
- **Table view**: edit tasks like a spreadsheet — arrow keys, Enter or typing to edit, copy and paste, paste several lines to create several tasks, sort, filter and choose columns.
- **Right-click menus** on cards and rows, **saved views** on the board and table, **card fields** (choose what cards show), **project and epic icons**, a project **picture gallery**, and **drop files** on a task or a card to attach them.
- **Appearance**: light, dark or system theme, high contrast, compact density, text size, an easy-to-read font and reduced motion — saved in your account with your other preferences so they follow you to every device.
- On phones: a **bottom navigation bar**, **pull to refresh** and **swipe** cards between columns.
- **German and Spanish** next to English and Hungarian (Profile → Language).

**Reporting**
- **Monte Carlo forecasts** (when will it be done, what fits by a date), **release burn-up**, **aging work in progress**, **bug trends**, **SLA** targets and reports, a team **health check**.
- More dashboard charts, **scheduled email reports**, and **Excel and PDF** exports.

**Service desk**
- A public **request portal** per project with a form builder, request tracking pages and a conversation with the requester, **duplicate detection**, a public **roadmap** and **changelog**, and an embeddable **feedback widget**.

**Integrations**
- **CI/CD status** on tasks and cards (GitHub, GitLab, Gitea or any CI), **branches and draft pull requests** from tasks and two-way **GitHub issue sync**, **slash commands** in Slack, Mattermost and Discord, **Teams and Mattermost** notifications, two-way **Google Calendar** sync, **REST hooks** for Zapier / n8n / Make, **link previews**, a **command-line tool** and an **OpenAPI** explorer at `/api-docs`.

**Claude assistant** (optional, off unless an Anthropic API key is set)
- Write a task from one sentence, summarize a discussion, "what changed", split an epic into tasks, draft sprint reviews and release notes, ask search questions in plain words, and get estimate and triage suggestions. Everything is a suggestion you review before it is saved.

**Admin, security and operations**
- Company sign-in with **OpenID Connect**, **SAML 2.0** and **LDAP / Active Directory**, **SCIM** provisioning, **require SSO**, session length and idle timeout, an **IP allowlist**, sign-in alerts, and deactivating accounts.
- **Data retention** rules, attachments **encrypted at rest**, **one-click restore** from a backup, **health alerts**, and **several instances** behind a load balancer via Redis.

## What's new in 4.0

**Security and accounts**
- **Two-step verification** (authenticator app, QR code, recovery codes), **Sign in with Google / GitHub**, a list of **signed-in devices** you can sign out remotely, **data export** and **account deletion**.
- Configurable **password rules**, a searchable **audit log** and per-user admin actions (reset 2FA, set a password, sign out everywhere, delete).
- **Personal API tokens** (read-only or read & write, with expiry) for scripts: `Authorization: Bearer fjt_…`.

**Tasks**
- **Issue types** (task, bug, story, spike), **task templates** and **recurring tasks**.
- **Undo** from the toast for moves, edits, bulk changes and deletes; unsent comments and new tasks are kept as **drafts**.
- Inline edits in the backlog, pasted/dropped **images**, comment **replies** and **reactions**, description diffs, **clone** and **move** to another project.
- **Custom fields** (text, number, date, choice, checkbox, URL) per project, searchable like built-in fields.
- **Public share links**: a read-only page for one task, optionally with comments.

**Planning**
- Board **swimlanes** (assignee, epic, priority, type), a **workload** panel and a **planning helper** comparing planned points with recent velocity.
- **Kanban mode** per project (no sprints), **cumulative flow**, **cycle/lead time** and **throughput** reports, **scope changes** on the burndown.
- **Sprint review** (copy as Markdown), a live **retrospective** board, **planning poker**, **releases** with notes, and **epic dependencies** on the roadmap.
- **Project templates** (Scrum, Kanban, bug tracking, marketing) and a per-project **accent colour**.

**Finding things and working together**
- **Search** with a query language (`assignee = me AND status != done ORDER BY due`) with autocomplete, results export and saved filters. Full-text search in the command palette covers comments, files, epics and releases.
- **Dashboards** with widgets, a **calendar** (due dates, sprints, releases, time off) with a private **iCal feed**, an **activity feed** across projects.
- **Teams** with `@team` mentions and **out-of-office** notes shown when assigning work.
- **Email in**: reply to a notification email to comment, or mail the project address to create a task.

**Automation and integrations**
- **Automation rules**: when a task is created / changes / gets a comment, or on a schedule (SLA escalation, stale tasks), if it matches a query, then set fields, assign (round-robin, least loaded), comment, notify or move to the active sprint. Each rule has a run log.
- **Outgoing webhooks** (signed with `X-FakeJIRA-Signature`, retries, delivery log), **Slack / Discord** notifications, **GitLab** and **Gitea / Forgejo** next to GitHub.
- **Import from Jira** (CSV) and **Trello** (JSON).

**Operations and polish**
- **PostgreSQL** support with a one-time copy from the built-in H2 database.
- **Off-site backups** to S3-compatible storage or WebDAV (Nextcloud), **attachment quotas**, `/api/health`, **Prometheus metrics** and an admin **System** page with an update check.
- **Hungarian** translation (Profile → Language), an **accessibility** pass (skip link, contrast, screen-reader labels), **offline reading** of pages you have opened, and a short **first-run tour**.

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
java -jar target/fake-jira-4.0.0.jar      # http://localhost:8080
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
| `app.base-url` | `APP_BASE_URL` | Empty. The public URL (e.g. `https://jira.example.com`) used for links in emails and invites. When empty, the address an admin opens the app at is used; set it anyway so links are right from the start. |
| `app.storage.dir` | `APP_STORAGE_DIR` | `./data/attachments` (`/data/attachments` in Docker) |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:h2:file:./data/fakejira` |
| `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD` | Empty. Password of the database user `sa`; an existing password-less database is switched to it on the next start. |
| `app.db-tcp.port` | `APP_DB_TCP_PORT` | Empty (off). Port for network access to the database, see below. |
| `app.backup.dir` | `APP_BACKUP_DIR` | `./data/backups` (`/data/backups` in Docker) |
| `app.backup.cron` | `APP_BACKUP_CRON` | `0 30 3 * * *` (03:30 every night). `-` turns scheduled backups off. |
| `app.backup.keep` | `APP_BACKUP_KEEP` | `14` backups kept |
| `app.digest.cron` | `APP_DIGEST_CRON` | `0 0 7 * * *`. Daily digests go out then; weekly ones on Mondays. |
| `app.registration.default-mode` | `APP_REGISTRATION_DEFAULT_MODE` | `OPEN`. Initial sign-up mode (`OPEN`, `APPROVAL`, `INVITE`) until an admin changes it. |
| `app.rate-limit.enabled` | `APP_RATE_LIMIT_ENABLED` | `true` |
| `app.rate-limit.client-ip-headers` | `APP_RATE_LIMIT_CLIENT_IP_HEADERS` | `CF-Connecting-IP,X-Forwarded-For`. Set it empty if the app is reachable without your proxy. |
| `app.push.subject` | `APP_PUSH_SUBJECT` | Contact for push services; defaults to the base URL. |
| `app.automation.cron` | `APP_AUTOMATION_CRON` | `0 */5 * * * *`. How often scheduled automation rules check their conditions. |
| `app.backup.s3.*` | `APP_BACKUP_S3_ENDPOINT`, `_BUCKET`, `_REGION`, `_ACCESS_KEY`, `_SECRET_KEY`, `_PREFIX`, `_PATH_STYLE` | Empty (off). Copy every backup to S3-compatible storage, see below. |
| `app.backup.webdav.*` | `APP_BACKUP_WEBDAV_URL`, `_USERNAME`, `_PASSWORD` | Empty (off). Copy every backup to a WebDAV folder. |
| `app.migrate-from.url` | `APP_MIGRATE_FROM_URL` (+ `_USERNAME`, `_PASSWORD`) | Empty. Old database to copy from once when moving to PostgreSQL. |
| `app.metrics.token` | `APP_METRICS_TOKEN` | Empty (off). Bearer token for Prometheus metrics at `/api/metrics`. |
| `app.update-check.enabled` | `APP_UPDATE_CHECK` | `true`. Daily check for a newer release, shown to admins. |
| `app.mail.inbound.address` | `APP_MAIL_INBOUND_ADDRESS` | Empty (off). Mailbox for reply-by-email and email-to-task, see below. |
| `app.mail.inbound.secret` | `APP_MAIL_INBOUND_SECRET` | Empty. Secret for `POST /api/inbound/email` (inbound-mail services). |
| `app.mail.inbound.imap.*` | `APP_MAIL_INBOUND_IMAP_HOST`, `_PORT`, `_USERNAME`, `_PASSWORD`, `_FOLDER`, `_POLL_MS` | Empty. Or poll the mailbox over IMAPS. |
| `app.oauth.google.*` / `app.oauth.github.*` | `APP_OAUTH_GOOGLE_CLIENT_ID`, `_SECRET`; `APP_OAUTH_GITHUB_CLIENT_ID`, `_SECRET` | Empty (off). Sign in with Google / GitHub. |
| `app.audit.retention-days` | `APP_AUDIT_RETENTION_DAYS` | `365` |
| `app.ai.*` | `ANTHROPIC_API_KEY`, `APP_AI_MODEL`, `APP_AI_FALLBACKS`, `APP_AI_REQUESTS_PER_HOUR` | Empty (off). The Claude assistant; model `claude-opus-5-5`, server-side fallbacks on, 60 requests per person per hour. See below. |
| `app.oauth.oidc.*` | `APP_OAUTH_OIDC_ISSUER`, `_CLIENT_ID`, `_CLIENT_SECRET`, `_LABEL`, `_TRUST_EMAIL` | Empty (off). Company sign-in with OpenID Connect. |
| `app.saml.*` | `APP_SAML_IDP_SSO_URL`, `APP_SAML_IDP_ENTITY_ID`, `APP_SAML_IDP_CERTIFICATE`, `APP_SAML_LABEL` | Empty (off). Company sign-in with SAML 2.0. |
| `app.ldap.*` | `APP_LDAP_URL`, `_USER_DN_PATTERN`, `_USER_SEARCH_BASE`, `_USER_SEARCH_FILTER`, `_BIND_DN`, `_BIND_PASSWORD`, `_EMAIL_ATTRIBUTE`, `_NAME_ATTRIBUTE` | Empty (off). Sign in with LDAP / Active Directory passwords. |
| `app.security.ip-allowlist-disabled` | `APP_SECURITY_IP_ALLOWLIST_DISABLED` | `false`. Recovery switch that ignores the admin's IP allowlist. |
| `app.storage.encryption-key` | `APP_STORAGE_ENCRYPTION_KEY` (+ `APP_STORAGE_PREVIOUS_ENCRYPTION_KEYS`) | Empty (off). Encrypt attachments at rest; see below. |
| `app.redis.url` | `APP_REDIS_URL` | Empty. Run several instances; see below. |
| `app.google-calendar.*` | `APP_GOOGLE_CALENDAR_CLIENT_ID`, `_CLIENT_SECRET` | The Google sign-in client. Two-way Google Calendar sync. |
| – | `TZ` | `UTC`. Time zone for backups and digests, e.g. `Europe/Budapest`. |
| `spring.mail.host` | `SPRING_MAIL_HOST` | Empty, so email is off. Also set `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` and `APP_MAIL_FROM`. For an SMTP relay without login, set `SPRING_MAIL_SMTP_AUTH=false`. |

**Email is optional.** Without SMTP, the email toggle in profiles is disabled. Password reset links are then written to the server log instead (`docker logs fakejira`), so an administrator can pass them on.

### Admins, sign-up and invites

The **first account** ever created is the administrator (after upgrading, the oldest account becomes one). Admins see **Admin** in the sidebar, where they choose who can sign up:

- **Open** — anyone can create an account.
- **Admin approval** — new accounts wait until an admin approves them on the Admin page.
- **Invite only** — only people with an invite link can sign up.

Invite links work in every mode and are valid for 7 days. Admins create them on the Admin page; project owners create them in **Project settings → Invite links** (the new user joins that project). If an email address is given, the invite only works for it and is emailed when SMTP is configured.

Sign-in (10/min), sign-up (5/h) and password reset (5/h) are limited per client IP, and failed sign-ins per account (20 per 15 min). Clients get `429` with a `Retry-After` header.

### Connecting to the database from another PC

The app stores its data in an embedded H2 database (`/data/fakejira.mv.db` in the Docker volume). To browse it live with DBeaver, DataGrip or the H2 console:

1. Set a database password in `.env`: `SPRING_DATASOURCE_PASSWORD=<something long>`. On the next start the existing database is switched to it. Keep it afterwards — removing or changing it in `.env` locks the app out (to change it, run `ALTER USER SA SET PASSWORD '<new>'` over the connection first, then update `.env`).
2. Start with the database override, which sets `APP_DB_TCP_PORT=9092` and publishes it on the server's loopback interface only:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.db.yml up -d --build
   # with Caddy in Docker:
   docker compose -f docker-compose.yml -f docker-compose.caddy.yml -f docker-compose.db.yml up -d --build
   ```

3. From the other PC open an SSH tunnel and connect through it:

   ```bash
   ssh -L 9092:localhost:9092 you@your-server
   ```

   JDBC URL `jdbc:h2:tcp://localhost:9092/fakejira`, user `sa`, the password from step 1, H2 driver 2.3.x.

The app refuses to open the port while the database has no password, and remote clients cannot create new databases. Changes made directly in the database skip the app's permission checks, activity history and live updates, so make a backup before editing data by hand.

### Backups and restore

A zip with the database, attachments and profile pictures is written every night to `/data/backups` (inside the data volume); the newest 14 are kept. Admins can make one on demand and download backups from the Admin page — keep a copy off the server.

**One-click restore** (built-in H2 database): **Admin → Data & backups → Restore** on a backup. After you confirm with your password, the app makes a safety backup of the current state, checks the backup, and restarts to apply it; the replaced data is kept on the server. With Docker the container restarts by itself (`restart: unless-stopped`).

To restore by hand instead:

```bash
docker compose stop fakejira
docker run --rm -v fake-jira_fakejira-data:/data -v "$PWD":/restore alpine sh -c \
  'cd /tmp && unzip -o /restore/fakejira-backup-XXXX.zip && unzip -o database.zip -d /data \
   && rm -rf /data/attachments /data/avatars && cp -r attachments /data/ 2>/dev/null; cp -r avatars /data/ 2>/dev/null; chown -R 100:101 /data'
docker compose start fakejira
```

(`docker volume ls` shows the exact volume name; `100:101` is the `app` user of the image — check with `docker compose exec fakejira id`.)

### PostgreSQL

The built-in H2 database is fine for small teams. To use PostgreSQL instead:

```bash
# in .env
POSTGRES_PASSWORD=<something long>
docker compose -f docker-compose.yml -f docker-compose.postgres.yml up -d --build
```

`docker-compose.postgres.yml` starts a `postgres:16` container and points the app at it. **Moving existing data:** for the first start, also set `APP_MIGRATE_FROM_URL=jdbc:h2:file:/data/fakejira` (plus `APP_MIGRATE_FROM_PASSWORD` if your H2 database has one). The app copies everything into the empty PostgreSQL database once and logs how many rows it moved; it never copies into a database that already has users. Remove the variable afterwards. Keep the old volume until you have checked the result. With PostgreSQL, backups contain a `database.sql` dump instead of the H2 file.

### Off-site backups

Set `APP_BACKUP_S3_ENDPOINT`, `APP_BACKUP_S3_BUCKET`, `APP_BACKUP_S3_ACCESS_KEY` and `APP_BACKUP_S3_SECRET_KEY` (AWS S3, Cloudflare R2, Backblaze B2, MinIO…; `APP_BACKUP_S3_REGION` for AWS, `APP_BACKUP_S3_PATH_STYLE=false` for virtual-hosted buckets) and/or `APP_BACKUP_WEBDAV_URL` with username and password (Nextcloud: `https://cloud.example.com/remote.php/dav/files/USER/Backups`). Every backup is then uploaded after it is written. **Admin → System** shows the last upload and has a *Copy latest backup now* button.

### Monitoring

`GET /api/health` returns `{"status":"UP","database":"UP","version":…}` without signing in (the Docker health check uses it). Set `APP_METRICS_TOKEN` and scrape `/api/metrics` with `Authorization: Bearer <token>` for Prometheus metrics (API requests, users, tasks, storage, backups, JVM, disk). **Admin → System** shows version, database, disk, memory, contents and storage per project, lets you set attachment quotas, and checks for new releases.

### Email in

Set `APP_MAIL_INBOUND_ADDRESS` to a mailbox that supports plus-addressing (`jira+anything@example.com` arrives at `jira@example.com`) and either poll it over IMAPS (`APP_MAIL_INBOUND_IMAP_*`) or have an inbound-mail service POST to `/api/inbound/email` with the `X-Inbound-Secret` header set to `APP_MAIL_INBOUND_SECRET`. Notification emails then get a signed reply address — replies become comments — and each project shows an address in **Project settings** that turns mail into tasks (members only).

### Automation, webhooks and API tokens

Project owners set up rules under **Automation**, and outgoing webhooks under **Project settings → Outgoing webhooks**. Each webhook delivery is a JSON POST with `X-FakeJIRA-Event` and `X-FakeJIRA-Signature: sha256=<HMAC of the body with the secret>`. Personal API tokens (**Profile → API tokens**) work as bearer tokens for the whole task API, but not for admin, profile or account endpoints.

### GitHub integration

(GitLab and Gitea/Forgejo work the same way; pick the host in **Project settings → Git hosting**.)

As the project owner open **Project settings → Git hosting → Connect a repository**. In the GitHub repository go to **Settings → Webhooks → Add webhook**, paste the payload URL and secret shown, pick content type `application/json` and the **Pushes** and **Pull requests** events. Task keys in commit messages, branch names and pull request titles/bodies then link commits and PRs to tasks (shown under *Development* on the task). Turn on *Move tasks to Done when a pull request is merged* to close tasks automatically. Deliveries are verified with the `X-Hub-Signature-256` HMAC.

### Push notifications and installing the app

Each user turns on push in **Profile → Push notifications** (per device). This needs HTTPS (fine behind Caddy/Cloudflare). On iPhone/iPad, first add FakeJIRA to the home screen (Share → Add to Home Screen), then enable push from the installed app. Keys for push (VAPID) are generated on first use and stored in the database.

### Claude assistant

Set `ANTHROPIC_API_KEY` to turn on the Claude features (the model is `claude-opus-5-5` unless `APP_AI_MODEL` says otherwise). They only run when someone clicks them, and only send the task, comment or project text needed for that request to the Anthropic API. Claude never changes anything by itself: every answer is a suggestion that a person reviews and applies, with the usual permissions. If Claude declines a request, it is retried on Anthropic's recommended fallback model; set `APP_AI_FALLBACKS=false` to turn that off. Each person can make `APP_AI_REQUESTS_PER_HOUR` requests per hour (default 60), and every request is in the audit log.

### Company sign-in (OpenID Connect, SAML, LDAP) and SCIM

- **OpenID Connect** (Okta, Entra ID, Keycloak, Auth0, Google Workspace…): set `APP_OAUTH_OIDC_ISSUER`, `APP_OAUTH_OIDC_CLIENT_ID` and `APP_OAUTH_OIDC_CLIENT_SECRET`, and register `<APP_BASE_URL>/api/auth/oauth/oidc/callback` as the redirect URL. Set `APP_OAUTH_OIDC_TRUST_EMAIL=true` if your provider does not send `email_verified` but only has verified addresses (Entra ID).
- **SAML 2.0**: give your identity provider the metadata at `<APP_BASE_URL>/api/auth/saml/metadata`, then set `APP_SAML_IDP_SSO_URL`, `APP_SAML_IDP_ENTITY_ID` and `APP_SAML_IDP_CERTIFICATE` (the IdP's signing certificate, PEM).
- **LDAP / Active Directory**: set `APP_LDAP_URL` (e.g. `ldaps://ldap.example.com:636`) and either `APP_LDAP_USER_DN_PATTERN` (`uid={0},ou=people,dc=example,dc=com`) or `APP_LDAP_USER_SEARCH_BASE` with an optional service account (`APP_LDAP_BIND_DN`, `APP_LDAP_BIND_PASSWORD`).
- **SCIM 2.0**: create a token in **Admin → Sign-in & access** and give your identity provider the SCIM base URL shown there. It creates, updates and deactivates accounts and syncs groups to teams.

In **Admin → Sign-in & access** admins can also require single sign-on (admins keep password access), set session length and idle timeout, turn on sign-in alerts and restrict access to an **IP allowlist**. If the allowlist ever locks everyone out, start once with `APP_SECURITY_IP_ALLOWLIST_DISABLED=true`.

### Encryption at rest

Set `APP_STORAGE_ENCRYPTION_KEY` to 32 random bytes in base64 (`openssl rand -base64 32`) to encrypt attachments with AES-256-GCM, then use **Admin → Data & backups → Encrypt all files now** for existing files. Keep the key safe: files and backups cannot be read without it. To change it, put the old key in `APP_STORAGE_PREVIOUS_ENCRYPTION_KEYS` (comma-separated) and run *Encrypt all files now* again.

### Running several instances

With PostgreSQL and a shared data volume, you can run several app instances behind a load balancer. Set `APP_REDIS_URL` (e.g. `redis://redis:6379`) on all of them: live updates reach people on every instance, scheduled jobs run on one instance only, and SAML sign-in hand-offs and admin settings are shared.

### Integrations in 5.0

- **GitHub repository** (Project settings → GitHub repository): with a fine-grained token, create branches and draft pull requests from tasks and sync issues both ways.
- **CI/CD status**: GitHub checks and workflow runs, commit statuses and GitLab pipelines arrive through the Git hosting webhook; any other CI can POST results to the endpoint shown in project settings.
- **Chat commands** (Project settings → Chat commands): `/fakejira` in Slack, Mattermost and Discord to look up, search and create tasks, plus task link previews in Slack.
- **Google Calendar** (Profile): uses the Google sign-in client unless `APP_GOOGLE_CALENDAR_CLIENT_ID` / `_SECRET` are set; register `<APP_BASE_URL>/api/integrations/google-calendar/callback` as a redirect URL.
- **REST hooks** for Zapier, n8n and Make, a command-line tool at `/cli/fakejira` (Python 3) and the interactive API reference at `/api-docs`.

### Service desk

Turn on the request portal in **Project settings → Service desk** and build request forms there. The portal is at `/portal/<KEY>`, the public roadmap and changelog at `/public/<KEY>/roadmap` and `/public/<KEY>/changelog`, and the feedback widget is one `<script>` tag shown in the same settings. Requesters need no account; they get a private tracking link.

### Upgrading

**From 4.x to 5.0:** deploy the new version on your existing database; new tables and columns are added automatically, and existing projects, tasks and settings carry over. All new integrations — Claude, company sign-in, SCIM, Google Calendar, chat commands, Redis, encryption — stay off until configured. Sessions stay valid.

**From 3.x to 4.0:** deploy the new version on your existing database; new tables and columns are added automatically and nothing needs to be migrated by hand. Everyone signs in once after the upgrade (sign-ins are now revocable sessions, which older tokens are not). All 4.0 integrations (OAuth, email in, off-site backups, metrics) stay off until configured.

**From 2.x to 3.0:** deploy the new version on your existing database; the schema is updated automatically. The oldest account becomes admin, email notification settings carry over, and existing projects get the default four board columns. Make sure `APP_BACKUP_DIR` points into the data volume (the Dockerfile sets `/data/backups`).

**From 2.0:** just deploy the new version on your existing database. On first start, all existing tasks move into a project called **FakeJIRA** (key `FJ`) and keep their `FJ-<number>` keys. Every existing user becomes a member of it. Logins stay valid if `APP_JWT_SECRET` is unchanged.

## Tests

```bash
cd backend && mvn test          # API integration tests (projects, sprints, roles, workflows, wiki, portal, forecasts, SSO, SCIM, Claude, restore, upgrade…)
cd frontend && npm run build    # type-check + production build
```

GitHub Actions runs the backend tests, the frontend build and a Docker build on every push and pull request (`.github/workflows/ci.yml`).

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
| GET / PUT | `/api/profile`, `/api/profile/settings`, `/api/profile/password` | Profile, display name and email frequency, password change |
| POST / DELETE | `/api/profile/avatar` | Upload (PNG/JPEG/GIF/WebP, 2 MB) or remove your profile picture |
| PUT | `/api/projects/{key}/members/{userId}/role` | Change a member's role (`MEMBER` / `VIEWER`) |
| GET / POST · PUT / DELETE | `/api/projects/{key}/epics` · `/api/epics/{id}` | Epics |
| GET / POST · PUT / DELETE · POST | `/api/projects/{key}/columns` · `/api/columns/{id}` · `/api/columns/{id}/move?direction=` | Board columns |
| GET | `/api/projects/{key}/velocity`, `/api/projects/{key}/time?from=&to=` | Velocity and time reports |
| GET / POST · DELETE | `/api/projects/{key}/filters` · `/api/filters/{id}` | Saved filters |
| GET · POST | `/api/projects/{key}/export.csv` · `/api/projects/{key}/import` | CSV export / import (`multipart`, field `file`) |
| POST | `/api/tasks/bulk` | Bulk change (`taskIds` plus the change) |
| GET / POST / DELETE | `/api/tasks/{id}/links`, `/api/tasks/{id}/time`, `/api/tasks/{id}/watch` | Links, time entries, watching |
| GET | `/api/tasks/{id}/subtasks`, `/api/tasks/{id}/dev`, `/api/tasks/key/{key}` | Subtasks, GitHub links, lookup by key |
| GET / POST / PUT / DELETE | `/api/projects/{key}/github` | GitHub webhook settings (owner) |
| POST | `/api/integrations/github/{key}` | GitHub webhook endpoint |
| GET / POST / DELETE | `/api/invites` | Invite links |
| GET / PUT / POST / DELETE | `/api/admin/...` | Admin: sign-up mode, users, backups |
| GET · POST / DELETE | `/api/push/key` · `/api/push/subscribe` | Web push |
| GET | `/api/search?q=<query>`, `/api/search/text?q=` | Query-language search / full-text search |
| GET / POST · PUT / DELETE | `/api/projects/{key}/automations` · `/api/automations/{id}` | Automation rules (`/run`, `/log`) |
| GET / POST · PUT / DELETE | `/api/projects/{key}/webhooks` · `/api/webhooks/{id}` | Outgoing webhooks |
| GET / POST · PUT / DELETE · PUT | `/api/projects/{key}/fields` · `/api/fields/{id}` · `/api/tasks/{id}/fields/{fieldId}` | Custom fields and values |
| GET / POST · POST | `/api/projects/{key}/releases` · `/api/releases/{id}/ship` | Releases |
| GET · GET | `/api/calendar?from=&to=` · `/api/calendar/feed/{token}.ics` | Calendar / iCal feed |
| GET / POST / DELETE | `/api/profile/tokens` | Personal API tokens |
| GET / POST / DELETE | `/api/tasks/{id}/shares` · `/api/public/share/{token}` | Share links / public read-only view |
| GET · GET | `/api/health` · `/api/metrics` | Health (public) / Prometheus metrics (metrics token) |
| GET | `/api/project-templates` | Templates for new projects (`template` in `POST /api/projects`) |

## Contact

If you have trouble building the project, email erosbalint3@proton.me.
