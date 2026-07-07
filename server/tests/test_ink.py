from weekly_shop.ink import segment_lines


def stroke(*points):
    return [{"x": x, "y": y, "t": 0} for x, y in points]


def test_empty():
    assert segment_lines([]) == []


def test_single_line_stays_together():
    # "milk": several strokes sharing one vertical band.
    strokes = [
        stroke((0, 10), (10, 60)),
        stroke((15, 20), (25, 55)),
        stroke((30, 15), (40, 58)),
    ]
    assert segment_lines(strokes) == [[0, 1, 2]]


def test_two_lines_split_top_to_bottom():
    strokes = [
        stroke((0, 200), (40, 260)),  # written second, sits lower
        stroke((0, 10), (40, 60)),
    ]
    assert segment_lines(strokes) == [[1], [0]]


def test_interleaved_strokes_group_by_band_not_order():
    strokes = [
        stroke((0, 10), (20, 60)),  # line 1
        stroke((0, 200), (20, 255)),  # line 2
        stroke((25, 15), (45, 58)),  # back up to line 1 (crossing a t)
    ]
    assert segment_lines(strokes) == [[0, 2], [1]]


def test_floating_dot_joins_its_line():
    # An i-dot hovering just above the ink band must not become its own line.
    strokes = [
        stroke((0, 30), (30, 80)),  # line 1 body, height 50
        stroke((12, 18), (14, 20)),  # the dot, 10 clear of the band
        stroke((0, 200), (30, 250)),  # line 2
    ]
    assert segment_lines(strokes) == [[0, 1], [2]]


def test_descender_overlap_merges():
    # A descender dipping into the next band's territory still counts as
    # overlap; the two strokes it touches belong to one line only if their
    # extents genuinely meet.
    strokes = [
        stroke((0, 10), (20, 90)),  # "g" with a deep tail
        stroke((25, 30), (45, 85)),
        stroke((0, 240), (30, 300)),  # clearly separate next line
    ]
    assert segment_lines(strokes) == [[0, 1], [2]]
