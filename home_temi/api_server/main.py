from pathlib import Path
import base64
import json
import os
import re
import sqlite3
from typing import Optional
import urllib.error
import urllib.request
import uuid

from fastapi import FastAPI, File, Form, HTTPException, Query, UploadFile
from pydantic import BaseModel, Field


BASE_DIR = Path(__file__).resolve().parent
ROOT_DIR = BASE_DIR.parent
DB_PATH = BASE_DIR / "inventory.db"
SCHEMA_PATH = BASE_DIR / "schema.sql"
UPLOAD_DIR = BASE_DIR / "uploads"
ENV_PATH = ROOT_DIR / ".env"
GEMINI_MODEL = "gemini-flash-latest"

app = FastAPI(title="Temi Item Finder API")


class UserCreate(BaseModel):
    name: str = Field(min_length=1)


class ItemCreate(BaseModel):
    name: str = Field(min_length=1)
    status: str = "available"


class PlacementCreate(BaseModel):
    item_name: str = Field(min_length=1)
    drawer_number: int = Field(ge=1)
    quantity: int = Field(default=1, ge=0)


class SensorCheckCreate(BaseModel):
    item_name: str = Field(min_length=1)
    detected: bool
    note: Optional[str] = None


class ShoppingListCreate(BaseModel):
    name: str = Field(min_length=1)
    quantity: int = Field(default=1, ge=1)
    user_id: Optional[int] = None
    note: Optional[str] = None


class ShoppingListUpdate(BaseModel):
    status: str = Field(pattern="^(pending|done)$")


class RecognitionCreate(BaseModel):
    user_id: Optional[int] = None
    image_path: str = Field(min_length=1)
    llm_model: Optional[str] = None
    raw_response: Optional[str] = None


class RecognizedItemInput(BaseModel):
    sequence_no: int = Field(gt=0)
    item_name: str = Field(min_length=1)
    confidence: Optional[float] = Field(default=None, ge=0, le=1)


class RecognizedItemsCreate(BaseModel):
    items: list[RecognizedItemInput]


class RecognizedItemUpdate(BaseModel):
    item_name: Optional[str] = Field(default=None, min_length=1)
    status: str = Field(pattern="^(detected|corrected|ignored|stored)$")


class StorageSessionCreate(BaseModel):
    recognition_session_id: int = Field(gt=0)


class StorageCurrentItemUpdate(BaseModel):
    current_sequence_no: int = Field(gt=0)


class StorageEventCreate(BaseModel):
    storage_session_id: int = Field(gt=0)
    recognized_item_id: Optional[int] = Field(default=None, gt=0)
    storage_location_id: Optional[int] = Field(default=None, gt=0)
    drawer_number: Optional[int] = Field(default=None, ge=1)
    sensor_type: str = Field(pattern="^(reed_switch|load_cell|top_camera|system)$")
    event_type: str = Field(
        pattern=(
            "^(drawer_open|drawer_close|weight_before|weight_after|"
            "weight_changed|camera_snapshot|sequence_started|"
            "sequence_completed|verification_failed)$"
        )
    )
    weight_before: Optional[float] = None
    weight_after: Optional[float] = None
    weight_delta: Optional[float] = None
    payload: Optional[str] = None


class DrawerCameraSnapshotCreate(BaseModel):
    storage_session_id: int = Field(gt=0)
    recognized_item_id: Optional[int] = Field(default=None, gt=0)
    image_path: str = Field(min_length=1)
    drawer_number: Optional[int] = Field(default=None, ge=1)
    note: Optional[str] = None


class PlacementVerificationCreate(BaseModel):
    storage_session_id: int = Field(gt=0)
    recognized_item_id: int = Field(gt=0)
    drawer_number: int = Field(ge=1)
    open_event_id: Optional[int] = Field(default=None, gt=0)
    weight_event_id: Optional[int] = Field(default=None, gt=0)
    close_event_id: Optional[int] = Field(default=None, gt=0)
    camera_snapshot_id: Optional[int] = Field(default=None, gt=0)
    result: str = Field(default="success", pattern="^(success|failed|manual_confirmed)$")
    weight_delta: Optional[float] = None
    note: Optional[str] = None


class ShoppingSessionCreate(BaseModel):
    title: str = Field(min_length=1)
    created_by_user_id: Optional[int] = None


class ShoppingRequestItemInput(BaseModel):
    item_name: str = Field(min_length=1)
    quantity: int = Field(default=1, gt=0)
    note: Optional[str] = None


class ShoppingRequestCreate(BaseModel):
    user_id: int = Field(gt=0)
    note: Optional[str] = None
    items: list[ShoppingRequestItemInput]


class FinalShoppingListCreate(BaseModel):
    title: Optional[str] = Field(default=None, min_length=1)


class StoreCreate(BaseModel):
    name: str = Field(min_length=1)
    store_type: Optional[str] = None
    address: Optional[str] = None
    temi_device_id: Optional[str] = None


class StoreSectionCreate(BaseModel):
    name: str = Field(min_length=1)
    aisle: Optional[str] = None
    shelf: Optional[str] = None
    floor: Optional[int] = None
    map_x: Optional[float] = None
    map_y: Optional[float] = None


class StoreProductCreate(BaseModel):
    item_name: str = Field(min_length=1)
    store_section_id: Optional[int] = Field(default=None, gt=0)
    in_stock: bool = True
    stock_quantity: Optional[int] = Field(default=None, ge=0)
    price: Optional[int] = Field(default=None, ge=0)


class ShoppingListTransferCreate(BaseModel):
    store_id: int = Field(gt=0)


class StoreNavigationCreate(BaseModel):
    store_id: int = Field(gt=0)


class NavigationStepUpdate(BaseModel):
    status: str = Field(pattern="^(pending|arrived|picked|skipped|out_of_stock)$")


def read_env_value(key: str) -> Optional[str]:
    value = os.environ.get(key)
    if value:
        return value
    if not ENV_PATH.exists():
        return None
    for line in ENV_PATH.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, raw_value = line.split("=", 1)
        if name.strip() == key:
            return raw_value.strip().strip("\"'")
    return None


