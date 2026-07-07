import base64
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, Field

from . import db, matching
from .config import get_settings
from .ink import render_strokes, segment_lines
from .recognition import build_recognizer


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    app.state.settings = settings
    app.state.conn = db.connect(settings.db_path)
    app.state.recognizer = build_recognizer(settings)
    yield
    app.state.conn.close()


app = FastAPI(title="weekly-shop", lifespan=lifespan)


class InkPoint(BaseModel):
    x: float
    y: float
    t: int = 0


class InkRequest(BaseModel):
    strokes: list[list[InkPoint]] = Field(default_factory=list)
    image_base64: str | None = None


class ItemOut(BaseModel):
    id: int
    name: str
    ocado_id: str
    score: float | None = None


class InkLine(BaseModel):
    """One handwritten line of the submission, recognised as one item."""

    raw_text: str
    status: str  # matched | ambiguous | unmatched
    item: ItemOut | None = None
    candidates: list[ItemOut] = Field(default_factory=list)
    basket_entry_id: int | None = None  # None when unmatched: nothing basketed
    # Which of the submitted strokes make up this line, so the tablet can tie
    # the outcome (✓ link, unparsed highlight) back to the exact ink.
    stroke_indices: list[int] = Field(default_factory=list)


class InkResponse(BaseModel):
    lines: list[InkLine]


class ItemIn(BaseModel):
    name: str
    ocado_id: str
    aliases: list[str] = Field(default_factory=list)


class ResolveRequest(BaseModel):
    item_id: int


def _candidate_out(c: matching.Candidate) -> ItemOut:
    return ItemOut(id=c.item_id, name=c.name, ocado_id=c.ocado_id, score=c.score)


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/ink", response_model=InkResponse)
def submit_ink(body: InkRequest, request: Request) -> InkResponse:
    settings = request.app.state.settings
    conn = request.app.state.conn
    recognizer = request.app.state.recognizer

    strokes = [[p.model_dump() for p in stroke] for stroke in body.strokes]
    if strokes:
        # One line = one item: split the submission before recognition so a
        # multi-item batch never gets transcribed as one garbled item.
        groups = segment_lines(strokes)
        images = [render_strokes([strokes[i] for i in group]) for group in groups]
    elif body.image_base64:
        # Image-only submissions can't be segmented; treat as a single line.
        groups = [[]]
        images = [base64.b64decode(body.image_base64)]
    else:
        raise HTTPException(status_code=422, detail="strokes or image_base64 required")

    vocabulary = sorted(
        {text for text, _ in db.alias_candidates(conn)}, key=str.lower
    )

    def recognize(image_and_group: tuple[bytes, list[int]]) -> str:
        image, group = image_and_group
        return recognizer.recognize(
            image, [strokes[i] for i in group], vocabulary=vocabulary
        )

    # Lines are independent, so a multi-line batch costs about the same wall
    # time as one line. Only recognition runs off-thread: the sqlite
    # connection stays on this thread.
    if len(images) == 1:
        texts = [recognize((images[0], groups[0]))]
    else:
        with ThreadPoolExecutor(max_workers=min(len(images), 8)) as pool:
            texts = list(pool.map(recognize, zip(images, groups)))

    lines = []
    for text, group in zip(texts, groups):
        result = matching.match(
            conn, text, settings.match_threshold, settings.candidate_threshold
        )

        if result.status == "unmatched":
            # Nothing lands in the basket; the tablet highlights this line's
            # strokes so the writer knows to rub out and retry.
            lines.append(
                InkLine(raw_text=text, status=result.status, stroke_indices=group)
            )
            continue

        item_id = result.best.item_id if result.best else None
        entry_id = db.add_basket_entry(conn, text, item_id, result.status)
        if result.best:
            # Learn this handwriting-as-recognised as an alias for next time.
            db.add_alias(conn, text, result.best.item_id)

        lines.append(
            InkLine(
                raw_text=text,
                status=result.status,
                item=_candidate_out(result.best) if result.best else None,
                candidates=[_candidate_out(c) for c in result.candidates],
                basket_entry_id=entry_id,
                stroke_indices=group,
            )
        )

    return InkResponse(lines=lines)


@app.get("/basket")
def get_basket(request: Request) -> list[dict]:
    return [dict(row) for row in db.list_basket(request.app.state.conn)]


@app.post("/basket/{entry_id}/resolve")
def resolve_entry(entry_id: int, body: ResolveRequest, request: Request) -> dict:
    conn = request.app.state.conn
    item = db.get_item(conn, body.item_id)
    if item is None:
        raise HTTPException(status_code=404, detail="unknown item")
    if not db.resolve_basket_entry(conn, entry_id, body.item_id):
        raise HTTPException(status_code=404, detail="unknown basket entry")
    entry = conn.execute(
        "SELECT raw_text FROM basket_entries WHERE id = ?", (entry_id,)
    ).fetchone()
    db.add_alias(conn, entry["raw_text"], body.item_id)
    return {"ok": True}


@app.delete("/basket/{entry_id}")
def delete_entry(entry_id: int, request: Request) -> dict:
    if not db.delete_basket_entry(request.app.state.conn, entry_id):
        raise HTTPException(status_code=404, detail="unknown basket entry")
    return {"ok": True}


@app.get("/items")
def get_items(request: Request) -> list[dict]:
    rows = request.app.state.conn.execute("SELECT * FROM items ORDER BY name")
    return [dict(row) for row in rows]


@app.post("/items", status_code=201)
def create_item(body: ItemIn, request: Request) -> dict:
    item_id = db.add_item(
        request.app.state.conn, body.name, body.ocado_id, body.aliases
    )
    return {"id": item_id}
