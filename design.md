# Design Brief — Wallet & Ledger API (Frontend)

**Stage:** 2 — **in progress**, started 2026-08-30. See [phases.md](phases.md#stage-2-frontend--in-progress).
**Companion documents:** [prd.md](prd.md) · [architecture.md](architecture.md) · [rules.md](rules.md)

> **Stage 1 is complete and merged, so this brief is now live.** It was held out of context until that was true, deliberately. What was *reconstructed* when it was written is still reconstructed — see the note directly below.

---

## ⚠️ Source Reconciliation Note

The Apple design-system reference was **not available** when this brief was written. Everything below is built from the tokens quoted directly in the task brief plus standard Apple HIG conventions.

| Confidence | Values |
|---|---|
| **Quoted directly — trustworthy** | Action Blue `#0066cc` · canvas white `#ffffff` · parchment `#f5f5f7` · ink `#1d1d1f` · `button-primary` (pill CTA) · `store-utility-card` · `search-input` · press animation `scale(0.95)` · Inter substitution for SF Pro |
| **Reconstructed — verify against the real reference** | The full type scale (sizes, weights, tracking) · the spacing scale · border radii · shadow values · muted gray hex values |

**That first task, attempted 2026-08-30:** the reference was still unavailable when Stage 2 opened, so nothing could be reconciled. The reconstructed values below stand as written, and carry into the token file with a comment marking them unverified. If the reference turns up later the reference still wins — but by then the correction is an edit to one stylesheet, not to every component, which is the entire reason these values are tokens.

---

## 1. Design Philosophy

Apple-style minimalism adapted for a financial dashboard: **one accent color, generous whitespace, and no decorative chrome.** Numbers are the interface — balances and transaction amounts get visual priority, and every UI element that isn't data earns its place or is removed.

A wallet screen is read, not browsed. The design succeeds when a user can answer "how much do I have, and what just moved?" in under two seconds, without a single decorative element competing for that attention.

---

## 2. Color Palette

Trimmed to the minimum this app needs. Six values total — new hues are not introduced without deleting one.

### Core

| Token | Hex | Use |
|---|---|---|
| `--canvas` | `#ffffff` | Default page background, card surfaces |
| `--parchment` | `#f5f5f7` | Alternating sections, page background behind white cards, input fills |
| `--ink` | `#1d1d1f` | Primary text, headings, **and all balance/amount figures** |
| `--ink-muted` | `#6e6e73` | Secondary text, labels, counterparty names |
| `--ink-subtle` | `#86868b` | Timestamps, metadata, placeholder text |
| `--separator` | `#d2d2d7` | Hairline dividers between transaction rows, input borders |

`--ink-muted`, `--ink-subtle`, and `--separator` are reconstructed values — verify against the reference.

### ✅ Decision — Accent Strategy (2026-08-30)

**Option B is confirmed.** Two colors, strictly separated jobs.

| Token | Hex | Its only job |
|---|---|---|
| `--action` | `#0066cc` | Buttons, links, focus rings, interactive affordances |
| `--credit` | `#1d8a4e` | Credit amounts, success confirmations |
| `--error` | `#d70015` | Validation errors, failure states |

Blue means "you can do something here." Green means "money came in." Neither ever does the other's job.

*Option A — a single blue everywhere, credit and debit separated by `+`/`−` and weight alone — was rejected. It is the more faithful reading of the reference, but a transaction list scanning as undifferentiated is not an acceptable cost when the list is the product.*

Two constraints ride along with the choice, and they matter more than the choice itself:

1. **The dashboard balance figure stays `--ink`, never green.** A balance is a neutral fact, not good news. Coloring it green makes a wallet read as a gain, and makes a genuinely low balance feel reassuring. Green is reserved for *change* — a credit that just happened.
2. **Debits are `--ink`, never red.** Red is reserved exclusively for validation errors and failure states. If every ordinary spend is red, the user learns to ignore red, and the one screen where it means "this transfer failed" no longer registers. A debit is normal; it gets a `−` sign and neutral ink.

`--credit: #1d8a4e` is a proposed value chosen to sit at similar weight and saturation to Action Blue — replace it if the reference already carries a green.

### Semantic — transaction states

| State | Treatment |
|---|---|
| **Credit** (money in) | `+` prefix · `--credit` · medium weight |
| **Debit** (money out) | `−` prefix · `--ink` · medium weight |
| **Pending** | `--ink-subtle`, italic optional |
| **Failed** | `--error: #d70015` — reserved for this and form validation only |

`--error` is reconstructed. Note the alignment with the backend contract: a `409 CONCURRENT_MODIFICATION` is a *retryable* state, not a failure — it should read as a retry prompt, not a red error. See [architecture.md §7](architecture.md#7-api-contract-summary).

---

## 3. Typography

**Font stack.** SF Pro is not licensed off Apple platforms, so **Inter** is the substitution — it shares SF Pro's proportions and neutral character closely enough to preserve the reference's feel.

```css
font-family: "Inter", -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
```

`-apple-system` first-in-stack after Inter means genuine Apple devices still render real SF Pro. Inter needs slightly tighter tracking than SF Pro at display sizes — the negative tracking on `hero-display` accounts for that.

### Scale — trimmed to four roles plus buttons

*(Sizes and weights reconstructed — verify against the reference.)*

| Role | Size / Weight | Tracking | Used for |
|---|---|---|---|
| `hero-display` | 56px / 700 | −0.02em | The dashboard balance figure. One per screen, never two. |
| `title` | 28px / 600 | −0.01em | Screen headings, card titles |
| `body` | 17px / 400 | 0 | Transaction rows, form labels, general copy |
| `caption` | 13px / 400 | 0 | Timestamps, metadata, helper text — always `--ink-subtle` |
| `button` | 17px / 500 | 0 | All button labels |

Four text roles is the whole system. If a fifth seems necessary, the layout is doing too much.

### Tabular numerals — mandatory

```css
font-variant-numeric: tabular-nums;
```

Applied to **every** figure: the balance hero, transaction amounts, account numbers. Without it, proportional digits make a column of amounts ragged and genuinely harder to compare — the single highest-impact typographic decision in a financial UI. Amounts are also **right-aligned** in lists so decimal points stack.

Amounts always render at fixed scale 2 (`1,250.00`, never `1250` or `1250.5`), matching the `BigDecimal(19,2)` contract in [architecture.md §2](architecture.md#2-entity-design).

---

## 4. Screens to Design

List only. No layouts, no components, no implementation — that work belongs to the Stage 2 build chunks in [phases.md](phases.md#stage-2-frontend--in-progress).

| # | Screen | Purpose | Key API |
|---|---|---|---|
| 1 | **Login / Register** | Authenticate, obtain JWT | `POST /api/v1/auth/login`, `/register` |
| 2 | **Dashboard** | Balance hero + recent transactions | `GET /api/v1/accounts`, `.../transactions` |
| 3 | **Transfer form** | Move money between accounts | `POST /api/v1/transfers` |
| 4 | **Transaction history** | Full filterable list | `GET /api/v1/accounts/{id}/transactions` |

### Addition — deposit action (2026-08-30)

Four screens was the plan, and it had a hole in it: **nothing on that list puts money into an account.** A user registers, creates a wallet, sees `0.00`, and cannot use the transfer form at all — the one screen the whole ledger exists to serve is unreachable from a cold start.

Closed with the smallest thing that closes it: **a deposit action on the Dashboard.** A secondary button and an amount input calling `POST /api/v1/accounts/{id}/deposit`. Not a fifth screen, no route of its own, no entry in the table above.

**Withdraw is deliberately excluded.** It is the same form against a different endpoint, and it demonstrates nothing a transfer does not already demonstrate — including the `422 INSUFFICIENT_FUNDS` path, which the transfer form exercises anyway.

Beyond these, out of scope for Stage 2 — see [prd.md §4](prd.md#4-out-of-scope).

**States each screen must account for** (easy to forget at design time, painful to retrofit): loading, empty (no accounts, no transactions yet), error, and — specific to this app — the **`409` retry state** on the transfer form. That last one is not an error message; it is a "this didn't go through, try again" prompt, and it needs a designed treatment because the backend produces it by design under concurrent load.

---

## 5. Component Reuse from the Reference

Three patterns carry the entire UI.

### `button-primary` — pill CTA

Primary actions: **Log in**, **Send transfer**. Fill `--action`, white label, fully rounded pill (`border-radius: 980px` — reference convention), `button` type role, generous horizontal padding.

Secondary actions are the same geometry with a transparent fill and `--action` text. **One primary button per screen** — if two things are equally primary, neither is.

Disabled state matters here: the transfer submit button stays disabled while a request is in flight, which is the frontend's half of double-submit protection (the backend's half is the idempotency key from [prd.md §3.4](prd.md#34-transfer-between-accounts-double-entry-atomic)).

### `store-utility-card` → transaction row card

Adapted from the reference's utility card. Per row: counterparty name (`body`, `--ink`), timestamp (`caption`, `--ink-subtle`), amount right-aligned with sign and tabular numerals.

For a **list**, the card's chrome gets stripped down — rows separated by `--separator` hairlines on `--canvas`, not as individually shadowed floating cards. Fifty shadowed cards stacked vertically is visual noise; the card *pattern* survives, its elevation does not. Reserve the full card treatment (radius + shadow) for the balance hero and standalone panels.

### `search-input` — history filtering

The transaction history filter bar. `--parchment` fill, no border at rest, `--action` focus ring, `caption`-weight placeholder in `--ink-subtle`. Same style covers the date-range inputs so the filter bar reads as one unit.

### Supporting tokens

*(All reconstructed — verify against the reference.)*

- **Spacing:** 4px base — `4 · 8 · 16 · 24 · 32 · 48 · 64`. Section padding is generous; whitespace is the primary structuring device, not borders or boxes.
- **Radii:** `8px` inputs and small cards · `12px` large panels · `980px` pill buttons.
- **Shadows:** two only — resting `0 1px 3px rgba(0,0,0,0.06)`, raised `0 4px 16px rgba(0,0,0,0.08)`. Never stack or invent a third.

---

## 6. Non-Goals for This Phase

Explicitly excluded. Deliberate omissions, not oversights.

- **No dark mode.** Light theme only. Retrofitting is cheap if every color is a token from §2 from the start — which is the actual reason for tokenizing them now.
- **No animation beyond the reference's system-wide press feedback** — `transform: scale(0.95)` on button press, nothing more. No page transitions, no number count-ups on the balance, no row stagger. A balance animating upward on load is precisely the kind of decorative chrome §1 rules out.
- **No mobile-specific redesign.** Desktop-first. Responsive is a later, separate pass — not a reason to build two layouts now.
- **No component library or design system build-out.** Three patterns from §5 cover four screens; a token file and those three patterns are sufficient.
- **No charts, spending analytics, or data visualization.** Not in the [PRD scope](prd.md#4-out-of-scope). A balance figure and a transaction list are the product.
- **No custom illustration, iconography, or branding.** No logo, no mascot, no empty-state artwork. Empty states are a line of `body` text and, where useful, a single primary action.

---

## 7. Stage 2 Opening — What Actually Happened

Recorded 2026-08-30, against the order of operations this section prescribed.

| Step | Outcome |
|---|---|
| 1. Reconcile every reconstructed value against the real reference | **Not done — the reference was unavailable.** The reconstructed values stand. See §0. |
| 2. Confirm the accent decision | **Done — Option B.** See §2. |
| 3. Freeze the API contract against what Phases 1–8 shipped | **Done**, read from the controllers and DTOs rather than from documentation. Three consequences below. |
| 4. Write the Stage 2 phase breakdown | **Done** — [phases.md](phases.md#stage-2-frontend--in-progress). One feature per branch, not one screen per phase. |

### What the contract freeze turned up

Three things the UI has to be built around, none of them obvious from the screen list:

- **Register does not return a token.** `POST /api/v1/auth/register` answers `201` with a `UserResponse`; only `/login` issues one. A registration flow is therefore two calls, and the second can fail on its own — which is a state the Login/Register screen has to have a designed answer for.
- **The backend has no CORS configuration.** `SecurityConfig` never calls `.cors(...)`, so a browser on a different origin cannot reach it at all. Development goes through a Vite proxy; the backend change is parked in [phases.md](phases.md#parking-lot) rather than made here, because Stage 1 is complete and tested and does not need reopening to build a UI.
- **The API never discloses another party's balance.** `TransferResponse` carries `fromBalanceAfter` only, and `TransactionDetailResponse` omits `balanceAfter` on both entries by design. There is no "recipient's balance after" to show — do not design a slot for one.