def extract_json_object(text: str) -> dict:
    cleaned = text.strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned, flags=re.IGNORECASE)
        cleaned = re.sub(r"\s*```$", "", cleaned)
    try:
        return json.loads(cleaned)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", cleaned, flags=re.DOTALL)
        if not match:
            raise
        return json.loads(match.group(0))


def call_gemini_image_analysis(image_bytes: bytes, mime_type: str) -> tuple[dict, str, str]:
    api_key = read_env_value("GEMINI_API_KEY")
    if not api_key:
        raise HTTPException(status_code=500, detail="GEMINI_API_KEY is not configured")

    prompt = """
사진 안에 있는 보관 대상 물건을 식별해줘.
같은 종류의 물건이 여러 개 있으면 각각 따로 번호를 붙여줘.
일반 배경, 손, 사람, 책상, 벽처럼 보관 대상이 아닌 것은 제외해줘.
반드시 JSON만 반환해줘.
형식:
{
  "items": [
    {"sequence_no": 1, "item_name": "book", "confidence": 0.92}
  ]
}
""".strip()
    payload = {
        "contents": [
            {
                "parts": [
                    {"text": prompt},
                    {
                        "inline_data": {
                            "mime_type": mime_type or "image/jpeg",
                            "data": base64.b64encode(image_bytes).decode("ascii"),
                        }
                    },
                ]
            }
        ],
        "generationConfig": {
            "response_mime_type": "application/json",
            "temperature": 0.1,
        },
    }
    body = json.dumps(payload).encode("utf-8")
    url = (
        "https://generativelanguage.googleapis.com/v1beta/models/"
        f"{GEMINI_MODEL}:generateContent?key={api_key}"
    )
    request = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            raw_response = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        error_body = exc.read().decode("utf-8", errors="replace")
        raise HTTPException(status_code=502, detail=f"Gemini API error: {error_body}") from exc
    except urllib.error.URLError as exc:
        raise HTTPException(status_code=502, detail=f"Gemini API request failed: {exc.reason}") from exc

    response_json = json.loads(raw_response)
    candidates = response_json.get("candidates") or []
    if not candidates:
        raise HTTPException(status_code=502, detail="Gemini returned no candidates")
    parts = candidates[0].get("content", {}).get("parts", [])
    text = "".join(part.get("text", "") for part in parts).strip()
    if not text:
        raise HTTPException(status_code=502, detail="Gemini returned empty text")
    parsed = extract_json_object(text)
    return parsed, raw_response, GEMINI_MODEL


def connect() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def row_dict(row: sqlite3.Row) -> dict:
    return dict(row)


def table_columns(conn: sqlite3.Connection, table: str) -> set[str]:
    rows = conn.execute(f"PRAGMA table_info({table})").fetchall()
    return {row["name"] for row in rows}


def require_row(conn: sqlite3.Connection, query: str, params: tuple, message: str) -> sqlite3.Row:
    row = conn.execute(query, params).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail=message)
    return row


def ensure_item(conn: sqlite3.Connection, name: str) -> int:
    item_name = name.strip()
    conn.execute("INSERT OR IGNORE INTO item(name) VALUES (?)", (item_name,))
    return conn.execute("SELECT id FROM item WHERE name = ?", (item_name,)).fetchone()["id"]


def ensure_location(conn: sqlite3.Connection, drawer_number: int) -> sqlite3.Row:
    location_name = f"{drawer_number}번 서랍"
    conn.execute(
        """
        INSERT OR IGNORE INTO storage_location(name, drawer_number, led_channel)
        VALUES (?, ?, ?)
        """,
        (location_name, drawer_number, drawer_number),
    )
    return conn.execute(
        "SELECT * FROM storage_location WHERE name = ?",
        (location_name,),
    ).fetchone()


def migrate_storage_schema(conn: sqlite3.Connection) -> None:
    storage_columns = table_columns(conn, "storage_location")
    if "led_channel" not in storage_columns:
        conn.execute(
            "ALTER TABLE storage_location ADD COLUMN led_channel INTEGER NOT NULL DEFAULT 0"
        )
        conn.execute(
            """
            UPDATE storage_location
            SET led_channel = drawer_number
            WHERE led_channel = 0
            """
        )
        storage_columns = table_columns(conn, "storage_location")

    if "description" in storage_columns:
        conn.execute("PRAGMA foreign_keys = OFF")
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS storage_location_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                drawer_number INTEGER NOT NULL,
                led_channel INTEGER NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """
        )
        conn.execute(
            """
            INSERT OR IGNORE INTO storage_location_new(
                id, name, drawer_number, led_channel, created_at
            )
            SELECT id, name, drawer_number, led_channel, created_at
            FROM storage_location
            """
        )
        conn.execute("DROP TABLE storage_location")
        conn.execute("ALTER TABLE storage_location_new RENAME TO storage_location")
        conn.execute("PRAGMA foreign_keys = ON")

    placement_columns = table_columns(conn, "item_placement")
    if "user_id" in placement_columns:
        conn.execute("PRAGMA foreign_keys = OFF")
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS item_placement_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                item_id INTEGER NOT NULL,
                storage_location_id INTEGER NOT NULL,
                quantity INTEGER NOT NULL DEFAULT 1,
                last_checked_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (item_id) REFERENCES item(id),
                FOREIGN KEY (storage_location_id) REFERENCES storage_location(id),
                UNIQUE(item_id, storage_location_id)
            )
            """
        )
        conn.execute(
            """
            INSERT OR IGNORE INTO item_placement_new(
                id, item_id, storage_location_id, quantity, last_checked_at
            )
            SELECT id, item_id, storage_location_id, quantity, last_checked_at
            FROM item_placement
            """
        )
        conn.execute("DROP TABLE item_placement")
        conn.execute("ALTER TABLE item_placement_new RENAME TO item_placement")
        conn.execute("PRAGMA foreign_keys = ON")


def migrate_shopping_schema(conn: sqlite3.Connection) -> None:
    result_columns = table_columns(conn, "shopping_result_item")
    if "led_channel" not in result_columns:
        conn.execute("ALTER TABLE shopping_result_item ADD COLUMN led_channel INTEGER")


