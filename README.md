# weekly-shop

A wall-mounted grocery list for our household. Handwriting on an Onyx BOOX Note
Air 2 Plus gets recognised, mapped to items we regularly buy from Ocado, and
collected into a basket.

```
┌─────────────────┐   strokes + PNG    ┌──────────────────────────────┐
│  BOOX tablet    │ ─────────────────▶ │  server (FastAPI, homelab)   │
│  (android/)     │ ◀───────────────── │  recognise → match → basket  │
└─────────────────┘   matched item     └──────────────────────────────┘
```

## Layout

- `server/` — Python/FastAPI backend: handwriting recognition, fuzzy matching
  of recognised text against known Ocado items, and the basket. See
  [server/README.md](server/README.md).
- `android/` — Kotlin app for the tablet: full-screen writing canvas that
  captures pen strokes and posts them to the server. See
  [android/README.md](android/README.md).

## The loop

1. Write an item name on the tablet with the pen (a finger rubs strokes out).
   There is nothing to tap: after a 2 s writing pause the app posts the ink
   to `POST /ink` automatically.
2. Server recognises the text, fuzzy-matches it against the item/alias table,
   and appends a basket entry.
3. The page is the list: basketed ink stays put and gains a ✓, and the
   basket badge ticks up. Rubbing an item out with a finger removes it from
   the basket as well. Ambiguous matches open a
   picker whose choice is learned as an alias. Ink the server can't parse is
   framed by a dashed highlight (from the server's `unparsed_regions`
   coordinates) — rub it out and rewrite.
4. The basket icon opens the list for review; deleting there also lifts the
   item's ink off the page. Edge chevrons flip between pages; a new page
   appears by flipping right off a written-on page, and empty pages collapse
   away.

## Deployment

The server runs on the homelab box (`beelink`) as a systemd user service on
port 8000; the app points at it via `server_url` in the Android resources.
Handwriting recognition is live via Claude Haiku: the recogniser defaults to
`auto`, which uses the Anthropic API when `ANTHROPIC_API_KEY` is set in
`server/.env` and the no-credentials stub otherwise (see
[server/README.md](server/README.md)).

Later: order placement via browser automation (no public Ocado API), and
fixing the raw e-ink pen latency (see
[android/README.md](android/README.md)).
