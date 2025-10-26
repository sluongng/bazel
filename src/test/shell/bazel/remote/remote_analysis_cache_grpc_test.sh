#!/usr/bin/env bash
#
# Copyright 2025 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -euo pipefail

# --- begin runfiles.bash initialization ---
if [[ ! -d "${RUNFILES_DIR:-/dev/null}" && ! -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  if [[ -f "$0.runfiles_manifest" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles_manifest"
  elif [[ -f "$0.runfiles/MANIFEST" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles/MANIFEST"
  elif [[ -f "$0.runfiles/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
    export RUNFILES_DIR="$0.runfiles"
  fi
fi
if [[ -f "${RUNFILES_DIR:-/dev/null}/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
  # shellcheck source=/dev/null
  source "${RUNFILES_DIR}/bazel_tools/tools/bash/runfiles/runfiles.bash"
elif [[ -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  # shellcheck disable=SC2002
  # shellcheck source=/dev/null
  source "$(grep -m1 "^bazel_tools/tools/bash/runfiles/runfiles.bash " \
            "$RUNFILES_MANIFEST_FILE" | cut -d ' ' -f 2-)"
else
  echo >&2 "ERROR: cannot find @bazel_tools//tools/bash/runfiles:runfiles.bash"
  exit 1
fi
# --- end runfiles.bash initialization ---

source "$(rlocation "io_bazel/src/test/shell/integration_test_setup.sh")" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }
source "$(rlocation "io_bazel/src/test/shell/bazel/remote_helpers.sh")" \
  || { echo "remote_helpers.sh not found!" >&2; exit 1; }

function set_up() {
  create_new_workspace remote_analysis_cache_grpc
}

function wait_for_server_ready() {
  local log_file="$1"
  local expected_line="$2"
  for _ in {1..150}; do
    if [[ -f "$log_file" ]] && grep -Fq "$expected_line" "$log_file"; then
      return 0
    fi
    sleep 0.1
  done
  if [[ -f "$log_file" ]]; then
    cat "$log_file" >&2 || true
  fi
  fail "server did not report readiness (${expected_line})"
}

function tear_down() {
  if [[ -n "${server_pid:-}" ]]; then
    kill "${server_pid}" >/dev/null 2>&1 || true
    wait "${server_pid}" >/dev/null 2>&1 || true
  fi
}

function test_remote_analysis_cache_download_mode() {
  local port
  port=$(pick_random_unused_tcp_port) || fail "failed to find unused tcp port"

  local server_exe
  server_exe="$(rlocation \
    "io_bazel/src/test/java/com/google/devtools/build/lib/skyframe/serialization/analysis/MockRemoteAnalysisCacheServer")" \
    || fail "mock server executable not found in runfiles"

  local server_log="${TEST_TMPDIR}/mock_server.log"
  local stats_file="${TEST_TMPDIR}/mock_server_stats.txt"
  : >"${server_log}"
  "${server_exe}" \
    --port="${port}" \
    --stats_file="${stats_file}" \
    >"${server_log}" 2>&1 &
  server_pid=$!
  wait_for_server_ready "${server_log}" "READY ${port}"

  cat >MODULE.bazel <<'EOF'
module(name = "remote_analysis_cache_grpc_test", version = "0.1")
EOF

  cat >WORKSPACE <<'EOF'
# Intentionally empty on purpose.
EOF

  cat >BUILD <<'EOF'
genrule(
    name = "hello",
    outs = ["hello.txt"],
    cmd = "echo RemoteAnalysisCache > $@",
)
EOF

  bazel --bazelrc=/dev/null build \
    --experimental_remote_analysis_cache_mode=download \
    --experimental_remote_analysis_cache="localhost:${port}" \
    --experimental_analysis_cache_enable_metadata_queries \
    //:hello \
    || fail "bazel build with remote analysis cache failed"

  kill "${server_pid}" >/dev/null 2>&1 || true
  wait "${server_pid}" >/dev/null 2>&1 || true
  server_pid=

  [[ -f "${stats_file}" ]] || fail "mock server did not produce stats file"
  cat "${stats_file}" >&2
  grep -q "^lookups=" "${stats_file}" \
    || fail "stats file missing lookup entry"
}

run_suite "remote analysis cache gRPC tests"
