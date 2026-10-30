// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe.serialization.analysis;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.testing.GcFinalization;
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapBuilder;
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapImpl;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkInfoWithMessage;
import com.google.devtools.build.lib.packages.StructProvider;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.eval.Tuple;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
@SuppressWarnings("deprecation")
public final class ProviderSnapshotTest {
  @Rule public final TemporaryFolder temporary = new TemporaryFolder();

  private Path directory() throws IOException {
    return temporary.newFolder().toPath();
  }

  private static TransitiveInfoProviderMapImpl map(Object value) {
    return (TransitiveInfoProviderMapImpl)
        new TransitiveInfoProviderMapBuilder().put("value", value).build();
  }

  @Test
  public void roundTrip_preservesSharedValuesAndOpaqueIdentity() throws Exception {
    Object nested =
        Depset.of(
            String.class, NestedSetBuilder.<String>stableOrder().add("input").add("other").build());
    Object nativeValue = new StarlarkValue() {};
    var info =
        StarlarkInfo.create(
            StructProvider.STRUCT,
            ImmutableMap.of(
                "text",
                "payload",
                "nested",
                nested,
                "native",
                nativeValue,
                "tuple",
                Tuple.of("left", StarlarkList.immutableCopyOf(List.of("right")))));
    var first = map(info);
    var second = map(info);
    int originalHash = first.hashCode();
    try (var snapshot =
        ProviderSnapshot.create(
            directory(), List.of(first, second), ProviderSnapshot.Limits.defaults())) {
      assertThat(first.isHibernated()).isTrue();
      assertThat(second.isHibernated()).isTrue();
      snapshot.restore();
      var restored = (StarlarkInfo) first.get("value");
      assertThat(restored).isNotSameInstanceAs(info);
      assertThat(second.get("value")).isSameInstanceAs(restored);
      assertThat(restored.getProvider()).isSameInstanceAs(StructProvider.STRUCT);
      assertThat(restored.getValue("nested")).isSameInstanceAs(nested);
      assertThat(restored.getValue("native")).isSameInstanceAs(nativeValue);
      assertThat(restored.getValue("tuple")).isEqualTo(info.getValue("tuple"));
      assertThat(restored.getValue("tuple")).isNotSameInstanceAs(info.getValue("tuple"));
      assertThat(first.hashCode()).isEqualTo(originalHash);
    }
    assertThat(first.isHibernated()).isFalse();
  }

  @Test
  public void compactedListWrappersShareBackingStorageWithinByteBudget() throws Exception {
    StarlarkList<String> source;
    try (var mutability = Mutability.create("shared list backing")) {
      source = StarlarkList.copyOf(mutability, Collections.nCopies(1000, "payload"));
    }
    var fields = ImmutableMap.<String, Object>builder();
    StarlarkList<String> previous = null;
    for (int i = 0; i < 100; i++) {
      var wrapper = source.unsafeOptimizeMemoryLayout();
      assertThat((Object) wrapper).isNotSameInstanceAs(previous);
      fields.put("f" + i, wrapper);
      previous = wrapper;
    }
    var value = map(StarlarkInfo.create(StructProvider.STRUCT, fields.buildOrThrow()));
    // Inline encoding duplicates the 1000 references for each wrapper and exceeds this cap.
    try (var snapshot =
        ProviderSnapshot.create(
            directory(), List.of(value), new ProviderSnapshot.Limits(32 * 1024, 10_000, 60))) {
      snapshot.restore();
      var restored = (StarlarkInfo) value.get("value");
      for (int i = 0; i < 100; i++) {
        var list = (StarlarkList<?>) restored.getValue("f" + i);
        assertThat(list.size()).isEqualTo(1000);
        assertThat(list.get(999)).isEqualTo("payload");
      }
    }
  }

