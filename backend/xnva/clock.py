import time


def now_ms() -> int:
    return int(time.time() * 1000)


DAY_MS = 86_400_000
