"""Minimal stdlib SVG primitives shared by the plotting CLIs.

The benchmark figures are hand-built SVG rather than matplotlib output: no extra
dependency for a repository whose analyzers are stdlib-only, text stays selectable
and searchable, and the files diff cleanly. Coordinates are in pixels; callers own
the layout.
"""

FONT = "DejaVu Sans, Helvetica, Arial, sans-serif"
INK = "#1f2328"
MUTED = "#57606a"
GRID = "#d0d7de"

# One colour per engine, used by the read and write figures.
COLORS = {
    "juring": "#1f6feb",
    "fio": "#e3742a",
    "seastar": "#2ea043",
    "matchedbatch": "#b45309",
    "sustained": "#2ea043",
    "fast": "#e3742a",
}


def esc(value):
    return (str(value).replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace('"', "&quot;"))


def text(x, y, s, size=12, anchor="start", fill=INK, weight="normal", opacity=None):
    extra = f' font-weight="{weight}"' if weight != "normal" else ""
    extra += f' opacity="{opacity}"' if opacity is not None else ""
    return (f'<text x="{x:.1f}" y="{y:.1f}" font-family="{FONT}" font-size="{size}" '
            f'fill="{fill}" text-anchor="{anchor}"{extra}>{esc(s)}</text>')


def line(x1, y1, x2, y2, stroke=INK, width=1, dash=None, opacity=None):
    extra = f' stroke-dasharray="{dash}"' if dash else ""
    extra += f' opacity="{opacity}"' if opacity is not None else ""
    return (f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" '
            f'stroke="{stroke}" stroke-width="{width}"{extra}/>')


def rect(x, y, w, h, fill, stroke=None, rx=0, opacity=None):
    extra = f' stroke="{stroke}" stroke-width="1"' if stroke else ""
    extra += f' opacity="{opacity}"' if opacity is not None else ""
    return (f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h:.1f}" '
            f'rx="{rx}" fill="{fill}"{extra}/>')


def circle(cx, cy, r, fill, stroke=None, width=1, opacity=None):
    extra = f' stroke="{stroke}" stroke-width="{width}"' if stroke else ""
    extra += f' opacity="{opacity}"' if opacity is not None else ""
    return (f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="{r}" fill="{fill}"{extra}/>')


def polyline(points, stroke, width=2, dash=None, opacity=None):
    pts = " ".join(f"{x:.1f},{y:.1f}" for x, y in points)
    extra = f' stroke-dasharray="{dash}"' if dash else ""
    extra += f' opacity="{opacity}"' if opacity is not None else ""
    return (f'<polyline points="{pts}" fill="none" stroke="{stroke}" '
            f'stroke-width="{width}"{extra}/>')


def document(width, height, body):
    """Wrap body elements in a white-background SVG document."""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
            f'viewBox="0 0 {width} {height}">\n'
            f'{rect(0, 0, width, height, "#ffffff")}\n' + "\n".join(body) + "\n</svg>\n")


def x_band(x0, x1, y_low, y_high, color, opacity="0.10"):
    """Horizontal shaded band helper: y_low < y_high in data terms, already mapped."""
    top, bottom = min(y_low, y_high), max(y_low, y_high)
    return rect(x0, top, x1 - x0, bottom - top, color, opacity=opacity)


def error_bar(cx, top, bottom, cap=7, stroke=INK, width=1.2):
    return [
        line(cx, top, cx, bottom, stroke=stroke, width=width),
        line(cx - cap, top, cx + cap, top, stroke=stroke, width=width),
        line(cx - cap, bottom, cx + cap, bottom, stroke=stroke, width=width),
    ]
