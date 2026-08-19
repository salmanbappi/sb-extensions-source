## 2024-05-14 - [Replace String.format with toHex for ByteArray]
**Learning:** In Kotlin, using `String.format("%02x")` with `joinToString` for byte array to hex string conversions causes significant performance overhead due to massive object allocation. The repository provides `keiyoushi.utils.toHex()`, which implements an optimized manual character array lookup with bitwise operations.
**Action:** Replace `String.format("%02x")` with `toHex()` for ByteArray conversions across the codebase.

## 2024-05-14 - [Avoid premature micro-optimizations]
**Learning:** Tachiyomi/Aniyomi extension modules are isolated. Utilizing shared utilities (like keiyoushi.utils.toHex() for optimized byte array to hex string conversions) requires explicitly declaring the dependency in the specific extension's build.gradle file. Avoid premature micro-optimizations if they require introducing new cross-module dependencies for negligible gains.
**Action:** Always verify dependencies and performance bottlenecks before optimizing string formatting operations that handle very small (e.g. 16-byte) arrays.

## 2024-05-14 - [Coroutines: preserve deterministic ordering]
**Learning:** For concurrent network requests in extensions, using Kotlin coroutines (e.g. async(Dispatchers.IO) / awaitAll()) should return results from async blocks and use .awaitAll().flatten() to preserve deterministic ordering instead of appending to a shared mutable list. Using async without Dispatchers.IO can also block the main thread.
**Action:** When using async/awaitAll, return results from async blocks and use .awaitAll().flatten() to preserve deterministic ordering instead of appending to a shared mutable list. Add Dispatchers.IO to async calls if they are missing.
