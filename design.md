# Design Brief — Wallet & Ledger API (Frontend)

**Stage:** 2 — **not started.** See [phases.md](phases.md#stage-2-frontend--later-separate-effort).
**Companion documents:** [prd.md](prd.md) · [architecture.md](architecture.md) · [rules.md](rules.md)

> **This document is reference material held for later.** No frontend work begins until every Stage 1 backend phase (1–8) is complete, merged, and tested. Nothing here is a licence to start building.

---

## ⚠️ Source Reconciliation Note

The Apple design-system reference was **not available** when this brief was written. Everything below is built from the tokens quoted directly in the task brief plus standard Apple HIG conventions.

| Confidence | Values |
|---|---|
| **Quoted directly — trustworthy** | Action Blue `#0066cc` · canvas white `#ffffff` · parchment `#f5f5f7` · ink `#1d1d1f` · `button-primary` (pill CTA) · `store-utility-card` · `search-input` · press animation `scale(0.95)` · Inter substitution for SF Pro |
| **Reconstructed — verify against the real reference** | The full type scale (sizes, weights, tracking) · the spacing scale · border radii · shadow values · muted gray hex values |

**First task when Stage 2 opens:** open the actual reference and reconcile the reconstructed rows. Where they differ, the reference wins — this file gets corrected, not the other way around.

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

### 🔶 Open Decision — Accent Strategy

**You need to confirm one of these before Stage 2 begins.**

**Option A — Single blue (strict reference fidelity)**
`--action: #0066cc` for every interactive element. Credit and debit distinguished by `+`/`−` signs and weight alone, no color at all.
*Most minimal, most faithful to the reference. Risk: a transaction list scans as undifferentiated at a glance.*

**Option B — Blue for actions, green for credits ✅ recommended**
`--action: #0066cc` — buttons, links, focus rings, interactive affordances only.
`--credit: #1d8a4e` — credit amounts and success confirmations only.
*Two colors with strictly separated jobs: blue means "you can do something here," green means "money came in." Neither ever does the other's job.*

I recommend **Option B**, with two constraints that matter more than the choice itself:

1. **The dashboard balance figure stays `--ink`, never green.** A balance is a neutral fact, not good news. Coloring it green makes a wallet read as a gain, and makes a genuinely low balance feel reassuring. Green is reserved for *change* — a credit that just happened.
2. **Debits are `--ink`, never red.** Red is reserved exclusively for validation errors and failure states. If every ordinary spend is red, the user learns to ignore red, and the one screen where it means "this transfer failed" no longer registers. A debit is normal; it gets a `−` sign and neutral ink.

`--credit: #1d8a4e` is a proposed value chosen to sit at similar weight and saturation to Action Blue — replace it if the reference already carries a green.

### Semantic — transaction states

| State | Treatment |
|---|---|
| **Credit** (money in) | `+` prefix · `--credit` (Option B) or `--ink` (Option A) · medium weight |
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

List only. No layouts, no components, no implementation — that work belongs to Stage 2.

| # | Screen | Purpose | Key API |
|---|---|---|---|
| 1 | **Login / Register** | Authenticate, obtain JWT | `POST /api/v1/auth/login`, `/register` |
| 2 | **Dashboard** | Balance hero + recent transactions | `GET /api/v1/accounts`, `.../transactions` |
| 3 | **Transfer form** | Move money between accounts | `POST /api/v1/transfers` |
| 4 | **Transaction history** | Full filterable list | `GET /api/v1/accounts/{id}/transactions` |

Four screens. Anything beyond them is out of scope for Stage 2 — see [prd.md §4](prd.md#4-out-of-scope).

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

## 7. When Stage 2 Opens

Order of operations, so the first session doesn't start by guessing:

1. Open the real Apple design reference; reconcile every **reconstructed** value flagged in §0, §2, §3, §5.
2. **Confirm the accent decision** in §2 — Option A or Option B.
3. Freeze the API contract against what Phases 1–8 actually shipped (endpoint list in [architecture.md §7](architecture.md#7-api-contract-summary)).
4. *Then* write the Stage 2 phase breakdown in [phases.md](phases.md), one screen per phase.

Steps 1–3 are inputs to step 4. Writing UI phases before them is the rework this document exists to prevent.