def init_db() -> None:
    with connect() as conn:
        conn.executescript(SCHEMA_PATH.read_text(encoding="utf-8"))
        migrate_storage_schema(conn)
        migrate_shopping_schema(conn)
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS sensor_check (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                item_name TEXT NOT NULL,
                detected INTEGER NOT NULL,
                note TEXT,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """
        )


def seed_db() -> None:
    samples = [
        ("remote", 2, 1),
        ("book", 1, 1),
        ("charger", 3, 2),
    ]
    with connect() as conn:
        for name, drawer, quantity in samples:
            item_id = ensure_item(conn, name)
            location = ensure_location(conn, drawer)
            conn.execute(
                """
                INSERT OR IGNORE INTO item_placement(
                    item_id, storage_location_id, quantity
                ) VALUES (?, ?, ?)
                """,
                (item_id, location["id"], quantity),
            )


@app.on_event("startup")
def startup() -> None:
    init_db()
    seed_db()


@app.get("/api/health")
def health() -> dict:
    return {"status": "ok"}


@app.get("/api/users")
def get_users() -> dict:
    with connect() as conn:
        rows = conn.execute("SELECT * FROM user ORDER BY id").fetchall()
    return {"users": [row_dict(r) for r in rows]}


@app.post("/api/users", status_code=201)
def create_user(user: UserCreate) -> dict:
    with connect() as conn:
        try:
            cursor = conn.execute(
                "INSERT INTO user(name) VALUES (?)",
                (user.name.strip(),),
            )
        except sqlite3.IntegrityError as exc:
            raise HTTPException(status_code=409, detail="User already exists") from exc
    return {"id": cursor.lastrowid, "name": user.name}


@app.get("/api/users/{user_id}")
def get_user(user_id: int) -> dict:
    with connect() as conn:
        row = require_row(conn, "SELECT * FROM user WHERE id = ?", (user_id,), "User not found")
    return row_dict(row)


@app.get("/api/items/search")
def search_item(name: str = Query(min_length=1)) -> dict:
    keyword = name.strip()
    with connect() as conn:
        row = conn.execute(
            """
            SELECT
                i.name,
                i.status,
                p.quantity,
                l.name AS location,
                l.drawer_number,
                l.led_channel
            FROM item i
            JOIN item_placement p ON p.item_id = i.id
            JOIN storage_location l ON l.id = p.storage_location_id
            WHERE i.name = ? OR i.name LIKE ?
            ORDER BY
                CASE WHEN i.name = ? THEN 0 ELSE 1 END,
                i.name
            LIMIT 1
            """,
            (keyword, f"%{keyword}%", keyword),
        ).fetchone()

    if row is None:
        return {"found": False, "name": keyword}

    return {
        "found": True,
        "name": row["name"],
        "status": row["status"],
        "quantity": row["quantity"],
        "location": row["location"],
        "drawer_number": row["drawer_number"],
        "led_channel": row["led_channel"],
    }


@app.post("/api/items", status_code=201)
def create_item(item: ItemCreate) -> dict:
    with connect() as conn:
        try:
            cursor = conn.execute(
                "INSERT INTO item(name, status) VALUES (?, ?)",
                (item.name.strip(), item.status),
            )
        except sqlite3.IntegrityError as exc:
            raise HTTPException(status_code=409, detail="Item already exists") from exc
    return {"id": cursor.lastrowid, "name": item.name}


@app.post("/api/placements", status_code=201)
def create_placement(placement: PlacementCreate) -> dict:
    with connect() as conn:
        item_id = ensure_item(conn, placement.item_name)
        location = ensure_location(conn, placement.drawer_number)
        conn.execute(
            """
            INSERT INTO item_placement(item_id, storage_location_id, quantity)
            VALUES (?, ?, ?)
            ON CONFLICT(item_id, storage_location_id)
            DO UPDATE SET quantity = excluded.quantity,
                          last_checked_at = CURRENT_TIMESTAMP
            """,
            (item_id, location["id"], placement.quantity),
        )
    return {"item_id": item_id, "storage_location_id": location["id"]}


@app.post("/api/sensor-checks", status_code=201)
def create_sensor_check(check: SensorCheckCreate) -> dict:
    with connect() as conn:
        cursor = conn.execute(
            """
            INSERT INTO sensor_check(item_name, detected, note)
            VALUES (?, ?, ?)
            """,
            (check.item_name.strip(), int(check.detected), check.note),
        )
    return {"id": cursor.lastrowid}


@app.post("/api/recognitions", status_code=201)
def create_recognition(recognition: RecognitionCreate) -> dict:
    with connect() as conn:
        cursor = conn.execute(
            """
            INSERT INTO recognition_session(user_id, image_path, llm_model, raw_response, status)
            VALUES (?, ?, ?, ?, 'uploaded')
            """,
            (
                recognition.user_id,
                recognition.image_path.strip(),
                recognition.llm_model,
                recognition.raw_response,
            ),
        )
    return {"id": cursor.lastrowid, "status": "uploaded"}


@app.get("/api/recognitions/{recognition_id}")
def get_recognition(recognition_id: int) -> dict:
    with connect() as conn:
        row = require_row(
            conn,
            "SELECT * FROM recognition_session WHERE id = ?",
            (recognition_id,),
            "Recognition not found",
        )
    data = row_dict(row)
    data.pop("raw_response", None)
    return data


@app.post("/api/recognitions/{recognition_id}/items", status_code=201)
def create_recognized_items(recognition_id: int, items: RecognizedItemsCreate) -> dict:
    if not items.items:
        raise HTTPException(status_code=400, detail="items must not be empty")

    with connect() as conn:
        require_row(
            conn,
            "SELECT id FROM recognition_session WHERE id = ?",
            (recognition_id,),
            "Recognition not found",
        )
        created = []
        try:
            for item in items.items:
                item_id = ensure_item(conn, item.item_name)
                cursor = conn.execute(
                    """
                    INSERT INTO recognized_item(
                        recognition_session_id, sequence_no, item_id, item_name, confidence
                    ) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(recognition_session_id, sequence_no)
                    DO UPDATE SET item_id = excluded.item_id,
                                  item_name = excluded.item_name,
                                  confidence = excluded.confidence,
                                  status = 'detected'
                    """,
                    (
                        recognition_id,
                        item.sequence_no,
                        item_id,
                        item.item_name.strip(),
                        item.confidence,
                    ),
                )
                row = conn.execute(
                    """
                    SELECT id, sequence_no, item_name, status
                    FROM recognized_item
                    WHERE recognition_session_id = ? AND sequence_no = ?
                    """,
                    (recognition_id, item.sequence_no),
                ).fetchone()
                created.append(row_dict(row))
            conn.execute(
                "UPDATE recognition_session SET status = 'detected' WHERE id = ?",
                (recognition_id,),
            )
        except sqlite3.IntegrityError as exc:
            raise HTTPException(status_code=409, detail="Duplicate recognized item") from exc

    return {"recognition_id": recognition_id, "items": created}


@app.get("/api/recognitions/{recognition_id}/items")
def get_recognized_items(recognition_id: int) -> dict:
    with connect() as conn:
        require_row(
            conn,
            "SELECT id FROM recognition_session WHERE id = ?",
            (recognition_id,),
            "Recognition not found",
        )
        rows = conn.execute(
            """
            SELECT id, sequence_no, item_name, confidence, status, created_at
            FROM recognized_item
            WHERE recognition_session_id = ?
            ORDER BY sequence_no
            """,
            (recognition_id,),
        ).fetchall()
    return {"items": [row_dict(r) for r in rows]}


@app.post("/api/photos/analyze", status_code=201)
async def analyze_photo(
    image: UploadFile = File(...),
    user_id: Optional[int] = Form(default=None),
) -> dict:
    image_bytes = await image.read()
    if not image_bytes:
        raise HTTPException(status_code=400, detail="image must not be empty")

    mime_type = image.content_type or "image/jpeg"
    if not mime_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="image must be an image file")

    UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
    extension = Path(image.filename or "").suffix.lower()
    if extension not in {".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif"}:
        extension = ".jpg"
    image_path = UPLOAD_DIR / f"{uuid.uuid4().hex}{extension}"
    image_path.write_bytes(image_bytes)

    parsed, raw_response, llm_model = call_gemini_image_analysis(image_bytes, mime_type)
    raw_items = parsed.get("items")
    if not isinstance(raw_items, list) or not raw_items:
        raise HTTPException(status_code=502, detail="Gemini did not return items")

    created = []
    with connect() as conn:
        cursor = conn.execute(
            """
            INSERT INTO recognition_session(user_id, image_path, llm_model, raw_response, status)
            VALUES (?, ?, ?, ?, 'detected')
            """,
            (user_id, str(image_path), llm_model, raw_response),
        )
        recognition_id = cursor.lastrowid

        for index, raw_item in enumerate(raw_items, start=1):
            if not isinstance(raw_item, dict):
                continue
            item_name = str(raw_item.get("item_name", "")).strip()
            if not item_name:
                continue
            sequence_no = raw_item.get("sequence_no") or index
            try:
                sequence_no = int(sequence_no)
            except (TypeError, ValueError):
                sequence_no = index
            confidence = raw_item.get("confidence")
            try:
                confidence = None if confidence is None else float(confidence)
            except (TypeError, ValueError):
                confidence = None
            if confidence is not None:
                confidence = max(0.0, min(1.0, confidence))

            item_id = ensure_item(conn, item_name)
            cursor = conn.execute(
                """
                INSERT INTO recognized_item(
                    recognition_session_id, sequence_no, item_id, item_name, confidence, status
                ) VALUES (?, ?, ?, ?, ?, 'detected')
                """,
                (recognition_id, sequence_no, item_id, item_name, confidence),
            )
            created.append(
                {
                    "id": cursor.lastrowid,
                    "sequence_no": sequence_no,
                    "item_name": item_name,
                    "confidence": confidence,
                    "status": "detected",
                }
            )

    if not created:
        raise HTTPException(status_code=502, detail="Gemini returned no valid items")

    summary_by_name: dict[str, int] = {}
    for item in created:
        summary_by_name[item["item_name"]] = summary_by_name.get(item["item_name"], 0) + 1
    summary = [
        {"item_name": name, "count": count}
        for name, count in sorted(summary_by_name.items())
    ]

    return {
        "recognition_id": recognition_id,
        "image_path": str(image_path),
        "llm_model": llm_model,
        "total_count": len(created),
        "summary": summary,
        "items": created,
    }


@app.put("/api/recognized-items/{recognized_item_id}")
def update_recognized_item(recognized_item_id: int, update: RecognizedItemUpdate) -> dict:
    with connect() as conn:
        row = require_row(
            conn,
            "SELECT * FROM recognized_item WHERE id = ?",
            (recognized_item_id,),
            "Recognized item not found",
        )
        item_name = update.item_name.strip() if update.item_name else row["item_name"]
        item_id = ensure_item(conn, item_name)
        conn.execute(
            """
            UPDATE recognized_item
            SET item_id = ?, item_name = ?, status = ?
            WHERE id = ?
            """,
            (item_id, item_name, update.status, recognized_item_id),
        )
    return {"id": recognized_item_id, "item_name": item_name, "status": update.status}


@app.post("/api/storage-sessions", status_code=201)
def create_storage_session(session: StorageSessionCreate) -> dict:
    with connect() as conn:
        require_row(
            conn,
            "SELECT id FROM recognition_session WHERE id = ?",
            (session.recognition_session_id,),
            "Recognition not found",
        )
        first_item = conn.execute(
            """
            SELECT MIN(sequence_no) AS sequence_no
            FROM recognized_item
            WHERE recognition_session_id = ? AND status != 'ignored'
            """,
            (session.recognition_session_id,),
        ).fetchone()["sequence_no"]
        cursor = conn.execute(
            """
            INSERT INTO storage_session(recognition_session_id, current_sequence_no)
            VALUES (?, ?)
            """,
            (session.recognition_session_id, first_item),
        )
    return {
        "id": cursor.lastrowid,
        "recognition_session_id": session.recognition_session_id,
        "current_sequence_no": first_item,
        "status": "in_progress",
    }


@app.put("/api/storage-sessions/{storage_session_id}/current-item")
def update_storage_current_item(
    storage_session_id: int,
    update: StorageCurrentItemUpdate,
) -> dict:
    with connect() as conn:
        session = require_row(
            conn,
            "SELECT * FROM storage_session WHERE id = ?",
            (storage_session_id,),
            "Storage session not found",
        )
        item = conn.execute(
            """
            SELECT id FROM recognized_item
            WHERE recognition_session_id = ? AND sequence_no = ?
            """,
            (session["recognition_session_id"], update.current_sequence_no),
        ).fetchone()
        if item is None:
            raise HTTPException(status_code=404, detail="Sequence item not found")
        conn.execute(
            """
            UPDATE storage_session
            SET current_sequence_no = ?
            WHERE id = ?
            """,
            (update.current_sequence_no, storage_session_id),
        )
    return {"id": storage_session_id, "current_sequence_no": update.current_sequence_no}


@app.get("/api/storage-sessions/{storage_session_id}/current-item")
def get_storage_current_item(storage_session_id: int) -> dict:
    with connect() as conn:
        session = require_row(
            conn,
            "SELECT * FROM storage_session WHERE id = ?",
            (storage_session_id,),
            "Storage session not found",
        )
        item = conn.execute(
            """
            SELECT id, sequence_no, item_name, confidence, status
            FROM recognized_item
            WHERE recognition_session_id = ? AND sequence_no = ?
            """,
            (session["recognition_session_id"], session["current_sequence_no"]),
        ).fetchone()

    return {
        "storage_session_id": storage_session_id,
        "recognition_session_id": session["recognition_session_id"],
        "current_sequence_no": session["current_sequence_no"],
        "status": session["status"],
        "recognized_item_id": item["id"] if item else None,
        "item_name": item["item_name"] if item else None,
        "item_status": item["status"] if item else None,
        "confidence": item["confidence"] if item else None,
    }


@app.post("/api/storage-events", status_code=201)
def create_storage_event(event: StorageEventCreate) -> dict:
    with connect() as conn:
        require_row(
            conn,
            "SELECT id FROM storage_session WHERE id = ?",
            (event.storage_session_id,),
            "Storage session not found",
        )
        storage_location_id = event.storage_location_id
        if storage_location_id is None and event.drawer_number is not None:
            storage_location_id = ensure_location(conn, event.drawer_number)["id"]
        cursor = conn.execute(
            """
            INSERT INTO storage_event(
                storage_session_id, recognized_item_id, storage_location_id,
                drawer_number, sensor_type, event_type,
                weight_before, weight_after, weight_delta, payload
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                event.storage_session_id,
                event.recognized_item_id,
                storage_location_id,
                event.drawer_number,
                event.sensor_type,
                event.event_type,
                event.weight_before,
                event.weight_after,
                event.weight_delta,
                event.payload,
            ),
        )
    return {"id": cursor.lastrowid}


