from invoice_tool import CREATE_INVOICE_SCHEMA


def test_schema_teaches_confirmed_flag_flow():
    desc = CREATE_INVOICE_SCHEMA.description.lower()
    assert "confirmed" in desc
    # internal mechanism vocabulary must not leak into the model-facing schema
    assert "stage" not in desc
    assert "confirm_action" not in desc


def test_schema_exposes_confirmed_property():
    assert "confirmed" in CREATE_INVOICE_SCHEMA.properties


def test_schema_drops_stale_pre_call_instruction():
    assert "before calling this tool" not in CREATE_INVOICE_SCHEMA.description.lower()
