import sys
import re

def fix_file(filepath):
    with open(filepath, 'r') as f:
        content = f.read()

    def repl(m):
        full = m.group(0)
        inner = m.group(1)
        qr_var = 'qrAB' if 'qrAB' in inner else ('qrAC' if 'qrAC' in inner else 'qr')
        name_var = '"B"' if '"B"' in inner else '"C"'
        new_name_arg = 'bobName = ' + name_var

        # Extract the poll part
        poll_match = re.search(r'(KtorMailboxClient\.poll.*?.first\(\))', inner, re.DOTALL)
        if not poll_match: return full
        poll_part = poll_match.group(1)

        return f'aStore.processKeyExchangeInit(\n                    discoveryTokenHex = {qr_var}.discoveryToken().toHex(),\n                    payload = {poll_part},\n                    bobName = {name_var}\n                )'

    content = re.sub(r'aStore\.processKeyExchangeInit\(\s+(KtorMailboxClient\.poll.*?\))\s+!!', repl, content, flags=re.DOTALL)

    with open(filepath, 'w') as f:
        f.write(content)

fix_file('server/src/test/kotlin/net/af0/where/E2eeBidirectionalEndToEndTest.kt')
