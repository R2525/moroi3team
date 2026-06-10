import sqlite3, sys
sys.stdout.reconfigure(encoding='utf-8')

conn = sqlite3.connect('api_server/inventory.db')

# user 테이블 생성
conn.execute("""
    CREATE TABLE IF NOT EXISTS user (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL UNIQUE,
        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
    )
""")

# 기존 테이블에 user_id 컬럼 추가 (이미 있으면 무시)
try:
    conn.execute("ALTER TABLE item_placement ADD COLUMN user_id INTEGER REFERENCES user(id)")
    print("item_placement.user_id 추가")
except Exception:
    print("item_placement.user_id 이미 존재")

try:
    conn.execute("ALTER TABLE shopping_list ADD COLUMN user_id INTEGER REFERENCES user(id)")
    print("shopping_list.user_id 추가")
except Exception:
    print("shopping_list.user_id 이미 존재")

conn.commit()

print()
print("=== user 테이블 ===")
rows = conn.execute("SELECT * FROM user").fetchall()
print("  (비어 있음)" if not rows else "")
for r in rows:
    print(" ", dict(r))

conn.close()
print("완료.")
