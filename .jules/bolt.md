## 2024-05-18 - [Coroutine Concurrency Issue]
**Learning:** In Kotlin coroutines, avoid using `synchronized` blocks inside `async` builders (e.g., when mutating a shared list during Jsoup DOM parsing), as it forces serial execution and completely negates concurrency benefits.
**Action:** Instead, return results directly from `async` blocks and aggregate them securely using `.awaitAll().flatten()`.
