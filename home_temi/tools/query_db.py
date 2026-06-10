import sqlite3, sys
sys.stdout.reconfigure(encoding='utf-8')
conn = sqlite3.connect('api_server/inventory.db')
conn.row_factory = sqlite3.Row
rows = conn.execute("""
    SELECT i.id, i.name, i.category, i.status,
           l.name as location, l.drawer_number, l.led_channel, l.description,
           p.quantity
    FROM item_placement p
    JOIN item i ON i.id = p.item_id
    JOIN storage_location l ON l.id = p.storage_location_id
    ORDER BY i.id
""").fetchall()

print("ID | 물건명 | 카테고리 | 상태 | 위치 | 서랍 | LED | 수량 | 설명")
print("---|--------|----------|------|------|------|-----|------|----")
for r in rows:
    d = dict(r)
    cat = d['category'] if d['category'] else '-'
    print(f"{d['id']} | {d['name']} | {cat} | {d['status']} | {d['location']} | {d['drawer_number']} | {d['led_channel']} | {d['quantity']} | {d['description']}")

print()
orphan_items = conn.execute("""
    SELECT id, name, category, status FROM item
    WHERE id NOT IN (SELECT item_id FROM item_placement)
""").fetchall()
if orphan_items:
    print("=== 위치 미등록 물건 ===")
    for r in orphan_items:
        d = dict(r)
        print(f"  {d['id']} | {d['name']} | {d['category'] or '-'} | {d['status']}")

sensors = conn.execute("SELECT * FROM sensor_check").fetchall()
if sensors:
    print()
    print("=== 센서 기록 ===")
    for r in sensors:
        print(dict(r))
else:
    print()
    print("=== 센서 기록: 없음 ===")

conn.close()
