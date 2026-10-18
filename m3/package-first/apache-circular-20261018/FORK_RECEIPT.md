# Fork work area: hsoliwal/commons-collections (upstream Apache-2.0)
Original base: `2d985c27e07bdcbb9516aea44b0dda3f3e487ced`. Canonical Synexia recipe: `d7c852e3fe30438da0f4d3f2e6dd46e7624d6af5`.
This is a *source-only package relocation*, without behavioral edits:
- `BoundedCollection.java` moves to `com.m3.collections.apache`
- `queue/CircularFifoQueue.java` moves to `com.m3.collections.apache.queue`
- queue imports the relocated `BoundedCollection`.
Unmodified original `org.apache.commons.collections4` API remains alongside.
The source-sealed Java21 parity oracle compares both versions on deterministic
ring/offset/iterator/serialization tests. No actual JDK or product library is
replaced by this stage. M3JDK21 already has `M3CircularFifoQueue`; reuse its
existing backend rather than shipping a duplicate production queue.
