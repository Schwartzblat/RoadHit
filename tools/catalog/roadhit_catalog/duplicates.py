"""A listing of rows that may be one song twice, or a cover of another row (``duplicates``).

Two rows of one origin (Israeli, or international) of ``work/dated.json`` are put in one group when
their titles (an international row's Latin title, when it has one)

* are the same after normalisation (case, punctuation, niqqud);
* are the same once a bracketed part is dropped ("מה אומרות עינייך (שני אורות בליל)");
* differ by one edit (one letter added, dropped or changed: "שיר של אחרי מלחמה" / "שיר של אחרי
  המלחמה"), when the shorter title has at least ``MIN_EDIT_LENGTH`` letters ("לב" / "לך" say nothing).

For international rows each side of a double A-side ("Bird Dog / Devoted to You") is a title too.

Groups are joined transitively. The listing is for a person or an agent to read: many groups are
different songs that share a name (שיר ערש, לילה טוב), and those stay.
"""

from __future__ import annotations

import re
from collections import defaultdict

from rapidfuzz.distance import Levenshtein

from .keys import normalize

MIN_EDIT_LENGTH = 5
_BRACKETS_RE = re.compile(r"\s*[(\[][^()\[\]]*[)\]]")


def _forms(title: str, sides: bool = False) -> set[str]:
    """The normalised title, and the same without its bracketed parts (and, with ``sides``, each side
    of an "A / B" title)."""
    titles = [title, *(title.split("/") if sides and "/" in title else [])]
    out = set()
    for text in titles:
        plain, previous = text, None
        while plain != previous:
            previous, plain = plain, _BRACKETS_RE.sub("", plain)
        out |= {f for f in (normalize(text), normalize(plain)) if f}
    return out


def _title(row: dict) -> str:
    return (row.get("title_latin") or row["title"]) if row.get("origin") == "INTL" else row["title"]


def _deletions(text: str) -> set[str]:
    return {text[:i] + text[i + 1:] for i in range(len(text))}


def find_groups(rows: list[dict], origin: str = "IL") -> list[list[dict]]:
    """Groups (two rows or more, by year) of rows of ``origin`` whose titles may be one song."""
    rows = [r for r in rows if r.get("origin") == origin]
    parent = list(range(len(rows)))

    def find(i: int) -> int:
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    def join(i: int, j: int) -> None:
        parent[find(i)] = find(j)

    by_form: dict[str, list[int]] = defaultdict(list)
    for i, r in enumerate(rows):
        for form in _forms(_title(r), sides=origin == "INTL"):
            by_form[form].append(i)
    for members in by_form.values():
        for i in members[1:]:
            join(members[0], i)
    # One edit apart: two strings at distance 1 share a one-letter deletion, or one is a deletion of the other.
    long_forms = [f for f in by_form if len(f) >= MIN_EDIT_LENGTH]
    by_deletion: dict[str, list[str]] = defaultdict(list)
    for form in long_forms:
        by_deletion[form].append(form)
        for d in _deletions(form):
            by_deletion[d].append(form)
    for forms in by_deletion.values():
        for x, a in enumerate(forms):
            for b in forms[x + 1:]:
                if a != b and Levenshtein.distance(a, b) <= 1:
                    join(by_form[a][0], by_form[b][0])
    groups: dict[int, list[dict]] = defaultdict(list)
    for i, r in enumerate(rows):
        groups[find(i)].append(r)
    out = [sorted(g, key=lambda r: (r.get("year") or 0, r["key"])) for g in groups.values() if len(g) > 1]
    return sorted(out, key=lambda g: (normalize(_title(g[0])), g[0]["key"]))


def report(groups: list[list[dict]], origin: str = "IL") -> str:
    """Markdown: one table per group (key prefix, title, credit, year, Deezer artist, sources)."""
    lines = [
        f"# {'Israeli' if origin == 'IL' else 'International'} rows that may be one song",
        "",
        f"{len(groups)} groups, {sum(map(len, groups))} rows. Titles that normalise alike, differ by one edit "
        f"(titles of {MIN_EDIT_LENGTH}+ letters) or by a bracketed part. Exclude a row that covers or re-records "
        "a song whose original performer has a row, or the same song twice; different songs that share a name stay.",
        "",
    ]
    for group in groups:
        lines += [f"## {group[0]['title']}", "", "| key | title | credit | year | Deezer artist | sources |",
                  "|---|---|---|---|---|---|"]
        for r in group:
            lines.append(f"| {r['key'][:12]} | {r['title']} | {r.get('credit') or r['artist']} | {r.get('year')} | "
                         f"{r.get('deezer_artist') or ''} | {', '.join(r.get('sources') or [])} |")
        lines.append("")
    return "\n".join(lines)