@app.post("/api/drawer-camera-snapshots", status_code=201)
def create_drawer_camera_snapshot(snapshot: DrawerCameraSnapshotCreate) -> dict:
    with connect() as conn:
        require_row(
            conn,
            "SELECT id FROM storage_session WHERE id = ?",
            (snapshot.storage_session_id,),
            "Storage session not found",
        )
        cursor = conn.execute(
            """
            INSERT INTO drawer_camera_snapshot(
                storage_session_id, recognized_item_id, image_path, drawer_number, note
            ) VALUES (?, ?, ?, ?, ?)
            """,
            (
                snapshot.storage_session_id,
                snapshot.recognized_item_id,
                snapshot.image_path.strip(),
                snapshot.drawer_number,
                snapshot.note,
            ),
        )
    return {"id": cursor.lastrowid}


@app.post("/api/placement-verifications", status_code=201)
def create_placement_verification(verification: PlacementVerificationCreate) -> dict:
    with connect() as conn:
        recognized = require_row(
            conn,
            "SELECT * FROM recognized_item WHERE id = ?",
            (verification.recognized_item_id,),
            "Recognized item not found",
        )
        require_row(
            conn,
            "SELECT id FROM storage_session WHERE id = ?",
            (verification.storage_session_id,),
            "Storage session not found",
        )
        item_id = ensure_item(conn, recognized["item_name"])
        location = ensure_location(conn, verification.drawer_number)
        try:
            cursor = conn.execute(
                """
                INSERT INTO placement_verification(
                    storage_session_id, recognized_item_id, item_id, storage_location_id,
                    open_event_id, weight_event_id, close_event_id, camera_snapshot_id,
                    result, weight_delta, note
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    verification.storage_session_id,
                    verification.recognized_item_id,
                    item_id,
                    location["id"],
                    verification.open_event_id,
                    verification.weight_event_id,
                    verification.close_event_id,
                    verification.camera_snapshot_id,
                    verification.result,
                    verification.weight_delta,
                    verification.note,
                ),
            )
        except sqlite3.IntegrityError as exc:
            raise HTTPException(status_code=409, detail="Verification already exists") from exc

        if verification.result in {"success", "manual_confirmed"}:
            conn.execute(
                """
                INSERT INTO item_placement(item_id, storage_location_id, quantity)
                VALUES (?, ?, 1)
                ON CONFLICT(item_id, storage_location_id)
                DO UPDATE SET quantity = quantity + 1,
                              last_checked_at = CURRENT_TIMESTAMP
                """,
                (item_id, location["id"]),
            )
            conn.execute(
                "UPDATE recognized_item SET status = 'stored', item_id = ? WHERE id = ?",
                (item_id, verification.recognized_item_id),
            )
            remaining = conn.execute(
                """
                SELECT COUNT(*) AS count
                FROM recognized_item ri
                JOIN storage_session ss ON ss.recognition_session_id = ri.recognition_session_id
                WHERE ss.id = ? AND ri.status NOT IN ('stored', 'ignored')
                """,
                (verification.storage_session_id,),
            ).fetchone()["count"]
            if remaining == 0:
                conn.execute(
                    """
                    UPDATE storage_session
                    SET status = 'completed', completed_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    (verification.storage_session_id,),
                )
                conn.execute(
                    """
                    UPDATE recognition_session
                    SET status = 'stored'
                    WHERE id = (
                        SELECT recognition_session_id FROM storage_session WHERE id = ?
                    )
                    """,
                    (verification.storage_session_id,),
                )

    return {
        "id": cursor.lastrowid,
        "item_id": item_id,
        "storage_location_id": location["id"],
        "result": verification.result,
    }


