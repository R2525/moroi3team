import sqlite3, sys
sys.stdout.reconfigure(encoding='utf-8')

conn = sqlite3.connect('api_server/inventory.db')
conn.row_factory = sqlite3.Row

# shopping_list 테이블 생성 (없으면)
conn.execute("""
    CREATE TABLE IF NOT EXISTS shopping_list (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        quantity INTEGER NOT NULL DEFAULT 1,
        category TEXT,
        status TEXT NOT NULL DEFAULT 'pending',
        note TEXT,
        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
    )
""")

# item 테이블에서 category='shopping_list' 인 항목 조회
misplaced = conn.execute(
    "SELECT * FROM item WHERE category = 'shopping_list' OR status = 'needed'"
).fetchall()

print(f"이동할 항목: {len(misplaced)}건")
for r in misplaced:
    d = dict(r)
    print(f"  item.id={d['id']} | {d['name']} | status={d['status']}")

    # shopping_list로 이동
    conn.execute(
        "INSERT OR IGNORE INTO shopping_list(name, status, created_at) VALUES (?, 'pending', ?)",
        (d['name'], d['created_at'])
    )

    # item_placement 에서 먼저 삭제
    conn.execute("DELETE FROM item_placement WHERE item_id = ?", (d['id'],))

    # item 에서 삭제
    conn.execute("DELETE FROM item WHERE id = ?", (d['id'],))

conn.commit()

print()
print("=== 이동 후 shopping_list ===")
for r in conn.execute("SELECT * FROM shopping_list"):
    print(dict(r))

print()
print("=== 정리 후 item ===")
for r in conn.execute("SELECT id, name, category, status FROM item"):
    print(dict(r))

conn.close()
print()
print("완료.")
