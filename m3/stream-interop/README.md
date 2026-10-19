# M3CommonsStreams: M3JDK21 operator superset receiver

This standalone opt-in Maven module exposes the **canonical** M3JDK21 object stream superset, not a second stream engine. The original provider is unchanged. Its typed factory returns `M3ObjectStream`, allowing `filter`, `map`, `flatMap`, `mapMulti`, `batch`, `window`, `groupBy`, `collect`, keyed parallelism and other M3 operations.

Install `com.m3:m3-collections:0.1.0-SNAPSHOT` from M3JDK21 draft PR #1313 and the fork's own provider Maven artifact; run `mvn -f m3/stream-interop/pom.xml test`. Snapshots detach at creation; live views track the provider-owned returned collection with normal iterator/concurrent-modification rules. For Spring, liveExistingValues tracks the existing list only, not later map-value replacement. Donor API, mutation semantics and licenses remain unchanged.

Canonical Synexia recipe draft PR #12358, crate `m3-commons-stream-bridge-20261018`. Real Maven/OpenRewrite fixed-point/CI gates remain pending, so this PR stays draft.
