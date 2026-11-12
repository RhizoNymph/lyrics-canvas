"""Request field types of the sync contract (docs/features/sync_versions.md)."""

from __future__ import annotations

import uuid
from typing import Annotated

from pydantic import Field

# The version the client's change was based on; a mismatch is 409 version_conflict.
BaseVersion = Annotated[int, Field(ge=1, description="the entity version this change is based on")]

# Per-entity base versions of a bulk request: {entity id: version}.
BaseVersions = dict[uuid.UUID, BaseVersion]

# A client-chosen id for a create; a retry of the identical create returns the same entity.
ClientId = Annotated[uuid.UUID, Field(description="client-chosen id; retrying the identical create is safe")]
