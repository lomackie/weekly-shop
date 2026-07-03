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

## PoC loop

1. Write an item name on the tablet, tap **Done**.
2. App posts the ink strokes (and a rendered PNG) to `POST /ink`.
3. Server recognises the text, fuzzy-matches it against the item/alias table,
   and appends a basket entry.
4. App shows what was matched; canvas clears for the next item.

Later: basket view on the tablet, multiple-choice resolution for ambiguous
matches, and order placement via browser automation (no public Ocado API).
