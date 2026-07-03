import io

from PIL import Image, ImageDraw

Point = dict  # {"x": float, "y": float, "t": int (ms, optional)}
Stroke = list[Point]


def render_strokes(
    strokes: list[Stroke],
    width: int = 1200,
    padding: int = 40,
    line_width: int = 6,
) -> bytes:
    """Render pen strokes to a black-on-white PNG, normalised to fit `width`."""
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

    image = Image.new("L", (width, height), color=255)
    draw = ImageDraw.Draw(image)

    def to_canvas(p: Point) -> tuple[float, float]:
        return (
            padding + (p["x"] - min_x) * scale,
            padding + (p["y"] - min_y) * scale,
        )

    for stroke in strokes:
        if len(stroke) == 1:
            x, y = to_canvas(stroke[0])
            r = line_width / 2
            draw.ellipse([x - r, y - r, x + r, y + r], fill=0)
        else:
            draw.line([to_canvas(p) for p in stroke], fill=0, width=line_width)

    buf = io.BytesIO()
    image.save(buf, format="PNG")
    return buf.getvalue()
