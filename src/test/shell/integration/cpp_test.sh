#!/usr/bin/env bash
#
# Copyright 2016 The Bazel Authors. All rights reserved.
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

# --- begin runfiles.bash initialization ---
# Copy-pasted from Bazel's Bash runfiles library (tools/bash/runfiles/runfiles.bash).
set -euo pipefail
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
  source "${RUNFILES_DIR}/bazel_tools/tools/bash/runfiles/runfiles.bash"
elif [[ -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  source "$(grep -m1 "^bazel_tools/tools/bash/runfiles/runfiles.bash " \
            "$RUNFILES_MANIFEST_FILE" | cut -d ' ' -f 2-)"
else
  echo >&2 "ERROR: cannot find @bazel_tools//tools/bash/runfiles:runfiles.bash"
  exit 1
fi
# --- end runfiles.bash initialization ---

source "$(rlocation "io_bazel/src/test/shell/integration_test_setup.sh")" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

if ! type try_with_timeout >&/dev/null; then
  # Bazel's testenv.sh defines try_with_timeout but the Google-internal version
  # uses a different testenv.sh.
  function try_with_timeout() { $* ; }
fi

#### TESTS #############################################################

function test_no_rebuild_on_irrelevant_header_change() {
  add_rules_cc MODULE.bazel
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<EOF
load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
load("@rules_cc//cc:cc_library.bzl", "cc_library")
cc_binary(name="a", srcs=["a.cc"], deps=["b"])
cc_library(name="b", srcs=["b1.h", "b2.h"])
EOF

  cat > $pkg/a.cc <<EOF
#include "$pkg/b1.h"

int main(void) {
  return B_RETURN_VALUE;
}
EOF

  cat > $pkg/b1.h <<EOF
#define B_RETURN_VALUE 31
EOF

  cat > $pkg/b2.h <<EOF
=== BANANA ===
EOF

  bazel build //$pkg:a || fail "build failed"
  echo "CHERRY" > $pkg/b2.h
  bazel build //$pkg:a >& $TEST_log || fail "build failed"
  expect_not_log "Compiling $pkg/a.cc"
}

function test_new_header_is_required() {
  add_rules_cc MODULE.bazel
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<EOF
load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
load("@rules_cc//cc:cc_library.bzl", "cc_library")
cc_binary(name="a", srcs=["a.cc"], deps=[":b"])
cc_library(name="b", srcs=["b1.h", "b2.h"])
EOF

  cat > $pkg/a.cc << EOF
#include "$pkg/b1.h"

int main(void) {
    return B1;
}
EOF

  cat > $pkg/b1.h <<EOF
#define B1 3
EOF

  cat > $pkg/b2.h <<EOF
#define B2 4
EOF

  bazel build //$pkg:a || fail "build failed"
  cat > $pkg/a.cc << EOF
#include "$pkg/b1.h"
#include "$pkg/b2.h"

int main(void) {
    return B1 + B2;
}
EOF

  bazel build //$pkg:a || fail "build failed"
}

function test_no_recompile_on_shutdown() {
  add_rules_cc MODULE.bazel
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<EOF
load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
load("@rules_cc//cc:cc_library.bzl", "cc_library")
cc_binary(name="a", srcs=["a.cc"], deps=["b"])
cc_library(name="b", includes=["."], hdrs=["b.h"])
EOF

  cat > $pkg/a.cc <<EOF
#include "b.h"

int main(void) {
  return B_RETURN_VALUE;
}
EOF

  cat > $pkg/b.h <<EOF
#define B_RETURN_VALUE 31
EOF

  bazel build -s //$pkg:a >& $TEST_log || fail "build failed"
  expect_log "Compiling $pkg/a.cc"
  try_with_timeout bazel shutdown || fail "shutdown failed"
  bazel build -s //$pkg:a >& $TEST_log || fail "build failed"
  expect_not_log "Compiling $pkg/a.cc"
}

function wait_for_analysis_hibernation_since() {
  local -r server_log="$1"
  local -r first_line="$2"
  local attempt
  for ((attempt = 0; attempt < 300; attempt++)); do
    if tail -n +"$first_line" "$server_log" \
        | grep -F '[Analysis hibernation] stored ' > /dev/null; then
      return
    fi
    sleep 0.1
  done
  fail "Analysis did not hibernate within 30 seconds"
}

function test_discovered_headers_survive_live_graph_hibernation() {
  add_rules_cc MODULE.bazel
  local -r pkg=$FUNCNAME
  mkdir -p "$pkg"
  cat > "$pkg/BUILD" <<'EOF'
load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
load("@rules_cc//cc:cc_library.bzl", "cc_library")
cc_binary(name = "a", srcs = ["a.cc"], deps = [":headers"])
cc_library(name = "headers", srcs = ["used.h"])
EOF
  cat > "$pkg/payload.bzl" <<'EOF'
Payload = provider(fields = ["data", "files"])
def _impl(target, ctx):
    return [Payload(
        data = [str(i) + "x" * 1024 for i in range(128)],
        files = target[DefaultInfo].files,
    )]
payload = aspect(implementation = _impl)
EOF
  cat > "$pkg/a.cc" <<EOF
#include <stdio.h>
#include "$pkg/used.h"
int main(void) { puts(VALUE); }
EOF
  echo '#define VALUE "before"' > "$pkg/used.h"

  # FileHandler flushes each record, so the wait observes detach completion rather
  # than a buffered message flushed only when the test daemon exits after 15s.
  local -r server_log="$TEST_TMPDIR/hibernation-server.log"
  local -r logging_config="$TEST_TMPDIR/hibernation-logging.properties"
  local java_server_log="$server_log" java_logging_config="$logging_config"
  local executable="bazel-bin/$pkg/a"
  if is_windows; then
    java_server_log=$(cygpath -m "$server_log")
    java_logging_config=$(cygpath -m "$logging_config")
    executable+=".exe"
  fi
  cat > "$logging_config" <<EOF
handlers=java.util.logging.FileHandler
.level=INFO
java.util.logging.FileHandler.pattern=$java_server_log
java.util.logging.FileHandler.formatter=com.google.devtools.build.lib.util.SingleLineFormatter
EOF
  local -a startup=(
    --max_idle_secs=120
    --host_jvm_args=-Xmx1g
    --host_jvm_args="-Djava.util.logging.config.file=$java_logging_config"
  )
  local server_pid first_line
  server_pid=$(bazel "${startup[@]}" info server_pid) || fail "could not get server PID"
  local -a flags=(
    --aspects="//$pkg:payload.bzl%payload"
    --spawn_strategy=local --remote_cache= --disk_cache= --nouse_action_cache -s
  )
  local -a hibernate=(
    --experimental_idle_analysis_cache
    --experimental_idle_analysis_cache_delay=0s
  )
  first_line=$(( $(wc -l < "$server_log") + 1 ))
  bazel "${startup[@]}" build "${flags[@]}" "${hibernate[@]}" "//$pkg:a" >& "$TEST_log" \
    || fail "initial build failed"
  expect_log "Compiling $pkg/a.cc"
  wait_for_analysis_hibernation_since "$server_log" "$first_line"
  assert_equals before "$("$executable" | tr -d '\r')"

  # Restore before a no-op build: no analysis or compile should be repeated.
  bazel "${startup[@]}" build "${flags[@]}" --noexperimental_idle_analysis_cache "//$pkg:a" \
    >& "$TEST_log" || fail "no-op restore build failed"
  expect_log 'Restoring analysis cache'
  expect_not_log 'Analysis snapshot could not be restored'
  expect_log 'INFO: Analyzed target .* (0 packages loaded, 0 targets configured)\.'
  expect_not_log "Compiling $pkg/a.cc"
  assert_equals before "$("$executable" | tr -d '\r')"

  first_line=$(( $(wc -l < "$server_log") + 1 ))
  bazel "${startup[@]}" build "${flags[@]}" "${hibernate[@]}" "//$pkg:a" >& "$TEST_log" \
    || fail "second hibernation build failed"
  expect_log 'INFO: Analyzed target .* (0 packages loaded, 0 targets configured)\.'
  expect_not_log "Compiling $pkg/a.cc"
  wait_for_analysis_hibernation_since "$server_log" "$first_line"

  echo '#define VALUE "after"' > "$pkg/used.h"
  bazel "${startup[@]}" build "${flags[@]}" --noexperimental_idle_analysis_cache "//$pkg:a" \
    >& "$TEST_log" || fail "included-header restore build failed"
  expect_log 'Restoring analysis cache'
  expect_not_log 'Analysis snapshot could not be restored'
  expect_log 'INFO: Analyzed target .* (0 packages loaded, 0 targets configured)\.'
  expect_log "Compiling $pkg/a.cc"
  assert_equals after "$("$executable" | tr -d '\r')"
  assert_equals "$server_pid" "$(bazel "${startup[@]}" info server_pid)"
}

run_suite "Tests for Bazel's C++ rules"
