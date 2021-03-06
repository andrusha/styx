/*-
 * -\-\-
 * Spotify Styx API Service
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

package com.spotify.styx.api;

import static com.spotify.apollo.Status.INTERNAL_SERVER_ERROR;
import static com.spotify.styx.serialization.Json.OBJECT_MAPPER;
import static io.opencensus.trace.AttributeValue.stringAttributeValue;
import static io.opencensus.trace.Status.UNKNOWN;
import static java.util.concurrent.CompletableFuture.completedFuture;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.common.base.CharMatcher;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.HttpHeaders;
import com.spotify.apollo.Request;
import com.spotify.apollo.RequestContext;
import com.spotify.apollo.Response;
import com.spotify.apollo.Status;
import com.spotify.apollo.route.AsyncHandler;
import com.spotify.apollo.route.Middleware;
import com.spotify.apollo.route.SyncHandler;
import com.spotify.styx.util.MDCUtil;
import io.opencensus.trace.Span;
import io.opencensus.trace.Tracer;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * A collection of static methods implementing the apollo Middleware interface, useful for
 * transforming Response objects holding value objects into Response object holding byte
 * strings.
 */
public final class Middlewares {

  private static final Logger LOG = LoggerFactory.getLogger(Middlewares.class);

  public static final String BEARER_PREFIX = "Bearer ";
  private static final Set<String> BLACKLISTED_HEADERS = ImmutableSet.of(HttpHeaders.AUTHORIZATION);

  private static final String REQUEST_ID = "request-id";
  private static final String X_REQUEST_ID = "X-Request-Id";

  private Middlewares() {
    throw new UnsupportedOperationException();
  }

  public static Middleware<SyncHandler<? extends Response<?>>, AsyncHandler<Response<ByteString>>>
      json() {
    return innerHandler -> jsonAsync().apply(Middleware.syncToAsync(innerHandler));
  }

  public static Middleware<AsyncHandler<? extends Response<?>>, AsyncHandler<Response<ByteString>>>
      jsonAsync() {
    return innerHandler -> innerHandler.map(response -> {
      if (!response.payload().isPresent()) {
        // noinspection unchecked
        return (Response<ByteString>) response;
      }

      final Object tPayload = response.payload().get();
      try {
        final byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(tPayload);
        final ByteString payload = ByteString.of(bytes);

        return response.withPayload(payload)
            .withHeader("Content-Type", "application/json");
      } catch (JsonProcessingException e) {
        return Response.forStatus(
            INTERNAL_SERVER_ERROR.withReasonPhrase(
                "Failed to serialize response " + e.getMessage()));
      }
    });
  }

  public static <T> Middleware<AsyncHandler<Response<T>>, AsyncHandler<Response<T>>> clientValidator(
      Supplier<List<String>> supplier) {
    return innerHandler -> requestContext -> {
      if (requestContext.request().header("User-Agent")
          // TODO: should the blacklist be a set so this lookup is O(1) instead of O(n) ?
          .map(header -> supplier.get().contains(header))
          .orElse(false)) {
        // TODO: fire some stats
        return
            completedFuture(Response.forStatus(Status.NOT_ACCEPTABLE.withReasonPhrase(
                "blacklisted client version, please upgrade")));
      } else {
        return innerHandler.invoke(requestContext);
      }
    };
  }

  public static <T> Middleware<AsyncHandler<Response<T>>, AsyncHandler<Response<T>>> exceptionAndRequestIdHandler() {
    return innerHandler -> requestContext -> {

      // Accept the request id from the incoming request if present. Otherwise generate one.
      final String requestIdHeader = requestContext.request().headers().get(X_REQUEST_ID);
      final String requestId = (requestIdHeader != null)
          ? requestIdHeader
          : UUID.randomUUID().toString().replace("-", ""); // UUID with no dashes, easier to deal with

      try (MDC.MDCCloseable mdc = MDCUtil.safePutCloseable(REQUEST_ID, requestId)) {
        return innerHandler.invoke(requestContext).handle((r, t) -> {
          final Response<T> response;
          if (t != null) {
            if (t instanceof ResponseException) {
              response = ((ResponseException) t).getResponse();
            } else {
              response = Response.forStatus(INTERNAL_SERVER_ERROR
                  .withReasonPhrase(internalServerErrorReason(requestId, t)));
            }
          } else {
            response = r;
          }
          return response.withHeader(X_REQUEST_ID, requestId);
        });
      } catch (ResponseException e) {
        return completedFuture(e.<T>getResponse()
            .withHeader(X_REQUEST_ID, requestId));
      } catch (Exception e) {
        return completedFuture(Response.<T>forStatus(INTERNAL_SERVER_ERROR
            .withReasonPhrase(internalServerErrorReason(requestId, e)))
            .withHeader(X_REQUEST_ID, requestId));
      }
    };
  }

