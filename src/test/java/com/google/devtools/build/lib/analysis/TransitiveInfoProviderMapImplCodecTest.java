// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class TransitiveInfoProviderMapImplCodecTest {

  @Test
  public void hibernationPreservesMapKeysIdentityAndHash(@TestParameter boolean singleton) {
    var original =
        singleton
            ? ImmutableMap.<Object, Object>of("a", "first")
            : ImmutableMap.<Object, Object>of("a", "first", "b", "second");
    var map = TransitiveInfoProviderMapImpl.create(original);
    var equalMap = TransitiveInfoProviderMapImpl.create(original);
    int hash = map.hashCode();
    Object[] values = map.getValuesAsArray();
    map.hibernateValues();
    assertThat(map.getProviderCount()).isEqualTo(original.size());
    assertThat(map.getProviderKeyAt(0)).isEqualTo("a");
    assertThat(map.toString()).contains("hibernated");
    assertThrows(IllegalStateException.class, () -> map.get("a"));
    assertThrows(IllegalArgumentException.class, () -> map.restoreValues(new Object[0]));
    assertThat(map.isHibernated()).isTrue();
    map.restoreValues(values);
    assertThat(map.get("a")).isEqualTo("first");
    assertThat(map).isEqualTo(equalMap);
    assertThat(map.hashCode()).isEqualTo(hash);
    assertThrows(IllegalStateException.class, () -> map.restoreValues(values));
  }

  @Test
  public void emptyMapCannotBeHibernated() {
    assertThrows(
        IllegalStateException.class, () -> TransitiveInfoProviderMapImpl.empty().hibernateValues());
  }

  @Test
  public void abandonedMapFailsWithDiagnostic() {
    var map = TransitiveInfoProviderMapImpl.create(ImmutableMap.of("a", "value"));
    map.hibernateValues();
    map.abandonHibernatedValues();
    assertThat(map.toString()).contains("abandoned");
    assertThat(assertThrows(IllegalStateException.class, () -> map.get("a")))
        .hasMessageThat()
        .contains("abandoned");
  }

  @Test
  public void serialization(@TestParameter boolean useSharedValues) throws Exception {
    var tester =
        new SerializationTester(
            TransitiveInfoProviderMapImpl.create(ImmutableMap.of()),
            TransitiveInfoProviderMapImpl.create(ImmutableMap.of("key", 1, "key2", 2)));

    if (useSharedValues) {
      tester
          .addCodec(TransitiveInfoProviderMapImpl.valueSharingCodec())
          .makeMemoizingAndAllowFutureBlocking(true);
    }

    tester.runTests();
  }
}
