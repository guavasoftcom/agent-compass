# Contributing to Agent Compass

Thanks for considering a contribution. This project is licensed under the
[GNU General Public License v3.0](LICENSE); by submitting a pull request you agree that your
contribution is provided under the same license.

## Before you start

- For anything beyond a small fix, open an issue first to discuss the change — it saves rework if
  the approach needs adjusting.
- Build, test, and convention details live in [AGENTS.md](AGENTS.md), and in the per-directory
  `CLAUDE.md` files it links to (`backend/CLAUDE.md`, `frontend/CLAUDE.md`, and one per
  `frontend/src/pages/<Name>Page/`). Read the ones relevant to your change before touching the code —
  they cover data-model gotchas, naming conventions, and layout decisions that aren't obvious from
  the code alone.

## Development setup

See [AGENTS.md](AGENTS.md#run--build--test) for the full command reference. In short:

```sh
# Backend (port 8080) — Testcontainers integration tests need Docker running.
./backend/mvnw -f backend/pom.xml spring-boot:run
./backend/mvnw -f backend/pom.xml verify

# Frontend (port 5173)
yarn --cwd frontend install && yarn --cwd frontend dev
yarn --cwd frontend build
yarn --cwd frontend typecheck
yarn --cwd frontend lint
yarn --cwd frontend test --run
```

## Making a change

- Follow the conventions in [AGENTS.md](AGENTS.md), [backend/CLAUDE.md](backend/CLAUDE.md), and
  [frontend/CLAUDE.md](frontend/CLAUDE.md) — schema changes go through a new Flyway migration,
  controllers stay thin, charts and tables stay hand-built SVG/CSS, names stay fully spelled out.
- Add or update tests for the behavior you change (backend: Testcontainers/`@WebMvcTest`; frontend:
  Vitest, colocated as `<name>.test.ts(x)`).
- Update the relevant `CLAUDE.md` if your change alters a documented data flow, endpoint, or gotcha —
  those files are meant to stay accurate, not describe a past state of the code.
- Run the build/test/lint commands above before opening a pull request; CI runs the same checks on
  every PR (see `.github/workflows/pull-request.yml`).

## Pull requests

- Keep PRs focused — one logical change per PR is easier to review and revert if needed.
- Describe *why* the change is needed, not just what changed; link the issue it addresses if there is
  one.
- Don't commit `.env` files, secrets, or generated build output (`frontend/dist`, `backend/target`).

## Reporting bugs / requesting features

Open a GitHub issue with enough detail to reproduce (for a bug) or enough context to evaluate (for a
feature request) — expected vs. actual behavior, and relevant environment details (Docker vs.
from-source, browser, OS) where applicable.
