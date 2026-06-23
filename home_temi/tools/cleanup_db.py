import sqlite3, sys
sys.stdout.reconfigure(encoding='utf-8')

conn = sqlite3.connect('api_server/inventory.db')
conn.row_factory = sqlite3.Row

delete_ids = [4, 5, 6, 7, 8, 11, 12]

# item_placement 먼저 삭제
conn.execute(f"DELETE FROM item_placement WHERE item_id IN ({','.join('?'*len(delete_ids))})", delete_ids)

# item 삭제
conn.execute(f"DELETE FROM item WHERE id IN ({','.join('?'*len(delete_ids))})", delete_ids)

# 아무 item도 참조하지 않는 storage_location 정리
conn.execute("""
    DELETE FROM storage_location
    WHERE id NOT IN (SELECT storage_location_id FROM item_placement)
""")

conn.commit()

print("=== 정리 후 item_placement (JOIN) ===")
rows = conn.execute("""
    SELECT i.id, i.name, i.category, l.name as location,
           l.drawer_number, l.led_channel, p.quantity
    FROM item_placement p
    JOIN item i ON i.id = p.item_id
    JOIN storage_location l ON l.id = p.storage_location_id
    ORDER BY i.id
""").fetchall()
for r in rows:
    d = dict(r)
    print(f"  [{d['id']}] {d['name']} | {d['category'] or '-'} | {d['location']} | 서랍{d['drawer_number']} | LED{d['led_channel']} | {d['quantity']}개")

print()
print("=== shopping_list ===")
for r in conn.execute("SELECT id, name, quantity, status FROM shopping_list"):
    print(f"  {dict(r)}")

conn.close()
print()
print("완료.")