@app.post("/api/shopping-sessions", status_code=201)
def create_shopping_session(session: ShoppingSessionCreate) -> dict:
    with connect() as conn:
        cursor = conn.execute(
            """
            INSERT INTO shopping_session(title, created_by_user_id)
            VALUES (?, ?)
            """,
            (session.title.strip(), session.created_by_user_id),
        )
    return {"id": cursor.lastrowid, "title": session.title, "status": "open"}


@app.post("/api/shopping-sessions/{session_id}/requests", status_code=201)
def create_shopping_request(session_id: int, request: ShoppingRequestCreate) -> dict:
    if not request.items:
        raise HTTPException(status_code=400, detail="items must not be empty")

    with connect() as conn:
        require_row(
            conn,
            "SELECT id FROM shopping_session WHERE id = ?",
            (session_id,),
            "Shopping session not found",
        )
        require_row(conn, "SELECT id FROM user WHERE id = ?", (request.user_id,), "User not found")
        cursor = conn.execute(
            """
            INSERT INTO shopping_request(session_id, user_id, note)
            VALUES (?, ?, ?)
            """,
            (session_id, request.user_id, request.note),
        )
        request_id = cursor.lastrowid
        for item in request.items:
            item_id = ensure_item(conn, item.item_name)
            conn.execute(
                """
                INSERT INTO shopping_request_item(request_id, item_id, item_name, quantity, note)
                VALUES (?, ?, ?, ?, ?)
                """,
                (request_id, item_id, item.item_name.strip(), item.quantity, item.note),
            )
    return {"request_id": request_id, "item_count": len(request.items)}


