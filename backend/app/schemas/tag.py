from pydantic import BaseModel


class TagCreate(BaseModel):
    name: str
    color: str = "#6366f1"


class TagUpdate(BaseModel):
    name: str | None = None
    color: str | None = None


class TagResponse(BaseModel):
    id: str
    canvas_id: str
    name: str
    color: str

    # Responses always carry every field, so defaulted fields are required in the output schema.
    model_config = {"from_attributes": True, "json_schema_serialization_defaults_required": True}


class TagLinkResponse(BaseModel):
    ok: bool = True

    model_config = {"json_schema_serialization_defaults_required": True}
