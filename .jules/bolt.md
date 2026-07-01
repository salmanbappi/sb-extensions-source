## 2024-05-18 - Optimize PBKDF2 implementation in ReAnime

**Learning:** When implementing crypto functions like PBKDF2 manually using `Mac` in loops, instantiating `Mac` on every single iteration is incredibly slow. `Mac.doFinal()` resets the object's state but retains the initialized key, which means the instance can be reused across iterations safely. Reusing it makes a huge difference in performance for functions executing 1,000+ iterations.
**Action:** When implementing or seeing manual cryptographic iterations, always hoist `Mac.getInstance(...)` and `mac.init(...)` out of the loop and reuse the instance.
