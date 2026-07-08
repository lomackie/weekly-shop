import base64
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager
from dataclasses import asdict
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from pydantic import BaseModel, Field

from . import db, matching
from .config import get_settings
from .ink import render_strokes, segment_lines
from .ocado import OcadoService
from .recognition import build_recognizer


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    app.state.settings = settings
    app.state.conn = db.connect(settings.db_path)
    app.state.recognizer = build_recognizer(settings)
    # Lazy: no browser is launched until the first Ocado call.
    app.state.ocado = OcadoService(Path(settings.ocado_state_path))
    yield
    app.state.ocado.close()
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
    ocado_uuid: str | None = None
    aliases: list[str] = Field(default_factory=list)


class ItemUpdate(BaseModel):
    name: str | None = None
    ocado_id: str | None = None
    ocado_uuid: str | None = None


class AliasIn(BaseModel):
    alias: str


class ResolveRequest(BaseModel):
    item_id: int


class ProductOut(BaseModel):
    product_id: str
    sku: str
    name: str
    brand: str
    pack_size: str
    price: str
    available: bool
    sponsored: bool
    quantity_in_basket: int
    image: str = ""


def _candidate_out(c: matching.Candidate) -> ItemOut:
    return ItemOut(id=c.item_id, name=c.name, ocado_id=c.ocado_id, score=c.score)


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.get("/")
def root() -> RedirectResponse:
    # Relative redirect so it also works behind a path-prefix reverse proxy.
    return RedirectResponse(url="panel")


@app.get("/panel", response_class=HTMLResponse)
def panel() -> str:
    return (Path(__file__).parent / "static" / "panel.html").read_text()


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
    return db.list_items(request.app.state.conn)


@app.post("/items", status_code=201)
def create_item(body: ItemIn, request: Request) -> dict:
    item_id = db.add_item(
        request.app.state.conn,
        body.name,
        body.ocado_id,
        body.aliases,
        ocado_uuid=body.ocado_uuid,
    )
    return {"id": item_id}


@app.put("/items/{item_id}")
def update_item(item_id: int, body: ItemUpdate, request: Request) -> dict:
    ok = db.update_item(
        request.app.state.conn,
        item_id,
        name=body.name,
        ocado_id=body.ocado_id,
        ocado_uuid=body.ocado_uuid,
    )
    if not ok:
        raise HTTPException(status_code=404, detail="unknown item")
    return {"ok": True}


@app.delete("/items/{item_id}")
def delete_item(item_id: int, request: Request) -> dict:
    if not db.delete_item(request.app.state.conn, item_id):
        raise HTTPException(status_code=404, detail="unknown item")
    return {"ok": True}


@app.post("/items/{item_id}/aliases", status_code=201)
def create_alias(item_id: int, body: AliasIn, request: Request) -> dict:
    conn = request.app.state.conn
    if db.get_item(conn, item_id) is None:
        raise HTTPException(status_code=404, detail="unknown item")
    db.add_alias(conn, body.alias, item_id)
    return {"ok": True}


@app.delete("/aliases/{alias_id}")
def delete_alias(alias_id: int, request: Request) -> dict:
    if not db.delete_alias(request.app.state.conn, alias_id):
        raise HTTPException(status_code=404, detail="unknown alias")
    return {"ok": True}


@app.get("/ocado/search")
def ocado_search(q: str, request: Request) -> list[ProductOut]:
    try:
        products = request.app.state.ocado.search(q)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=503, detail=str(exc))
    # Sponsored tiles repeat products that also appear organically; keep one
    # entry per product and prefer the organic copy.
    best: dict[str, ProductOut] = {}
    for p in products:
        out = ProductOut(**asdict(p))
        current = best.get(out.product_id)
        if current is None or (current.sponsored and not out.sponsored):
            best[out.product_id] = out
    return list(best.values())


@app.post("/ocado/submit")
def ocado_submit(request: Request) -> dict:
    """Push all matched, not-yet-submitted basket entries to the real trolley."""
    conn = request.app.state.conn
    ocado = request.app.state.ocado

    # Several entries of the same item (milk written twice) become one
    # quantity bump, since apply-quantity takes a delta.
    by_item: dict[int, dict] = {}
    for row in db.unsubmitted_matched(conn):
        group = by_item.setdefault(
            row["item_id"],
            {
                "name": row["name"],
                "ocado_id": row["ocado_id"],
                "ocado_uuid": row["ocado_uuid"],
                "entry_ids": [],
            },
        )
        group["entry_ids"].append(row["id"])

    pushed, failed = [], []
    for item_id, group in by_item.items():
        try:
            uuid = group["ocado_uuid"]
            if not uuid:
                uuid = _resolve_product_uuid(ocado, group["name"], group["ocado_id"])
                db.update_item(conn, item_id, ocado_uuid=uuid)
            ocado.change_quantity(uuid, len(group["entry_ids"]))
        except FileNotFoundError as exc:
            raise HTTPException(status_code=503, detail=str(exc))
        except Exception as exc:
            failed.append({"name": group["name"], "error": str(exc)})
            continue
        db.mark_submitted(conn, group["entry_ids"])
        pushed.append({"name": group["name"], "quantity": len(group["entry_ids"])})

    return {
        "submitted_entries": sum(p["quantity"] for p in pushed),
        "items": pushed,
        "failed": failed,
    }


def _resolve_product_uuid(ocado, name: str, sku: str) -> str:
    """Find the cart-API UUID for an item we only know by sku, via search."""
    for product in ocado.search(name):
        if product.sku == sku:
            return product.product_id
    raise LookupError(
        f"no product with sku {sku} in search results for {name!r}; "
        "re-pick the product in the control panel"
    )
