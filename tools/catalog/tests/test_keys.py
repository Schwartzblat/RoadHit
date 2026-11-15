from roadhit_catalog.keys import normalize, song_key


def test_case_and_punctuation_do_not_change_the_key():
    assert song_key("Careless Whisper", "George Michael") == song_key(
        "careless whisper!", "George Michael"
    )


def test_niqqud_does_not_change_the_key():
    assert song_key("יְרוּשָׁלַיִם שֶׁל זָהָב", "שולי נתן") == song_key(
        "ירושלים של זהב", "שולי נתן"
    )


def test_different_artists_give_different_keys():
    assert song_key("Hello", "Adele") != song_key("Hello", "Lionel Richie")


def test_apostrophes_and_gershayim_vanish_and_whitespace_collapses():
    assert normalize("  Don't   Stop ") == normalize("Dont stop")
    assert normalize('להקת הנח"ל') == normalize("להקת הנחל")


def test_key_is_sha1_hex_of_normalized_title_and_artist():
    import hashlib

    expected = hashlib.sha1("careless whisper|george michael".encode()).hexdigest()
    assert song_key("Careless Whisper", "George Michael") == expected
