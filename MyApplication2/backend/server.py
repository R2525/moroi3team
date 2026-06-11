#!/usr/bin/env python3
import argparse
import json
import sqlite3
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse


ROOT_DIR = Path(__file__).resolve().parent
DEFAULT_DB_PATH = ROOT_DIR / "data" / "temi_shopping.db"


def now_iso():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def row_to_dict(row):
    return {key: row[key] for key in row.keys()}


class TemiDatabase:
    def __init__(self, db_path):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.init_schema()

    def connect(self):
        connection = sqlite3.connect(self.db_path)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        return connection

    def init_schema(self):
        with self.connect() as db:
            db.executescript(
                """
                CREATE TABLE IF NOT EXISTS "User" (
                    user_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_name TEXT NOT NULL UNIQUE,
                    phone TEXT
                );

                CREATE TABLE IF NOT EXISTS Temi (
                    temi_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL,
                    temi_type TEXT NOT NULL CHECK (temi_type IN ('HOME', 'STORE')),
                    status TEXT NOT NULL DEFAULT '대기 중',
                    serial_no TEXT NOT NULL UNIQUE,
                    FOREIGN KEY(user_id) REFERENCES "User"(user_id) ON DELETE CASCADE
                );

                CREATE TABLE IF NOT EXISTS Smart_Drawer (
                    drawer_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    temi_id INTEGER NOT NULL,
                    drawer_name TEXT NOT NULL,
                    FOREIGN KEY(temi_id) REFERENCES Temi(temi_id) ON DELETE CASCADE
                );

                CREATE TABLE IF NOT EXISTS Items (
                    item_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    drawer_id INTEGER NOT NULL,
                    item_name TEXT NOT NULL,
                    quantity INTEGER NOT NULL DEFAULT 1,
                    vlm_tag TEXT,
                    vlm_analysis_tag TEXT,
                    image_url TEXT,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY(drawer_id) REFERENCES Smart_Drawer(drawer_id) ON DELETE CASCADE
                );

                CREATE TABLE IF NOT EXISTS Shopping_List (
                    list_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL,
                    item_name TEXT NOT NULL,
                    quantity INTEGER NOT NULL DEFAULT 1,
                    is_bought INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY(user_id) REFERENCES "User"(user_id) ON DELETE CASCADE
                );

                CREATE TABLE IF NOT EXISTS Store_Map (
                    map_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    item_name TEXT NOT NULL,
                    section TEXT,
                    section_name TEXT NOT NULL,
                    coord_x REAL NOT NULL,
                    coord_y REAL NOT NULL
                );

                CREATE TRIGGER IF NOT EXISTS trg_items_quantity_zero_insert
                AFTER INSERT ON Items
                WHEN NEW.quantity <= 0
                BEGIN
                    INSERT INTO Shopping_List (user_id, item_name, quantity, is_bought, created_at)
                    SELECT Temi.user_id, NEW.item_name, 1, 0, CURRENT_TIMESTAMP
                    FROM Smart_Drawer
                    JOIN Temi ON Temi.temi_id = Smart_Drawer.temi_id
                    WHERE Smart_Drawer.drawer_id = NEW.drawer_id
                      AND NOT EXISTS (
                          SELECT 1
                          FROM Shopping_List
                          WHERE Shopping_List.user_id = Temi.user_id
                            AND Shopping_List.item_name = NEW.item_name
                            AND Shopping_List.is_bought = 0
                      );
                END;

                CREATE TRIGGER IF NOT EXISTS trg_items_quantity_zero_update
                AFTER UPDATE OF quantity ON Items
                WHEN NEW.quantity <= 0 AND OLD.quantity > 0
                BEGIN
                    INSERT INTO Shopping_List (user_id, item_name, quantity, is_bought, created_at)
                    SELECT Temi.user_id, NEW.item_name, 1, 0, CURRENT_TIMESTAMP
                    FROM Smart_Drawer
                    JOIN Temi ON Temi.temi_id = Smart_Drawer.temi_id
                    WHERE Smart_Drawer.drawer_id = NEW.drawer_id
                      AND NOT EXISTS (
                          SELECT 1
                          FROM Shopping_List
                          WHERE Shopping_List.user_id = Temi.user_id
                            AND Shopping_List.item_name = NEW.item_name
                            AND Shopping_List.is_bought = 0
                      );
                END;
                """
            )
            self.migrate_schema(db)
            self.seed(db)

    def migrate_schema(self, db):
        self.ensure_column(db, "Temi", "status", "TEXT NOT NULL DEFAULT '대기 중'")
        self.ensure_column(db, "Items", "vlm_tag", "TEXT")
        self.ensure_column(db, "Store_Map", "section", "TEXT")
        db.execute("UPDATE Items SET vlm_tag = COALESCE(vlm_tag, vlm_analysis_tag)")
        db.execute("UPDATE Store_Map SET section = COALESCE(section, section_name)")

    def ensure_column(self, db, table_name, column_name, definition):
        columns = [row["name"] for row in db.execute(f'PRAGMA table_info("{table_name}")').fetchall()]
        if column_name not in columns:
            db.execute(f'ALTER TABLE "{table_name}" ADD COLUMN {column_name} {definition}')

    def seed(self, db):
        user_count = db.execute('SELECT COUNT(*) AS count FROM "User"').fetchone()["count"]
        if user_count > 0:
            return

        db.execute('INSERT INTO "User" (user_name, phone) VALUES (?, ?)', ("사용자 1", "010-0000-0001"))
        db.execute('INSERT INTO "User" (user_name, phone) VALUES (?, ?)', ("사용자 2", "010-0000-0002"))
        db.execute(
            "INSERT INTO Temi (user_id, temi_type, status, serial_no) VALUES (?, ?, ?, ?)",
            (1, "HOME", "대기 중", "HOME-TEMI-01"),
        )
        db.execute(
            "INSERT INTO Temi (user_id, temi_type, status, serial_no) VALUES (?, ?, ?, ?)",
            (1, "STORE", "대기 중", "STORE-TEMI-03"),
        )
        db.execute("INSERT INTO Smart_Drawer (temi_id, drawer_name) VALUES (?, ?)", (1, "1번 서랍"))
        db.execute("INSERT INTO Smart_Drawer (temi_id, drawer_name) VALUES (?, ?)", (1, "2번 서랍"))
        db.execute("INSERT INTO Smart_Drawer (temi_id, drawer_name) VALUES (?, ?)", (1, "3번 서랍"))
        db.executemany(
            """
            INSERT INTO Items (drawer_id, item_name, quantity, vlm_tag, vlm_analysis_tag, image_url, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            [
                (1, "세제", 1, "생필품", "detergent", None, now_iso()),
                (2, "수건", 3, "생필품", "towel", None, now_iso()),
                (3, "샴푸", 1, "생필품", "shampoo", None, now_iso()),
                (1, "휴지", 2, "생필품", "tissue", None, now_iso()),
            ],
        )
        db.executemany(
            """
            INSERT INTO Store_Map (item_name, section, section_name, coord_x, coord_y)
            VALUES (?, ?, ?, ?, ?)
            """,
            [
                ("세제", "생활용품", "생활용품", 12.5, 4.0),
                ("수건", "욕실용품", "욕실용품", 7.2, 8.5),
                ("샴푸", "욕실용품", "욕실용품", 8.0, 9.0),
                ("휴지", "생활용품", "생활용품", 11.0, 3.0),
            ],
        )

    def list_users(self):
        with self.connect() as db:
            rows = db.execute('SELECT * FROM "User" ORDER BY user_id').fetchall()
            return [row_to_dict(row) for row in rows]

    def create_user(self, payload):
        user_name = required_text(payload, "user_name", fallback_key="name")
        phone = optional_text(payload.get("phone"))
        with self.connect() as db:
            cursor = db.execute(
                'INSERT INTO "User" (user_name, phone) VALUES (?, ?)',
                (user_name, phone),
            )
            return self.get_user(cursor.lastrowid, db)

    def update_user(self, user_id, payload):
        user_name = required_text(payload, "user_name", fallback_key="name")
        phone = optional_text(payload.get("phone"))
        with self.connect() as db:
            db.execute(
                'UPDATE "User" SET user_name = ?, phone = COALESCE(?, phone) WHERE user_id = ?',
                (user_name, phone, user_id),
            )
            user = self.get_user(user_id, db)
            if user is None:
                raise NotFoundError("user not found")
            return user

    def get_user(self, user_id, db=None):
        owns_connection = db is None
        db = db or self.connect()
        try:
            row = db.execute('SELECT * FROM "User" WHERE user_id = ?', (user_id,)).fetchone()
            return row_to_dict(row) if row else None
        finally:
            if owns_connection:
                db.close()

    def list_temi(self, user_id=None, temi_type=None):
        sql = """
            SELECT Temi.*, "User".user_name
            FROM Temi
            JOIN "User" ON "User".user_id = Temi.user_id
            WHERE 1 = 1
        """
        params = []
        if user_id is not None:
            sql += " AND Temi.user_id = ?"
            params.append(user_id)
        if temi_type is not None:
            sql += " AND Temi.temi_type = ?"
            params.append(temi_type)
        sql += " ORDER BY Temi.temi_id"
        with self.connect() as db:
            return [row_to_dict(row) for row in db.execute(sql, params).fetchall()]

    def create_temi(self, payload):
        user_id = positive_int(payload.get("user_id"), "user_id")
        temi_type = required_text(payload, "temi_type").upper()
        if temi_type not in ("HOME", "STORE"):
            raise ValidationError("temi_type must be HOME or STORE")
        serial_no = required_text(payload, "serial_no")
        status = optional_text(payload.get("status")) or "대기 중"
        with self.connect() as db:
            cursor = db.execute(
                "INSERT INTO Temi (user_id, temi_type, status, serial_no) VALUES (?, ?, ?, ?)",
                (user_id, temi_type, status, serial_no),
            )
            return self.get_temi(cursor.lastrowid, db)

    def get_temi(self, temi_id, db=None):
        owns_connection = db is None
        db = db or self.connect()
        try:
            row = db.execute(
                """
                SELECT Temi.*, "User".user_name
                FROM Temi
                JOIN "User" ON "User".user_id = Temi.user_id
                WHERE Temi.temi_id = ?
                """,
                (temi_id,),
            ).fetchone()
            return row_to_dict(row) if row else None
        finally:
            if owns_connection:
                db.close()

    def list_drawers(self, temi_id=None):
        sql = """
            SELECT Smart_Drawer.*, Temi.temi_type, Temi.serial_no
            FROM Smart_Drawer
            JOIN Temi ON Temi.temi_id = Smart_Drawer.temi_id
            WHERE 1 = 1
        """
        params = []
        if temi_id is not None:
            sql += " AND Smart_Drawer.temi_id = ?"
            params.append(temi_id)
        sql += " ORDER BY Smart_Drawer.drawer_id"
        with self.connect() as db:
            return [row_to_dict(row) for row in db.execute(sql, params).fetchall()]

    def create_drawer(self, payload):
        temi_id = positive_int(payload.get("temi_id"), "temi_id")
        drawer_name = required_text(payload, "drawer_name")
        with self.connect() as db:
            cursor = db.execute(
                "INSERT INTO Smart_Drawer (temi_id, drawer_name) VALUES (?, ?)",
                (temi_id, drawer_name),
            )
            return self.get_drawer(cursor.lastrowid, db)

    def get_drawer(self, drawer_id, db=None):
        owns_connection = db is None
        db = db or self.connect()
        try:
            row = db.execute(
                """
                SELECT Smart_Drawer.*, Temi.temi_type, Temi.serial_no
                FROM Smart_Drawer
                JOIN Temi ON Temi.temi_id = Smart_Drawer.temi_id
                WHERE Smart_Drawer.drawer_id = ?
                """,
                (drawer_id,),
            ).fetchone()
            return row_to_dict(row) if row else None
        finally:
            if owns_connection:
                db.close()

    def list_items(self, keyword=None):
        sql = """
            SELECT Items.*, Smart_Drawer.drawer_name, Temi.temi_id, Temi.temi_type, "User".user_id, "User".user_name
            FROM Items
            JOIN Smart_Drawer ON Smart_Drawer.drawer_id = Items.drawer_id
            JOIN Temi ON Temi.temi_id = Smart_Drawer.temi_id
            JOIN "User" ON "User".user_id = Temi.user_id
            WHERE 1 = 1
        """
        params = []
        if keyword:
            sql += " AND Items.item_name LIKE ?"
            params.append(f"%{keyword}%")
        sql += " ORDER BY Items.item_id DESC"
        with self.connect() as db:
            return [row_to_dict(row) for row in db.execute(sql, params).fetchall()]

    def create_item(self, payload):
        drawer_id = payload.get("drawer_id")
        if drawer_id is None and payload.get("drawer_number") is not None:
            drawer_id = self.get_or_create_drawer_by_number(positive_int(payload.get("drawer_number"), "drawer_number"))
        drawer_id = positive_int(drawer_id, "drawer_id")
        item_name = required_text(payload, "item_name", fallback_key="name")
        quantity = nonnegative_int(payload.get("quantity", 1), "quantity")
        vlm_tag = optional_text(payload.get("vlm_tag", payload.get("vlm_analysis_tag")))
        vlm_analysis_tag = optional_text(payload.get("vlm_analysis_tag", payload.get("vlm_tag")))
        image_url = optional_text(payload.get("image_url"))
        with self.connect() as db:
            cursor = db.execute(
                """
                INSERT INTO Items (drawer_id, item_name, quantity, vlm_tag, vlm_analysis_tag, image_url, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (drawer_id, item_name, quantity, vlm_tag, vlm_analysis_tag, image_url, now_iso()),
            )
            return self.get_item(cursor.lastrowid, db)

    def update_item(self, item_id, payload):
        item = self.get_item(item_id)
        if item is None:
            raise NotFoundError("item not found")

        drawer_id = payload.get("drawer_id", item["drawer_id"])
        if payload.get("drawer_number") is not None:
            drawer_id = self.get_or_create_drawer_by_number(positive_int(payload.get("drawer_number"), "drawer_number"))
        drawer_id = positive_int(drawer_id, "drawer_id")
        item_name = optional_text(payload.get("item_name", payload.get("name"))) or item["item_name"]
        quantity = nonnegative_int(payload.get("quantity", item["quantity"]), "quantity")
        vlm_tag = optional_text(payload.get("vlm_tag", payload.get("vlm_analysis_tag"))) or item["vlm_tag"]
        vlm_analysis_tag = optional_text(payload.get("vlm_analysis_tag", payload.get("vlm_tag"))) or item["vlm_analysis_tag"]
        image_url = optional_text(payload.get("image_url")) or item["image_url"]

        with self.connect() as db:
            db.execute(
                """
                UPDATE Items
                SET drawer_id = ?, item_name = ?, quantity = ?, vlm_tag = ?,
                    vlm_analysis_tag = ?, image_url = ?, updated_at = ?
                WHERE item_id = ?
                """,
                (drawer_id, item_name, quantity, vlm_tag, vlm_analysis_tag, image_url, now_iso(), item_id),
            )
            return self.get_item(item_id, db)

    def get_or_create_drawer_by_number(self, drawer_number):
        drawer_name = f"{drawer_number}번 서랍"
        with self.connect() as db:
            row = db.execute(
                "SELECT drawer_id FROM Smart_Drawer WHERE drawer_name = ? ORDER BY drawer_id LIMIT 1",
                (drawer_name,),
            ).fetchone()
            if row:
                return row["drawer_id"]

            home_temi = db.execute(
                "SELECT temi_id FROM Temi WHERE temi_type = 'HOME' ORDER BY temi_id LIMIT 1"
            ).fetchone()
            if home_temi is None:
                raise ValidationError("HOME Temi is required before creating drawers")
            cursor = db.execute(
                "INSERT INTO Smart_Drawer (temi_id, drawer_name) VALUES (?, ?)",
                (home_temi["temi_id"], drawer_name),
            )
            return cursor.lastrowid

    def delete_item(self, item_id):
        with self.connect() as db:
            cursor = db.execute("DELETE FROM Items WHERE item_id = ?", (item_id,))
            if cursor.rowcount == 0:
                raise NotFoundError("item not found")

    def get_item(self, item_id, db=None):
        owns_connection = db is None
        db = db or self.connect()
        try:
            row = db.execute(
                """
                SELECT Items.*, Smart_Drawer.drawer_name, Temi.temi_id, Temi.temi_type,
                    "User".user_id, "User".user_name
                FROM Items
                JOIN Smart_Drawer ON Smart_Drawer.drawer_id = Items.drawer_id
                JOIN Temi ON Temi.temi_id = Smart_Drawer.temi_id
                JOIN "User" ON "User".user_id = Temi.user_id
                WHERE Items.item_id = ?
                """,
                (item_id,),
            ).fetchone()
            return row_to_dict(row) if row else None
        finally:
            if owns_connection:
                db.close()

    def list_shopping_items(self, user_id=None, is_bought=None):
        sql = """
            SELECT Shopping_List.*, "User".user_name, Store_Map.section_name, Store_Map.coord_x, Store_Map.coord_y
            FROM Shopping_List
            JOIN "User" ON "User".user_id = Shopping_List.user_id
            LEFT JOIN Store_Map ON Store_Map.item_name = Shopping_List.item_name
            WHERE 1 = 1
        """
        params = []
        if user_id is not None:
            sql += " AND Shopping_List.user_id = ?"
            params.append(user_id)
        if is_bought is not None:
            sql += " AND Shopping_List.is_bought = ?"
            params.append(1 if is_bought else 0)
        sql += " ORDER BY Shopping_List.list_id DESC"
        with self.connect() as db:
            return [row_to_dict(row) for row in db.execute(sql, params).fetchall()]

    def create_shopping_item(self, payload):
        user_id = positive_int(payload.get("user_id"), "user_id")
        item_name = required_text(payload, "item_name", fallback_key="name")
        quantity = positive_int(payload.get("quantity", 1), "quantity")
        is_bought = bool_value(payload.get("is_bought", False))
        with self.connect() as db:
            cursor = self.insert_shopping_item(db, user_id, item_name, quantity, is_bought)
            return self.get_shopping_item(cursor.lastrowid, db)

    def insert_shopping_item(self, db, user_id, item_name, quantity, is_bought):
        return db.execute(
            """
            INSERT INTO Shopping_List (user_id, item_name, quantity, is_bought, created_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            (user_id, item_name, quantity, 1 if is_bought else 0, now_iso()),
        )

    def create_shopping_items_bulk(self, payload):
        items = payload.get("items", [])
        if not isinstance(items, list):
            raise ValidationError("items must be a list")
        created = []
        for item in items:
            created.append(self.create_shopping_item(item))
        return created

    def update_shopping_item(self, list_id, payload):
        is_bought = bool_value(payload.get("is_bought"))
        with self.connect() as db:
            db.execute(
                "UPDATE Shopping_List SET is_bought = ? WHERE list_id = ?",
                (1 if is_bought else 0, list_id),
            )
            item = self.get_shopping_item(list_id, db)
            if item is None:
                raise NotFoundError("shopping list item not found")
            return item

    def delete_shopping_item(self, list_id):
        with self.connect() as db:
            cursor = db.execute("DELETE FROM Shopping_List WHERE list_id = ?", (list_id,))
            if cursor.rowcount == 0:
                raise NotFoundError("shopping list item not found")

    def get_shopping_item(self, list_id, db=None):
        owns_connection = db is None
        db = db or self.connect()
        try:
            row = db.execute(
                """
                SELECT Shopping_List.*, "User".user_name, Store_Map.section_name, Store_Map.coord_x, Store_Map.coord_y
                FROM Shopping_List
                JOIN "User" ON "User".user_id = Shopping_List.user_id
                LEFT JOIN Store_Map ON Store_Map.item_name = Shopping_List.item_name
                WHERE Shopping_List.list_id = ?
                """,
                (list_id,),
            ).fetchone()
            return row_to_dict(row) if row else None
        finally:
            if owns_connection:
                db.close()

    def list_store_map(self, keyword=None):
        sql = "SELECT * FROM Store_Map WHERE 1 = 1"
        params = []
        if keyword:
            sql += " AND item_name LIKE ?"
            params.append(f"%{keyword}%")
        sql += " ORDER BY map_id"
        with self.connect() as db:
            return [row_to_dict(row) for row in db.execute(sql, params).fetchall()]

    def create_store_map(self, payload):
        item_name = required_text(payload, "item_name", fallback_key="name")
        section_name = required_text(payload, "section_name", fallback_key="section")
        coord_x = float_value(payload.get("coord_x"), "coord_x")
        coord_y = float_value(payload.get("coord_y"), "coord_y")
        with self.connect() as db:
            cursor = db.execute(
                """
                INSERT INTO Store_Map (item_name, section, section_name, coord_x, coord_y)
                VALUES (?, ?, ?, ?, ?)
                """,
                (item_name, section_name, section_name, coord_x, coord_y),
            )
            row = db.execute("SELECT * FROM Store_Map WHERE map_id = ?", (cursor.lastrowid,)).fetchone()
            return row_to_dict(row)


class ApiHandler(BaseHTTPRequestHandler):
    db = None

    def do_GET(self):
        self.handle_request("GET")

    def do_POST(self):
        self.handle_request("POST")

    def do_PATCH(self):
        self.handle_request("PATCH")

    def do_DELETE(self):
        self.handle_request("DELETE")

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_default_headers()
        self.end_headers()

    def handle_request(self, method):
        try:
            parsed = urlparse(self.path)
            path = parsed.path.rstrip("/") or "/"
            query = parse_qs(parsed.query)
            payload = self.read_json() if method in ("POST", "PATCH") else {}
            status, result = self.route(method, path, query, payload)
            self.send_json(status, result)
        except ValidationError as error:
            self.send_json(400, {"error": str(error)})
        except NotFoundError as error:
            self.send_json(404, {"error": str(error)})
        except sqlite3.IntegrityError as error:
            self.send_json(409, {"error": "database constraint failed", "detail": str(error)})
        except Exception as error:
            self.send_json(500, {"error": "internal server error", "detail": str(error)})

    def route(self, method, path, query, payload):
        parts = [part for part in path.split("/") if part]

        if method == "GET" and path == "/health":
            return 200, {"ok": True, "database": str(self.db.db_path), "schema": "temi-smart-home-v2"}

        if path == "/users":
            if method == "GET":
                return 200, {"users": self.db.list_users()}
            if method == "POST":
                return 201, {"user": self.db.create_user(payload)}
        if len(parts) == 2 and parts[0] == "users" and method == "PATCH":
            return 200, {"user": self.db.update_user(to_int(parts[1], "user_id"), payload)}

        if path == "/temi":
            if method == "GET":
                user_id = optional_int(first_query_value(query, "user_id"), "user_id")
                temi_type = first_query_value(query, "temi_type")
                return 200, {"temi": self.db.list_temi(user_id, temi_type)}
            if method == "POST":
                return 201, {"temi": self.db.create_temi(payload)}

        if path == "/drawers":
            if method == "GET":
                temi_id = optional_int(first_query_value(query, "temi_id"), "temi_id")
                return 200, {"drawers": self.db.list_drawers(temi_id)}
            if method == "POST":
                return 201, {"drawer": self.db.create_drawer(payload)}

        if path in ("/items", "/storage-items"):
            if method == "GET":
                keyword = first_query_value(query, "keyword")
                return 200, {"items": self.db.list_items(keyword)}
            if method == "POST":
                return 201, {"item": self.db.create_item(payload)}
        if len(parts) == 2 and parts[0] in ("items", "storage-items") and method == "DELETE":
            self.db.delete_item(to_int(parts[1], "item_id"))
            return 200, {"deleted": True}
        if len(parts) == 2 and parts[0] in ("items", "storage-items") and method == "PATCH":
            return 200, {"item": self.db.update_item(to_int(parts[1], "item_id"), payload)}

        if path in ("/shopping-list", "/shopping-list-items"):
            if method == "GET":
                user_id = optional_int(first_query_value(query, "user_id"), "user_id")
                bought = optional_bool(first_query_value(query, "is_bought"))
                return 200, {"shopping_list": self.db.list_shopping_items(user_id, bought)}
            if method == "POST":
                return 201, {"shopping_list_item": self.db.create_shopping_item(payload)}

        if path == "/shopping-lists":
            if method == "GET":
                return 200, {"shopping_lists": self.db.list_shopping_items()}
            if method == "POST":
                return 201, {"shopping_list": self.db.create_shopping_items_bulk(payload)}

        if len(parts) == 2 and parts[0] in ("shopping-list", "shopping-list-items") and method == "PATCH":
            return 200, {"shopping_list_item": self.db.update_shopping_item(to_int(parts[1], "list_id"), payload)}
        if len(parts) == 2 and parts[0] in ("shopping-list", "shopping-list-items") and method == "DELETE":
            self.db.delete_shopping_item(to_int(parts[1], "list_id"))
            return 200, {"deleted": True}

        if path in ("/store-map", "/store-map-items"):
            if method == "GET":
                keyword = first_query_value(query, "keyword")
                return 200, {"store_map": self.db.list_store_map(keyword)}
            if method == "POST":
                return 201, {"store_map_item": self.db.create_store_map(payload)}

        if path == "/db/items" and method == "GET":
            keyword = first_query_value(query, "keyword")
            return 200, {"items": self.db.list_items(keyword)}

        raise NotFoundError("endpoint not found")

    def read_json(self):
        content_length = int(self.headers.get("Content-Length", 0))
        if content_length == 0:
            return {}
        raw_body = self.rfile.read(content_length).decode("utf-8")
        try:
            payload = json.loads(raw_body)
        except json.JSONDecodeError:
            raise ValidationError("request body must be valid JSON")
        if not isinstance(payload, dict):
            raise ValidationError("request body must be a JSON object")
        return payload

    def send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_default_headers()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_default_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def log_message(self, format_text, *args):
        print("[%s] %s" % (self.log_date_time_string(), format_text % args))


class ValidationError(Exception):
    pass


class NotFoundError(Exception):
    pass


def first_query_value(query, key):
    values = query.get(key)
    if not values:
        return None
    value = values[0].strip()
    return value or None


def required_text(payload, key, fallback_key=None):
    value = payload.get(key)
    if (value is None or value == "") and fallback_key:
        value = payload.get(fallback_key)
    if not isinstance(value, str) or len(value.strip()) == 0:
        raise ValidationError(f"{key} is required")
    return value.strip()


def optional_text(value):
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValidationError("text value must be a string")
    return value.strip() or None


def to_int(value, name):
    try:
        return int(value)
    except (TypeError, ValueError):
        raise ValidationError(f"{name} must be an integer")


def optional_int(value, name):
    if value is None or value == "":
        return None
    return to_int(value, name)


def positive_int(value, name):
    integer = to_int(value, name)
    if integer <= 0:
        raise ValidationError(f"{name} must be greater than 0")
    return integer


def nonnegative_int(value, name):
    integer = to_int(value, name)
    if integer < 0:
        raise ValidationError(f"{name} must be 0 or greater")
    return integer


def float_value(value, name):
    try:
        return float(value)
    except (TypeError, ValueError):
        raise ValidationError(f"{name} must be a number")


def bool_value(value):
    if isinstance(value, bool):
        return value
    if isinstance(value, int):
        return value != 0
    if isinstance(value, str):
        lowered = value.strip().lower()
        if lowered in ("true", "1", "yes", "y"):
            return True
        if lowered in ("false", "0", "no", "n"):
            return False
    raise ValidationError("boolean value must be true or false")


def optional_bool(value):
    if value is None or value == "":
        return None
    return bool_value(value)


def main():
    parser = argparse.ArgumentParser(description="TEMI smart home database API server")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", default=8080, type=int)
    parser.add_argument("--db", default=str(DEFAULT_DB_PATH))
    args = parser.parse_args()

    ApiHandler.db = TemiDatabase(args.db)
    server = ThreadingHTTPServer((args.host, args.port), ApiHandler)
    print(f"TEMI DB server running at http://{args.host}:{args.port}")
    print(f"SQLite database: {Path(args.db).resolve()}")
    server.serve_forever()


if __name__ == "__main__":
    main()
