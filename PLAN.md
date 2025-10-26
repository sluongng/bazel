# Remote Analysis Cache gRPC Integration – Remaining Tasks

## Outstanding Issues
1. **Shell test flakiness**
   - `remote_analysis_cache_grpc_test.sh` currently waits for the mock server by polling with `nc`. The mock server never prints a readiness marker, so the test times out intermittently.
   - The server process also runs indefinitely; the test relies on killing it manually and does not guarantee a clean shutdown.

2. **Mock server behaviour**
   - `MockRemoteAnalysisCacheServer` needs to log a “ready” message as soon as it starts listening.
   - It should handle `SIGTERM`/`SIGINT` and exit cleanly, flushing stats before termination.

3. **Java proto/grpc wiring**
   - The gRPC client is hooked up, but we still need unit coverage that exercises the new proto pathway (e.g. fake server + client integration test in Java).
   - Ensure RemoteAnalysisCachingOptions wiring chooses TLS or plaintext appropriately. Right now we hardcode plaintext.

## Next Steps
1. **Introduce readiness signalling**
   - Update `MockRemoteAnalysisCacheServer` to print `READY <port>` once the gRPC server has started.
   - Adjust the shell test to wait for that line (use `timeout` + `grep` or tail).

2. **Graceful shutdown**
   - Register a shutdown hook (currently exists) but also handle external signals so the server terminates promptly and writes stats.

3. **Refine test synchronization**
   - Replace the manual sleep/poll loop in the shell test with a `wait_until_ready` helper that watches a log file for the readiness token.
   - Ensure the test waits for the Bazel command to finish before killing the server; capture exit codes accordingly.

4. **Additional validation**
   - Add a Java-level integration test that spins up the mock server in-process (using `GrpcRemoteAnalysisCacheClient`) and verifies lookup/add metadata behaviour.

5. **Follow-up cleanup**
   - Scan for unused imports/resources introduced earlier.
   - Run `google-java-format` over any files touched during the follow-up work.

## Notes
- All proto/build wiring is in place (`remote_analysis_cache.proto`, Java/grpc targets, client wiring inside `SerializationModule`).
- `remote_analysis_cache_grpc_test.sh` is runnable but currently flaky because of the sync issues described above.
- Keep in mind that the mock server binary lives at `//src/test/java/...:MockRemoteAnalysisCacheServer`; the shell test expects that target to stay deployable.
