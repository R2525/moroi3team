import sqlite3, sys
sys.stdout.reconfigure(encoding='utf-8')
conn = sqlite3.connect('api_server/inventory.db')
conn.row_factory = sqlite3.Row

needed_ids = [r['id'] for r in conn.execute("SELECT id FROM item WHERE status = 'needed'")]
if needed_ids:
    conn.execute(f"DELETE FROM item_placement WHERE item_id IN ({','.join('?'*len(needed_ids))})", needed_ids)
    conn.execute(f"DELETE FROM item WHERE id IN ({','.join('?'*len(needed_ids))})", needed_ids)

conn.commit()

print("=== 최종 item ===")
for r in conn.execute("SELECT id, name, status FROM item"):
    print(" ", dict(r))

print()
print("=== 최종 shopping_list ===")
rows = conn.execute("SELECT * FROM shopping_list").fetchall()
print("  (비어 있음)" if not rows else "")
for r in rows:
    print(" ", dict(r))

conn.close()
