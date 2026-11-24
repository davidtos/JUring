"""Markdown tables and number formatting shared by the bench analyzers.

The formatters are named for their output, not their caller: ``fixed`` is a
plain fixed-point number, ``thousands`` adds separators, ``auto`` picks the
decimal count from the magnitude (the read/write table style) and uses an em
dash for an absent value.
"""


def table(headers, rows):
    """Markdown table without a trailing newline."""
    out = ["| " + " | ".join(headers) + " |",
           "|" + "|".join("---" for _ in headers) + "|"]
    for row in rows:
        out.append("| " + " | ".join(str(c) for c in row) + " |")
    return "\n".join(out)


def fixed(value, digits=1, dash="n/a"):
    """Fixed-point number, ``dash`` when absent (analyze_stream family)."""
    if value is None:
        return dash
    return ("%." + str(digits) + "f") % float(value)


def signed(value, digits=2, dash="n/a"):
    """Fixed-point number with an explicit sign (analyze_durability family)."""
    if value is None:
        return dash
    return ("%+." + str(digits) + "f") % float(value)


def thousands(value, digits=1, dash="n/a"):
    """Thousands-separated fixed-point number (analyze_scale_out / host-setup)."""
    if value is None:
        return dash
    try:
        return f"{value:,.{digits}f}"
    except (TypeError, ValueError):
        return dash


def num(value, nd=None):
    """Human number, thousands separators, em dash when absent (read/write tables).

    With ``nd=None`` the decimal count follows the magnitude: 0 decimals at
    >= 1000, 1 at >= 100, 2 below that.
    """
    if value is None:
        return "\u2014"
    if nd is None:
        a = abs(value)
        if a >= 1000:
            return f"{value:,.0f}"
        if a >= 100:
            return f"{value:,.1f}"
        return f"{value:,.2f}"
    return f"{value:,.{nd}f}"


def ci(mean, half, nd=None):
    """``mean +- half`` in the read/write style; ``(n<2)`` when the CI is absent."""
    if mean is None:
        return "\u2014"
    if half is None:
        return f"{num(mean, nd)} (n<2)"
    return f"{num(mean, nd)} \u00b1 {num(half, nd)}"


def ratio(value):
    """Ratio with the percent difference from 1.0 (read/write tables)."""
    if value is None:
        return "\u2014"
    return f"{value:.4f} ({100.0 * (value - 1.0):+.1f}%)"


def ci_ratio(mean, half):
    if mean is None:
        return "\u2014"
    if half is None:
        return f"{mean:.4f} (n<2)"
    return f"{mean:.4f} \u00b1 {half:.4f}"


def pct(value, digits=1, dash="n/a"):
    """A fraction rendered as a percentage with a space before the sign."""
    return dash if value is None else f"{100.0 * value:.{digits}f} %"


def stat_ci(stat, digits=0):
    """``mean [lb, ub]`` for a ``{"mean", "lb", "ub"}`` block (scaling tables)."""
    if not stat or stat.get("mean") is None:
        return "n/a"
    return (f"{stat['mean']:,.{digits}f} "
            f"[{stat.get('lb', 0):,.{digits}f}, {stat.get('ub', 0):,.{digits}f}]")
