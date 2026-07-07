# weekly-shop server

FastAPI backend: receives ink from the tablet, recognises the handwriting,
fuzzy-matches it against our known Ocado items, and keeps the basket.

## Run

```sh
cd server
uv sync
uv run python -m weekly_shop.seed seed/items.example.json
uv run uvicorn weekly_shop.main:app --host 0.0.0.0 --port 8000
```

Note: the ocado_ids in `seed/items.example.json` are placeholders — replace
them with real SKUs from your order history (the number in the product URL,
e.g. `ocado.com/products/...-13175011`).

## Configuration

Environment variables (or `.env` in `server/` or the repo root), all prefixed
`WEEKLY_SHOP_`:

| Variable            | Default                      | Notes                                    |
| ------------------- | ---------------------------- | ---------------------------------------- |
| `DB_PATH`           | `weekly_shop.sqlite3`        | SQLite file                              |
| `RECOGNIZER`        | `auto`                       | `auto`, `stub`, `claude`, or `openai`; `auto` prefers claude, then openai, by configured key |
| `STUB_TEXT`         | `milk`                       | what the stub "recognises"               |
| `ANTHROPIC_API_KEY` | —                            | required for `claude`; the unprefixed name `ANTHROPIC_API_KEY` also works |
| `ANTHROPIC_MODEL`   | `claude-haiku-4-5-20251001`  |                                          |
| `OPENAI_API_KEY`    | —                            | required for `openai`; the unprefixed name `OPENAI_API_KEY` also works |
| `OPENAI_MODEL`      | `gpt-5.4-mini`               |                                          |
| `MATCH_THRESHOLD`   | `87`                         | score (0-100) to auto-accept a match     |
| `CANDIDATE_THRESHOLD` | `55`                       | minimum score to offer as a candidate    |

## API

- `POST /ink` — `{strokes: [[{x,y,t},…],…], image_base64?}`. Renders the
  strokes to PNG (unless an image is supplied), recognises, matches, and adds
  a basket entry. Returns `{raw_text, status, item?, candidates,
  basket_entry_id?, unparsed_regions}` where `status` is `matched` /
  `ambiguous` / `unmatched`. Unmatched ink is **not** basketed: instead
  `unparsed_regions` carries bounding boxes (`{left, top, right, bottom}`, in
  the tablet's stroke coordinate space) that the tablet highlights so the
  writer knows to rub the ink out and retry. There is no real region
  detection yet — the whole submission's bounding box is flagged.
- `GET /basket`, `DELETE /basket/{id}`
- `POST /basket/{id}/resolve` — `{item_id}`; fixes up an ambiguous entry and
  learns the raw text as an alias for that item.
- `GET /items`, `POST /items` — `{name, ocado_id, aliases?}`
- `GET /health`

Matched text is auto-learned as an alias, so recognition of your household's
handwriting quirks improves with use.

## Tests

```sh
uv run pytest
```

## Recognisers

- `stub` — fixed response; lets the whole pipeline run with no credentials.
- `claude` — sends the rendered PNG to the Anthropic API (Haiku by default);
  very accurate on messy handwriting, costs pennies at household volume.
- Adding a local backend (e.g. TrOCR) means implementing the two-method
  `Recognizer` protocol in `weekly_shop/recognition.py` — strokes are passed
  through as well as the image, so online recognition is possible too.
