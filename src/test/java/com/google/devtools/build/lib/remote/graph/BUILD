load("@rules_java//java:defs.bzl", "java_test")

package(
    default_applicable_licenses = ["//:license"],
    default_testonly = 1,
)

filegroup(
    name = "srcs",
    testonly = 0,
    srcs = glob(["**"]),
    visibility = ["//src:__subpackages__"],
)

java_test(
    name = "graph_action_collector_test",
    size = "small",
    srcs = ["GraphActionCollectorTest.java"],
    test_class = "com.google.devtools.build.lib.remote.graph.GraphActionCollectorTest",
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:workspace_status_action",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/remote/graph:graph_action_collector",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/lib/vfs/inmemoryfs",
        "//src/test/java/com/google/devtools/build/lib/actions/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:mockito",
        "//third_party:truth",
    ],
)

java_test(
    name = "graph_execution_client_test",
    size = "small",
    srcs = ["GraphExecutionClientTest.java"],
    test_class = "com.google.devtools.build.lib.remote.graph.GraphExecutionClientTest",
    deps = [
        "//src/main/java/com/google/devtools/build/lib/remote/graph:graph_execution_client",
        "//src/main/protobuf:graph_execution_java_grpc",
        "//src/main/protobuf:graph_execution_java_proto",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "//third_party/grpc-java:grpc-jar",
        "@com_google_protobuf//:protobuf_java",
        "@googleapis//google/rpc:rpc_java_proto",
        "@remoteapis//:build_bazel_remote_execution_v2_remote_execution_java_proto",
    ],
)
