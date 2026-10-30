# Experimental provider hibernation: implementation notes

This experiment explores lower idle memory while retaining the live graph and
warm incremental state. It releases selected Starlark provider payloads to a
local snapshot while the server is idle, then restores them before the next
command. This document records the implementation, its supported boundary, and
the evidence needed to assess it.

It is disabled by default. It does not persist analysis across server restarts,
persist the whole Skyframe graph, or load individual nodes on demand.

## Try it

Use a source-built Bazel containing this change and an isolated output base:

```text
common --experimental_idle_analysis_cache
common --experimental_idle_analysis_cache_delay=60s
common --experimental_idle_analysis_cache_max_size_mb=256
```

Put these settings in the `common` section to retain the behavior after commands
such as `info`. A command without the enable flag restores an existing snapshot
but does not schedule another one. For a short experiment, use a delay of `0s`.
Batch mode does not run idle tasks. The maximum size is the **uncompressed** size
in MiB; accepted settings are 1 through 1024.

Snapshots live under `OUTPUT_BASE/server/provider-hibernation/session-*` and
are private to the current daemon. Never copy them between servers. The next
snapshot initialization removes abandoned files from this feature's session
directories. A restored or abandoned generation is deleted. Nothing is imported
on server startup. Info logs contain `[Analysis hibernation]` with stored and
restored map counts, compressed bytes, and elapsed milliseconds. Restore runs
before the command profiler starts; these logs, not profiler spans, measure it.
Restore progress and skip warnings go directly to stderr before options are
parsed, so options such as `--noshow_progress` and `--ui_event_filters` cannot
suppress them. This also applies to scripted `info` calls with stderr redirected.

## Relationship to Buck2 infinite graph

