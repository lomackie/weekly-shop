import io

from PIL import Image, ImageDraw

Point = dict  # {"x": float, "y": float, "t": int (ms, optional)}
Stroke = list[Point]


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
