## 2024-05-18 - Optimize PBKDF2 implementation in ReAnime

**Learning:** When implementing crypto functions like PBKDF2 manually using `Mac` in loops, instantiating `Mac` on every single iteration is incredibly slow. `Mac.doFinal()` resets the object's state but retains the initialized key, which means the instance can be reused across iterations safely. Reusing it makes a huge difference in performance for functions executing 1,000+ iterations.
**Action:** When implementing or seeing manual cryptographic iterations, always hoist `Mac.getInstance(...)` and `mac.init(...)` out of the loop and reuse the instance.

## 2024-07-09 - String.format in loops is a performance bottleneck for ByteArray to Hex String conversion
**Learning:** `String.format("%02x")` combined with `joinToString` creates massive overhead for converting ByteArrays to Hex Strings, often used for crypto or hashes. Replacing it with a manual character array lookup and bitwise operations (`v ushr 4` and `v and 0x0F`) is over 100x faster, taking milliseconds instead of over a second for 1MB.
**Action:** Always avoid `String.format` in loops. For hex conversion, use a manual char array and bitwise operations.
