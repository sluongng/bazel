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

import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapImpl;
import com.google.devtools.build.lib.packages.StarlarkInfoNoSchema;
import com.google.devtools.build.lib.packages.StarlarkInfoWithSchema;
import com.google.devtools.build.lib.skyframe.serialization.ArrayCodec;
import com.google.devtools.build.lib.skyframe.serialization.DynamicCodec;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecs;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.skyframe.serialization.strings.UnsafeStringCodec;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.Tuple;

/** A private, same-process snapshot of provider payloads, not a persistent Skyframe cache. */
@SuppressWarnings("deprecation") // Provider array access is intentionally limited to quiescence.
public final class ProviderSnapshot implements AutoCloseable {
  private static final long MIB = 1024L * 1024;
  private static final int MAX_STRING_LENGTH = 1024 * 1024;
  private static final String FORMAT = "provider-snapshot-v1";

  /** A deterministic size/traversal rejection; retrying the same graph would repeat the cost. */
  public static final class LimitExceededException extends IOException {
    public LimitExceededException(String message) {
      super(message);
    }
  }

  /** Hard bounds apply even to experimental command-line settings. */
  public record Limits(long maxBytes, int maxObjects, int maxDepth) {
    public Limits {
      if (maxBytes <= 0
          || maxBytes > 1024 * MIB
          || maxObjects <= 0
          || maxObjects > 1_000_000
          || maxDepth <= 0
          || maxDepth > 100) {
        throw new IllegalArgumentException("invalid provider snapshot limits");
      }
    }

    public static Limits defaults() {
      return new Limits(256 * MIB, 250_000, 60);
    }
  }

  private final Path file;
  private final List<TransitiveInfoProviderMapImpl> maps;
  private final ObjectCodecs codecs;
  private final byte[] checksum;
  private final long bytes;
  private final long decodedBytes;
  private final long estimatedRestoreBytes;
  private boolean closed;

  private ProviderSnapshot(
      Path file,
      List<TransitiveInfoProviderMapImpl> maps,
      ObjectCodecs codecs,
      byte[] checksum,
      long bytes,
      long decodedBytes,
      long estimatedRestoreBytes) {
    this.file = file;
    this.maps = List.copyOf(maps);
    this.codecs = codecs;
    this.checksum = checksum;
    this.bytes = bytes;
    this.decodedBytes = decodedBytes;
    this.estimatedRestoreBytes = estimatedRestoreBytes;
  }

  /**
   * Writes and commits all payloads before detaching any map. The caller must exclude commands,
   * evaluator writers and concurrent close/restore throughout this operation.
   */
  public static ProviderSnapshot create(
      Path directory, List<TransitiveInfoProviderMapImpl> maps, Limits limits)
      throws IOException, SerializationException {
    return create(directory, maps, limits, UnaryOperator.identity());
  }

  /** Skips without writing/detaching when estimated copied payload cannot cover bookkeeping. */
  @Nullable
  public static ProviderSnapshot createForHibernation(
      Path directory, List<TransitiveInfoProviderMapImpl> maps, Limits limits)
      throws IOException, SerializationException {
    return create(directory, maps, limits, UnaryOperator.identity(), true);
  }

  // Output decoration lets transaction tests interrupt the real serialization stream.
  static ProviderSnapshot create(
      Path directory,
      List<TransitiveInfoProviderMapImpl> maps,
      Limits limits,
      UnaryOperator<OutputStream> outputDecorator)
      throws IOException, SerializationException {
    return Objects.requireNonNull(create(directory, maps, limits, outputDecorator, false));
  }

