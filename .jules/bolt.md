## 2024-05-18 - Avoid synchronized blocks when processing async results
**Learning:** In coroutines (specifically for HTML parsing like Jsoup `Document` traversal), passing a mutable list and synchronizing on it inside an `async { ... }` block forces the execution of the entire block to be serial, killing the benefits of concurrency.
**Action:** Instead of mutating a shared list using `synchronized`, return a list of items from the `async` blocks. Then, collect all results securely using `.awaitAll().flatten()`.
