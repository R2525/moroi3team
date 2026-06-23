import re, sys
sys.stdout.reconfigure(encoding='utf-8')

path = r'app\src\main\java\org\techtown\hello\MainActivity.java'
with open(path, encoding='utf-8') as f:
    content = f.read()

def escape_korean(m):
    s = m.group(0)
    result = ''
    for ch in s:
        if ord(ch) > 127:
            result += f'\\u{ord(ch):04X}'
        else:
            result += ch
    return result

# K 클래스 내 SHOPPING 상수 문자열만 변환
new_content = re.sub(
    r'(static final String SHOPPING_[A-Z_]+ = ")([^"]+)(")',
    lambda m: m.group(1) + ''.join(
        f'\\u{ord(c):04X}' if ord(c) > 127 else c for c in m.group(2)
    ) + m.group(3),
    content
)

with open(path, 'w', encoding='utf-8') as f:
    f.write(new_content)

print("완료")