  private static String internalServerErrorReason(String requestId, Throwable t) {
    // TODO: returning internal error messages in reason phrase might be a security issue. Make configurable?
    final StringBuilder reason = new StringBuilder(INTERNAL_SERVER_ERROR.reasonPhrase())
        .append(" (").append("Request ID: ").append(requestId).append(")")
        .append(": ").append(t.getClass().getSimpleName())
        .append(": ").append(t.getMessage());
    final Throwable rootCause = Throwables.getRootCause(t);
    if (!t.equals(rootCause)) {
      reason.append(": ").append(rootCause.getClass().getSimpleName())
            .append(": ").append(rootCause.getMessage());
    }
    // Remove any line breaks
    return CharMatcher.anyOf("\n\r").replaceFrom(reason.toString(), ' ');
  }

  public static <T> Middleware<AsyncHandler<Response<T>>, AsyncHandler<Response<T>>> httpLogger(
      Authenticator authenticator) {
    return httpLogger(LOG, authenticator);
  }

  public static <T> Middleware<AsyncHandler<Response<T>>, AsyncHandler<Response<T>>> httpLogger(
      Logger log,
      Authenticator authenticator) {
    return innerHandler -> requestContext -> {
      final Request request = requestContext.request();

      log.info("{}{} {} by {} with headers {} parameters {} and payload {}",
               "GET".equals(request.method()) ? "" : "[AUDIT] ",
               request.method(),
               request.uri(),
               auth(requestContext, authenticator).map(idToken -> idToken.getPayload()
                   .getEmail())
                   .orElse("anonymous"),
               hideSensitiveHeaders(request.headers()),
               request.parameters(),
               request.payload().map(ByteString::utf8).orElse("")
                   .replaceAll("\n", " "));

      return innerHandler.invoke(requestContext);
    };
  }

  public static <T> Middleware<AsyncHandler<Response<T>>, AsyncHandler<Response<T>>> tracer(
      Tracer tracer, String service) {
    return innerHandler -> requestContext -> {
      final Request request = requestContext.request();
      final URI uri = URI.create(request.uri());
      final Span span = tracer.spanBuilder(service + '/' + uri.getPath()).startSpan();
      span.putAttribute("method", stringAttributeValue(request.method()));
      span.putAttribute("uri", stringAttributeValue(request.uri()));
      final CompletionStage<Response<T>> response;
      try  {
        response = innerHandler.invoke(requestContext);
      } catch (Exception e) {
        span.setStatus(UNKNOWN);
        span.end();
        Throwables.throwIfUnchecked(e);
        throw new RuntimeException(e);
      }
      response.whenComplete((r, ex) -> {
        if (ex != null) {
          span.setStatus(UNKNOWN);
        }
        span.end();
      });
      return response;
    };
  }

  private static Optional<GoogleIdToken> auth(RequestContext requestContext,
                                              Authenticator authenticator) {
    final Request request = requestContext.request();
    final boolean hasAuthHeader = request.header(HttpHeaders.AUTHORIZATION).isPresent();

    if (!hasAuthHeader) {
      return Optional.empty();
    }

    final String authHeader = request.header(HttpHeaders.AUTHORIZATION).get();
    if (!authHeader.startsWith(BEARER_PREFIX)) {
      throw new ResponseException(Response.forStatus(Status.BAD_REQUEST
          .withReasonPhrase("Authorization token must be of type Bearer")));
    }

    final GoogleIdToken googleIdToken;
    try {
      googleIdToken = authenticator.authenticate(authHeader.substring(BEARER_PREFIX.length()));
    } catch (IllegalArgumentException e) {
      throw new ResponseException(Response.forStatus(Status.BAD_REQUEST
          .withReasonPhrase("Failed to parse Authorization token")), e);
    }

    if (googleIdToken == null) {
      throw new ResponseException(Response.forStatus(Status.UNAUTHORIZED
          .withReasonPhrase("Authorization token is invalid")));
    }

    return Optional.of(googleIdToken);
  }

  private static Map<String, String> hideSensitiveHeaders(Map<String, String> headers) {
    return headers.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey,
            entry -> BLACKLISTED_HEADERS.contains(entry.getKey()) ? "<hidden>" : entry.getValue()));
  }

  public static <T> Middleware<AsyncHandler<Response<T>>, AsyncHandler<Response<T>>> authenticator(
      Authenticator authenticator) {
    return h -> rc -> {
      if (!"GET".equals(rc.request().method()) && !auth(rc, authenticator).isPresent()) {
        return completedFuture(
            Response.forStatus(Status.UNAUTHORIZED.withReasonPhrase("Unauthorized access")));
      }

      return h.invoke(rc);
    };
  }
}
