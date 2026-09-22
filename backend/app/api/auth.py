import logging

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

logger = logging.getLogger(__name__)

from app.db.database import get_db
from app.schemas.auth import RegisterRequest, LoginRequest, GoogleAuthRequest, TokenResponse, UserResponse
from app.services.auth_service import (
    register_user, authenticate_user, create_access_token,
    verify_google_token, get_or_create_oauth_user,
)
from app.api.deps import get_current_user
from app.models.user import User

router = APIRouter(prefix="/api/auth", tags=["auth"])


@router.post("/register", response_model=TokenResponse)
async def register(req: RegisterRequest, db: AsyncSession = Depends(get_db)):
    try:
        user = await register_user(db, req.email, req.password, req.display_name)
    except Exception as e:
        logger.exception("Registration failed")
        raise HTTPException(status_code=400, detail=f"Registration failed: {e}")
    token = create_access_token(str(user.id))
    return TokenResponse(access_token=token)


@router.post("/login", response_model=TokenResponse)
async def login(req: LoginRequest, db: AsyncSession = Depends(get_db)):
    user = await authenticate_user(db, req.email, req.password)
    if not user:
        raise HTTPException(status_code=401, detail="Invalid credentials")
    token = create_access_token(str(user.id))
    return TokenResponse(access_token=token)


@router.post("/google", response_model=TokenResponse)
async def google_auth(req: GoogleAuthRequest, db: AsyncSession = Depends(get_db)):
    try:
        payload = verify_google_token(req.credential)
    except Exception:
        raise HTTPException(status_code=401, detail="Invalid Google token")
    user = await get_or_create_oauth_user(
        db,
        email=payload["email"],
        display_name=payload.get("name", payload["email"]),
        provider="google",
        oauth_id=payload["sub"],
    )
    token = create_access_token(str(user.id))
    return TokenResponse(access_token=token)


@router.get("/me", response_model=UserResponse)
async def me(user: User = Depends(get_current_user)):
    return UserResponse(
        id=str(user.id),
        email=user.email,
        display_name=user.display_name,
        oauth_provider=user.oauth_provider,
        created_at=str(user.created_at),
    )
