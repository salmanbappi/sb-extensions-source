## 2024-05-18 - Optimize PBKDF2 implementation in ReAnime

**Learning:** When implementing crypto functions like PBKDF2 manually using `Mac` in loops, instantiating `Mac` on every single iteration is incredibly slow. `Mac.doFinal()` resets the object's state but retains the initialized key, which means the instance can be reused across iterations safely. Reusing it makes a huge difference in performance for functions executing 1,000+ iterations.
**Action:** When implementing or seeing manual cryptographic iterations, always hoist `Mac.getInstance(...)` and `mac.init(...)` out of the loop and reuse the instance.
## 2026-07-06 - Optimizing ByteArray.toHex()
**Learning:** Using `String.format("%02x")` inside a `.joinToString()` loop for byte arrays is a severe performance anti-pattern in Kotlin because it allocates a new format string, parser, and string object for every single byte, creating massive GC pressure.
**Action:** Always replace `String.format` hex encoding with a pre-allocated `CharArray` and bitwise shifts (`v ushr 4` and `v and 0x0F`), achieving roughly a 200x speedup for hex string conversions.
