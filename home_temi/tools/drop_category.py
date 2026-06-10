import sqlite3, sys
sys.stdout.reconfigure(encoding='utf-8')

conn = sqlite3.connect('api_server/inventory.db')

# shopping_list apple, kiwi 삭제
conn.execute("DELETE FROM shopping_list WHERE name IN ('apple', 'kiwi')")

# category 컬럼 제거
conn.execute("ALTER TABLE item DROP COLUMN category")
conn.execute("ALTER TABLE shopping_list DROP COLUMN category")

conn.commit()

conn.row_factory = sqlite3.Row
print("=== item ===")
for r in conn.execute("SELECT * FROM item"):
    print(" ", dict(r))

print()
print("=== shopping_list ===")
rows = conn.execute("SELECT * FROM shopping_list").fetchall()
print("  (비어 있음)" if not rows else "")
for r in rows:
    print(" ", dict(r))

conn.close()
print()
print("완료.")
