"""The ride PIN, derived exactly as ride-service's OtpService does.

A real driver hears the PIN from the rider. A simulated driver has no rider to ask, so in
development it derives the PIN from the ride id with ride-service's OTP secret (OTP_SECRET,
whose local default is below). Against a deployment it only works if you pass that
deployment's secret, which you only have if you run it.
"""

from __future__ import annotations

import hashlib
import hmac

DEV_SECRET = "dev-only-otp-secret-change-me-in-prod"


def otp_for(ride_id: str, secret: str = DEV_SECRET) -> str:
    h = hmac.new(secret.encode(), str(ride_id).encode(), hashlib.sha256).digest()
    value = ((h[0] & 0x7F) << 24 | h[1] << 16 | h[2] << 8 | h[3]) % 10_000
    return f"{value:04d}"
