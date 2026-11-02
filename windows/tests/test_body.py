from memento_win.core import note as fmt


def test_body_splits_and_rebuilds_checklist():
    parts = fmt.parse_body("Intro\n\n- [x] fait\n- [ ] à faire\n\n![](a.png)")
    assert parts.before == "Intro"
    assert [i.text for i in parts.items] == ["fait", "à faire"]
    assert parts.after == "![](a.png)"
    # Checked items go to the bottom.
    assert fmt.build_body(parts) == "Intro\n\n- [ ] à faire\n- [x] fait\n\n![](a.png)"


def test_body_without_checklist():
    parts = fmt.parse_body("juste du texte")
    assert parts.before == "juste du texte" and parts.items == [] and parts.after == ""
    assert fmt.build_body(parts) == "juste du texte"


def test_urls_are_found_once_and_trimmed():
    assert fmt.find_urls("voir https://a.fr/x). et https://a.fr/x puis http://b.org") == ["https://a.fr/x", "http://b.org"]
