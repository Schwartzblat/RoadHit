"""Turning a line of wikitext into plain text."""

from __future__ import annotations

import html
import re

# Templates whose (last) argument is visible text; every other template is dropped.
_WRAPPERS = {
    "משמאל לימין",
    "מימין לשמאל",
    "ltr",
    "rtl",
    "נוסח",
    "lang",
    "nowrap",
    "תעתיק",
}
_SPACE_TEMPLATES = {"ש", "-"}

_REF_RE = re.compile(r"<ref[^>/]*/>|<ref[^>]*>.*?</ref>", re.S | re.I)
_COMMENT_RE = re.compile(r"<!--.*?-->", re.S)
_TAG_RE = re.compile(r"</?[a-zA-Z][^>]*>")
_LINK_RE = re.compile(
    r"\[\[(?!(?:קובץ|File|Image|תמונה|קטגוריה|Category):)([^\[\]|]*)(?:\|([^\[\]]*))?\]\]"
)
_EXT_LINK_RE = re.compile(r"\[(?:https?:)?//[^\s\]]+\s*([^\]]*)\]")
_FOOTNOTE_RE = re.compile(r"\[(?:\d+|[a-z]|הערה \d+|note \d+)\]")


def _replace_templates(text: str) -> str:
    """Remove/unwrap {{...}} templates, innermost first (handles nesting)."""
    inner = re.compile(r"\{\{([^{}]*)\}\}")
    while True:
        new = inner.sub(_template_text, text)
        if new == text:
            return new
        text = new


def _template_text(match: re.Match) -> str:
    parts = match.group(1).split("|")
    name = parts[0].strip()
    if name in _SPACE_TEMPLATES:
        return " "
    if name.lower() in _WRAPPERS or name in _WRAPPERS:
        args = [p for p in parts[1:] if "=" not in p.split("[[")[0]]
        return args[-1] if args else ""
    return ""


def clean(text: str) -> str:
    """Wikitext → plain text: no refs, templates, links, markup or footnotes."""
    text = _COMMENT_RE.sub("", text)
    text = _REF_RE.sub("", text)
    # Links first, so pipes inside links do not split template arguments.
    text = _LINK_RE.sub(lambda m: m.group(2) if m.group(2) is not None else m.group(1), text)
    text = re.sub(r"\[\[(?:קובץ|File|Image|תמונה|קטגוריה|Category):[^\[\]]*\]\]", "", text)
    text = _replace_templates(text)
    text = _EXT_LINK_RE.sub(lambda m: m.group(1), text)
    text = text.replace("'''", "").replace("''", "")
    text = _TAG_RE.sub("", text)
    text = html.unescape(text)
    text = _FOOTNOTE_RE.sub("", text)
    text = text.replace("‏", "").replace("‎", "").replace("\xa0", " ")
    return re.sub(r"\s+", " ", text).strip()


_QUOTES = "\"“”„‟״«»"


def strip_quotes(text: str) -> str:
    """Drop one pair of quote marks that wraps the whole text.

    Inner quotes stay (Love Theme From "The Godfather", נח"ל). A lone quote
    left at one end by a dash split ('"title' with no closing mark) is dropped
    only when the text then has an even number of quote marks, so the result
    is always balanced.
    """
    text = text.strip()
    if len(text) >= 2 and text[0] in _QUOTES and text[-1] in _QUOTES:
        return text[1:-1].strip()
    count = sum(text.count(q) for q in _QUOTES)
    if count % 2 == 1:
        if text[:1] in _QUOTES:
            return text[1:].strip()
        if text[-1:] in _QUOTES:
            return text[:-1].strip()
    return text


def is_quoted(text: str) -> bool:
    text = text.strip()
    return len(text) >= 2 and text[0] in _QUOTES


_AFTER_TITLE_RE = re.compile(r"^\s*(\([^()]*\)\s*)?(\s[–—-]\s|[–—]|$)")


def split_quoted(text: str) -> tuple[str, str] | None:
    """'"Title" rest' → ('Title', 'rest'); None when there is no closing quote.

    A closing quote is a quote mark followed by the end, whitespace, a
    parenthesis, a dash, a comma or a colon (so gershayim inside words, נח"ל,
    stay). Of those, the first one after which the line goes on with the dash
    separator (optionally after a parenthesis) or ends is used, so titles with
    inner quotes ('"Love Theme From "The Godfather" (Speak Softly Love)"',
    '"יא" 2" – טונה') stay whole; otherwise the first candidate.
    """
    text = text.strip()
    if not is_quoted(text):
        return None
    candidates = [
        i for i in range(1, len(text))
        if text[i] in _QUOTES and (i == len(text) - 1 or text[i + 1] in " (–-—,:")
    ]  # fmt: skip
    for i in candidates:
        if _AFTER_TITLE_RE.match(" " + text[i + 1 :]) and strip_quotes(text[: i + 1]):
            return strip_quotes(text[: i + 1]), text[i + 1 :].strip()
    for i in candidates:
        if strip_quotes(text[: i + 1]):
            return strip_quotes(text[: i + 1]), text[i + 1 :].strip()
    return None


_DASH_RE = re.compile(r"\s[–—-]\s|\s?[–—]\s?")


def split_dash(text: str) -> tuple[str, str] | None:
    """Split at the first dash separator (spaced hyphen, or en/em dash)."""
    m = _DASH_RE.search(text)
    if not m:
        return None
    return text[: m.start()].strip(), text[m.end() :].strip()


def strip_trailing_parens(text: str) -> str:
    """Drop one balanced (...) group at the end — chart credits like (lyrics/music)."""
    text = text.strip()
    if not text.endswith(")"):
        return text
    depth = 0
    for i in range(len(text) - 1, -1, -1):
        if text[i] == ")":
            depth += 1
        elif text[i] == "(":
            depth -= 1
            if depth == 0:
                return text[:i].strip()
    return text
