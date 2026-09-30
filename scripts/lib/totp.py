#!/usr/bin/env python3
"""Print "<step> <code>": the RFC 6238 code (SHA-1, 6 digits, 30 s steps) of a base32 secret.

    totp.py SECRET [LAST_STEP]

Studio accepts the previous, current or next step and refuses a step it has already seen, so the step is
the first one after LAST_STEP (the current one when omitted); when that is still ahead of the accepted
window, this waits for the clock instead of handing out a code that would be refused.
"""
import base64, hashlib, hmac, struct, sys, time

secret, last = sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else -1
step = max(int(time.time()) // 30, last + 1)
while step > int(time.time()) // 30 + 1:
    time.sleep(1)
mac = hmac.new(base64.b32decode(secret.upper() + "=" * (-len(secret) % 8)), struct.pack(">Q", step), hashlib.sha1).digest()
offset = mac[-1] & 15
print(step, "%06d" % ((struct.unpack(">I", mac[offset : offset + 4])[0] & 0x7FFFFFFF) % 10**6))