  @Test
  public void opaqueHeavyPayloadIsNotDetachedForNegativeEstimatedSavings() throws Exception {
    var numbers = new ArrayList<StarlarkInt>();
    for (int i = 0; i < 1000; i++) {
      numbers.add(StarlarkInt.of(10000 + i));
    }
    var info =
        StarlarkInfo.create(
            StructProvider.STRUCT,
            ImmutableMap.of("numbers", StarlarkList.immutableCopyOf(numbers)));
    var value = map(info);
    Path directory = directory();
    assertThat(
            ProviderSnapshot.createForHibernation(
                directory, List.of(value), ProviderSnapshot.Limits.defaults()))
        .isNull();
    assertThat(value.get("value")).isSameInstanceAs(info);
    try (var files = Files.list(directory)) {
      assertThat(files.count()).isEqualTo(0);
    }
  }

  @Test
  public void opaquePrimitiveArrayIsNotCountedAsReclaimablePayload() throws Exception {
    var value =
        (TransitiveInfoProviderMapImpl)
            new TransitiveInfoProviderMapBuilder()
                .put(
                    "value",
                    StarlarkInfo.create(StructProvider.STRUCT, ImmutableMap.of("text", "tiny")))
                .put("legacy_array", new int[10000])
                .build();
    assertThat(
            ProviderSnapshot.createForHibernation(
                directory(), List.of(value), ProviderSnapshot.Limits.defaults()))
        .isNull();
    assertThat(value.isHibernated()).isFalse();
  }

  @Test
  public void temporarySingletonEnvelopesDoNotCountAsReclaimablePayload() throws Exception {
    var info = StarlarkInfo.create(StructProvider.STRUCT, ImmutableMap.of("text", "small"));
    var maps = new ArrayList<TransitiveInfoProviderMapImpl>();
    for (int i = 0; i < 1000; i++) {
      maps.add(map(info));
    }
    assertThat(
            ProviderSnapshot.createForHibernation(
                directory(), maps, ProviderSnapshot.Limits.defaults()))
        .isNull();
    assertThat(maps.get(0).get("value")).isSameInstanceAs(info);
    assertThat(maps.get(999).isHibernated()).isFalse();
  }

  @Test
  public void substantialCopiedPayloadPassesEstimatedSavingsScreen() throws Exception {
    var value =
        map(
            StarlarkInfo.create(
                StructProvider.STRUCT, ImmutableMap.of("text", "payload".repeat(4096))));
    try (var snapshot =
        Objects.requireNonNull(
            ProviderSnapshot.createForHibernation(
                directory(), List.of(value), ProviderSnapshot.Limits.defaults()))) {
      assertThat(value.isHibernated()).isTrue();
      snapshot.restore();
      assertThat(((StarlarkInfo) value.get("value")).getValue("text"))
          .isEqualTo("payload".repeat(4096));
    }
  }

  @Test
  public void opaqueSubclassBypassesRegisteredSuperclassCodec() throws Exception {
    var ordinary = StarlarkInfo.create(StructProvider.STRUCT, ImmutableMap.of("text", "ordinary"));
    var special =
        StarlarkInfoWithMessage.createWithCustomMessage(
            StructProvider.STRUCT, ImmutableMap.of("text", "special"), "custom missing %s");
    var first = map(ordinary);
    var second = map(special);
    try (var snapshot =
        ProviderSnapshot.create(
            directory(), List.of(first, second), ProviderSnapshot.Limits.defaults())) {
      snapshot.restore();
      assertThat(first.get("value")).isNotSameInstanceAs(ordinary);
      assertThat(second.get("value")).isSameInstanceAs(special);
      assertThat(((StarlarkInfo) second.get("value")).getErrorMessageForUnknownField("x"))
          .contains("custom missing x");
    }
  }

