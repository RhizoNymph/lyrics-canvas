from pydantic import BaseModel


class GroupCreate(BaseModel):
    name: str
    position_x: float = 0.0
    position_y: float = 0.0


class GroupUpdate(BaseModel):
    name: str | None = None
    position_x: float | None = None
    position_y: float | None = None


class GroupResponse(BaseModel):
    id: str
    canvas_id: str
    name: str
    position_x: float
    position_y: float
    snippet_ids: list[str] = []
    created_at: str

    # Responses always carry every field, so defaulted fields are required in the output schema.
    model_config = {"from_attributes": True, "json_schema_serialization_defaults_required": True}
