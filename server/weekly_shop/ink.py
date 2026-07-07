import io

from PIL import Image, ImageDraw

Point = dict  # {"x": float, "y": float, "t": int (ms, optional)}
Stroke = list[Point]

# A dot or crossbar can float just clear of its line's ink band; anything
# within this fraction of the typical line height is folded back in.
LINE_SLACK = 0.3


def segment_lines(strokes: list[Stroke]) -> list[list[int]]:
    """Group strokes into handwritten lines by vertical position.

    One line = one item is the writing contract, so each returned group is
    recognised as a single item. Returns groups of indices into `strokes`,
    ordered top to bottom.
    """
    intervals = sorted(
        (
            (min(p["y"] for p in stroke), max(p["y"] for p in stroke), i)
            for i, stroke in enumerate(strokes)
            if stroke
        ),
    )
    if not intervals:
        return []

    # Pass 1: merge strokes whose vertical extents overlap.
    clusters: list[list] = []  # [top, bottom, indices]
    for top, bottom, idx in intervals:
        if clusters and top <= clusters[-1][1]:
            clusters[-1][1] = max(clusters[-1][1], bottom)
            clusters[-1][2].append(idx)
        else:
            clusters.append([top, bottom, [idx]])

    if len(clusters) > 1:
        # Pass 2: an i-dot or t-bar written clear of the ink band shows up as
        # a sliver cluster hovering near its line — fold near neighbours in.
        heights = sorted(bottom - top for top, bottom, _ in clusters)
        slack = LINE_SLACK * heights[len(heights) // 2]
        merged = [clusters[0]]
        for top, bottom, indices in clusters[1:]:
            if top - merged[-1][1] < slack:
                merged[-1][1] = max(merged[-1][1], bottom)
                merged[-1][2].extend(indices)
            else:
                merged.append([top, bottom, indices])
        clusters = merged

    return [sorted(indices) for _, _, indices in clusters]


def render_strokes(
    strokes: list[Stroke],
    width: int = 1200,
    padding: int = 40,
    pen_width: float = 6.0,
) -> bytes:
    """Render pen strokes to a black-on-white PNG, normalised to fit `width`.

    `pen_width` is in stroke coordinates (the tablet draws at STROKE_WIDTH=6f
    in the same space), so it scales with the ink — rendering at a fixed pixel
    width produced spidery hairlines that hurt recognition badly.
    """
    points = [p for stroke in strokes for p in stroke]
    if not points:
        raise ValueError("no ink to render")

    xs = [p["x"] for p in points]
    ys = [p["y"] for p in points]
    min_x, max_x = min(xs), max(xs)
    min_y, max_y = min(ys), max(ys)
    span_x = max(max_x - min_x, 1.0)
    span_y = max(max_y - min_y, 1.0)

    scale = (width - 2 * padding) / span_x
    height = int(span_y * scale) + 2 * padding
    line_width = max(6, round(pen_width * scale))

    image = Image.new("L", (width, height), color=255)
    draw = ImageDraw.Draw(image)

    def to_canvas(p: Point) -> tuple[float, float]:
        return (
            padding + (p["x"] - min_x) * scale,
            padding + (p["y"] - min_y) * scale,
        )

    radius = line_width / 2
    for stroke in strokes:
        canvas_points = [to_canvas(p) for p in stroke]
        if len(canvas_points) > 1:
            draw.line(canvas_points, fill=0, width=line_width, joint="curve")
        # Round caps: dots for single-point strokes, smooth stroke ends.
        for x, y in (canvas_points[0], canvas_points[-1]):
            draw.ellipse(
                [x - radius, y - radius, x + radius, y + radius], fill=0
            )

    buf = io.BytesIO()
    image.save(buf, format="PNG")
    return buf.getvalue()