  @Nullable
  private static ProviderSnapshot create(
      Path directory,
      List<TransitiveInfoProviderMapImpl> maps,
      Limits limits,
      UnaryOperator<OutputStream> outputDecorator,
      boolean requireEstimatedSavings)
      throws IOException, SerializationException {
    checkInterrupted();
    maps = List.copyOf(maps);
    requireHeapHeadroom(16 * MIB + 128L * maps.size());
    if (maps.size() > limits.maxObjects()) {
      throw new LimitExceededException("too many provider maps");
    }
    if (maps.isEmpty()) {
      throw new IOException("no provider payloads to hibernate");
    }
    Object[] payloads = new Object[maps.size()];
    long stagingBytes = 32L + 8L * maps.size() + 2L * FORMAT.length() + 48;
    IdentityHashMap<TransitiveInfoProviderMapImpl, Boolean> seen = new IdentityHashMap<>();
    for (int i = 0; i < maps.size(); i++) {
      checkInterrupted();
      var map = maps.get(i);
      if (map.size() == 0 || map.isHibernated() || seen.put(map, true) != null) {
        throw new IOException("invalid or duplicate provider map");
      }
      payloads[i] = map.getValuesAsArray();
      if (map.size() == 1) {
        // Singleton maps pack the bare value; this array is temporary, not evictable storage.
        stagingBytes += 40;
      }
    }
    Preflight preflight = new Preflight(limits);
    preflight.visit(FORMAT, 0);
    preflight.visit(payloads, 0);
    // This is a screening heuristic, not a promise that other holders release these objects.
    // Use half the conservative copied-size estimate and generous per-constant registry overhead.
    long bookkeeping = 4096 + 128L * preflight.opaque.size() + 8L * maps.size();
    long payloadBytes = Math.max(0, preflight.estimatedCopiedBytes - stagingBytes);
    if (requireEstimatedSavings && payloadBytes / 2 <= bookkeeping) {
      return null;
    }
    long estimate = preflight.estimatedCopiedBytes + 128L * preflight.visited.size();
    requireHeapHeadroom(2 * estimate + 16 * MIB);
    ObjectCodecs codecs = preflight.codecs();
    // Do not retain preflight's original objects through the generation.
    preflight.clear();
    Files.createDirectories(directory);
    if (Files.isSymbolicLink(directory)) {
      throw new IOException("snapshot directory must not be a symbolic link");
    }
    if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }
    if (Files.getFileStore(directory).getUsableSpace() < limits.maxBytes() + 16 * MIB) {
      throw new IOException("insufficient snapshot disk headroom");
    }
    Path temporary = Files.createTempFile(directory, "snapshot-", ".tmp");
    Path published = temporary.resolveSibling(temporary.getFileName() + ".committed");
    MessageDigest digest = digest();
    boolean committed = false;
    try {
      long uncompressed;
      try (OutputStream raw = Files.newOutputStream(temporary);
          DigestOutputStream hashed = new DigestOutputStream(raw, digest);
          GZIPOutputStream compressed = new GZIPOutputStream(hashed);
          BoundedOutput output = new BoundedOutput(compressed, limits.maxBytes())) {
        codecs.serializeMemoized(new Object[] {FORMAT, payloads}, outputDecorator.apply(output));
        uncompressed = output.count;
      }
      checkInterrupted();
      Files.move(temporary, published, StandardCopyOption.ATOMIC_MOVE);
      long written = Files.size(published);
      // Allocate every generation-owned structure before the short detach transaction.
      ProviderSnapshot result =
          new ProviderSnapshot(
              published, maps, codecs, digest.digest(), written, uncompressed, estimate);
      for (var map : maps) {
        if (map.isHibernated()) {
          throw new IOException("provider state changed during snapshot");
        }
      }
      checkInterrupted();
      for (var map : maps) {
        map.hibernateValues();
      }
      committed = true;
      return result;
    } finally {
      if (!committed) {
        Files.deleteIfExists(temporary);
        Files.deleteIfExists(published);
      }
    }
  }

  /** Validates and decodes the whole generation before changing any live provider map. */
  public void restore() throws IOException, SerializationException {
    if (closed) {
      throw new IOException("provider snapshot is closed");
    }
    requireHeapHeadroom(2 * estimatedRestoreBytes + 16 * MIB);
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != bytes) {
      throw new IOException("provider snapshot is missing or has changed size");
    }
    MessageDigest actual = digest();
    try (InputStream input = new DigestInputStream(Files.newInputStream(file), actual)) {
      byte[] buffer = new byte[32 * 1024];
      while (input.read(buffer) != -1) {
        checkInterrupted();
      }
    }
    if (!MessageDigest.isEqual(checksum, actual.digest())) {
      throw new IOException("provider snapshot checksum mismatch");
    }
    Object decoded;
    actual = digest();
    try (InputStream raw = Files.newInputStream(file);
        DigestInputStream hashed = new DigestInputStream(raw, actual);
        GZIPInputStream compressed = new GZIPInputStream(hashed);
        BoundedInput input = new BoundedInput(compressed, decodedBytes)) {
      decoded = codecs.deserializeMemoized(input);
      if (input.count != decodedBytes) {
        throw new IOException("provider snapshot decoded size mismatch");
      }
      // Include all compressed bytes in the second checksum before reattachment.
      while (hashed.read() != -1) {
        checkInterrupted();
      }
    }
    if (!MessageDigest.isEqual(checksum, actual.digest())) {
      throw new IOException("provider snapshot changed during restore");
    }
    if (!(decoded instanceof Object[] envelope)
        || envelope.length != 2
        || !FORMAT.equals(envelope[0])
        || !(envelope[1] instanceof Object[] payloads)
        || payloads.length != maps.size()) {
      throw new IOException("invalid provider snapshot envelope");
    }
    for (int i = 0; i < maps.size(); i++) {
      checkInterrupted();
      if (!(payloads[i] instanceof Object[] values)) {
        throw new IOException("invalid provider snapshot payload");
      }
      maps.get(i).checkHibernatedValues(values);
    }
    checkInterrupted();
    for (int i = 0; i < maps.size(); i++) {
      maps.get(i).restoreValues((Object[]) payloads[i]);
    }
  }

  public int mapCount() {
    return maps.size();
  }

  public long storedBytes() {
    return bytes;
  }

  public long estimatedRestoreBytes() {
    return estimatedRestoreBytes;
  }

  /**
   * Drops backing data only after restore, or after the caller abandons the entire owning graph.
   */
  @Override
  public void close() throws IOException {
    if (!closed) {
      closed = true;
      for (var map : maps) {
        map.abandonHibernatedValues();
      }
      Files.deleteIfExists(file);
    }
  }

  private static long availableHeap() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
  }

  private static void requireHeapHeadroom(long required) throws IOException {
    if (required > availableHeap()) {
      throw new IOException("insufficient heap headroom for provider snapshot");
    }
  }

  private static void checkInterrupted() throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedIOException("provider snapshot interrupted");
    }
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Preflight classification precedes codec dispatch, including dispatch through superclasses. */
  private static final class Preflight {
    private final Limits limits;
    private final IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();
    private final List<Object> opaque = new ArrayList<>();
    private final Map<Class<?>, ObjectCodec<?>> copiedCodecs = new LinkedHashMap<>();
    private final Map<Class<?>, List<Field>> fields = new LinkedHashMap<>();
    private long estimatedCopiedBytes;

    Preflight(Limits limits) throws IOException {
      this.limits = limits;
      requireHeapHeadroom(16 * MIB);
    }

    void visit(@Nullable Object value, int depth) throws IOException, SerializationException {
      checkInterrupted();
      if (value == null) {
        return;
      }
      if (active.containsKey(value)) {
        throw new IOException("cyclic provider snapshot payload");
      }
      if (visited.put(value, true) != null) {
        return;
      }
      if (depth > limits.maxDepth() || visited.size() > limits.maxObjects()) {
        throw new LimitExceededException("provider snapshot traversal budget exceeded");
      }
      // Heap occupancy includes uncollected garbage and can change between idle periods. Unlike
      // fixed traversal budgets, rejecting this estimate must not disable later attempts forever.
      requireHeapHeadroom(256L * visited.size() + 16 * MIB);
      Class<?> type = value.getClass();
      if (value instanceof String string) {
        if (string.length() > MAX_STRING_LENGTH) {
          opaque.add(value);
          return;
        }
        estimatedCopiedBytes += 2L * string.length() + 48;
        copiedCodecs.putIfAbsent(String.class, UnsafeStringCodec.stringCodec());
        return;
      }
      if (type.isArray()) {
        int length = Array.getLength(value);
        if (length > limits.maxObjects()) {
          throw new LimitExceededException("provider array too large");
        }
        if (type.getComponentType().isPrimitive()) {
          // The private codec routes every array field through the context, so retaining this
          // primitive array by identity also applies when it is used as a typed backing field.
          opaque.add(value);
          return;
        }
        estimatedCopiedBytes += 32L + 8L * length;
        copiedCodecs.putIfAbsent(type, ArrayCodec.forComponentType(type.getComponentType()));
        active.put(value, true);
        for (Object item : (Object[]) value) {
          visit(item, depth + 1);
        }
        active.remove(value);
        return;
      }
      boolean eagerList =
          value instanceof StarlarkList<?>
              && (type.getName().equals("net.starlark.java.eval.RegularImmutableStarlarkList")
                  || type.getName().equals("net.starlark.java.eval.ImmutableSingletonStarlarkList")
                  || type.getName().equals("net.starlark.java.eval.MutableStarlarkList"));
      boolean info =
          type == StarlarkInfoNoSchema.class
              || (StarlarkInfoWithSchema.class.isAssignableFrom(type)
                  && type.getEnclosingClass() == StarlarkInfoWithSchema.class);
      if (!eagerList && !info && !(value instanceof Tuple)) {
        opaque.add(value);
        return;
      }
      if (eagerList && !((StarlarkList<?>) value).mutability().isFrozen()) {
        throw new IOException("mutable provider list");
      }
      if (value == Tuple.empty() || value == StarlarkList.empty()) {
        opaque.add(value);
        return;
      }
      // These exact concrete implementations have no custom codec or evaluation callbacks.
      copiedCodecs.putIfAbsent(type, DynamicCodec.createWithContextualArrayFields(type));
      active.put(value, true);
      for (Field field : fields.computeIfAbsent(type, Preflight::fieldsFor)) {
        if (!field.getType().isPrimitive()) {
          try {
            visit(field.get(value), depth + 1);
          } catch (IllegalAccessException e) {
            throw new IOException(e);
          }
        }
      }
      active.remove(value);
      estimatedCopiedBytes += 64;
    }

    private static List<Field> fieldsFor(Class<?> type) {
      var result = new ArrayList<Field>();
      for (Field field : DynamicCodec.getFieldHandlerMap(type).keySet()) {
        field.setAccessible(true);
        result.add(field);
      }
      return result;
    }

    ObjectCodecs codecs() {
      var builder = ObjectCodecRegistry.newBuilder().setAllowDefaultCodec(false);
      copiedCodecs.values().forEach(builder::add);
      // Constants are checked before superclass codec dispatch. Unknown subclasses, lazy lists,
      // modules, depsets, artifacts and native providers therefore retain exact resident identity.
      builder.addReferenceConstants(opaque);
      return new ObjectCodecs(builder.build());
    }

    void clear() {
      visited.clear();
      active.clear();
      opaque.clear();
      fields.clear();
    }
  }

  private static final class BoundedOutput extends FilterOutputStream {
    private final long maximum;
    private long count;

    BoundedOutput(OutputStream output, long maximum) {
      super(output);
      this.maximum = maximum;
    }

    @Override
    public void write(int value) throws IOException {
      check(1);
      out.write(value);
    }

    @Override
    public void write(byte[] value, int offset, int length) throws IOException {
      check(length);
      out.write(value, offset, length);
    }

    private void check(int length) throws IOException {
      checkInterrupted();
      if (length > maximum - count) {
        throw new LimitExceededException("provider snapshot byte limit exceeded");
      }
      count += length;
    }
  }

  private static final class BoundedInput extends FilterInputStream {
    private final long maximum;
    private long count;

    BoundedInput(InputStream input, long maximum) {
      super(input);
      this.maximum = maximum;
    }

    @Override
    public int read() throws IOException {
      checkInterrupted();
      int value = in.read();
      if (value >= 0) {
        check(1);
      }
      return value;
    }

    @Override
    public int read(byte[] value, int offset, int length) throws IOException {
      checkInterrupted();
      int read = in.read(value, offset, (int) Math.min(length, maximum - count + 1));
      if (read > 0) {
        check(read);
      }
      return read;
    }

    private void check(int length) throws IOException {
      count += length;
      if (count > maximum) {
        throw new IOException("provider snapshot decoded byte limit exceeded");
      }
    }
  }
}