def compare_shopping_session(conn: sqlite3.Connection, session_id: int) -> list[dict]:
    request_rows = conn.execute(
        """
        SELECT
            sri.item_name,
            MIN(sri.item_id) AS item_id,
            SUM(sri.quantity) AS requested_quantity
        FROM shopping_request_item sri
        JOIN shopping_request sr ON sr.id = sri.request_id
        WHERE sr.session_id = ?
        GROUP BY sri.item_name
        ORDER BY sri.item_name
        """,
        (session_id,),
    ).fetchall()

    conn.execute("DELETE FROM shopping_result_item WHERE session_id = ?", (session_id,))

    results = []
    for request in request_rows:
        placements = conn.execute(
            """
            SELECT
                i.id AS item_id,
                SUM(p.quantity) AS owned_quantity,
                l.id AS storage_location_id,
                l.drawer_number,
                l.led_channel
            FROM item i
            JOIN item_placement p ON p.item_id = i.id
            JOIN storage_location l ON l.id = p.storage_location_id
            WHERE i.name = ?
            GROUP BY i.id
            ORDER BY p.quantity DESC, l.drawer_number
            LIMIT 1
            """,
            (request["item_name"],),
        ).fetchone()

        requested_quantity = int(request["requested_quantity"] or 0)
        owned_quantity = int(placements["owned_quantity"] or 0) if placements else 0
        need_to_buy = max(requested_quantity - owned_quantity, 0)
        if need_to_buy == 0 and owned_quantity > 0:
            status = "owned"
        elif owned_quantity > 0:
            status = "partial"
        else:
            status = "need_to_buy"

        item_id = placements["item_id"] if placements else request["item_id"]
        storage_location_id = placements["storage_location_id"] if placements else None
        led_channel = placements["led_channel"] if placements else None

        conn.execute(
            """
            INSERT INTO shopping_result_item(
                session_id, item_id, item_name, requested_quantity,
                owned_quantity, need_to_buy_quantity, status,
                storage_location_id, led_channel
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                session_id,
                item_id,
                request["item_name"],
                requested_quantity,
                owned_quantity,
                need_to_buy,
                status,
                storage_location_id,
                led_channel,
            ),
        )

        results.append(
            {
                "item_name": request["item_name"],
                "requested_quantity": requested_quantity,
                "owned_quantity": owned_quantity,
                "need_to_buy_quantity": need_to_buy,
                "status": status,
                "drawer_number": placements["drawer_number"] if placements else None,
                "led_channel": led_channel,
            }
        )

    conn.execute(
        "UPDATE shopping_session SET status = 'compared' WHERE id = ?",
        (session_id,),
    )
    return results


@app.post("/api/shopping-sessions/{session_id}/compare")
def compare_shopping(session_id: int) -> dict:
    with connect() as conn:
        require_row(
            conn,
            "SELECT id FROM shopping_session WHERE id = ?",
            (session_id,),
            "Shopping session not found",
        )
        results = compare_shopping_session(conn, session_id)
    return {"session_id": session_id, "results": results}


@app.get("/api/shopping-sessions/{session_id}/results")
def get_shopping_results(session_id: int) -> dict:
    with connect() as conn:
        rows = conn.execute(
            """
            SELECT
                r.item_name,
                r.requested_quantity,
                r.owned_quantity,
                r.need_to_buy_quantity,
                r.status,
                l.drawer_number,
                r.led_channel
            FROM shopping_result_item r
            LEFT JOIN storage_location l ON l.id = r.storage_location_id
            WHERE r.session_id = ?
            ORDER BY r.item_name
            """,
            (session_id,),
        ).fetchall()
    return {"session_id": session_id, "items": [row_dict(r) for r in rows]}


@app.get("/api/shopping-sessions/{session_id}/owned-items")
def get_shopping_owned_items(session_id: int) -> dict:
    with connect() as conn:
        rows = conn.execute(
            """
            SELECT
                r.item_name,
                r.owned_quantity,
                l.drawer_number,
                r.led_channel
            FROM shopping_result_item r
            JOIN storage_location l ON l.id = r.storage_location_id
            WHERE r.session_id = ? AND r.status IN ('owned', 'partial')
            ORDER BY l.drawer_number, r.item_name
            """,
            (session_id,),
        ).fetchall()
    return {"items": [row_dict(r) for r in rows]}


@app.post("/api/shopping-sessions/{session_id}/finalize", status_code=201)
def finalize_shopping_session(session_id: int, final_list: FinalShoppingListCreate) -> dict:
    with connect() as conn:
        session = require_row(
            conn,
            "SELECT * FROM shopping_session WHERE id = ?",
            (session_id,),
            "Shopping session not found",
        )
        existing_results = conn.execute(
            "SELECT COUNT(*) AS count FROM shopping_result_item WHERE session_id = ?",
            (session_id,),
        ).fetchone()["count"]
        if existing_results == 0:
            compare_shopping_session(conn, session_id)

        title = final_list.title or f"{session['title']} final list"
        cursor = conn.execute(
            """
            INSERT INTO final_shopping_list(session_id, title)
            VALUES (?, ?)
            """,
            (session_id, title),
        )
        final_list_id = cursor.lastrowid
        rows = conn.execute(
            """
            SELECT * FROM shopping_result_item
            WHERE session_id = ? AND need_to_buy_quantity > 0
            ORDER BY item_name
            """,
            (session_id,),
        ).fetchall()
        items = []
        for row in rows:
            cursor = conn.execute(
                """
                INSERT INTO final_shopping_list_item(
                    final_list_id, shopping_result_item_id, item_id,
                    item_name, quantity
                ) VALUES (?, ?, ?, ?, ?)
                """,
                (
                    final_list_id,
                    row["id"],
                    row["item_id"],
                    row["item_name"],
                    row["need_to_buy_quantity"],
                ),
            )
            items.append(
                {
                    "id": cursor.lastrowid,
                    "item_name": row["item_name"],
                    "quantity": row["need_to_buy_quantity"],
                    "status": "pending",
                }
            )
    return {"final_list_id": final_list_id, "items": items}


@app.get("/api/final-shopping-lists/{final_list_id}")
def get_final_shopping_list(final_list_id: int) -> dict:
    with connect() as conn:
        final_list = require_row(
            conn,
            "SELECT * FROM final_shopping_list WHERE id = ?",
            (final_list_id,),
            "Final shopping list not found",
        )
        items = conn.execute(
            """
            SELECT id, item_name, quantity, status, note, purchased_at, created_at
            FROM final_shopping_list_item
            WHERE final_list_id = ?
            ORDER BY item_name
            """,
            (final_list_id,),
        ).fetchall()
    data = row_dict(final_list)
    data["items"] = [row_dict(r) for r in items]
    return data


@app.post("/api/stores", status_code=201)
def create_store(store: StoreCreate) -> dict:
    with connect() as conn:
        cursor = conn.execute(
            """
            INSERT INTO store(name, store_type, address, temi_device_id)
            VALUES (?, ?, ?, ?)
            """,
            (
                store.name.strip(),
                store.store_type,
                store.address,
                store.temi_device_id,
            ),
        )
    return {"id": cursor.lastrowid, "name": store.name}


@app.post("/api/stores/{store_id}/sections", status_code=201)
def create_store_section(store_id: int, section: StoreSectionCreate) -> dict:
    with connect() as conn:
        require_row(conn, "SELECT id FROM store WHERE id = ?", (store_id,), "Store not found")
        cursor = conn.execute(
            """
            INSERT INTO store_section(store_id, name, aisle, shelf, floor, map_x, map_y)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(store_id, name)
            DO UPDATE SET aisle = excluded.aisle,
                          shelf = excluded.shelf,
                          floor = excluded.floor,
                          map_x = excluded.map_x,
                          map_y = excluded.map_y
            """,
            (
                store_id,
                section.name.strip(),
                section.aisle,
                section.shelf,
                section.floor,
                section.map_x,
                section.map_y,
            ),
        )
        row = conn.execute(
            "SELECT id, name FROM store_section WHERE store_id = ? AND name = ?",
            (store_id, section.name.strip()),
        ).fetchone()
    return row_dict(row)


@app.post("/api/stores/{store_id}/products", status_code=201)
def create_store_product(store_id: int, product: StoreProductCreate) -> dict:
    with connect() as conn:
        require_row(conn, "SELECT id FROM store WHERE id = ?", (store_id,), "Store not found")
        item_id = ensure_item(conn, product.item_name)
        conn.execute(
            """
            INSERT INTO store_product(
                store_id, store_section_id, item_id, item_name,
                in_stock, stock_quantity, price, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(store_id, item_name)
            DO UPDATE SET store_section_id = excluded.store_section_id,
                          item_id = excluded.item_id,
                          in_stock = excluded.in_stock,
                          stock_quantity = excluded.stock_quantity,
                          price = excluded.price,
                          updated_at = CURRENT_TIMESTAMP
            """,
            (
                store_id,
                product.store_section_id,
                item_id,
                product.item_name.strip(),
                int(product.in_stock),
                product.stock_quantity,
                product.price,
            ),
        )
        row = conn.execute(
            """
            SELECT id, item_name, in_stock
            FROM store_product
            WHERE store_id = ? AND item_name = ?
            """,
            (store_id, product.item_name.strip()),
        ).fetchone()
    data = row_dict(row)
    data["in_stock"] = bool(data["in_stock"])
    return data


def transfer_items(conn: sqlite3.Connection, final_list_id: int, store_id: int) -> list[dict]:
    rows = conn.execute(
        """
        SELECT
            fli.id AS final_list_item_id,
            fli.item_name,
            fli.quantity,
            sp.id AS store_product_id,
            sp.in_stock,
            ss.id AS store_section_id,
            ss.name AS section,
            ss.aisle,
            ss.shelf,
            ss.map_x,
            ss.map_y
        FROM final_shopping_list_item fli
        LEFT JOIN store_product sp
            ON sp.store_id = ? AND sp.item_name = fli.item_name
        LEFT JOIN store_section ss
            ON ss.id = sp.store_section_id
        WHERE fli.final_list_id = ?
        ORDER BY fli.item_name
        """,
        (store_id, final_list_id),
    ).fetchall()
    items = []
    for row in rows:
        item = row_dict(row)
        item["in_stock"] = bool(item["in_stock"]) if item["in_stock"] is not None else False
        items.append(item)
    return items


@app.post("/api/final-shopping-lists/{final_list_id}/transfer", status_code=201)
def transfer_final_shopping_list(
    final_list_id: int,
    transfer: ShoppingListTransferCreate,
) -> dict:
    with connect() as conn:
        require_row(
            conn,
            "SELECT id FROM final_shopping_list WHERE id = ?",
            (final_list_id,),
            "Final shopping list not found",
        )
        require_row(conn, "SELECT id FROM store WHERE id = ?", (transfer.store_id,), "Store not found")
        items = transfer_items(conn, final_list_id, transfer.store_id)
        payload = json.dumps({"items": items}, ensure_ascii=False)
        cursor = conn.execute(
            """
            INSERT INTO shopping_list_transfer(final_list_id, store_id, payload)
            VALUES (?, ?, ?)
            """,
            (final_list_id, transfer.store_id, payload),
        )
        conn.execute(
            """
            UPDATE final_shopping_list
            SET status = 'sent_to_store', sent_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            (final_list_id,),
        )
    return {
        "transfer_id": cursor.lastrowid,
        "store_id": transfer.store_id,
        "status": "sent",
        "items": items,
    }


@app.get("/api/stores/{store_id}/transfers/{transfer_id}")
def get_store_transfer(store_id: int, transfer_id: int) -> dict:
    with connect() as conn:
        transfer = require_row(
            conn,
            """
            SELECT * FROM shopping_list_transfer
            WHERE id = ? AND store_id = ?
            """,
            (transfer_id, store_id),
            "Transfer not found",
        )
        conn.execute(
            """
            UPDATE shopping_list_transfer
            SET status = 'received', received_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            (transfer_id,),
        )
        items = transfer_items(conn, transfer["final_list_id"], store_id)
    return {
        "transfer_id": transfer_id,
        "final_list_id": transfer["final_list_id"],
        "store_id": store_id,
        "items": items,
    }


@app.post("/api/transfers/{transfer_id}/navigation", status_code=201)
def create_store_navigation(transfer_id: int, navigation: StoreNavigationCreate) -> dict:
    with connect() as conn:
        transfer = require_row(
            conn,
            """
            SELECT * FROM shopping_list_transfer
            WHERE id = ? AND store_id = ?
            """,
            (transfer_id, navigation.store_id),
            "Transfer not found",
        )
        cursor = conn.execute(
            """
            INSERT INTO store_navigation_session(transfer_id, store_id)
            VALUES (?, ?)
            """,
            (transfer_id, navigation.store_id),
        )
        navigation_session_id = cursor.lastrowid
        items = transfer_items(conn, transfer["final_list_id"], navigation.store_id)
        steps = []
        step_order = 1
        for item in items:
            if not item["in_stock"]:
                instruction = f"{item['item_name']} is out of stock."
                status = "out_of_stock"
            else:
                section = item["section"] or "unknown section"
                aisle = item["aisle"] or "-"
                shelf = item["shelf"] or "-"
                instruction = f"Go to {section}, aisle {aisle}, shelf {shelf}."
                status = "pending"
            cursor = conn.execute(
                """
                INSERT INTO store_navigation_step(
                    navigation_session_id, step_order, final_list_item_id,
                    store_product_id, store_section_id, instruction, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    navigation_session_id,
                    step_order,
                    item["final_list_item_id"],
                    item["store_product_id"],
                    item["store_section_id"],
                    instruction,
                    status,
                ),
            )
            steps.append(
                {
                    "id": cursor.lastrowid,
                    "step_order": step_order,
                    "item_name": item["item_name"],
                    "instruction": instruction,
                    "map_x": item["map_x"],
                    "map_y": item["map_y"],
                    "status": status,
                }
            )
            step_order += 1
    return {"navigation_session_id": navigation_session_id, "steps": steps}


@app.get("/api/navigation-sessions/{navigation_session_id}/steps")
def get_navigation_steps(navigation_session_id: int) -> dict:
    with connect() as conn:
        rows = conn.execute(
            """
            SELECT
                step.id,
                step.step_order,
                fli.item_name,
                step.instruction,
                step.status,
                section.map_x,
                section.map_y
            FROM store_navigation_step step
            LEFT JOIN final_shopping_list_item fli ON fli.id = step.final_list_item_id
            LEFT JOIN store_section section ON section.id = step.store_section_id
            WHERE step.navigation_session_id = ?
            ORDER BY step.step_order
            """,
            (navigation_session_id,),
        ).fetchall()
    return {
        "navigation_session_id": navigation_session_id,
        "steps": [row_dict(r) for r in rows],
    }


@app.put("/api/navigation-steps/{step_id}")
def update_navigation_step(step_id: int, update: NavigationStepUpdate) -> dict:
    with connect() as conn:
        require_row(
            conn,
            "SELECT id FROM store_navigation_step WHERE id = ?",
            (step_id,),
            "Navigation step not found",
        )
        completed_expr = "CURRENT_TIMESTAMP" if update.status in {"picked", "skipped", "out_of_stock"} else "NULL"
        conn.execute(
            f"""
            UPDATE store_navigation_step
            SET status = ?, completed_at = {completed_expr}
            WHERE id = ?
            """,
            (update.status, step_id),
        )
    return {"id": step_id, "status": update.status}


@app.get("/api/shopping-list")
def get_shopping_list(status: Optional[str] = None) -> dict:
    query = "SELECT * FROM shopping_list"
    params: list = []
    if status:
        query += " WHERE status = ?"
        params.append(status)
    query += " ORDER BY created_at DESC"
    with connect() as conn:
        rows = conn.execute(query, params).fetchall()
    return {"items": [row_dict(r) for r in rows]}


@app.post("/api/shopping-list", status_code=201)
def create_shopping_list_item(item: ShoppingListCreate) -> dict:
    with connect() as conn:
        cursor = conn.execute(
            """
            INSERT INTO shopping_list(name, quantity, user_id, note)
            VALUES (?, ?, ?, ?)
            """,
            (item.name.strip(), item.quantity, item.user_id, item.note),
        )
    return {"id": cursor.lastrowid, "name": item.name}


@app.put("/api/shopping-list/{item_id}", status_code=200)
def update_shopping_list_item(item_id: int, update: ShoppingListUpdate) -> dict:
    with connect() as conn:
        result = conn.execute(
            "UPDATE shopping_list SET status = ? WHERE id = ?",
            (update.status, item_id),
        )
        if result.rowcount == 0:
            raise HTTPException(status_code=404, detail="Item not found")
    return {"id": item_id, "status": update.status}


@app.delete("/api/shopping-list/{item_id}", status_code=200)
def delete_shopping_list_item(item_id: int) -> dict:
    with connect() as conn:
        result = conn.execute("DELETE FROM shopping_list WHERE id = ?", (item_id,))
        if result.rowcount == 0:
            raise HTTPException(status_code=404, detail="Item not found")
    return {"id": item_id, "deleted": True}
