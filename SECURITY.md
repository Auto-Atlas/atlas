# Security Policy

Atlas is a self-hosted assistant that can read your email and calendar, speak
in your home, approve payments, and delegate work to agents. Treat its host
machine like a password manager: if the box is compromised, so is everything
Atlas can touch.

## Reporting a vulnerability

Please **do not open a public issue for security problems.**

Use GitHub's private vulnerability reporting on this repository
("Security" tab → "Report a vulnerability"). You will get an acknowledgment
within 7 days. Please include reproduction steps and what an attacker gains.

We currently support only the latest release with security fixes.

## Scope notes for researchers

- The server binds voice and approval endpoints to loopback by default and is
  designed to be exposed only over a private network (e.g. Tailscale).
  Reports that assume the operator deliberately exposed an endpoint to the
  open internet without the token layer are out of scope; bypasses of the
  token/device-credential layer are very much in scope.
- Anything that lets a non-owner speaker cross the speaker-ID trust tiers
  (guest → family → owner) is in scope.
- Anything that gets a payment/SMS/email action past the approval gate
  without an explicit approval is in scope.
- Prompt-injection reports are in scope when they cross a privilege boundary
  (e.g. content from an email or web page causing a gated tool action) —
  "the model said something wrong" alone is not.
