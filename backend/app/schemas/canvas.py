from pydantic import BaseModel


class CanvasCreate(BaseModel):
    title: str = "Untitled Canvas"


class CanvasUpdate(BaseModel):
    title: str | None = None
    is_public: bool | None = None
    viewport_state: dict | None = None


class CanvasResponse(BaseModel):
    id: str
    owner_id: str
    title: str
    is_public: bool
    share_token: str | None = None
    viewport_state: dict | None = None
    created_at: str
    updated_at: str

    model_config = {"from_attributes": True}


class CanvasDetailResponse(CanvasResponse):
    snippets: list = []
    groups: list = []
    tags: list = []
