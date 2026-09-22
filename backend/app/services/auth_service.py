import uuid
from datetime import datetime, timedelta, timezone

from jose import JWTError, jwt
from passlib.context import CryptContext
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.models.user import User

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")

ALGORITHM = "HS256"


def hash_password(password: str) -> str:
    return pwd_context.hash(password)


def verify_password(plain: str, hashed: str) -> bool:
    return pwd_context.verify(plain, hashed)


def create_access_token(user_id: str) -> str:
    expire = datetime.now(timezone.utc) + timedelta(minutes=settings.access_token_expire_minutes)
    return jwt.encode({"sub": user_id, "exp": expire}, settings.secret_key, algorithm=ALGORITHM)


def decode_access_token(token: str) -> str | None:
    try:
        payload = jwt.decode(token, settings.secret_key, algorithms=[ALGORITHM])
        return payload.get("sub")
    except JWTError:
        return None


async def register_user(db: AsyncSession, email: str, password: str, display_name: str) -> User:
    user = User(
        email=email,
        password_hash=hash_password(password),
        display_name=display_name,
    )
    db.add(user)
    await db.commit()
    await db.refresh(user)
    return user


async def authenticate_user(db: AsyncSession, email: str, password: str) -> User | None:
    result = await db.execute(select(User).where(User.email == email))
    user = result.scalar_one_or_none()
    if user and user.password_hash and verify_password(password, user.password_hash):
        return user
    return None


async def get_user_by_id(db: AsyncSession, user_id: str) -> User | None:
    result = await db.execute(select(User).where(User.id == uuid.UUID(user_id)))
    return result.scalar_one_or_none()


def verify_google_token(token: str) -> dict:
    """Verify a Google ID token and return the decoded payload."""
    return google_id_token.verify_oauth2_token(
        token, google_requests.Request(), settings.google_client_id
    )


async def get_or_create_oauth_user(
    db: AsyncSession,
    email: str,
    display_name: str,
    provider: str,
    oauth_id: str,
) -> User:
    """Find user by OAuth provider+id, or create/link one."""
    # Check for existing OAuth user
    result = await db.execute(
        select(User).where(User.oauth_provider == provider, User.oauth_id == oauth_id)
    )
    user = result.scalar_one_or_none()
    if user:
        return user

    # Check if email already exists (password-only account) — link it
    result = await db.execute(select(User).where(User.email == email))
    user = result.scalar_one_or_none()
    if user:
        user.oauth_provider = provider
        user.oauth_id = oauth_id
        await db.commit()
        await db.refresh(user)
        return user

    # Create new user
    user = User(
        email=email,
        display_name=display_name,
        oauth_provider=provider,
        oauth_id=oauth_id,
    )
    db.add(user)
    await db.commit()
    await db.refresh(user)
    return user
