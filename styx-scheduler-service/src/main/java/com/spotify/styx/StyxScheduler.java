/*-
 * -\-\-
 * Spotify Styx Scheduler Service
 * --
 * Copyright (C) 2016 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.styx;

import static com.spotify.styx.monitoring.MeteredProxy.instrument;
import static com.spotify.styx.util.Connections.createBigTableConnection;
import static com.spotify.styx.util.Connections.createDatastore;
import static com.spotify.styx.util.ParameterUtil.incrementInstant;
import static com.spotify.styx.util.ParameterUtil.truncateInstant;
import static com.spotify.styx.util.ReplayEvents.replayActiveStates;
import static com.spotify.styx.util.ReplayEvents.transitionLogger;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toMap;

import com.codahale.metrics.Gauge;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.util.Utils;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.services.container.Container;
import com.google.api.services.container.ContainerScopes;
import com.google.api.services.container.model.Cluster;
import com.google.cloud.datastore.Datastore;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.io.Closer;
import com.google.common.util.concurrent.RateLimiter;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.spotify.apollo.AppInit;
import com.spotify.apollo.Environment;
import com.spotify.apollo.route.Route;
import com.spotify.metrics.core.SemanticMetricRegistry;
import com.spotify.styx.api.SchedulerResource;
import com.spotify.styx.docker.DockerRunner;
import com.spotify.styx.docker.WorkflowValidator;
import com.spotify.styx.model.Event;
import com.spotify.styx.model.Partitioning;
import com.spotify.styx.model.Workflow;
import com.spotify.styx.model.WorkflowId;
import com.spotify.styx.model.WorkflowInstance;
import com.spotify.styx.model.WorkflowState;
import com.spotify.styx.monitoring.MetricsStats;
import com.spotify.styx.monitoring.MonitoringHandler;
import com.spotify.styx.monitoring.Stats;
import com.spotify.styx.publisher.Publisher;
import com.spotify.styx.schedule.ScheduleSource;
import com.spotify.styx.schedule.ScheduleSourceFactory;
import com.spotify.styx.state.OutputHandler;
import com.spotify.styx.state.QueuedStateManager;
import com.spotify.styx.state.RunState;
import com.spotify.styx.state.StateManager;
import com.spotify.styx.state.TimeoutConfig;
import com.spotify.styx.state.handlers.DockerRunnerHandler;
import com.spotify.styx.state.handlers.ExecutionDescriptionHandler;
import com.spotify.styx.state.handlers.PublisherHandler;
import com.spotify.styx.state.handlers.TerminationHandler;
import com.spotify.styx.storage.AggregateStorage;
import com.spotify.styx.storage.InMemStorage;
import com.spotify.styx.storage.Storage;
import com.spotify.styx.util.RetryUtil;
import com.spotify.styx.util.StorageFactory;
import com.spotify.styx.util.Time;
import com.typesafe.config.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.io.Closeable;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.hadoop.hbase.client.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StyxScheduler implements AppInit {

  public static final String SERVICE_NAME = "styx-scheduler";

  public static final String GKE_CLUSTER_PREFIX = "styx.gke.";
  public static final String GKE_CLUSTER_PROJECT_ID = ".project-id";
  public static final String GKE_CLUSTER_ZONE = ".cluster-zone";
  public static final String GKE_CLUSTER_ID = ".cluster-id";

  public static final String STYX_STALE_STATE_TTL_CONFIG = "styx.stale-state-ttls";
  public static final String STYX_MODE = "styx.mode";
  public static final String STYX_MODE_DEVELOPMENT = "development";

  public static final int SCHEDULER_TICK_INTERVAL_SECONDS = 2;
  public static final int TRIGGER_MANAGER_TICK_INTERVAL_SECONDS = 1;
  public static final int RUNTIME_CONFIG_UPDATE_INTERVAL_SECONDS = 5;
  public static final Duration DEFAULT_RETRY_BASE_DELAY = Duration.ofMinutes(3);
  public static final int DEFAULT_RETRY_MAX_EXPONENT = 4;
  public static final Duration DEFAULT_RETRY_BASE_DELAY_BT = Duration.ofSeconds(1);
  public static final RetryUtil DEFAULT_RETRY_UTIL =
      new RetryUtil(DEFAULT_RETRY_BASE_DELAY, DEFAULT_RETRY_MAX_EXPONENT);
  public static final double DEFAULT_SUBMISSION_RATE_PER_SEC = 1000D;

  private static final Logger LOG = LoggerFactory.getLogger(StyxScheduler.class);

  // === Type aliases for dependency injectors ====================================================
  public interface StateFactory extends Function<WorkflowInstance, RunState> { }
  public interface ScheduleSources extends Supplier<Iterable<ScheduleSourceFactory>> { }
  public interface StatsFactory extends Function<Environment, Stats> { }
  public interface PublisherFactory extends Function<Environment, Publisher> { }

  @FunctionalInterface
  interface DockerRunnerFactory {
    DockerRunner create(
        String id,
        Environment environment,
        StateManager stateManager,
        ScheduledExecutorService scheduler,
        Stats stats);
  }

  @FunctionalInterface
  interface ExecutorFactory {
    ScheduledExecutorService create(
        int threads,
        ThreadFactory threadFactory);
  }

  public static class Builder {

    private Time time = Instant::now;
    private StorageFactory storageFactory = storage(StyxScheduler::storage);
    private DockerRunnerFactory dockerRunnerFactory = StyxScheduler::createDockerRunner;
    private ScheduleSources scheduleSources = () -> ServiceLoader.load(ScheduleSourceFactory.class);
    private StatsFactory statsFactory = StyxScheduler::stats;
    private ExecutorFactory executorFactory = Executors::newScheduledThreadPool;
    private PublisherFactory publisherFactory = (env) -> Publisher.NOOP;
    private RetryUtil retryUtil = DEFAULT_RETRY_UTIL;

    public Builder setTime(Time time) {
      this.time = time;
      return this;
    }

    public Builder setStorageFactory(StorageFactory storageFactory) {
      this.storageFactory = storageFactory;
      return this;
    }

    public Builder setDockerRunnerFactory(DockerRunnerFactory dockerRunnerFactory) {
      this.dockerRunnerFactory = dockerRunnerFactory;
      return this;
    }

    public Builder setScheduleSources(ScheduleSources scheduleSources) {
      this.scheduleSources = scheduleSources;
      return this;
    }

    public Builder setStatsFactory(StatsFactory statsFactory) {
      this.statsFactory = statsFactory;
      return this;
    }

    public Builder setExecutorFactory(ExecutorFactory executorFactory) {
      this.executorFactory = executorFactory;
      return this;
    }

    public Builder setPublisherFactory(PublisherFactory publisherFactory) {
      this.publisherFactory = publisherFactory;
      return this;
    }

    public Builder setRetryUtil(RetryUtil retryUtil) {
      this.retryUtil = retryUtil;
      return this;
    }

    public StyxScheduler build() {
      return new StyxScheduler(
          time,
          storageFactory,
          dockerRunnerFactory,
          scheduleSources,
          statsFactory,
          executorFactory,
          publisherFactory,
          retryUtil);
    }
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static StyxScheduler createDefault() {
    return newBuilder().build();
  }

  // ==============================================================================================

  private final Time time;
  private final StorageFactory storageFactory;
  private final DockerRunnerFactory dockerRunnerFactory;
  private final ScheduleSources scheduleSources;
  private final StatsFactory statsFactory;
  private final ExecutorFactory executorFactory;
  private final PublisherFactory publisherFactory;
  private final RetryUtil retryUtil;

  private StateManager stateManager;
  private Scheduler scheduler;
  private TriggerManager triggerManager;

  private StyxScheduler(
      Time time,
      StorageFactory storageFactory,
      DockerRunnerFactory dockerRunnerFactory,
      ScheduleSources scheduleSources,
      StatsFactory statsFactory,
      ExecutorFactory executorFactory,
      PublisherFactory publisherFactory,
      RetryUtil retryUtil) {
    this.time = requireNonNull(time);
    this.storageFactory = requireNonNull(storageFactory);
    this.dockerRunnerFactory = requireNonNull(dockerRunnerFactory);
    this.scheduleSources = requireNonNull(scheduleSources);
    this.statsFactory = requireNonNull(statsFactory);
    this.executorFactory = requireNonNull(executorFactory);
    this.publisherFactory = requireNonNull(publisherFactory);
    this.retryUtil = requireNonNull(retryUtil);
  }

  @Override
  public void create(Environment environment) {
    final Config config = environment.config();
    final Closer closer = environment.closer();

    final Thread.UncaughtExceptionHandler uncaughtExceptionHandler =
        (thread, throwable) -> LOG.error("Thread {} threw {}", thread, throwable);
    final ThreadFactory schedulerTf = new ThreadFactoryBuilder()
        .setDaemon(true)
        .setNameFormat("styx-scheduler-%d")
        .setUncaughtExceptionHandler(uncaughtExceptionHandler)
        .build();
    final ThreadFactory eventTf = new ThreadFactoryBuilder()
        .setDaemon(true)
        .setNameFormat("styx-event-worker-%d")
        .setUncaughtExceptionHandler(uncaughtExceptionHandler)
        .build();
    final ThreadFactory dockerRunnerTf = new ThreadFactoryBuilder()
        .setDaemon(true)
        .setNameFormat("styx-docker-runner-%d")
        .setUncaughtExceptionHandler(uncaughtExceptionHandler)
        .build();

    final ScheduledExecutorService executor = executorFactory.create(3, schedulerTf);
    final ExecutorService eventWorker = Executors.newFixedThreadPool(16, eventTf);
    final ExecutorService dockerRunnerExecutor = Executors.newSingleThreadExecutor(dockerRunnerTf);
    closer.register(executorCloser("scheduler", executor));
    closer.register(executorCloser("event-worker", eventWorker));
    closer.register(executorCloser("docker-runner", dockerRunnerExecutor));

    final Stats stats = statsFactory.apply(environment);
    final WorkflowCache workflowCache = new InMemWorkflowCache();
    final Storage storage = instrument(Storage.class, storageFactory.apply(environment), stats, time);

    warmUpCache(workflowCache, storage);

    final QueuedStateManager stateManager = closer.register(
        new QueuedStateManager(time, eventWorker, storage));

    final Config staleStateTtlConfig = config.getConfig(STYX_STALE_STATE_TTL_CONFIG);
    final TimeoutConfig timeoutConfig = TimeoutConfig.createFromConfig(staleStateTtlConfig);

    final Supplier<String> dockerId = new CachedSupplier<>(storage::globalDockerRunnerId, time);
    final DockerRunner routingDockerRunner = DockerRunner.routing(
        id -> dockerRunnerFactory.create(id, environment, stateManager, executor, stats),
        dockerId);
    final DockerRunner dockerRunner = instrument(DockerRunner.class, routingDockerRunner, stats, time);
    final Publisher publisher = publisherFactory.apply(environment);

    RateLimiter submissionRateLimiter = RateLimiter.create(DEFAULT_SUBMISSION_RATE_PER_SEC);

    final OutputHandler[] outputHandlers = new OutputHandler[] {
        transitionLogger(""),
        new DockerRunnerHandler(
            dockerRunner, stateManager, storage, submissionRateLimiter, dockerRunnerExecutor),
        new TerminationHandler(retryUtil, stateManager),
        new MonitoringHandler(time, stats),
        new PublisherHandler(publisher),
        new ExecutionDescriptionHandler(storage, stateManager)
    };
    final StateFactory stateFactory =
        (workflowInstance) -> RunState.fresh(workflowInstance, time, outputHandlers);

    final TriggerListener trigger =
        new StateInitializingTrigger(stateFactory, stateManager, storage);
    final TriggerManager triggerManager = new TriggerManager(trigger, time, storage, stats);

    final Scheduler scheduler = new Scheduler(time, timeoutConfig, stateManager, workflowCache,
                                              storage, trigger);

    final Consumer<Workflow> workflowChangeListener = workflowChanged(workflowCache, storage,
                                                                      stats, stateManager, time);
    final Consumer<Workflow> workflowRemoveListener = workflowRemoved(storage);

    restoreState(storage, outputHandlers, stateManager);
    startTriggerManager(triggerManager, executor);
    startScheduleSources(environment, executor, workflowChangeListener, workflowRemoveListener);
    startScheduler(scheduler, executor);
    startRuntimeConfigUpdate(storage, executor, submissionRateLimiter);
    setupMetrics(stateManager, workflowCache, storage, stats);

    final SchedulerResource schedulerResource = new SchedulerResource(stateManager, trigger,
                                                                      storage, time);

    environment.routingEngine()
        .registerAutoRoute(Route.sync("GET", "/ping", rc -> "pong"))
        .registerRoutes(schedulerResource.routes());

    this.stateManager = stateManager;
    this.scheduler = scheduler;
    this.triggerManager = triggerManager;
  }

  @VisibleForTesting
  void receive(Event event) throws StateManager.IsClosed {
    stateManager.receive(event);
  }

  @VisibleForTesting
  RunState getState(WorkflowInstance workflowInstance) {
    return stateManager.get(workflowInstance);
  }

  @VisibleForTesting
  void tickScheduler() {
    scheduler.tick();
  }

  @VisibleForTesting
  void tickTriggerManager() {
    triggerManager.tick();
  }

  private void warmUpCache(WorkflowCache cache, Storage storage) {
    try {
      storage.workflowsWithNextNaturalTrigger().keySet().forEach(cache::store);
    } catch (IOException e) {
      LOG.warn("Failed to get workflows from storage", e);
    }
  }

  private void restoreState(
      Storage storage,
      OutputHandler[] outputHandlers,
      StateManager stateManager) {
    try {
      final Map<WorkflowInstance, Long> activeInstances =
          storage.readActiveWorkflowInstances();

      replayActiveStates(activeInstances, storage, true)
          .entrySet().stream()
          .collect(toMap(
              e -> e.getKey()
                  .withHandlers(outputHandlers)
                  .withTime(time),
              Map.Entry::getValue))
          .forEach(stateManager::restore);
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  private void startScheduleSources(
      Environment environment,
      ScheduledExecutorService scheduler,
      Consumer<Workflow> workflowChangeListener,
      Consumer<Workflow> workflowRemoveListener) {
    for (ScheduleSourceFactory sourceFactory : scheduleSources.get()) {
      try {
        LOG.info("Loading auto-discovered ScheduleSource from {}", sourceFactory);
        final ScheduleSource scheduleSource = sourceFactory.create(
            workflowChangeListener, workflowRemoveListener, environment, scheduler);
        scheduleSource.start();
      } catch (Throwable t) {
        LOG.warn("ScheduleSourceFactory {} threw", sourceFactory, t);
      }
    }
  }

  private static void startTriggerManager(TriggerManager triggerManager, ScheduledExecutorService exec) {
    exec.scheduleAtFixedRate(
        guard(triggerManager::tick),
        TRIGGER_MANAGER_TICK_INTERVAL_SECONDS,
        TRIGGER_MANAGER_TICK_INTERVAL_SECONDS,
        TimeUnit.SECONDS);
  }

  private static void startScheduler(Scheduler scheduler, ScheduledExecutorService exec) {
    exec.scheduleAtFixedRate(
        guard(scheduler::tick),
        SCHEDULER_TICK_INTERVAL_SECONDS,
        SCHEDULER_TICK_INTERVAL_SECONDS,
        TimeUnit.SECONDS);
  }

  private static void startRuntimeConfigUpdate(Storage storage, ScheduledExecutorService exec,
      RateLimiter submissionRateLimiter) {
    exec.scheduleAtFixedRate(
        guard(() -> updateRuntimeConfig(storage, submissionRateLimiter)),
        0,
        RUNTIME_CONFIG_UPDATE_INTERVAL_SECONDS,
        TimeUnit.SECONDS);
  }

  private static void updateRuntimeConfig(Storage storage, RateLimiter rateLimiter) {
    try {
      double currentRate = rateLimiter.getRate();
      Double updatedRate = storage.submissionRateLimit().orElse(
          StyxScheduler.DEFAULT_SUBMISSION_RATE_PER_SEC);
      if (Double.compare(updatedRate, currentRate) != 0) {
        LOG.info("Updating submission rate limit: {} -> {}", currentRate, updatedRate);
        rateLimiter.setRate(updatedRate);
      }
    } catch (IOException e) {
      LOG.warn("Failed to fetch the submission rate config from storage, "
          + "skipping RateLimiter update");
    }
  }

  private static Runnable guard(Runnable delegate) {
    return () -> {
      try {
        delegate.run();
      } catch (Throwable t) {
        LOG.warn("Guarded runnable threw", t);
      }
    };
  }

  private void setupMetrics(
      StateManager stateManager,
      WorkflowCache workflowCache,
      Storage storage,
      Stats stats) {

    final Gauge<Long> queuedEventsCount = stateManager::getQueuedEventsCount;
    final Gauge<Long> allWorkflowsCount = () -> workflowCache.all().stream().count();
    final Gauge<Long> configuredWorkflowsCount = () -> workflowCache.all().stream()
        .filter(WorkflowValidator::hasDockerConfiguration)
        .count();
    final Gauge<Long> configuredEnabledWorkflowsCount = () -> {
      try {
        final Set<WorkflowId> enabledWorkflowsSet = storage.enabled();
        return workflowCache.all().stream()
            .filter(WorkflowValidator::hasDockerConfiguration)
            .filter((workflow) -> enabledWorkflowsSet.contains(WorkflowId.ofWorkflow(workflow)))
            .count();
      } catch (IOException e) {
        LOG.error("Failed to read enabled status from BigTable", e);
        return 0L;
      }
    };

    Arrays.stream(RunState.State.values()).forEach(state -> stats.registerActiveStates(
        state,
        () -> stateManager.activeStates().values().stream()
            .filter(runState -> runState.state().equals(state)).count()));
    stats.registerQueuedEvents(queuedEventsCount);
    stats.registerWorkflowCount("all", allWorkflowsCount);
    stats.registerWorkflowCount("configured", configuredWorkflowsCount);
    stats.registerWorkflowCount("enabled", configuredEnabledWorkflowsCount);
  }

  private static Consumer<Workflow> workflowChanged(
      WorkflowCache cache,
      Storage storage,
      Stats stats,
      StateManager stateManager,
      Time time) {

    return (workflow) -> {
      stats.registerActiveStates(
          workflow.id(),
          () -> stateManager.getActiveStatesCount(workflow.id()));

      cache.store(workflow);
      try {
        Optional<Workflow> optWorkflow = storage.workflow(workflow.id());
        storage.storeWorkflow(workflow);

        // update nextNaturalTrigger only when partitioning specification changes.
        final Partitioning partitioning = workflow.schedule().partitioning();
        if (optWorkflow.isPresent() && !optWorkflow.get().schedule().partitioning()
            .equals(partitioning)) {
          final Instant nextNaturalTrigger =
              incrementInstant(truncateInstant(time.get(), partitioning),
                  partitioning);
          storage.patchState(workflow.id(),
              WorkflowState.builder()
                  .nextNaturalTrigger(nextNaturalTrigger)
                  .build());
        }
      } catch (IOException e) {
        LOG.warn("Failed to store workflow " + workflow, e);
      }
    };
  }

  private static Consumer<Workflow> workflowRemoved(Storage storage) {
    return workflow -> {
      try {
        storage.delete(workflow.id());
      } catch (IOException e) {
        LOG.warn("Couldn't remove workflow {}. ", workflow.id());
      }
    };
  }

  private static Stats stats(Environment environment) {
    return new MetricsStats(environment.resolve(SemanticMetricRegistry.class));
  }

  private static StorageFactory storage(StorageFactory storage) {
    return (environment) -> {
      if (isDevMode(environment.config())) {
        LOG.info("Running Styx in development mode, will use InMemStorage");
        return new InMemStorage();
      } else {
        return storage.apply(environment);
      }
    };
  }

  private static AggregateStorage storage(Environment environment) {
    final Config config = environment.config();
    final Closer closer = environment.closer();

    final Connection bigTable = closer.register(createBigTableConnection(config));
    final Datastore datastore = createDatastore(config);
    return new AggregateStorage(bigTable, datastore, DEFAULT_RETRY_BASE_DELAY_BT);
  }

  private static DockerRunner createDockerRunner(
      String id,
      Environment environment,
      StateManager stateManager,
      ScheduledExecutorService scheduler,
      Stats stats) {
    final Config config = environment.config();
    final Closer closer = environment.closer();

    if (isDevMode(config)) {
      LOG.info("Creating LocalDockerRunner");
      return closer.register(DockerRunner.local(scheduler, stateManager));
    } else {
      final KubernetesClient kubernetes = closer.register(getKubernetesClient(config, id));
      return closer.register(DockerRunner.kubernetes(kubernetes, stateManager, stats));
    }
  }

  private static KubernetesClient getKubernetesClient(Config config, String id) {
    try {
      final HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
      final JsonFactory jsonFactory = Utils.getDefaultJsonFactory();
      final GoogleCredential credential =
          GoogleCredential.getApplicationDefault(httpTransport, jsonFactory)
              .createScoped(ContainerScopes.all());
      final Container gke = new Container.Builder(httpTransport, jsonFactory, credential)
          .setApplicationName(SERVICE_NAME)
          .build();

      final String projectKey = GKE_CLUSTER_PREFIX + id + GKE_CLUSTER_PROJECT_ID;
      final String zoneKey = GKE_CLUSTER_PREFIX + id + GKE_CLUSTER_ZONE;
      final String clusterIdKey = GKE_CLUSTER_PREFIX + id + GKE_CLUSTER_ID;

      final Cluster cluster = gke.projects().zones().clusters()
          .get(config.getString(projectKey),
               config.getString(zoneKey),
               config.getString(clusterIdKey)).execute();

      final io.fabric8.kubernetes.client.Config kubeConfig = new ConfigBuilder()
          .withMasterUrl("https://" + cluster.getEndpoint())
          .withCaCertData(cluster.getMasterAuth().getClusterCaCertificate())
          .withClientCertData(cluster.getMasterAuth().getClientCertificate())
          .withClientKeyData(cluster.getMasterAuth().getClientKey())
          .build();

      return new DefaultKubernetesClient(kubeConfig);
    } catch (GeneralSecurityException | IOException e) {
      throw Throwables.propagate(e);
    }
  }

  private static Closeable executorCloser(String name, ExecutorService executor) {
    return () -> {
      LOG.info("Shutting down executor: {}", name);
      executor.shutdown();
      try {
        executor.awaitTermination(1, SECONDS);
      } catch (InterruptedException ignored) {
      }
      final List<Runnable> runnables = executor.shutdownNow();
      if (!runnables.isEmpty()) {
        LOG.warn("{} task(s) in {} did not execute", runnables.size(), name);
      }
    };
  }

  private static boolean isDevMode(Config config) {
    return STYX_MODE_DEVELOPMENT.equals(config.getString(STYX_MODE));
  }
}
