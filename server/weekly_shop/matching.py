import sqlite3
from dataclasses import dataclass, field

from rapidfuzz import fuzz, process

from . import db


@dataclass
class Candidate:
    item_id: int
    name: str
    ocado_id: str
    score: float


@dataclass
class MatchResult:
    status: str  # matched | ambiguous | unmatched
    best: Candidate | None = None
    candidates: list[Candidate] = field(default_factory=list)


def match(
    conn: sqlite3.Connection,
    text: str,
    match_threshold: float,
    candidate_threshold: float,
    limit: int = 4,
) -> MatchResult:
    """Fuzzy-match recognised text against item names and learned aliases."""
    choices = db.alias_candidates(conn)
    if not text.strip() or not choices:
        return MatchResult(status="unmatched")

    hits = process.extract(
        text.strip(),
        [c[0] for c in choices],
        scorer=fuzz.WRatio,
        processor=str.lower,
        score_cutoff=candidate_threshold,
        limit=len(choices),
    )

    # Several aliases can point at the same item; keep each item's best score.
    best_by_item: dict[int, Candidate] = {}
    for _, score, idx in hits:
        item_id = choices[idx][1]
        if item_id in best_by_item and best_by_item[item_id].score >= score:
            continue
        item = db.get_item(conn, item_id)
        best_by_item[item_id] = Candidate(
            item_id=item_id, name=item["name"], ocado_id=item["ocado_id"], score=score
        )

    candidates = sorted(best_by_item.values(), key=lambda c: -c.score)[:limit]
    if not candidates:
        return MatchResult(status="unmatched")
    if candidates[0].score >= match_threshold:
        return MatchResult(status="matched", best=candidates[0], candidates=candidates)
    return MatchResult(status="ambiguous", candidates=candidates)
