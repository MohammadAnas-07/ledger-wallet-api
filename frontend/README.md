# Frontend — Wallet & Ledger API

The Stage 2 UI. Four screens against the Stage 1 backend, built to
[design.md](../design.md); the chunk-by-chunk plan is in
[phases.md](../phases.md#stage-2-frontend--in-progress).

React 19 + Vite + TypeScript. No UI library and no component framework —
design.md §6 rules one out, and three patterns cover four screens.

## Running it

```bash
npm install
npm run dev
```

The dev server listens on <http://localhost:5173>.

**It needs the backend running.** From the repository root:

```bash
docker compose up
```

Calls to `/api/*` are proxied there by `vite.config.ts`. That proxy is not a
convenience — the backend has no CORS configuration, so a browser on :5173
cannot reach it directly, and going through the proxy makes every call
same-origin. Serving a production build from a different origin will need CORS
on the backend; that change is parked in phases.md rather than made here.

### The backend port is not always 8080

`docker compose up` publishes the app on `SERVER_PORT` from the repository root
`.env`, which is 8080 only if nothing else on that machine holds it. Point the
proxy at whatever it actually is:

```bash
cp .env.example .env.local   # then edit VITE_API_TARGET
```

`.env.local` is git-ignored, so each machine keeps its own. Getting this wrong
looks like a routing bug in this app: every API call returns a 404 served by
whatever else is listening on that port.

## Scripts

| Command | Does |
|---|---|
| `npm run dev` | Dev server with HMR, API proxy to the backend |
| `npm run build` | Type-check (`tsc -b`) then production build into `dist/` |
| `npm test` | Vitest, one pass. Pure logic only — no DOM, no jsdom |
| `npm run lint` | oxlint |
| `npm run preview` | Serve the built `dist/` — no proxy, so API calls will fail |

## Layout

```
src/
  api/
    types.ts     Every DTO, mirrored field for field
    json.ts      Parsing that keeps money exact
    errors.ts    Every failure as one type, plus what the user is told
    session.ts   Where the token lives, and who hears when it expires
    client.ts    The one place that talks to the backend
    endpoints.ts One function per endpoint, so no screen writes a URL
  format/
    money.ts     Amounts, always rendered at scale 2
  auth/          The session: provider, context, useAuth
  components/    Button, TextField, Notice — the three patterns, nothing more
  screens/
    auth/        Sign in and create account, in one screen with two modes
    signed-in/   Placeholder behind the session; becomes the dashboard
  styles/
    tokens.css   Every color, size, space, radius, and shadow in the app
    base.css     Reset, page defaults, the type roles from design.md §3
  main.tsx       Entry point; loads the font and both stylesheets
  App.tsx        The protected route: which screen the session allows
```

Tests sit beside what they test, as `*.test.ts`.

**No component hardcodes a design value.** A hex code, font size, or pixel
spacing outside `tokens.css` is a bug: it is a value that cannot be corrected
in one place. This matters more than usual here, because the Apple reference
design.md was built from was never available — the reconstructed values are
marked as such in `tokens.css`, and correcting them later should be an edit to
that one file.
