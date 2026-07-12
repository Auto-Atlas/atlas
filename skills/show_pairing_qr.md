---
tool: show_pairing_qr
risk: medium
requires_confirmation: false
loads_on: call
catalog: Show the phone-pairing QR.
---

# show_pairing_qr

Display a pairing QR code on the screen so a phone can connect to EVE without anyone typing a
token or reading a code aloud.

Call this when the user asks to **pair**, **connect**, or **set up** their phone, or asks you to
**show the pairing code / QR**.

The QR encodes the connection (the tailnet address and the app token) as a single scan. After you
call it, tell the user in one short spoken sentence: the pairing code is on the screen — open the
EVE app on the phone, tap **Scan to connect**, and point it at the code. There is nothing to read
out — never speak the token or the address.

If the tool reports it isn't configured (no address or token), say plainly that pairing isn't set
up yet and stop — do not invent a code.
