import re
s = open('res/values-en/strings.xml', encoding='utf-8').read()
for m in re.finditer(chr(92) + r'(.)', s):
    c = m.group(1)
    if c not in 'nt"' + chr(92):
        print('非标准转义:', repr(c), '上下文:', s[max(0, m.start()-25):m.end()+15].replace('\n', ' '))
print('检查完成')