  @Test
  public void lazyListIsNeverForced() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    var lazy =
        StarlarkList.lazyImmutable(
            () -> {
              calls.incrementAndGet();
              throw new AssertionError("snapshot must not force lazy values");
            });
    var map = map(StarlarkInfo.create(StructProvider.STRUCT, ImmutableMap.of("lazy", lazy)));
    try (var snapshot =
        ProviderSnapshot.create(directory(), List.of(map), ProviderSnapshot.Limits.defaults())) {
      assertThat(calls.get()).isEqualTo(0);
      snapshot.restore();
      assertThat(((StarlarkInfo) map.get("value")).getValue("lazy")).isSameInstanceAs(lazy);
      assertThat(calls.get()).isEqualTo(0);
    }
  }

  private record CollectibleSnapshot(
      ProviderSnapshot snapshot,
      TransitiveInfoProviderMapImpl map,
      WeakReference<StarlarkInfo> original) {}

  private static CollectibleSnapshot createCollectible(Path directory) throws Exception {
    var original =
        StarlarkInfo.create(
            StructProvider.STRUCT,
            ImmutableMap.of(
                "payload", StarlarkList.immutableCopyOf(List.of("x".repeat(4096), "y"))));
    var map = map(original);
    return new CollectibleSnapshot(
        ProviderSnapshot.create(directory, List.of(map), ProviderSnapshot.Limits.defaults()),
        map,
        new WeakReference<>(original));
  }

  @Test
  public void committedSnapshotDoesNotPinCopiedProviderPayload() throws Exception {
    var state = createCollectible(directory());
    try (var snapshot = state.snapshot()) {
      GcFinalization.awaitClear(state.original());
      snapshot.restore();
      var restored = (StarlarkInfo) state.map().get("value");
      assertThat(((StarlarkList<?>) restored.getValue("payload")).get(0))
          .isEqualTo("x".repeat(4096));
    }
  }

  @Test
  public void byteLimitFailureLeavesEveryMapResidentAndNoFiles() throws Exception {
    var first = map("first");
    var second = map("x".repeat(4096));
    Path directory = directory();
    assertThrows(
        Exception.class,
        () ->
            ProviderSnapshot.create(
                directory, List.of(first, second), new ProviderSnapshot.Limits(64, 100, 20)));
    assertThat(first.get("value")).isEqualTo("first");
    assertThat(second.get("value")).isEqualTo("x".repeat(4096));
    try (var files = Files.list(directory)) {
      assertThat(files.count()).isEqualTo(0);
    }
  }

  @Test
  public void objectCountBudgetRejectsActualPayloadWithoutDetaching() throws Exception {
    var map =
        map(
            StarlarkInfo.create(
                StructProvider.STRUCT,
                ImmutableMap.of(
                    "items", StarlarkList.immutableCopyOf(List.of("one", "two", "three")))));
    assertThrows(
        ProviderSnapshot.LimitExceededException.class,
        () ->
            ProviderSnapshot.create(
                directory(), List.of(map), new ProviderSnapshot.Limits(1024 * 1024, 4, 20)));
    assertThat(map.isHibernated()).isFalse();
  }

  @Test
  public void depthBudgetRejectsActualPayloadWithoutDetaching() throws Exception {
    var map =
        map(
            StarlarkInfo.create(
                StructProvider.STRUCT, ImmutableMap.of("nested", Tuple.of(Tuple.of("value")))));
    assertThrows(
        ProviderSnapshot.LimitExceededException.class,
        () ->
            ProviderSnapshot.create(
                directory(), List.of(map), new ProviderSnapshot.Limits(1024 * 1024, 100, 2)));
    assertThat(map.isHibernated()).isFalse();
  }

  @Test
  public void interruptionLeavesEveryMapResident() throws Exception {
    var map = map("value");
    Path directory = directory();
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          InterruptedIOException.class,
          () ->
              ProviderSnapshot.create(directory, List.of(map), ProviderSnapshot.Limits.defaults()));
    } finally {
      Thread.interrupted();
    }
    assertThat(map.get("value")).isEqualTo("value");
  }

  @Test
  public void interruptionDuringStreamingLeavesAllMapsResidentAndRemovesFiles() throws Exception {
    var first = map("first");
    var second = map("x".repeat(32 * 1024));
    Path directory = directory();
    AtomicBoolean streamed = new AtomicBoolean();
    try {
      assertThrows(
          Exception.class,
          () ->
              ProviderSnapshot.create(
                  directory,
                  List.of(first, second),
                  ProviderSnapshot.Limits.defaults(),
                  output ->
                      new FilterOutputStream(output) {
                        @Override
                        public void write(byte[] bytes, int offset, int length) throws IOException {
                          out.write(bytes, offset, length);
                          streamed.set(true);
                          Thread.currentThread().interrupt();
                          throw new InterruptedIOException("interrupted during streaming");
                        }

                        @Override
                        public void write(int value) throws IOException {
                          write(new byte[] {(byte) value}, 0, 1);
                        }
                      }));
    } finally {
      Thread.interrupted();
    }
    assertThat(streamed.get()).isTrue();
    assertThat(first.get("value")).isEqualTo("first");
    assertThat(second.get("value")).isEqualTo("x".repeat(32 * 1024));
    try (var files = Files.list(directory)) {
      assertThat(files.count()).isEqualTo(0);
    }
  }

  @Test
  public void interruptedRestoreKeepsGenerationForRetry() throws Exception {
    var map = map("payload");
    try (var snapshot =
        ProviderSnapshot.create(directory(), List.of(map), ProviderSnapshot.Limits.defaults())) {
      Thread.currentThread().interrupt();
      try {
        assertThrows(InterruptedIOException.class, snapshot::restore);
      } finally {
        Thread.interrupted();
      }
      assertThat(map.isHibernated()).isTrue();
      snapshot.restore();
      assertThat(map.get("value")).isEqualTo("payload");
    }
  }

  @Test
  public void corruptionNeverPartiallyRestoresAndCloseAbandons() throws Exception {
    var first = map("first");
    var second = map("second");
    Path directory = directory();
    try (var snapshot =
        ProviderSnapshot.create(
            directory, List.of(first, second), ProviderSnapshot.Limits.defaults())) {
      Path file;
      try (var files = Files.list(directory)) {
        file = files.findFirst().orElseThrow();
      }
      Files.write(file, new byte[] {1}, StandardOpenOption.APPEND);
      assertThrows(IOException.class, snapshot::restore);
      assertThat(first.isHibernated()).isTrue();
      assertThat(second.isHibernated()).isTrue();
    }
    assertThat(first.toString()).contains("abandoned");
    assertThat(assertThrows(IllegalStateException.class, () -> first.get("value")))
        .hasMessageThat()
        .contains("abandoned");
  }

  @Test
  public void checksumRejectsSameSizeCorruptionBeforeRestoring() throws Exception {
    var map = map("payload");
    Path directory = directory();
    try (var snapshot =
        ProviderSnapshot.create(directory, List.of(map), ProviderSnapshot.Limits.defaults())) {
      Path file;
      try (var files = Files.list(directory)) {
        file = files.findFirst().orElseThrow();
      }
      byte[] bytes = Files.readAllBytes(file);
      bytes[bytes.length / 2] ^= 1;
      Files.write(file, bytes);
      assertThat(assertThrows(IOException.class, snapshot::restore))
          .hasMessageThat()
          .contains("checksum");
      assertThat(map.isHibernated()).isTrue();
    }
  }

  @Test
  public void oversizedStringIsOpaqueEvenWhenOtherStringsAreCopied() throws Exception {
    String large = "x".repeat(1024 * 1024 + 1);
    var info =
        StarlarkInfo.create(
            StructProvider.STRUCT, ImmutableMap.of("large", large, "small", "small"));
    var map = map(info);
    try (var snapshot =
        ProviderSnapshot.create(directory(), List.of(map), ProviderSnapshot.Limits.defaults())) {
      snapshot.restore();
      StarlarkInfo restored = (StarlarkInfo) map.get("value");
      assertThat(restored).isNotSameInstanceAs(info);
      assertThat(restored.getValue("large")).isSameInstanceAs(large);
    }
  }

  @Test
  public void cyclicBackingArrayIsRejectedBeforeDetach() throws Exception {
    Object[] cycle = new Object[1];
    cycle[0] = cycle;
    var map = map(cycle);
    assertThrows(
        IOException.class,
        () ->
            ProviderSnapshot.create(directory(), List.of(map), ProviderSnapshot.Limits.defaults()));
    assertThat(map.get("value")).isSameInstanceAs(cycle);
  }
}
