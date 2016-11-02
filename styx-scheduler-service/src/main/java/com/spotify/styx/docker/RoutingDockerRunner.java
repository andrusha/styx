package com.spotify.styx.docker;

import com.google.common.collect.Maps;
import com.google.common.io.Closer;

import com.spotify.styx.model.WorkflowInstance;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * A {@link DockerRunner} that routes to several underlying instances that are created using the
 * injected {@link com.spotify.styx.docker.DockerRunner.DockerRunnerFactory}.
 *
 * Current implementation only creates one runner with the default id.
 */
class RoutingDockerRunner implements DockerRunner {

  private final DockerRunnerFactory dockerRunnerFactory;
  private final Supplier<String> runnerId;

  private final ConcurrentMap<String, DockerRunner> dockerRunners = Maps.newConcurrentMap();

  RoutingDockerRunner(DockerRunnerFactory dockerRunnerFactory, Supplier<String> runnerId) {
    this.dockerRunnerFactory = Objects.requireNonNull(dockerRunnerFactory);
    this.runnerId = Objects.requireNonNull(runnerId);
  }

  @Override
  public String start(WorkflowInstance workflowInstance, RunSpec runSpec) throws IOException {
    return runner().start(workflowInstance, runSpec);
  }

  @Override
  public void cleanup(String executionId) {
    runner().cleanup(executionId);
  }

  @Override
  public void close() throws IOException {
    final Closer closer = Closer.create();
    dockerRunners.values().forEach(closer::register);
    closer.close();
  }

  private DockerRunner runner() {
    return dockerRunners.computeIfAbsent(runnerId.get(), dockerRunnerFactory);
  }
}