The source research used Buck2 revision
[`35595371a08b`](https://github.com/meta/buck2/commit/35595371a08ba8878dec896551d2f133fdfb1721).
Its DICE value lifecycle supports resident, disk-backed, and paged-out values
without discarding graph metadata; a mixed-residency graph remains usable and
values hydrate on demand. See the [value lifecycle](https://github.com/meta/buck2/blob/35595371a08ba8878dec896551d2f133fdfb1721/dice/dice/src/core/graph/lifecycle.rs).
Idle paging is cancellable when graph-contending commands arrive, rather than
requiring the entire graph to finish paging first. See the [daemon coordinator](https://github.com/meta/buck2/blob/35595371a08ba8878dec896551d2f133fdfb1721/app/buck2_server/src/paging.rs).
When enabled, the researched defaults separate writing from eviction through
`OncePerValue` and `OnMemoryPressure`; they do not imply every idle period evicts
all analysis. See [hydration settings](https://github.com/meta/buck2/blob/35595371a08ba8878dec896551d2f133fdfb1721/app/buck2_common/src/settings/hydration.rs).

This Bazel prototype borrows the goal, not that full architecture. It preserves
existing identities by paging only supported provider payloads, commits one whole
generation, and bulk-restores before commands. It does not add mixed-residency
Skyframe nodes, demand hydration, memory-pressure eviction, or restart reuse.
Those require broader evaluator and identity-lifecycle work. The smaller scope
keeps ordinary invalidation and post-execution action state intact.

## Why page provider payloads, not SkyValues?

Configured targets and actions have identity-sensitive references outside their
SkyValues. Execution can retain nested-set arrays, artifacts, discovered inputs,
and other mutable action state. Serializing and replacing a whole analysis value
would require reconstructing all those relationships and preserving execution
state, not just adding a codec to a graph node.

This prototype keeps configured targets, aspects, actions, graph keys, graph
edges, versions, events, and provider-map identities resident. Only the value
storage behind eligible `TransitiveInfoProviderMapImpl` objects is detached.
Aliases and other holders keep the same map shell and see its values again after
restore. Key lookup layout and existing remote-analysis codec formats are
unchanged. Ordinary immutable maps keep their existing final value storage.
The snapshot codec never serializes the map shell itself. Serializing a detached
shell through the generic reflective fallback is unsupported; ordinary provider
codecs read values through the fail-fast accessor. Provider maps use an
additional volatile storage field and an accessor hook.
The hook also changes the value-read path for other `ImmutableSharedKeyMap` users.
There is no claim of zero or negligible normal-build overhead. The flag controls
paging, not this representation change: the hook and volatile slot exist even
when paging is off. Representative analysis CPU and compact-header memory costs
must be measured before adopting this build broadly.

The copied domain is deliberately small:

| Copied into the snapshot | Kept resident by exact object identity |
| --- | --- |
| Exact supported concrete schema and schemaless Starlark info implementations | Unknown info subclasses and native providers |
| Bounded strings, tuples, and eager frozen Starlark lists | Lazy lists, dicts, provider definitions, modules, closures, mutability objects |
| Reference arrays used by those supported implementations | Depsets, nested sets, artifacts, actions, and all other unknown objects |

The private codec routes array-typed fields through the memoizing context,
including backing arrays shared by distinct compacted list wrappers. Each copied
array is decoded once, rather than once per wrapper. Primitive arrays are bounded
and pinned by identity. The ordinary DynamicCodec wire format is unchanged.

Opaque constants also cost memory: their generation-owned registry keeps a list,
an identity lookup, and tags. Before writing, production hibernation compares
half the conservative copied-size estimate (excluding staging envelopes and
singleton wrappers that are not resident payload) with bookkeeping estimated at
4 KiB,
128 bytes per opaque constant, and 8 bytes per map. It skips obviously unfavorable
generations and applies the five-minute retry delay. The low-level snapshot
transaction API remains usable without this optimization policy for codec tests.
This is an uncalibrated screening heuristic, not a net-memory guarantee: opaque
resident holders may retain original copied objects, and restored copies bypass
some interners. Actual retained heap must still be measured. In particular,
opaque-heavy providers need not benefit. No graph or action object is reconstructed.

## Ownership and transaction

`IdleTaskManager` excludes command execution while the idle task runs and
interrupts/joins it before admitting a new command. The coordinator does not
hold its state lock across disk I/O or garbage collection.

1. Select identity-deduplicated provider maps on completed local rule and aspect
   values. Skip cleared/error values and maps without supported Starlark info.
2. Preflight the exact fields the selected codecs will serialize. Do not call
   provider getters or force lazy list suppliers. Bound traversal depth, object
   count, backing-array lengths, individual strings, and estimated heap use.
3. Build a generation-owned codec registry. Register unsupported objects as
   reference constants **before** superclass codec dispatch can copy them. Disable
   default reflective fallback; explicitly register supported concrete codecs.
4. Stream all maps with one memoization scope through gzip and SHA-256 into a
   temporary file. Enforce a byte limit and check disk/heap headroom. Atomically
   publish the file, then detach all maps in a short noninterruptible transaction.
   A write failure leaves all maps resident.
5. Release transient serializer and original-payload references, then explicitly
   request `System.gc()` (without interner shrinking), retaining/checking the
   interrupt status afterwards. Other holders may still keep copied objects alive.
   GC does not guarantee an RSS reduction.
6. Before option parsing or command-environment construction, verify the file's
   size and checksum, decode using fresh memoization state, verify a second
   checksum and every map payload, then reattach all maps. Consume the snapshot.

Interruption before detach leaves values resident. Interruption before reattach
leaves the complete snapshot available for the next command to retry. Because
restoration precedes command/module setup, interruption there may produce no BEP
file or BES invocation. There is no partially restored graph. Missing/corrupt
data, validation failure, or insufficient estimated restore headroom causes a
full evaluator reset before command handling. The next command receives a stderr
warning with the restore failure and recomputation decision; the server log
retains the full exception. Stale detached shells then report that they were
abandoned; they do not silently become empty maps.

`clean` and `shutdown` drop the graph and snapshot without decoding. This happens
before their options are parsed, so even an invalid `clean` command or a
conditional shutdown that elects to remain alive can cold-reset analysis. Other
known commands conservatively restore first, including help/info/version and
commands with invalid options. Unknown command names do not read the graph and
can leave it hibernated.

Shutdown marks the controller closed and interrupts an active operation. That
operation owns deferred cleanup; shutdown does not race file deletion against a
writer/decoder or wait indefinitely on I/O. Nonfatal idle failures become
`IdleTaskException`, so a failed optimization cannot poison the next command's
idle-to-busy transition. Fatal VM failures follow Bazel's crash handler.

Heap estimates and byte limits reduce allocation risk; they do not prove that an
OOM is impossible. The restore estimate includes unreachable objects not yet
collected, so it can conservatively reset a graph that would fit after GC. This
prototype does not add another pre-command GC to try to recover that space.

The generation-wide limits are 250,000 visited objects, arrays of at most 250,000
elements, depth 60, and the configured byte cap. These can reject realistic large
graphs even if only some maps are large. A budget rejection leaves all maps
resident, emits one warning on the next known command, and disables further
attempts until the evaluator is reset (for example by `clean`) or the daemon is
restarted. This avoids repeatedly scanning the same oversized graph. Increasing
the size flag alone does not clear that exclusion. Other failures, including
estimated heap/disk pressure and unsupported cycles, instead delay new attempts
for at least five minutes and report one warning on the next known command.
A retry still requires a later idle task; no timer retries storage during an
otherwise uninterrupted idle period. Evaluator reset clears the delay. Partial-map
paging and adaptive budgets remain future work.

Traversal and stream I/O poll interruption, but a bounded codec operation and an uninterruptible GC can still delay an arriving command. Repeated
no-op commands may write new snapshots. The idle delay limits churn; there is no
snapshot-reuse or no-rewrite guarantee in this version.

## Background-reader exclusion

Quiescence includes readers, not just Skyframe writers. Build Event Protocol
(BEP) transports can render events from providers after command completion.
`BlazeModule.mayReadAnalysisAfterCommand` lets modules report such readers after
all `afterCommand` callbacks and before `commandComplete` clears module state.

For the standard BEP/BES module, hibernation is disabled for the remainder of the
daemon if a transport is not in `WAIT_FOR_UPLOAD_COMPLETE` mode, its original
close future is pending/cancelled/failed, or the streamer has not closed. A failed
future is not a reader-drain barrier. This exclusion applies even when the
feature was disabled for the earlier command, and survives evaluator resets.
Changing the upload mode later does not prove old readers have drained. External
modules with post-command analysis readers must implement the hook; the
experimental mode does not support undisclosed background readers.

Also skip discarded/nonincremental analysis, builds without retained state, and
any daemon which has used remote analysis caching. These exclusions are
conservative, not claims that the excluded combinations cannot be supported by
future work.

## Validation and remaining scope

The focused suites cover streaming compatibility, array-field sharing and failures; provider-map
singleton/multiple-key semantics and existing codec formats; shared values,
opaque identity, lazy-list non-evaluation, weak-reference collection, limits,
corruption, interruption during serialization, and interrupted restore; unchecked
idle failure and shutdown interruption; repeated unchanged builds, aliases and
their shared aspect provider maps, BUILD and `.bzl` invalidation,
cold-reset fallback, and the sticky background-reader gate. The dispatcher interruption regression uses the real dispatcher, including an
invalid option to check pre-parser ordering. Analysis integration also inspects
the actual afterCommand result for delay, size and disable/no-retained-state
gates. The real source-built binary adds command-lifecycle and query smoke checks.
Executed-action integration coverage also checks retained target, provider-map,
artifact, action, and execution-value identities across hibernation.
A real C++ shell fixture disables persistent action caching and disk/remote
caches, requires zero target/aspect reanalysis and no compilation on wake, then
verifies that an included-header edit recompiles in the same daemon.
These are narrow correctness fixtures, not representative language-workload
performance measurements.

This is a same-process prototype, not a general production memory claim. Before
expanding its copied domain or enabling it by default, validate representative
Java/C++ and mixed repositories, discovered-input actions, aspects/transitions,
large graph metadata, remote execution, custom modules, memory pressure, peak
restore heap, and command arrival during expensive serialization/GC. The synthetic
provider-heavy experiment below only demonstrates this bounded domain. Full
repository validation and production rollout are not part of this change.

## Bounded feasibility measurement (2026-10-29)

Source-built control and candidate binaries ran on the same Linux host with
OpenJDK 27+35, G1, `-Xmx1g`, separate output bases, no user/system/workspace rc,
and no remote execution. The fixture has 20 Starlark rules, each returning a
provider containing 1,000 distinct strings of approximately 1 KiB, plus a
`ctx.actions.write` output. This intentionally favors the copied domain.

Each arm used three repeated idle/resume cycles in one daemon. Both arms waited
12 seconds before each memory sample, allowing the ordinary 10-second idle GC.
The enabled arm also requested post-detach GC. Resume disabled scheduling of the
next snapshot so post-resume memory could be sampled after another 12 seconds.
Arms ran sequentially, not randomized or interleaved; samples are not independent
fresh-daemon trials. Heap figures come from `jcmd GC.heap_info`; RSS/PSS from
`/proc/PID/smaps_rollup`. All samples, including the first slower restore, are
included. No source/profile-based extrapolation is made.

The primary comparison uses the same candidate binary with the flag off/on:

| Metric (median of 3) | Disabled | Enabled | Change |
| --- | ---: | ---: | ---: |
| Idle live heap (MiB) | 50.86 | 28.57 | -43.8% |
| Post-resume live heap (MiB) | 50.98 | 49.25 | -3.4% |
| Idle RSS (MiB; noisy observation) | 506.32 | 429.00 | -15.3% |
| Idle PSS (MiB; noisy observation) | 500.62 | 423.31 | -15.4% |
| No-op command wall time (ms) | 58.42 | 63.42 | +8.6% |

Each enabled cycle wrote 133,688 compressed bytes (401,064 across three cycles).
Store times were 40/17/14 ms, excluding the subsequent GC; restore times were
15/7/5 ms. Post-detach GC took 23/22/24 ms. These durations come from
server logs included in the JSON. Enabled no-op wall times were
113.83/63.42/61.45 ms versus disabled
139.6/58.42/55.05 ms. All six primary no-op
builds configured zero targets and all output hashes matched.

The primary run's startup did not overlap test validation. An earlier pilot's
startup did overlap validation; its samples are retained separately and are not
mixed into these six samples. The JSON also retains the original nine samples
from before the array-sharing correction. The measured binary includes the
shared-array codec and savings screen; subsequent accounting-only corrections for opaque primitive arrays and staging
envelopes do not change eligibility or serialized data for this all-string fixture.

Neither the baseline nor flag-off/on timings establish the normal-build cost of
the added field/dispatch. No negligible-overhead claim is made. RSS and PSS are
especially weak causal evidence: the historical original-source baseline idle
RSS median was 556.14 MiB versus 503.05 MiB with the original candidate flag off,
a 53.09 MiB between-daemon difference without paging. RSS also rises across
repetitions, so these runs do not establish a steady state. Treat RSS/PSS rows as
noisy observations, not attributable paging savings; the live-heap result is
stronger. Peak serialization/restore memory was not sampled. Representative
repository, normal-analysis-overhead, committed-heap, peak-memory, and steady-state
RSS measurements remain necessary before broader adoption.

Recreate the payload fixture with a `defs.bzl` containing:

```python
Payload = provider(fields = ["data"])
def _impl(ctx):
    data = ["%s-%s-%s" % (ctx.label.name, i, "x" * 1024) for i in range(1000)]
    out = ctx.actions.declare_file(ctx.label.name + ".out")
    ctx.actions.write(out, ctx.label.name)
    return [Payload(data = data), DefaultInfo(files = depset([out]))]
payload = rule(implementation = _impl)
```

And `BUILD.bazel`:

```python
load(":defs.bzl", "payload")
[payload(name = "t%s" % i) for i in range(20)]
```

Run `build //...`, wait 12 seconds, sample memory, then run an unchanged
`build //...` and sample again after 12 seconds. In the enabled arm, use the
enable flag and `0s` delay before the idle sample, and the explicit disable flag
on resume. Repeat three times in each isolated output base. The sanitized raw
samples are in [analysis-hibernation-measurements.json](analysis-hibernation-measurements.json).

Source-built lifecycle smoke checks also passed: normalized `aquery
--skyframe_state` action keys/output paths/configurations matched before/after
hibernation; `cquery` read provider names; enabled `build -> info -> idle`
rehydrated and wrote another generation; invalid options restored first; explicit
disable restored without rewriting; and clean/shutdown dropped snapshots without
decoding. These checks used file-write actions, not discovered-input actions.
