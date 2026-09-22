# Authentication

JWT-based stateless authentication with bcrypt password hashing and Google OAuth support.

## Configuration

```python
# app/config.py
secret_key: str = "dev-secret-key-change-in-production"
access_token_expire_minutes: int = 10080  # 7 days
google_client_id: str = ""  # Google OAuth client ID (env: GOOGLE_CLIENT_ID)
```

## Auth Service

**File:** `app/services/auth_service.py`

### Password Hashing

- Library: `passlib` with bcrypt scheme
- `hash_password(password: str) -> str`: Bcrypt hash with auto cost factor
- `verify_password(plain: str, hashed: str) -> bool`: Constant-time verification

### JWT Tokens

- Algorithm: HS256 (HMAC with SHA-256)
- Payload: `{"sub": user_id, "exp": expiration_datetime}`
- Expiration: 7 days from creation

```python
create_access_token(user_id: str) -> str
decode_access_token(token: str) -> str | None  # Returns user_id or None on JWTError
```

### User Operations

```python
async def register_user(db, email, password, display_name) -> User
async def authenticate_user(db, email, password) -> User | None
async def get_user_by_id(db, user_id: str) -> User | None
```

- `register_user`: Creates user with hashed password. No duplicate email handling (raises IntegrityError on conflict).
- `authenticate_user`: Queries by email, verifies password. Returns None on failure.
- `get_user_by_id`: Converts string to UUID, queries by primary key.

### Google OAuth

```python
def verify_google_token(token: str) -> dict
async def get_or_create_oauth_user(db, email, display_name, provider, oauth_id) -> User
```

- `verify_google_token`: Verifies a Google ID token using `google.oauth2.id_token.verify_oauth2_token`. Returns the decoded payload containing `email`, `name`, and `sub` (Google user ID). Raises on invalid/expired tokens.
- `get_or_create_oauth_user`: Looks up user by `oauth_provider` + `oauth_id`. If not found, checks if the email exists on a password-only account and links it by setting the OAuth fields. Otherwise creates a new user (no password_hash).

## Dependency Injection

**File:** `app/api/deps.py`

```python
security = HTTPBearer()

async def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(security),
    db: AsyncSession = Depends(get_db),
) -> User
```

Flow:
1. `HTTPBearer` extracts token from `Authorization: Bearer <token>` header
2. `decode_access_token(token)` extracts user_id from JWT
3. `get_user_by_id(db, user_id)` fetches User from database
4. Returns User or raises HTTP 401

## Schemas

**File:** `app/schemas/auth.py`

| Schema | Fields |
|--------|--------|
| `RegisterRequest` | email, password, display_name |
| `LoginRequest` | email, password |
| `GoogleAuthRequest` | credential |
| `TokenResponse` | access_token, token_type="bearer" |
| `UserResponse` | id, email, display_name, oauth_provider, created_at |

`UserResponse` uses `from_attributes = True` for ORM model mapping.

## API Routes

**File:** `app/api/auth.py`
**Prefix:** `/api/auth`

| Method | Path | Auth | Request | Response | Errors |
|--------|------|------|---------|----------|--------|
| POST | `/register` | - | RegisterRequest | TokenResponse | 400 on failure |
| POST | `/login` | - | LoginRequest | TokenResponse | 401 invalid credentials |
| POST | `/google` | - | GoogleAuthRequest | TokenResponse | 401 invalid Google token |
| GET | `/me` | Bearer | - | UserResponse | 401 invalid/expired token |

### Registration Flow

```
POST /api/auth/register { email, password, display_name }
  -> register_user() -> hash password, create User row
  -> create_access_token(user.id) -> JWT
  -> return { access_token, token_type: "bearer" }
```

### Login Flow

```
POST /api/auth/login { email, password }
  -> authenticate_user() -> query by email, verify bcrypt
  -> create_access_token(user.id) -> JWT
  -> return { access_token, token_type: "bearer" }
```

### Google OAuth Flow

```
POST /api/auth/google { credential }
  -> verify_google_token(credential) -> verify with Google, extract email/name/sub
  -> get_or_create_oauth_user(db, email, name, "google", sub)
     -> find by oauth_provider+oauth_id? return existing
     -> find by email? link OAuth fields to existing account
     -> otherwise create new user (no password)
  -> create_access_token(user.id) -> JWT
  -> return { access_token, token_type: "bearer" }
```

The frontend uses Google Identity Services (GIS) to render a "Sign in with Google" button. Google returns an ID token (JWT) directly to the frontend callback, which POSTs it to `/api/auth/google`. Requires `GOOGLE_CLIENT_ID` on the backend and `VITE_GOOGLE_CLIENT_ID` on the frontend.

## User Model

**File:** `app/models/user.py`
**Table:** `users`

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | UUID | PK, default: uuid4 |
| `email` | String(255) | UNIQUE, NOT NULL, indexed |
| `password_hash` | String(255) | Nullable (null for OAuth-only users) |
| `display_name` | String(100) | NOT NULL |
| `oauth_provider` | String(50) | Nullable |
| `oauth_id` | String(255) | Nullable |
| `created_at` | DateTime(tz) | server_default: now() |

**Relationships:** `canvases` (1:N, cascade: all, delete-orphan)
