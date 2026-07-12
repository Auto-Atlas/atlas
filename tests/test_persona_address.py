import persona


def test_address_owner_uses_nick():
    assert persona.address_for("Owner", "owner") == persona.USER_NICK


def test_address_known_uses_first_name():
    assert "Alex" in persona.address_for("Alex", "known")


def test_refusal_known_names_owner_and_offers_alternative():
    msg = persona.refusal_instruction("create_invoice", "known", "Alex")
    low = msg.lower()
    assert "alex" in low
    assert persona.USER_NAME.lower() in low      # frame as the owner's domain
    assert "denied" not in low                   # never the cold word


def test_refusal_kid_is_gentle():
    msg = persona.refusal_instruction("send_to_channel", "kid", "Theo").lower()
    assert "denied" not in msg and "not allowed" not in msg


def test_refusal_reprompt_asks_for_name():
    msg = persona.refusal_instruction("create_invoice", "unknown", None, reprompt=True).lower()
    assert "name" in msg
