"""Writes contracts/ai/suggestions.schema.json from the pydantic models: ``python -m app.contract``."""

from __future__ import annotations

import json
from pathlib import Path

from .schemas import SuggestionRequest, SuggestionResponse

CONTRACT = Path(__file__).resolve().parents[2] / "contracts" / "ai" / "suggestions.schema.json"


def schema() -> dict:
    return {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": "https://rydex.dev/contracts/ai/suggestions.schema.json",
        "title": "Smart ride suggestions",
        "description": "POST /suggestions on ai-service (internal): ride-service sends a SuggestionRequest with every "
                       "rider's estimate and merges the SuggestionResponse into it. Generated from ai-service/app/schemas.py.",
        "$defs": {
            "SuggestionRequest": SuggestionRequest.model_json_schema(ref_template="#/$defs/{model}"),
            "SuggestionResponse": SuggestionResponse.model_json_schema(ref_template="#/$defs/{model}"),
        },
    }


def render() -> str:
    doc = schema()
    defs = doc["$defs"]
    # hoist the nested models next to the two top-level ones
    for name in ("SuggestionRequest", "SuggestionResponse"):
        defs.update(defs[name].pop("$defs", {}))
    return json.dumps(doc, indent=2, ensure_ascii=False) + "\n"


if __name__ == "__main__":
    CONTRACT.parent.mkdir(parents=True, exist_ok=True)
    CONTRACT.write_text(render(), encoding="utf-8")
    print(f"wrote {CONTRACT}")
