# BuildBuddy RBE fork

This fork keeps the configuration needed to build and test Bazel on
BuildBuddy remote execution. The upstream project documentation remains in
`README.md` unchanged.

## Local use

Put the BuildBuddy API-key header in the ignored `user.bazelrc` file, then use
the shared remote configuration:

```text
common:bb-remote --remote_header=x-buildbuddy-api-key=<API_KEY>
```

```shell
bazel test --config=bb-remote //path/to:target
```

Compact execution logs are written to
`bazel-compact_exec_log.binpb.zst`. The `bazel-*` name keeps the generated log
out of Git, Bazel source packaging, and source-inventory tests without a
fork-specific source-watcher patch.

If an existing checkout still contains `compact_exec_log.binpb.zst`, archive
or rename that file before switching to this stack. The old filename is no
longer excluded from Bazel's source inventory.

## Continuous integration

`buildbuddy.yaml` runs the fork test workflow. Keep provider-specific test
quarantines there instead of adding `manual` tags to upstream BUILD targets.
The sandboxed signal case in `//src/test/shell/integration:test_test` is the
only current remote-only exclusion.

`.github/workflows/rbe-sync.yml` rebases the direct stack one upstream commit
at a time and publishes a matching generated merge. It checkpoints the
generated branch first and the direct stack second, both with exact SHA
leases, so an interrupted run can resume from at most one missing checkpoint.
