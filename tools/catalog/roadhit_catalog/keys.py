"""Song identity: the normalised text and sha1 key every stage shares."""

from __future__ import annotations

import hashlib
import re
import unicodedata


# Apostrophes and quote marks vanish without leaving a gap ("Don't" == "Dont",
# Hebrew geresh/gershayim: נח"ל == נחל); other punctuation becomes a space.
_DELETED = set("'\"`´‘’‚‛“”„‟׳״")


def normalize(text: str) -> str:
    """Lower-case, drop diacritics/niqqud and punctuation, collapse whitespace."""
    decomposed = unicodedata.normalize("NFKD", text)
    kept = []
    for ch in decomposed:
        category = unicodedata.category(ch)
        if category.startswith("M") or ch in _DELETED:  # accents, niqqud, quotes
            continue
        if category.startswith("P") or category.startswith("S"):
            kept.append(" ")
            continue
        kept.append(ch)
    folded = "".join(kept).casefold()
    return re.sub(r"\s+", " ", folded).strip()


def song_key(title: str, artist: str) -> str:
    return hashlib.sha1(f"{normalize(title)}|{normalize(artist)}".encode("utf-8")).hexdigest()
