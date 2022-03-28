/*
 * Copyright 2021 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;

import com.github.udpa.udpa.type.v1.TypedStruct;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.Value;
import com.google.protobuf.util.Durations;
import com.google.re2j.Pattern;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbPolicy;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LeastRequestLbConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.RingHashLbConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.RingHashLbConfig.HashFunction;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.CidrRange;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.DataSource;
import io.envoyproxy.envoy.config.core.v3.HttpProtocolOptions;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.core.v3.RuntimeFractionalPercent;
import io.envoyproxy.envoy.config.core.v3.SelfConfigSource;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.core.v3.TrafficDirection;
import io.envoyproxy.envoy.config.core.v3.TransportSocket;
import io.envoyproxy.envoy.config.core.v3.TypedExtensionConfig;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.listener.v3.Filter;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.FilterChainMatch;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.listener.v3.ListenerFilter;
import io.envoyproxy.envoy.config.rbac.v3.Permission;
import io.envoyproxy.envoy.config.rbac.v3.Policy;
import io.envoyproxy.envoy.config.rbac.v3.Principal;
import io.envoyproxy.envoy.config.rbac.v3.RBAC;
import io.envoyproxy.envoy.config.rbac.v3.RBAC.Action;
import io.envoyproxy.envoy.config.route.v3.DirectResponseAction;
import io.envoyproxy.envoy.config.route.v3.FilterAction;
import io.envoyproxy.envoy.config.route.v3.NonForwardingAction;
import io.envoyproxy.envoy.config.route.v3.RedirectAction;
import io.envoyproxy.envoy.config.route.v3.RetryPolicy;
import io.envoyproxy.envoy.config.route.v3.RetryPolicy.RetryBackOff;
import io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.ConnectionProperties;
import io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.FilterState;
import io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.Header;
import io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.QueryParameter;
import io.envoyproxy.envoy.config.route.v3.RouteAction.MaxStreamDuration;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.WeightedCluster;
import io.envoyproxy.envoy.extensions.filters.common.fault.v3.FaultDelay;
import io.envoyproxy.envoy.extensions.filters.http.fault.v3.FaultAbort;
import io.envoyproxy.envoy.extensions.filters.http.fault.v3.HTTPFault;
import io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBACPerRoute;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateProviderPluginInstance;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext.CertificateProviderInstance;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext.CombinedCertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsCertificate;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsParameters;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatchAndSubstitute;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher.GoogleRE2;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import io.envoyproxy.envoy.type.v3.FractionalPercent;
import io.envoyproxy.envoy.type.v3.FractionalPercent.DenominatorType;
import io.envoyproxy.envoy.type.v3.Int64Range;
import io.grpc.ClientInterceptor;
import io.grpc.InsecureChannelCredentials;
import io.grpc.LoadBalancer;
import io.grpc.Status.Code;
import io.grpc.lookup.v1.GrpcKeyBuilder;
import io.grpc.lookup.v1.GrpcKeyBuilder.Name;
import io.grpc.lookup.v1.NameMatcher;
import io.grpc.lookup.v1.RouteLookupClusterSpecifier;
import io.grpc.lookup.v1.RouteLookupConfig;
import io.grpc.xds.AbstractXdsClient.ResourceType;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.ClientXdsClient.ResourceInvalidException;
import io.grpc.xds.ClientXdsClient.StructOrError;
import io.grpc.xds.ClusterSpecifierPlugin.NamedPluginConfig;
import io.grpc.xds.ClusterSpecifierPlugin.PluginConfig;
import io.grpc.xds.Endpoints.LbEndpoint;
import io.grpc.xds.Endpoints.LocalityLbEndpoints;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.RouteLookupServiceClusterSpecifierPlugin.RlsPluginConfig;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.VirtualHost.Route.RouteAction;
import io.grpc.xds.VirtualHost.Route.RouteAction.ClusterWeight;
import io.grpc.xds.VirtualHost.Route.RouteAction.HashPolicy;
import io.grpc.xds.VirtualHost.Route.RouteMatch;
import io.grpc.xds.VirtualHost.Route.RouteMatch.PathMatcher;
import io.grpc.xds.XdsClient.CdsUpdate;
import io.grpc.xds.internal.Matchers.FractionMatcher;
import io.grpc.xds.internal.Matchers.HeaderMatcher;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ClientXdsClientDataTest {

  private static final ServerInfo LRS_SERVER_INFO =
      ServerInfo.create("lrs.googleapis.com", InsecureChannelCredentials.create(), true);

  @SuppressWarnings("deprecation") // https://github.com/grpc/grpc-java/issues/7467
  @Rule
  public final ExpectedException thrown = ExpectedException.none();
  private final FilterRegistry filterRegistry = FilterRegistry.getDefaultRegistry();
  private boolean originalEnableRetry;
  private boolean originalEnableRbac;
  private boolean originalEnableRouteLookup;
  private boolean originalEnableLeastRequest;

  @Before
  public void setUp() {
    originalEnableRetry = ClientXdsClient.enableRetry;
    assertThat(originalEnableRetry).isTrue();
    originalEnableRbac = ClientXdsClient.enableRbac;
    assertThat(originalEnableRbac).isTrue();
    originalEnableRouteLookup = ClientXdsClient.enableRouteLookup;
    assertThat(originalEnableRouteLookup).isFalse();
    originalEnableLeastRequest = ClientXdsClient.enableLeastRequest;
    assertThat(originalEnableLeastRequest).isFalse();
  }

  @After
  public void tearDown() {
    ClientXdsClient.enableRetry = originalEnableRetry;
    ClientXdsClient.enableRbac = originalEnableRbac;
    ClientXdsClient.enableRouteLookup = originalEnableRouteLookup;
    ClientXdsClient.enableLeastRequest = originalEnableLeastRequest;
  }

  @Test
  public void parseRoute_withRouteAction() {
    io.envoyproxy.envoy.config.route.v3.Route proto =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-blade")
            .setMatch(
                io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
                    .setPath("/service/method"))
            .setRoute(
                io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
                    .setCluster("cluster-foo"))
            .build();
    StructOrError<Route> struct = ClientXdsClient.parseRoute(
        proto, filterRegistry, false, ImmutableMap.<String, PluginConfig>of());
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct())
        .isEqualTo(
            Route.forAction(
                RouteMatch.create(PathMatcher.fromPath("/service/method", false),
                    Collections.<HeaderMatcher>emptyList(), null),
                RouteAction.forCluster(
                    "cluster-foo", Collections.<HashPolicy>emptyList(), null, null),
                ImmutableMap.<String, FilterConfig>of()));
  }

  @Test
  public void parseRoute_withNonForwardingAction() {
    io.envoyproxy.envoy.config.route.v3.Route proto =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-blade")
            .setMatch(
                io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
                    .setPath("/service/method"))
            .setNonForwardingAction(NonForwardingAction.getDefaultInstance())
            .build();
    StructOrError<Route> struct = ClientXdsClient.parseRoute(
        proto, filterRegistry, false, ImmutableMap.<String, PluginConfig>of());
    assertThat(struct.getStruct())
        .isEqualTo(
            Route.forNonForwardingAction(
                RouteMatch.create(PathMatcher.fromPath("/service/method", false),
                    Collections.<HeaderMatcher>emptyList(), null),
                ImmutableMap.<String, FilterConfig>of()));
  }

  @Test
  public void parseRoute_withUnsupportedActionTypes() {
    StructOrError<Route> res;
    io.envoyproxy.envoy.config.route.v3.Route redirectRoute =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-blade")
            .setMatch(io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPath(""))
            .setRedirect(RedirectAction.getDefaultInstance())
            .build();
    res = ClientXdsClient.parseRoute(
        redirectRoute, filterRegistry, false, ImmutableMap.<String, PluginConfig>of());
    assertThat(res.getStruct()).isNull();
    assertThat(res.getErrorDetail())
        .isEqualTo("Route [route-blade] with unknown action type: REDIRECT");

    io.envoyproxy.envoy.config.route.v3.Route directResponseRoute =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-blade")
            .setMatch(io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPath(""))
            .setDirectResponse(DirectResponseAction.getDefaultInstance())
            .build();
    res = ClientXdsClient.parseRoute(
        directResponseRoute, filterRegistry, false, ImmutableMap.<String, PluginConfig>of());
    assertThat(res.getStruct()).isNull();
    assertThat(res.getErrorDetail())
        .isEqualTo("Route [route-blade] with unknown action type: DIRECT_RESPONSE");

    io.envoyproxy.envoy.config.route.v3.Route filterRoute =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-blade")
            .setMatch(io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPath(""))
            .setFilterAction(FilterAction.getDefaultInstance())
            .build();
    res = ClientXdsClient.parseRoute(
        filterRoute, filterRegistry, false, ImmutableMap.<String, PluginConfig>of());
    assertThat(res.getStruct()).isNull();
    assertThat(res.getErrorDetail())
        .isEqualTo("Route [route-blade] with unknown action type: FILTER_ACTION");
  }

  @Test
  public void parseRoute_skipRouteWithUnsupportedMatcher() {
    io.envoyproxy.envoy.config.route.v3.Route proto =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("ignore me")
            .setMatch(
                io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
                    .setPath("/service/method")
                    .addQueryParameters(
                        io.envoyproxy.envoy.config.route.v3.QueryParameterMatcher
                            .getDefaultInstance()))  // query parameter not supported
            .setRoute(
                io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
                    .setCluster("cluster-foo"))
            .build();
    assertThat(ClientXdsClient.parseRoute(
            proto, filterRegistry, false, ImmutableMap.<String, PluginConfig>of()))
        .isNull();
  }

  @Test
  public void parseRoute_skipRouteWithUnsupportedAction() {
    io.envoyproxy.envoy.config.route.v3.Route proto =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("ignore me")
            .setMatch(
                io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
                    .setPath("/service/method"))
            .setRoute(
                io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
                    .setClusterHeader("cluster header"))  // cluster_header action not supported
            .build();
    assertThat(ClientXdsClient.parseRoute(
            proto, filterRegistry, false, ImmutableMap.<String, PluginConfig>of()))
        .isNull();
  }

  @Test
  @SuppressWarnings("deprecation")
  public void parseRouteMatch_withHeaderMatcher() {
    io.envoyproxy.envoy.config.route.v3.RouteMatch proto =
        io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
            .setPrefix("")
            .addHeaders(
                io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
                    .setName(":scheme")
                    .setPrefixMatch("http"))
            .addHeaders(
                io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
                    .setName(":method")
                    .setExactMatch("PUT"))
            .build();
    StructOrError<RouteMatch> struct = ClientXdsClient.parseRouteMatch(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct())
        .isEqualTo(
            RouteMatch.create(
                PathMatcher.fromPrefix("", false),
                Arrays.asList(
                    HeaderMatcher.forPrefix(":scheme", "http", false),
                    HeaderMatcher.forExactValue(":method", "PUT", false)),
                null));
  }

  @Test
  public void parseRouteMatch_withRuntimeFractionMatcher() {
    io.envoyproxy.envoy.config.route.v3.RouteMatch proto =
        io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
            .setPrefix("")
            .setRuntimeFraction(
                RuntimeFractionalPercent.newBuilder()
                    .setDefaultValue(
                        FractionalPercent.newBuilder()
                            .setNumerator(30)
                            .setDenominator(FractionalPercent.DenominatorType.HUNDRED)))
            .build();
    StructOrError<RouteMatch> struct = ClientXdsClient.parseRouteMatch(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct())
        .isEqualTo(
            RouteMatch.create(
                PathMatcher.fromPrefix( "", false), Collections.<HeaderMatcher>emptyList(),
                FractionMatcher.create(30, 100)));
  }

  @Test
  public void parsePathMatcher_withFullPath() {
    io.envoyproxy.envoy.config.route.v3.RouteMatch proto =
        io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
            .setPath("/service/method")
            .build();
    StructOrError<PathMatcher> struct = ClientXdsClient.parsePathMatcher(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct()).isEqualTo(
        PathMatcher.fromPath("/service/method", false));
  }

  @Test
  public void parsePathMatcher_withPrefix() {
    io.envoyproxy.envoy.config.route.v3.RouteMatch proto =
        io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPrefix("/").build();
    StructOrError<PathMatcher> struct = ClientXdsClient.parsePathMatcher(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct()).isEqualTo(
        PathMatcher.fromPrefix("/", false));
  }

  @Test
  public void parsePathMatcher_withSafeRegEx() {
    io.envoyproxy.envoy.config.route.v3.RouteMatch proto =
        io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
            .setSafeRegex(RegexMatcher.newBuilder().setRegex("."))
            .build();
    StructOrError<PathMatcher> struct = ClientXdsClient.parsePathMatcher(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct()).isEqualTo(PathMatcher.fromRegEx(Pattern.compile(".")));
  }

  @Test
  @SuppressWarnings("deprecation")
  public void parseHeaderMatcher_withExactMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName(":method")
            .setExactMatch("PUT")
            .build();
    StructOrError<HeaderMatcher> struct1 = ClientXdsClient.parseHeaderMatcher(proto);
    assertThat(struct1.getErrorDetail()).isNull();
    assertThat(struct1.getStruct()).isEqualTo(
        HeaderMatcher.forExactValue(":method", "PUT", false));
  }

  @Test
  @SuppressWarnings("deprecation")
  public void parseHeaderMatcher_withSafeRegExMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName(":method")
            .setSafeRegexMatch(RegexMatcher.newBuilder().setRegex("P*"))
            .build();
    StructOrError<HeaderMatcher> struct3 = ClientXdsClient.parseHeaderMatcher(proto);
    assertThat(struct3.getErrorDetail()).isNull();
    assertThat(struct3.getStruct()).isEqualTo(
        HeaderMatcher.forSafeRegEx(":method", Pattern.compile("P*"), false));
  }

  @Test
  public void parseHeaderMatcher_withRangeMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName("timeout")
            .setRangeMatch(Int64Range.newBuilder().setStart(10L).setEnd(20L))
            .build();
    StructOrError<HeaderMatcher> struct4 = ClientXdsClient.parseHeaderMatcher(proto);
    assertThat(struct4.getErrorDetail()).isNull();
    assertThat(struct4.getStruct()).isEqualTo(
        HeaderMatcher.forRange("timeout", HeaderMatcher.Range.create(10L, 20L), false));
  }

  @Test
  public void parseHeaderMatcher_withPresentMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName("user-agent")
            .setPresentMatch(true)
            .build();
    StructOrError<HeaderMatcher> struct5 = ClientXdsClient.parseHeaderMatcher(proto);
    assertThat(struct5.getErrorDetail()).isNull();
    assertThat(struct5.getStruct()).isEqualTo(
        HeaderMatcher.forPresent("user-agent", true, false));
  }

  @Test
  @SuppressWarnings("deprecation")
  public void parseHeaderMatcher_withPrefixMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName("authority")
            .setPrefixMatch("service-foo")
            .build();
    StructOrError<HeaderMatcher> struct6 = ClientXdsClient.parseHeaderMatcher(proto);
    assertThat(struct6.getErrorDetail()).isNull();
    assertThat(struct6.getStruct()).isEqualTo(
        HeaderMatcher.forPrefix("authority", "service-foo", false));
  }

  @Test
  @SuppressWarnings("deprecation")
  public void parseHeaderMatcher_withSuffixMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName("authority")
            .setSuffixMatch("googleapis.com")
            .build();
    StructOrError<HeaderMatcher> struct7 = ClientXdsClient.parseHeaderMatcher(proto);
    assertThat(struct7.getErrorDetail()).isNull();
    assertThat(struct7.getStruct()).isEqualTo(
        HeaderMatcher.forSuffix("authority", "googleapis.com", false));
  }

  @Test
  @SuppressWarnings("deprecation")
  public void parseHeaderMatcher_malformedRegExPattern() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName(":method")
            .setSafeRegexMatch(RegexMatcher.newBuilder().setRegex("["))
            .build();
    StructOrError<HeaderMatcher> struct = ClientXdsClient.parseHeaderMatcher(proto);
    assertThat(struct.getErrorDetail()).isNotNull();
    assertThat(struct.getStruct()).isNull();
  }

  @Test
  public void parseRouteAction_withCluster() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setCluster("cluster-foo")
            .build();
    StructOrError<RouteAction> struct =
        ClientXdsClient.parseRouteAction(proto, filterRegistry, false,
        ImmutableMap.<String, PluginConfig>of());
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct().cluster()).isEqualTo("cluster-foo");
    assertThat(struct.getStruct().weightedClusters()).isNull();
  }

  @Test
  public void parseRouteAction_withWeightedCluster() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setWeightedClusters(
                WeightedCluster.newBuilder()
                    .addClusters(
                        WeightedCluster.ClusterWeight
                            .newBuilder()
                            .setName("cluster-foo")
                            .setWeight(UInt32Value.newBuilder().setValue(30)))
                    .addClusters(WeightedCluster.ClusterWeight
                        .newBuilder()
                        .setName("cluster-bar")
                        .setWeight(UInt32Value.newBuilder().setValue(70))))
            .build();
    StructOrError<RouteAction> struct =
        ClientXdsClient.parseRouteAction(proto, filterRegistry, false,
        ImmutableMap.<String, PluginConfig>of());
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct().cluster()).isNull();
    assertThat(struct.getStruct().weightedClusters()).containsExactly(
        ClusterWeight.create("cluster-foo", 30, ImmutableMap.<String, FilterConfig>of()),
        ClusterWeight.create("cluster-bar", 70, ImmutableMap.<String, FilterConfig>of()));
  }

  @Test
  public void parseRouteAction_withTimeoutByGrpcTimeoutHeaderMax() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setCluster("cluster-foo")
            .setMaxStreamDuration(
                MaxStreamDuration.newBuilder()
                    .setGrpcTimeoutHeaderMax(Durations.fromSeconds(5L))
                    .setMaxStreamDuration(Durations.fromMillis(20L)))
            .build();
    StructOrError<RouteAction> struct =
        ClientXdsClient.parseRouteAction(proto, filterRegistry, false,
        ImmutableMap.<String, PluginConfig>of());
    assertThat(struct.getStruct().timeoutNano()).isEqualTo(TimeUnit.SECONDS.toNanos(5L));
  }

  @Test
  public void parseRouteAction_withTimeoutByMaxStreamDuration() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setCluster("cluster-foo")
            .setMaxStreamDuration(
                MaxStreamDuration.newBuilder()
                    .setMaxStreamDuration(Durations.fromSeconds(5L)))
            .build();
    StructOrError<RouteAction> struct =
        ClientXdsClient.parseRouteAction(proto, filterRegistry, false,
        ImmutableMap.<String, PluginConfig>of());
    assertThat(struct.getStruct().timeoutNano()).isEqualTo(TimeUnit.SECONDS.toNanos(5L));
  }

  @Test
  public void parseRouteAction_withTimeoutUnset() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setCluster("cluster-foo")
            .build();
    StructOrError<RouteAction> struct =
        ClientXdsClient.parseRouteAction(proto, filterRegistry, false,
        ImmutableMap.<String, PluginConfig>of());
    assertThat(struct.getStruct().timeoutNano()).isNull();
  }

  @Test
  public void parseRouteAction_withRetryPolicy() {
    ClientXdsClient.enableRetry = true;
    RetryPolicy.Builder builder = RetryPolicy.newBuilder()
        .setNumRetries(UInt32Value.of(3))
        .setRetryBackOff(
            RetryBackOff.newBuilder()
                .setBaseInterval(Durations.fromMillis(500))
                .setMaxInterval(Durations.fromMillis(600)))
        .setPerTryTimeout(Durations.fromMillis(300))
        .setRetryOn(
            "cancelled,deadline-exceeded,internal,resource-exhausted,unavailable");
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setCluster("cluster-foo")
            .setRetryPolicy(builder.build())
            .build();
    StructOrError<RouteAction> struct =
        ClientXdsClient.parseRouteAction(proto, filterRegistry, false,
        ImmutableMap.<String, PluginConfig>of());
    RouteAction.RetryPolicy retryPolicy = struct.getStruct().retryPolicy();
    assertThat(retryPolicy.maxAttempts()).isEqualTo(4);
    assertThat(retryPolicy.initialBackoff()).isEqualTo(Durations.fromMillis(500));
    assertThat(retryPolicy.maxBackoff()).isEqualTo(Durations.fromMillis(600));
    // Not supporting per_try_timeout yet.
    assertThat(retryPolicy.perAttemptRecvTimeout()).isEqualTo(null);
    assertThat(retryPolicy.retryableStatusCodes()).containsExactly(
        Code.CANCELLED, Code.DEADLINE_EXCEEDED, Code.INTERNAL, Code.RESOURCE_EXHAUSTED,
        Code.UNAVAILABLE);

    // empty retry_on
    builder = RetryPolicy.newBuilder()
        .setNumRetries(UInt32Value.of(3))
        .setRetryBackOff(
            RetryBackOff.newBuilder()
                .setBaseInterval(Durations.fromMillis(500))
                .setMaxInterval(Durations.fromMillis(600)))
        .setPerTryTimeout(Durations.fromMillis(300)); // Not supporting per_try_timeout yet.
    proto = io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
        .setCluster("cluster-foo")
        .setRetryPolicy(builder.build())
        .build();
    struct = ClientXdsClient.parseRouteAction(proto, filterRegistry, false,
        ImmutableMap.<String, PluginConfig>of());
    assertThat(struct.getStruct().retryPolicy()).isNotNull();
    assertThat(struct.getStruct().retryPolicy().retryableStatusCodes()).isEmpty();

    // base_interval unset
    builder
        .setRetryOn("cancelled")
        .setRetryBackOff(RetryBackOff.newBuilder().setMaxInterval(Durations.fromMillis(600)));
    proto = io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
        .setCluster("cluster-foo")
        .setRetryPolicy(builder)
        .build();
    struct = ClientXdsClient.parseRouteAction(proto, filterRegistry, false,
        ImmutableMap.<String, PluginConfig>of());
    assertThat(struct.getErrorDetail()).isEqualTo("No base_interval specified in retry_backoff");

    // max_interval unset
    builder.setRetryBackOff(RetryBackOff.newBuilder().setBaseInterval(Durations.fromMillis(500)));
    proto = io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
        .setCluster("cluster-foo")
        .setRetryPolicy(builder)
        .build();
    struct = ClientXdsClient.parseRouteAction(proto, filterRegistry, false,
        ImmutableMap.<String, PluginConfig>of());
    retryPolicy = struct.getStruct().retryPolicy();
    assertThat(retryPolicy.maxBackoff()).isEqualTo(Durations.fromMillis(500 * 10));

    // base_interval < 0
    builder.setRetryBackOff(RetryBackOff.newBuilder().setBaseInterval(Durations.fromMillis(-1)));
    proto = io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
        .setCluster("cluster-foo")
        .setRetryPolicy(builder)
        .build();
    struct = ClientXdsClient.parseRouteAction(proto, filterRegistry, false,
        ImmutableMap.<String, PluginConfig>of());
    assertThat(struct.getErrorDetail())
        .isEqualTo("base_interval in retry_backoff must be positive");

    // base_interval > max_interval > 1ms
    builder.setRetryBackOff(
        RetryBackOff.newBuilder()
            .setBaseInterval(Durations.fromMillis(200)).setMaxInterval(Durations.fromMillis(100)));
    proto = io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
        .setCluster("cluster-foo")
        .setRetryPolicy(builder)
        .build();
    struct = ClientXdsClient.parseRouteAction(proto, filterRegistry, false,
        ImmutableMap.<String, PluginConfig>of());
    assertThat(struct.getErrorDetail())
        .isEqualTo("max_interval in retry_backoff cannot be less than base_interval");

    // 1ms > base_interval > max_interval
    builder.setRetryBackOff(
        RetryBackOff.newBuilder()
            .setBaseInterval(Durations.fromNanos(200)).setMaxInterval(Durations.fromNanos(100)));
    proto = io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
        .setCluster("cluster-foo")
        .setRetryPolicy(builder)
        .build();
    struct = ClientXdsClient.parseRouteAction(proto, filterRegistry, false,
        ImmutableMap.<String, PluginConfig>of());
    assertThat(struct.getErrorDetail())
        .isEqualTo("max_interval in retry_backoff cannot be less than base_interval");

    // 1ms > max_interval > base_interval
    builder.setRetryBackOff(
        RetryBackOff.newBuilder()
            .setBaseInterval(Durations.fromNanos(100)).setMaxInterval(Durations.fromNanos(200)));
    proto = io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
        .setCluster("cluster-foo")
        .setRetryPolicy(builder)
        .build();
    struct = ClientXdsClient.parseRouteAction(proto, filterRegistry, false,
        ImmutableMap.<String, PluginConfig>of());
    assertThat(struct.getStruct().retryPolicy().initialBackoff())
        .isEqualTo(Durations.fromMillis(1));
    assertThat(struct.getStruct().retryPolicy().maxBackoff())
        .isEqualTo(Durations.fromMillis(1));

    // retry_backoff unset
    builder = RetryPolicy.newBuilder()
        .setNumRetries(UInt32Value.of(3))
        .setPerTryTimeout(Durations.fromMillis(300))
        .setRetryOn("cancelled");
    proto = io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
        .setCluster("cluster-foo")
        .setRetryPolicy(builder)
        .build();
    struct = ClientXdsClient.parseRouteAction(proto, filterRegistry, false,
        ImmutableMap.<String, PluginConfig>of());
    retryPolicy = struct.getStruct().retryPolicy();
    assertThat(retryPolicy.initialBackoff()).isEqualTo(Durations.fromMillis(25));
    assertThat(retryPolicy.maxBackoff()).isEqualTo(Durations.fromMillis(250));

    // unsupported retry_on value
    builder = RetryPolicy.newBuilder()
        .setNumRetries(UInt32Value.of(3))
        .setRetryBackOff(
            RetryBackOff.newBuilder()
                .setBaseInterval(Durations.fromMillis(500))
                .setMaxInterval(Durations.fromMillis(600)))
        .setPerTryTimeout(Durations.fromMillis(300))
        .setRetryOn("cancelled,unsupported-foo");
    proto = io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
        .setCluster("cluster-foo")
        .setRetryPolicy(builder)
        .build();
    struct = ClientXdsClient.parseRouteAction(proto, filterRegistry, false,
        ImmutableMap.<String, PluginConfig>of());
    assertThat(struct.getStruct().retryPolicy().retryableStatusCodes())
        .containsExactly(Code.CANCELLED);

    // unsupported retry_on code
    builder = RetryPolicy.newBuilder()
        .setNumRetries(UInt32Value.of(3))
        .setRetryBackOff(
            RetryBackOff.newBuilder()
                .setBaseInterval(Durations.fromMillis(500))
                .setMaxInterval(Durations.fromMillis(600)))
        .setPerTryTimeout(Durations.fromMillis(300))
        .setRetryOn("cancelled,abort");
    proto = io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
        .setCluster("cluster-foo")
        .setRetryPolicy(builder)
        .build();
    struct = ClientXdsClient.parseRouteAction(proto, filterRegistry, false,
        ImmutableMap.<String, PluginConfig>of());
    assertThat(struct.getStruct().retryPolicy().retryableStatusCodes())
        .containsExactly(Code.CANCELLED);

    // whitespace in retry_on
    builder = RetryPolicy.newBuilder()
        .setNumRetries(UInt32Value.of(3))
        .setRetryBackOff(
            RetryBackOff.newBuilder()
                .setBaseInterval(Durations.fromMillis(500))
                .setMaxInterval(Durations.fromMillis(600)))
        .setPerTryTimeout(Durations.fromMillis(300))
        .setRetryOn("abort, , cancelled , ");
    proto = io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
        .setCluster("cluster-foo")
        .setRetryPolicy(builder)
        .build();
    struct = ClientXdsClient.parseRouteAction(proto, filterRegistry, false,
        ImmutableMap.<String, PluginConfig>of());
    assertThat(struct.getStruct().retryPolicy().retryableStatusCodes())
        .containsExactly(Code.CANCELLED);
  }

  @Test
  public void parseRouteAction_withHashPolicies() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setCluster("cluster-foo")
            .addHashPolicy(
                io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.newBuilder()
                    .setHeader(
                        Header.newBuilder()
                            .setHeaderName("user-agent")
                            .setRegexRewrite(
                                RegexMatchAndSubstitute.newBuilder()
                                    .setPattern(
                                        RegexMatcher.newBuilder()
                                            .setGoogleRe2(GoogleRE2.getDefaultInstance())
                                            .setRegex("grpc.*"))
                                    .setSubstitution("gRPC"))))
            .addHashPolicy(
                io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.newBuilder()
                    .setConnectionProperties(ConnectionProperties.newBuilder().setSourceIp(true))
                    .setTerminal(true))  // unsupported
            .addHashPolicy(
                io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.newBuilder()
                    .setFilterState(
                        FilterState.newBuilder()
                            .setKey(ClientXdsClient.HASH_POLICY_FILTER_STATE_KEY)))
            .addHashPolicy(
                io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.newBuilder()
                    .setQueryParameter(
                        QueryParameter.newBuilder().setName("param"))) // unsupported
            .build();
    StructOrError<RouteAction> struct =
        ClientXdsClient.parseRouteAction(proto, filterRegistry, false,
            ImmutableMap.<String, PluginConfig>of());
    List<HashPolicy> policies = struct.getStruct().hashPolicies();
    assertThat(policies).hasSize(2);
    assertThat(policies.get(0).type()).isEqualTo(HashPolicy.Type.HEADER);
    assertThat(policies.get(0).headerName()).isEqualTo("user-agent");
    assertThat(policies.get(0).isTerminal()).isFalse();
    assertThat(policies.get(0).regEx().pattern()).isEqualTo("grpc.*");
    assertThat(policies.get(0).regExSubstitution()).isEqualTo("gRPC");

    assertThat(policies.get(1).type()).isEqualTo(HashPolicy.Type.CHANNEL_ID);
    assertThat(policies.get(1).isTerminal()).isFalse();
  }

  @Test
  public void parseClusterWeight() {
    io.envoyproxy.envoy.config.route.v3.WeightedCluster.ClusterWeight proto =
        io.envoyproxy.envoy.config.route.v3.WeightedCluster.ClusterWeight.newBuilder()
            .setName("cluster-foo")
            .setWeight(UInt32Value.newBuilder().setValue(30))
            .build();
    ClusterWeight clusterWeight =
        ClientXdsClient.parseClusterWeight(proto, filterRegistry, false).getStruct();
    assertThat(clusterWeight.name()).isEqualTo("cluster-foo");
    assertThat(clusterWeight.weight()).isEqualTo(30);
  }

  @Test
  public void parseLocalityLbEndpoints_withHealthyEndpoints() {
    io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints proto =
        io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints.newBuilder()
            .setLocality(Locality.newBuilder()
                .setRegion("region-foo").setZone("zone-foo").setSubZone("subZone-foo"))
            .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(100))  // locality weight
            .setPriority(1)
            .addLbEndpoints(io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint.newBuilder()
                .setEndpoint(Endpoint.newBuilder()
                    .setAddress(Address.newBuilder()
                        .setSocketAddress(
                            SocketAddress.newBuilder()
                                .setAddress("172.14.14.5").setPortValue(8888))))
                .setHealthStatus(io.envoyproxy.envoy.config.core.v3.HealthStatus.HEALTHY)
                .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(20)))  // endpoint weight
            .build();
    StructOrError<LocalityLbEndpoints> struct = ClientXdsClient.parseLocalityLbEndpoints(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct()).isEqualTo(
        LocalityLbEndpoints.create(
            Collections.singletonList(LbEndpoint.create("172.14.14.5", 8888, 20, true)), 100, 1));
  }

  @Test
  public void parseLocalityLbEndpoints_treatUnknownHealthAsHealthy() {
    io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints proto =
        io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints.newBuilder()
            .setLocality(Locality.newBuilder()
                .setRegion("region-foo").setZone("zone-foo").setSubZone("subZone-foo"))
            .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(100))  // locality weight
            .setPriority(1)
            .addLbEndpoints(io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint.newBuilder()
                .setEndpoint(Endpoint.newBuilder()
                    .setAddress(Address.newBuilder()
                        .setSocketAddress(
                            SocketAddress.newBuilder()
                                .setAddress("172.14.14.5").setPortValue(8888))))
                .setHealthStatus(io.envoyproxy.envoy.config.core.v3.HealthStatus.UNKNOWN)
                .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(20)))  // endpoint weight
            .build();
    StructOrError<LocalityLbEndpoints> struct = ClientXdsClient.parseLocalityLbEndpoints(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct()).isEqualTo(
        LocalityLbEndpoints.create(
            Collections.singletonList(LbEndpoint.create("172.14.14.5", 8888, 20, true)), 100, 1));
  }

  @Test
  public void parseLocalityLbEndpoints_withUnHealthyEndpoints() {
    io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints proto =
        io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints.newBuilder()
            .setLocality(Locality.newBuilder()
                .setRegion("region-foo").setZone("zone-foo").setSubZone("subZone-foo"))
            .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(100))  // locality weight
            .setPriority(1)
            .addLbEndpoints(io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint.newBuilder()
                .setEndpoint(Endpoint.newBuilder()
                    .setAddress(Address.newBuilder()
                        .setSocketAddress(
                            SocketAddress.newBuilder()
                                .setAddress("172.14.14.5").setPortValue(8888))))
                .setHealthStatus(io.envoyproxy.envoy.config.core.v3.HealthStatus.UNHEALTHY)
                .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(20)))  // endpoint weight
            .build();
    StructOrError<LocalityLbEndpoints> struct = ClientXdsClient.parseLocalityLbEndpoints(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct()).isEqualTo(
        LocalityLbEndpoints.create(
            Collections.singletonList(LbEndpoint.create("172.14.14.5", 8888, 20, false)), 100, 1));
  }

  @Test
  public void parseLocalityLbEndpoints_ignorZeroWeightLocality() {
    io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints proto =
        io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints.newBuilder()
            .setLocality(Locality.newBuilder()
                .setRegion("region-foo").setZone("zone-foo").setSubZone("subZone-foo"))
            .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(0))  // locality weight
            .setPriority(1)
            .addLbEndpoints(io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint.newBuilder()
                .setEndpoint(Endpoint.newBuilder()
                    .setAddress(Address.newBuilder()
                        .setSocketAddress(
                            SocketAddress.newBuilder()
                                .setAddress("172.14.14.5").setPortValue(8888))))
                .setHealthStatus(io.envoyproxy.envoy.config.core.v3.HealthStatus.UNKNOWN)
                .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(20)))  // endpoint weight
            .build();
    assertThat(ClientXdsClient.parseLocalityLbEndpoints(proto)).isNull();
  }

  @Test
  public void parseLocalityLbEndpoints_invalidPriority() {
    io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints proto =
        io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints.newBuilder()
            .setLocality(Locality.newBuilder()
                .setRegion("region-foo").setZone("zone-foo").setSubZone("subZone-foo"))
            .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(100))  // locality weight
            .setPriority(-1)
            .addLbEndpoints(io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint.newBuilder()
                .setEndpoint(Endpoint.newBuilder()
                    .setAddress(Address.newBuilder()
                        .setSocketAddress(
                            SocketAddress.newBuilder()
                                .setAddress("172.14.14.5").setPortValue(8888))))
                .setHealthStatus(io.envoyproxy.envoy.config.core.v3.HealthStatus.UNKNOWN)
                .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(20)))  // endpoint weight
            .build();
    StructOrError<LocalityLbEndpoints> struct = ClientXdsClient.parseLocalityLbEndpoints(proto);
    assertThat(struct.getErrorDetail()).isEqualTo("negative priority");
  }

  @Test
  public void parseHttpFilter_unsupportedButOptional() {
    HttpFilter httpFilter = HttpFilter.newBuilder()
        .setIsOptional(true)
        .setTypedConfig(Any.pack(StringValue.of("unsupported")))
        .build();
    assertThat(ClientXdsClient.parseHttpFilter(httpFilter, filterRegistry, true)).isNull();
  }

  private static class SimpleFilterConfig implements FilterConfig {
    private final Message message;

    public SimpleFilterConfig(Message rawProtoMessage) {
      message = rawProtoMessage;
    }

    public Message getConfig() {
      return message;
    }

    @Override
    public String typeUrl() {
      return null;
    }
  }

  private static class TestFilter implements io.grpc.xds.Filter,
      io.grpc.xds.Filter.ClientInterceptorBuilder {
    @Override
    public String[] typeUrls() {
      return new String[]{"test-url"};
    }

    @Override
    public ConfigOrError<? extends FilterConfig> parseFilterConfig(Message rawProtoMessage) {
      return ConfigOrError.fromConfig(new SimpleFilterConfig(rawProtoMessage));
    }

    @Override
    public ConfigOrError<? extends FilterConfig> parseFilterConfigOverride(
        Message rawProtoMessage) {
      return ConfigOrError.fromConfig(new SimpleFilterConfig(rawProtoMessage));
    }

    @Nullable
    @Override
    public ClientInterceptor buildClientInterceptor(FilterConfig config,
                                                    @Nullable FilterConfig overrideConfig,
                                                    LoadBalancer.PickSubchannelArgs args,
                                                    ScheduledExecutorService scheduler) {
      return null;
    }
  }

  @Test
  public void parseHttpFilter_typedStructMigration() {
    filterRegistry.register(new TestFilter());
    Struct rawStruct = Struct.newBuilder()
        .putFields("name", Value.newBuilder().setStringValue("default").build())
        .build();
    HttpFilter httpFilter = HttpFilter.newBuilder()
        .setIsOptional(true)
        .setTypedConfig(Any.pack(
            com.github.udpa.udpa.type.v1.TypedStruct.newBuilder()
                .setTypeUrl("test-url")
                .setValue(rawStruct)
        .build())).build();
    FilterConfig config = ClientXdsClient.parseHttpFilter(httpFilter, filterRegistry,
        true).getStruct();
    assertThat(((SimpleFilterConfig)config).getConfig()).isEqualTo(rawStruct);

    HttpFilter httpFilterNewTypeStruct = HttpFilter.newBuilder()
        .setIsOptional(true)
        .setTypedConfig(Any.pack(
            TypedStruct.newBuilder()
                .setTypeUrl("test-url")
                .setValue(rawStruct)
                .build())).build();
    config = ClientXdsClient.parseHttpFilter(httpFilterNewTypeStruct, filterRegistry,
        true).getStruct();
    assertThat(((SimpleFilterConfig)config).getConfig()).isEqualTo(rawStruct);
  }

  @Test
  public void parseOverrideHttpFilter_typedStructMigration() {
    filterRegistry.register(new TestFilter());
    Struct rawStruct0 = Struct.newBuilder()
        .putFields("name", Value.newBuilder().setStringValue("default0").build())
        .build();
    Struct rawStruct1 = Struct.newBuilder()
        .putFields("name", Value.newBuilder().setStringValue("default1").build())
        .build();
    Map<String, Any> rawFilterMap = ImmutableMap.of(
        "struct-0", Any.pack(
            com.github.udpa.udpa.type.v1.TypedStruct.newBuilder()
                .setTypeUrl("test-url")
                .setValue(rawStruct0)
                .build()),
          "struct-1", Any.pack(
              TypedStruct.newBuilder()
                  .setTypeUrl("test-url")
                  .setValue(rawStruct1)
                  .build())
    );
    Map<String, FilterConfig> map = ClientXdsClient.parseOverrideFilterConfigs(rawFilterMap,
        filterRegistry).getStruct();
    assertThat(((SimpleFilterConfig)map.get("struct-0")).getConfig()).isEqualTo(rawStruct0);
    assertThat(((SimpleFilterConfig)map.get("struct-1")).getConfig()).isEqualTo(rawStruct1);
  }

  @Test
  public void parseHttpFilter_unsupportedAndRequired() {
    HttpFilter httpFilter = HttpFilter.newBuilder()
        .setIsOptional(false)
        .setName("unsupported.filter")
        .setTypedConfig(Any.pack(StringValue.of("string value")))
        .build();
    assertThat(ClientXdsClient.parseHttpFilter(httpFilter, filterRegistry, true)
        .getErrorDetail()).isEqualTo(
            "HttpFilter [unsupported.filter]"
                + "(type.googleapis.com/google.protobuf.StringValue) is required but unsupported "
                + "for client");
  }

  @Test
  public void parseHttpFilter_routerFilterForClient() {
    filterRegistry.register(RouterFilter.INSTANCE);
    HttpFilter httpFilter =
        HttpFilter.newBuilder()
            .setIsOptional(false)
            .setName("envoy.router")
            .setTypedConfig(Any.pack(Router.getDefaultInstance()))
            .build();
    FilterConfig config = ClientXdsClient.parseHttpFilter(
        httpFilter, filterRegistry, true /* isForClient */).getStruct();
    assertThat(config.typeUrl()).isEqualTo(RouterFilter.TYPE_URL);
  }

  @Test
  public void parseHttpFilter_routerFilterForServer() {
    filterRegistry.register(RouterFilter.INSTANCE);
    HttpFilter httpFilter =
        HttpFilter.newBuilder()
            .setIsOptional(false)
            .setName("envoy.router")
            .setTypedConfig(Any.pack(Router.getDefaultInstance()))
            .build();
    FilterConfig config = ClientXdsClient.parseHttpFilter(
        httpFilter, filterRegistry, false /* isForClient */).getStruct();
    assertThat(config.typeUrl()).isEqualTo(RouterFilter.TYPE_URL);
  }

  @Test
  public void parseHttpFilter_faultConfigForClient() {
    filterRegistry.register(FaultFilter.INSTANCE);
    HttpFilter httpFilter =
        HttpFilter.newBuilder()
            .setIsOptional(false)
            .setName("envoy.fault")
            .setTypedConfig(
                Any.pack(
                    HTTPFault.newBuilder()
                        .setDelay(
                            FaultDelay.newBuilder()
                                .setFixedDelay(Durations.fromNanos(1234L)))
                        .setAbort(
                            FaultAbort.newBuilder()
                                .setHttpStatus(300)
                                .setPercentage(
                                    FractionalPercent.newBuilder()
                                        .setNumerator(10)
                                        .setDenominator(DenominatorType.HUNDRED)))
                        .build()))
            .build();
    FilterConfig config = ClientXdsClient.parseHttpFilter(
        httpFilter, filterRegistry, true /* isForClient */).getStruct();
    assertThat(config).isInstanceOf(FaultConfig.class);
  }

  @Test
  public void parseHttpFilter_faultConfigUnsupportedForServer() {
    filterRegistry.register(FaultFilter.INSTANCE);
    HttpFilter httpFilter =
        HttpFilter.newBuilder()
            .setIsOptional(false)
            .setName("envoy.fault")
            .setTypedConfig(
                Any.pack(
                    HTTPFault.newBuilder()
                        .setDelay(
                            FaultDelay.newBuilder()
                                .setFixedDelay(Durations.fromNanos(1234L)))
                        .setAbort(
                            FaultAbort.newBuilder()
                                .setHttpStatus(300)
                                .setPercentage(
                                    FractionalPercent.newBuilder()
                                        .setNumerator(10)
                                        .setDenominator(DenominatorType.HUNDRED)))
                        .build()))
            .build();
    StructOrError<FilterConfig> config =
        ClientXdsClient.parseHttpFilter(httpFilter, filterRegistry, false /* isForClient */);
    assertThat(config.getErrorDetail()).isEqualTo(
        "HttpFilter [envoy.fault](" + FaultFilter.TYPE_URL + ") is required but "
            + "unsupported for server");
  }

  @Test
  public void parseHttpFilter_rbacConfigForServer() {
    filterRegistry.register(RbacFilter.INSTANCE);
    HttpFilter httpFilter =
        HttpFilter.newBuilder()
            .setIsOptional(false)
            .setName("envoy.auth")
            .setTypedConfig(
                Any.pack(
                    io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC.newBuilder()
                        .setRules(
                            RBAC.newBuilder()
                                .setAction(Action.ALLOW)
                                .putPolicies(
                                    "allow-all",
                                    Policy.newBuilder()
                                        .addPrincipals(Principal.newBuilder().setAny(true))
                                        .addPermissions(Permission.newBuilder().setAny(true))
                                        .build())
                                .build())
                        .build()))
            .build();
    FilterConfig config = ClientXdsClient.parseHttpFilter(
        httpFilter, filterRegistry, false /* isForClient */).getStruct();
    assertThat(config).isInstanceOf(RbacConfig.class);
  }

  @Test
  public void parseHttpFilter_rbacConfigUnsupportedForClient() {
    filterRegistry.register(RbacFilter.INSTANCE);
    HttpFilter httpFilter =
        HttpFilter.newBuilder()
            .setIsOptional(false)
            .setName("envoy.auth")
            .setTypedConfig(
                Any.pack(
                    io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC.newBuilder()
                        .setRules(
                            RBAC.newBuilder()
                                .setAction(Action.ALLOW)
                                .putPolicies(
                                    "allow-all",
                                    Policy.newBuilder()
                                        .addPrincipals(Principal.newBuilder().setAny(true))
                                        .addPermissions(Permission.newBuilder().setAny(true))
                                        .build())
                                .build())
                        .build()))
            .build();
    StructOrError<FilterConfig> config =
        ClientXdsClient.parseHttpFilter(httpFilter, filterRegistry, true /* isForClient */);
    assertThat(config.getErrorDetail()).isEqualTo(
        "HttpFilter [envoy.auth](" + RbacFilter.TYPE_URL + ") is required but "
            + "unsupported for client");
  }

  @Test
  public void parseOverrideRbacFilterConfig() {
    filterRegistry.register(RbacFilter.INSTANCE);
    RBACPerRoute rbacPerRoute =
        RBACPerRoute.newBuilder()
            .setRbac(
                io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC.newBuilder()
                    .setRules(
                        RBAC.newBuilder()
                            .setAction(Action.ALLOW)
                            .putPolicies(
                                "allow-all",
                                Policy.newBuilder()
                                    .addPrincipals(Principal.newBuilder().setAny(true))
                                    .addPermissions(Permission.newBuilder().setAny(true))
                            .build())))
            .build();
    Map<String, Any> configOverrides = ImmutableMap.of("envoy.auth", Any.pack(rbacPerRoute));
    Map<String, FilterConfig> parsedConfigs =
        ClientXdsClient.parseOverrideFilterConfigs(configOverrides, filterRegistry).getStruct();
    assertThat(parsedConfigs).hasSize(1);
    assertThat(parsedConfigs).containsKey("envoy.auth");
    assertThat(parsedConfigs.get("envoy.auth")).isInstanceOf(RbacConfig.class);
  }

  @Test
  public void parseOverrideFilterConfigs_unsupportedButOptional() {
    filterRegistry.register(FaultFilter.INSTANCE);
    HTTPFault httpFault = HTTPFault.newBuilder()
        .setDelay(FaultDelay.newBuilder().setFixedDelay(Durations.fromNanos(3000)))
        .build();
    Map<String, Any> configOverrides = ImmutableMap.of(
        "envoy.fault",
        Any.pack(httpFault),
        "unsupported.filter",
        Any.pack(io.envoyproxy.envoy.config.route.v3.FilterConfig.newBuilder()
            .setIsOptional(true).setConfig(Any.pack(StringValue.of("string value")))
            .build()));
    Map<String, FilterConfig> parsedConfigs =
        ClientXdsClient.parseOverrideFilterConfigs(configOverrides, filterRegistry).getStruct();
    assertThat(parsedConfigs).hasSize(1);
    assertThat(parsedConfigs).containsKey("envoy.fault");
  }

  @Test
  public void parseOverrideFilterConfigs_unsupportedAndRequired() {
    filterRegistry.register(FaultFilter.INSTANCE);
    HTTPFault httpFault = HTTPFault.newBuilder()
        .setDelay(FaultDelay.newBuilder().setFixedDelay(Durations.fromNanos(3000)))
        .build();
    Map<String, Any> configOverrides = ImmutableMap.of(
        "envoy.fault",
        Any.pack(httpFault),
        "unsupported.filter",
        Any.pack(io.envoyproxy.envoy.config.route.v3.FilterConfig.newBuilder()
            .setIsOptional(false).setConfig(Any.pack(StringValue.of("string value")))
            .build()));
    assertThat(ClientXdsClient.parseOverrideFilterConfigs(configOverrides, filterRegistry)
        .getErrorDetail()).isEqualTo(
            "HttpFilter [unsupported.filter]"
                + "(type.googleapis.com/google.protobuf.StringValue) is required but unsupported");

    configOverrides = ImmutableMap.of(
        "envoy.fault",
        Any.pack(httpFault),
        "unsupported.filter",
        Any.pack(StringValue.of("string value")));
    assertThat(ClientXdsClient.parseOverrideFilterConfigs(configOverrides, filterRegistry)
        .getErrorDetail()).isEqualTo(
            "HttpFilter [unsupported.filter]"
                + "(type.googleapis.com/google.protobuf.StringValue) is required but unsupported");
  }

  @Test
  public void parseHttpConnectionManager_xffNumTrustedHopsUnsupported()
      throws ResourceInvalidException {
    @SuppressWarnings("deprecation")
    HttpConnectionManager hcm = HttpConnectionManager.newBuilder().setXffNumTrustedHops(2).build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("HttpConnectionManager with xff_num_trusted_hops unsupported");
    ClientXdsClient.parseHttpConnectionManager(
        hcm, new HashSet<String>(), filterRegistry, false /* does not matter */,
        true /* does not matter */);
  }

  @Test
  public void parseHttpConnectionManager_OriginalIpDetectionExtensionsMustEmpty()
      throws ResourceInvalidException {
    @SuppressWarnings("deprecation")
    HttpConnectionManager hcm = HttpConnectionManager.newBuilder()
        .addOriginalIpDetectionExtensions(TypedExtensionConfig.newBuilder().build())
        .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("HttpConnectionManager with original_ip_detection_extensions unsupported");
    ClientXdsClient.parseHttpConnectionManager(
        hcm, new HashSet<String>(), filterRegistry, false /* does not matter */, false);
  }
  
  @Test
  public void parseHttpConnectionManager_missingRdsAndInlinedRouteConfiguration()
      throws ResourceInvalidException {
    HttpConnectionManager hcm =
        HttpConnectionManager.newBuilder()
            .setCommonHttpProtocolOptions(
                HttpProtocolOptions.newBuilder()
                    .setMaxStreamDuration(Durations.fromNanos(1000L)))
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("HttpConnectionManager neither has inlined route_config nor RDS");
    ClientXdsClient.parseHttpConnectionManager(
        hcm, new HashSet<String>(), filterRegistry, false /* does not matter */,
        true /* does not matter */);
  }

  @Test
  public void parseHttpConnectionManager_duplicateHttpFilters() throws ResourceInvalidException {
    HttpConnectionManager hcm =
        HttpConnectionManager.newBuilder()
            .addHttpFilters(
                HttpFilter.newBuilder().setName("envoy.filter.foo").setIsOptional(true))
            .addHttpFilters(
                HttpFilter.newBuilder().setName("envoy.filter.foo").setIsOptional(true))
            .addHttpFilters(
                HttpFilter.newBuilder().setName("terminal").setTypedConfig(
                        Any.pack(Router.newBuilder().build())).setIsOptional(true))
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("HttpConnectionManager contains duplicate HttpFilter: envoy.filter.foo");
    ClientXdsClient.parseHttpConnectionManager(
        hcm, new HashSet<String>(), filterRegistry, true /* parseHttpFilter */,
        true /* does not matter */);
  }

  @Test
  public void parseHttpConnectionManager_lastNotTerminal() throws ResourceInvalidException {
    filterRegistry.register(FaultFilter.INSTANCE);
    HttpConnectionManager hcm =
          HttpConnectionManager.newBuilder()
              .addHttpFilters(
                HttpFilter.newBuilder().setName("envoy.filter.foo").setIsOptional(true))
              .addHttpFilters(
                HttpFilter.newBuilder().setName("envoy.filter.bar").setIsOptional(true)
                    .setTypedConfig(Any.pack(HTTPFault.newBuilder().build())))
                    .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("The last HttpFilter must be a terminal filter: envoy.filter.bar");
    ClientXdsClient.parseHttpConnectionManager(
            hcm, new HashSet<String>(), filterRegistry, true /* parseHttpFilter */,
            true /* does not matter */);
  }

  @Test
  public void parseHttpConnectionManager_terminalNotLast() throws ResourceInvalidException {
    filterRegistry.register(RouterFilter.INSTANCE);
    HttpConnectionManager hcm =
            HttpConnectionManager.newBuilder()
                    .addHttpFilters(
                            HttpFilter.newBuilder().setName("terminal").setTypedConfig(
                                    Any.pack(Router.newBuilder().build())).setIsOptional(true))
                    .addHttpFilters(
                            HttpFilter.newBuilder().setName("envoy.filter.foo").setIsOptional(true))
                    .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("A terminal HttpFilter must be the last filter: terminal");
    ClientXdsClient.parseHttpConnectionManager(
            hcm, new HashSet<String>(), filterRegistry, true /* parseHttpFilter */,
            true);
  }

  @Test
  public void parseHttpConnectionManager_unknownFilters() throws ResourceInvalidException {
    HttpConnectionManager hcm =
            HttpConnectionManager.newBuilder()
                    .addHttpFilters(
                            HttpFilter.newBuilder().setName("envoy.filter.foo").setIsOptional(true))
                    .addHttpFilters(
                            HttpFilter.newBuilder().setName("envoy.filter.bar").setIsOptional(true))
                    .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("The last HttpFilter must be a terminal filter: envoy.filter.bar");
    ClientXdsClient.parseHttpConnectionManager(
            hcm, new HashSet<String>(), filterRegistry, true /* parseHttpFilter */,
            true /* does not matter */);
  }

  @Test
  public void parseHttpConnectionManager_emptyFilters() throws ResourceInvalidException {
    HttpConnectionManager hcm =
            HttpConnectionManager.newBuilder()
                    .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("Missing HttpFilter in HttpConnectionManager.");
    ClientXdsClient.parseHttpConnectionManager(
            hcm, new HashSet<String>(), filterRegistry, true /* parseHttpFilter */,
            true /* does not matter */);
  }

  @Test
  public void parseHttpConnectionManager_clusterSpecifierPlugin() throws Exception {
    ClientXdsClient.enableRouteLookup = true;
    RouteLookupConfig routeLookupConfig = RouteLookupConfig.newBuilder()
        .addGrpcKeybuilders(
            GrpcKeyBuilder.newBuilder()
                .addNames(Name.newBuilder().setService("service1"))
                .addNames(Name.newBuilder().setService("service2"))
                .addHeaders(
                    NameMatcher.newBuilder().setKey("key1").addNames("v1").setRequiredMatch(true)))
        .setLookupService("rls-cbt.googleapis.com")
        .setLookupServiceTimeout(Durations.fromMillis(1234))
        .setCacheSizeBytes(5000)
        .addValidTargets("valid-target")
        .build();
    RouteLookupClusterSpecifier specifier =
        RouteLookupClusterSpecifier.newBuilder().setRouteLookupConfig(routeLookupConfig).build();
    TypedExtensionConfig typedExtensionConfig = TypedExtensionConfig.newBuilder()
        .setName("rls-plugin-1")
        .setTypedConfig(Any.pack(specifier))
        .build();
    io.envoyproxy.envoy.config.route.v3.Route route =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-1")
            .setMatch(io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPrefix(""))
            .setRoute(io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
                .setClusterSpecifierPlugin("rls-plugin-1"))
            .build();
    HttpConnectionManager hcm =
        HttpConnectionManager.newBuilder()
            .setRouteConfig(
                RouteConfiguration.newBuilder()
                    .addClusterSpecifierPlugins(
                        io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin.newBuilder()
                            .setExtension(typedExtensionConfig)
                            .build())
                    .addVirtualHosts(io.envoyproxy.envoy.config.route.v3.VirtualHost.newBuilder()
                        .setName("virtual-host-1")
                        .addRoutes(route)))
            .build();

    io.grpc.xds.HttpConnectionManager parsedHcm = ClientXdsClient.parseHttpConnectionManager(
        hcm, new HashSet<String>(), filterRegistry, false /* parseHttpFilter */,
        true /* does not matter */);

    VirtualHost virtualHost = Iterables.getOnlyElement(parsedHcm.virtualHosts());
    Route parsedRoute = Iterables.getOnlyElement(virtualHost.routes());
    NamedPluginConfig namedPluginConfig =
        parsedRoute.routeAction().namedClusterSpecifierPluginConfig();
    assertThat(namedPluginConfig.name()).isEqualTo("rls-plugin-1");
    assertThat(namedPluginConfig.config()).isInstanceOf(RlsPluginConfig.class);
  }

  @Test
  public void parseHttpConnectionManager_duplicatePluginName() throws Exception {
    ClientXdsClient.enableRouteLookup = true;
    RouteLookupConfig routeLookupConfig1 = RouteLookupConfig.newBuilder()
        .addGrpcKeybuilders(
            GrpcKeyBuilder.newBuilder()
                .addNames(Name.newBuilder().setService("service1"))
                .addNames(Name.newBuilder().setService("service2"))
                .addHeaders(
                    NameMatcher.newBuilder().setKey("key1").addNames("v1").setRequiredMatch(true)))
        .setLookupService("rls-cbt.googleapis.com")
        .setLookupServiceTimeout(Durations.fromMillis(1234))
        .setCacheSizeBytes(5000)
        .addValidTargets("valid-target")
        .build();
    RouteLookupClusterSpecifier specifier1 =
        RouteLookupClusterSpecifier.newBuilder().setRouteLookupConfig(routeLookupConfig1).build();
    RouteLookupConfig routeLookupConfig2 = RouteLookupConfig.newBuilder()
        .addGrpcKeybuilders(
            GrpcKeyBuilder.newBuilder()
                .addNames(Name.newBuilder().setService("service3"))
                .addHeaders(
                    NameMatcher.newBuilder().setKey("key1").addNames("v1").setRequiredMatch(true)))
        .setLookupService("rls-cbt.googleapis.com")
        .setLookupServiceTimeout(Durations.fromMillis(1234))
        .setCacheSizeBytes(5000)
        .addValidTargets("valid-target")
        .build();
    RouteLookupClusterSpecifier specifier2 =
        RouteLookupClusterSpecifier.newBuilder().setRouteLookupConfig(routeLookupConfig2).build();
    TypedExtensionConfig typedExtensionConfig = TypedExtensionConfig.newBuilder()
        .setName("rls-plugin-1")
        .setTypedConfig(Any.pack(specifier1))
        .build();
    TypedExtensionConfig typedExtensionConfig2 = TypedExtensionConfig.newBuilder()
        .setName("rls-plugin-1")
        .setTypedConfig(Any.pack(specifier2))
        .build();
    io.envoyproxy.envoy.config.route.v3.Route route =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-1")
            .setMatch(io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPrefix(""))
            .setRoute(io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
                .setClusterSpecifierPlugin("rls-plugin-1"))
            .build();
    HttpConnectionManager hcm =
        HttpConnectionManager.newBuilder()
            .setRouteConfig(
                RouteConfiguration.newBuilder()
                    .addClusterSpecifierPlugins(
                        io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin.newBuilder()
                            .setExtension(typedExtensionConfig)
                            .build())
                    .addClusterSpecifierPlugins(
                        io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin.newBuilder()
                            .setExtension(typedExtensionConfig2)
                            .build())
                    .addVirtualHosts(io.envoyproxy.envoy.config.route.v3.VirtualHost.newBuilder()
                        .setName("virtual-host-1")
                        .addRoutes(route)))
            .build();

    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("Multiple ClusterSpecifierPlugins with the same name: rls-plugin-1");

    ClientXdsClient.parseHttpConnectionManager(
        hcm, new HashSet<String>(), filterRegistry, false /* parseHttpFilter */,
        true /* does not matter */);
  }

  @Test
  public void parseHttpConnectionManager_pluginNameNotFound() throws Exception {
    ClientXdsClient.enableRouteLookup = true;
    RouteLookupConfig routeLookupConfig = RouteLookupConfig.newBuilder()
        .addGrpcKeybuilders(
            GrpcKeyBuilder.newBuilder()
                .addNames(Name.newBuilder().setService("service1"))
                .addNames(Name.newBuilder().setService("service2"))
                .addHeaders(
                    NameMatcher.newBuilder().setKey("key1").addNames("v1").setRequiredMatch(true)))
        .setLookupService("rls-cbt.googleapis.com")
        .setLookupServiceTimeout(Durations.fromMillis(1234))
        .setCacheSizeBytes(5000)
        .addValidTargets("valid-target")
        .build();
    RouteLookupClusterSpecifier specifier =
        RouteLookupClusterSpecifier.newBuilder().setRouteLookupConfig(routeLookupConfig).build();
    TypedExtensionConfig typedExtensionConfig = TypedExtensionConfig.newBuilder()
        .setName("rls-plugin-1")
        .setTypedConfig(Any.pack(specifier))
        .build();
    io.envoyproxy.envoy.config.route.v3.Route route =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-1")
            .setMatch(io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPrefix(""))
            .setRoute(io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
                .setClusterSpecifierPlugin("invalid-plugin-name"))
            .build();
    HttpConnectionManager hcm =
        HttpConnectionManager.newBuilder()
            .setRouteConfig(
                RouteConfiguration.newBuilder()
                    .addClusterSpecifierPlugins(
                        io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin.newBuilder()
                            .setExtension(typedExtensionConfig)
                            .build())
                    .addVirtualHosts(io.envoyproxy.envoy.config.route.v3.VirtualHost.newBuilder()
                        .setName("virtual-host-1")
                        .addRoutes(route)))
            .build();

    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("ClusterSpecifierPlugin for [invalid-plugin-name] not found");

    ClientXdsClient.parseHttpConnectionManager(
        hcm, new HashSet<String>(), filterRegistry, false /* parseHttpFilter */,
        true /* does not matter */);
  }

  @Test
  public void parseHttpConnectionManager_validateRdsConfigSource() throws Exception {
    ClientXdsClient.enableRouteLookup = true;
    Set<String> rdsResources = new HashSet<>();

    HttpConnectionManager hcm1 =
        HttpConnectionManager.newBuilder()
            .setRds(Rds.newBuilder()
                .setRouteConfigName("rds-config-foo")
                .setConfigSource(
                    ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance())))
            .build();
    ClientXdsClient.parseHttpConnectionManager(
        hcm1, rdsResources, filterRegistry, false /* parseHttpFilter */,
        true /* does not matter */);

    HttpConnectionManager hcm2 =
        HttpConnectionManager.newBuilder()
            .setRds(Rds.newBuilder()
                .setRouteConfigName("rds-config-foo")
                .setConfigSource(
                    ConfigSource.newBuilder().setSelf(SelfConfigSource.getDefaultInstance())))
            .build();
    ClientXdsClient.parseHttpConnectionManager(
        hcm2, rdsResources, filterRegistry, false /* parseHttpFilter */,
        true /* does not matter */);

    HttpConnectionManager hcm3 =
        HttpConnectionManager.newBuilder()
            .setRds(Rds.newBuilder()
                .setRouteConfigName("rds-config-foo")
                .setConfigSource(
                    ConfigSource.newBuilder().setPath("foo-path")))
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "HttpConnectionManager contains invalid RDS: must specify ADS or self ConfigSource");
    ClientXdsClient.parseHttpConnectionManager(
        hcm3, rdsResources, filterRegistry, false /* parseHttpFilter */,
        true /* does not matter */);
  }

  @Test
  public void parseClusterSpecifierPlugin_typedStructInTypedExtension() throws Exception {
    class TestPluginConfig implements PluginConfig {
      @Override
      public String typeUrl() {
        return "type.googleapis.com/google.protobuf.Empty";
      }
    }

    ClusterSpecifierPluginRegistry registry = ClusterSpecifierPluginRegistry.newRegistry();
    registry.register(new ClusterSpecifierPlugin() {
      @Override
      public String[] typeUrls() {
        return new String[] {
            "type.googleapis.com/google.protobuf.Empty",
        };
      }

      @Override
      public ConfigOrError<? extends PluginConfig> parsePlugin(Message rawProtoMessage) {
        return ConfigOrError.fromConfig(new TestPluginConfig());
      }
    });

    TypedStruct typedStruct = TypedStruct.newBuilder()
        .setTypeUrl("type.googleapis.com/google.protobuf.Empty")
        .setValue(Struct.newBuilder())
        .build();
    io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin pluginProto =
        io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin.newBuilder()
            .setExtension(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(typedStruct)))
            .build();

    PluginConfig pluginConfig = ClientXdsClient.parseClusterSpecifierPlugin(pluginProto, registry);
    assertThat(pluginConfig).isInstanceOf(TestPluginConfig.class);
  }

  @Test
  public void parseClusterSpecifierPlugin_v3TypedStructInTypedExtension() throws Exception {
    class TestPluginConfig implements PluginConfig {
      @Override
      public String typeUrl() {
        return "type.googleapis.com/google.protobuf.Empty";
      }
    }

    ClusterSpecifierPluginRegistry registry = ClusterSpecifierPluginRegistry.newRegistry();
    registry.register(new ClusterSpecifierPlugin() {
      @Override
      public String[] typeUrls() {
        return new String[] {
            "type.googleapis.com/google.protobuf.Empty",
        };
      }

      @Override
      public ConfigOrError<? extends PluginConfig> parsePlugin(Message rawProtoMessage) {
        return ConfigOrError.fromConfig(new TestPluginConfig());
      }
    });

    com.github.xds.type.v3.TypedStruct typedStruct = com.github.xds.type.v3.TypedStruct.newBuilder()
        .setTypeUrl("type.googleapis.com/google.protobuf.Empty")
        .setValue(Struct.newBuilder())
        .build();
    io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin pluginProto =
        io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin.newBuilder()
            .setExtension(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(typedStruct)))
            .build();

    PluginConfig pluginConfig = ClientXdsClient.parseClusterSpecifierPlugin(pluginProto, registry);
    assertThat(pluginConfig).isInstanceOf(TestPluginConfig.class);
  }

  @Test
  public void parseClusterSpecifierPlugin_unregisteredPlugin() throws Exception {
    ClusterSpecifierPluginRegistry registry = ClusterSpecifierPluginRegistry.newRegistry();
    io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin pluginProto =
        io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin.newBuilder()
            .setExtension(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(StringValue.of("unregistered"))))
            .build();

    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "Unsupported ClusterSpecifierPlugin type: type.googleapis.com/google.protobuf.StringValue");

    ClientXdsClient.parseClusterSpecifierPlugin(pluginProto, registry);
  }

  @Test
  public void parseCluster_ringHashLbPolicy_defaultLbConfig() throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder()
        .setName("cluster-foo.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.getDefaultInstance()))
                .setServiceName("service-foo.googleapis.com"))
        .setLbPolicy(LbPolicy.RING_HASH)
        .build();

    CdsUpdate update = ClientXdsClient.processCluster(
        cluster, new HashSet<String>(), null, LRS_SERVER_INFO);
    assertThat(update.lbPolicy()).isEqualTo(CdsUpdate.LbPolicy.RING_HASH);
    assertThat(update.minRingSize())
        .isEqualTo(ClientXdsClient.DEFAULT_RING_HASH_LB_POLICY_MIN_RING_SIZE);
    assertThat(update.maxRingSize())
        .isEqualTo(ClientXdsClient.DEFAULT_RING_HASH_LB_POLICY_MAX_RING_SIZE);
  }

  @Test
  public void parseCluster_leastRequestLbPolicy_defaultLbConfig() throws ResourceInvalidException {
    ClientXdsClient.enableLeastRequest = true;
    Cluster cluster = Cluster.newBuilder()
        .setName("cluster-foo.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.getDefaultInstance()))
                .setServiceName("service-foo.googleapis.com"))
        .setLbPolicy(LbPolicy.LEAST_REQUEST)
        .build();

    CdsUpdate update = ClientXdsClient.processCluster(
        cluster, new HashSet<String>(), null, LRS_SERVER_INFO);
    assertThat(update.lbPolicy()).isEqualTo(CdsUpdate.LbPolicy.LEAST_REQUEST);
    assertThat(update.choiceCount())
        .isEqualTo(ClientXdsClient.DEFAULT_LEAST_REQUEST_CHOICE_COUNT);
  }

  @Test
  public void parseCluster_transportSocketMatches_exception() throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder()
        .setName("cluster-foo.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.getDefaultInstance()))
                .setServiceName("service-foo.googleapis.com"))
        .setLbPolicy(LbPolicy.ROUND_ROBIN)
        .addTransportSocketMatches(
            Cluster.TransportSocketMatch.newBuilder().setName("match1").build())
        .build();

    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "Cluster cluster-foo.googleapis.com: transport-socket-matches not supported.");
    ClientXdsClient.processCluster(cluster, new HashSet<String>(), null, LRS_SERVER_INFO);
  }

  @Test
  public void parseCluster_ringHashLbPolicy_invalidRingSizeConfig_minGreaterThanMax()
      throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder()
        .setName("cluster-foo.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.getDefaultInstance()))
                .setServiceName("service-foo.googleapis.com"))
        .setLbPolicy(LbPolicy.RING_HASH)
        .setRingHashLbConfig(
            RingHashLbConfig.newBuilder()
                .setHashFunction(HashFunction.XX_HASH)
                .setMinimumRingSize(UInt64Value.newBuilder().setValue(1000L))
                .setMaximumRingSize(UInt64Value.newBuilder().setValue(100L)))
        .build();

    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("Cluster cluster-foo.googleapis.com: invalid ring_hash_lb_config");
    ClientXdsClient.processCluster(cluster, new HashSet<String>(), null, LRS_SERVER_INFO);
  }

  @Test
  public void parseCluster_ringHashLbPolicy_invalidRingSizeConfig_tooLargeRingSize()
      throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder()
        .setName("cluster-foo.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.getDefaultInstance()))
                .setServiceName("service-foo.googleapis.com"))
        .setLbPolicy(LbPolicy.RING_HASH)
        .setRingHashLbConfig(
            RingHashLbConfig.newBuilder()
                .setHashFunction(HashFunction.XX_HASH)
                .setMinimumRingSize(UInt64Value.newBuilder().setValue(1000L))
                .setMaximumRingSize(
                    UInt64Value.newBuilder()
                        .setValue(ClientXdsClient.MAX_RING_HASH_LB_POLICY_RING_SIZE + 1)))
        .build();

    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("Cluster cluster-foo.googleapis.com: invalid ring_hash_lb_config");
    ClientXdsClient.processCluster(cluster, new HashSet<String>(), null, LRS_SERVER_INFO);
  }

  @Test
  public void parseCluster_leastRequestLbPolicy_invalidChoiceCountConfig_tooSmallChoiceCount()
      throws ResourceInvalidException {
    ClientXdsClient.enableLeastRequest = true;
    Cluster cluster = Cluster.newBuilder()
        .setName("cluster-foo.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.getDefaultInstance()))
                .setServiceName("service-foo.googleapis.com"))
        .setLbPolicy(LbPolicy.LEAST_REQUEST)
        .setLeastRequestLbConfig(
            LeastRequestLbConfig.newBuilder()
                .setChoiceCount(UInt32Value.newBuilder().setValue(1))
        )
        .build();

    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("Cluster cluster-foo.googleapis.com: invalid least_request_lb_config");
    ClientXdsClient.processCluster(cluster, new HashSet<String>(), null, LRS_SERVER_INFO);
  }

  @Test
  public void parseCluster_validateEdsSourceConfig() throws ResourceInvalidException {
    Set<String> retainedEdsResources = new HashSet<>();
    Cluster cluster1 = Cluster.newBuilder()
        .setName("cluster-foo.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.getDefaultInstance()))
                .setServiceName("service-foo.googleapis.com"))
        .setLbPolicy(LbPolicy.ROUND_ROBIN)
        .build();
    ClientXdsClient.processCluster(cluster1, retainedEdsResources, null, LRS_SERVER_INFO);

    Cluster cluster2 = Cluster.newBuilder()
        .setName("cluster-foo.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setSelf(SelfConfigSource.getDefaultInstance()))
                .setServiceName("service-foo.googleapis.com"))
        .setLbPolicy(LbPolicy.ROUND_ROBIN)
        .build();
    ClientXdsClient.processCluster(cluster2, retainedEdsResources, null, LRS_SERVER_INFO);

    Cluster cluster3 = Cluster.newBuilder()
        .setName("cluster-foo.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setPath("foo-path"))
                .setServiceName("service-foo.googleapis.com"))
        .setLbPolicy(LbPolicy.ROUND_ROBIN)
        .build();

    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "Cluster cluster-foo.googleapis.com: field eds_cluster_config must be set to indicate to"
            + " use EDS over ADS or self ConfigSource");
    ClientXdsClient.processCluster(cluster3, retainedEdsResources, null, LRS_SERVER_INFO);
  }

  @Test
  public void parseServerSideListener_invalidTrafficDirection() throws ResourceInvalidException {
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.OUTBOUND)
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("Listener listener1 with invalid traffic direction: OUTBOUND");
    ClientXdsClient.parseServerSideListener(
        listener, new HashSet<String>(), null, filterRegistry, null, true /* does not matter */);
  }

  @Test
  public void parseServerSideListener_listenerFiltersPresent() throws ResourceInvalidException {
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .addListenerFilters(ListenerFilter.newBuilder().build())
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("Listener listener1 cannot have listener_filters");
    ClientXdsClient.parseServerSideListener(
        listener, new HashSet<String>(), null, filterRegistry, null, true /* does not matter */);
  }

  @Test
  public void parseServerSideListener_useOriginalDst() throws ResourceInvalidException {
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .setUseOriginalDst(BoolValue.of(true))
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("Listener listener1 cannot have use_original_dst set to true");
    ClientXdsClient.parseServerSideListener(
        listener, new HashSet<String>(), null, filterRegistry, null, true /* does not matter */);
  }

  @Test
  public void parseServerSideListener_nonUniqueFilterChainMatch() throws ResourceInvalidException {
    Filter filter1 = buildHttpConnectionManagerFilter(
        HttpFilter.newBuilder().setName("http-filter-1").setTypedConfig(
            Any.pack(Router.newBuilder().build())).setIsOptional(true).build());
    FilterChainMatch filterChainMatch1 =
        FilterChainMatch.newBuilder()
            .addAllSourcePorts(Arrays.asList(80, 8080))
            .addAllPrefixRanges(Arrays.asList(CidrRange.newBuilder().setAddressPrefix("192.168.0.0")
                    .setPrefixLen(UInt32Value.of(16)).build(),
                CidrRange.newBuilder().setAddressPrefix("10.0.0.0").setPrefixLen(UInt32Value.of(8))
                    .build()))
            .build();
    FilterChain filterChain1 =
        FilterChain.newBuilder()
            .setName("filter-chain-1")
            .setFilterChainMatch(filterChainMatch1)
            .addFilters(filter1)
            .build();
    Filter filter2 = buildHttpConnectionManagerFilter(
        HttpFilter.newBuilder().setName("http-filter-2").setTypedConfig(
            Any.pack(Router.newBuilder().build())).setIsOptional(true).build());
    FilterChainMatch filterChainMatch2 =
        FilterChainMatch.newBuilder()
            .addAllSourcePorts(Arrays.asList(443, 8080))
            .addAllPrefixRanges(Arrays.asList(
                CidrRange.newBuilder().setAddressPrefix("2001:DB8::8:800:200C:417A")
                    .setPrefixLen(UInt32Value.of(60)).build(),
                CidrRange.newBuilder().setAddressPrefix("192.168.0.0")
                    .setPrefixLen(UInt32Value.of(16)).build()))
            .build();
    FilterChain filterChain2 =
        FilterChain.newBuilder()
            .setName("filter-chain-2")
            .setFilterChainMatch(filterChainMatch2)
            .addFilters(filter2)
            .build();
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .addAllFilterChains(Arrays.asList(filterChain1, filterChain2))
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("FilterChainMatch must be unique. Found duplicate:");
    ClientXdsClient.parseServerSideListener(
        listener, new HashSet<String>(), null, filterRegistry, null, true /* does not matter */);
  }

  @Test
  public void parseServerSideListener_nonUniqueFilterChainMatch_sameFilter()
      throws ResourceInvalidException {
    Filter filter1 = buildHttpConnectionManagerFilter(
        HttpFilter.newBuilder().setName("http-filter-1").setTypedConfig(
            Any.pack(Router.newBuilder().build())).setIsOptional(true).build());
    FilterChainMatch filterChainMatch1 =
        FilterChainMatch.newBuilder()
            .addAllSourcePorts(Arrays.asList(80, 8080))
            .addAllPrefixRanges(Arrays.asList(
                CidrRange.newBuilder().setAddressPrefix("10.0.0.0").setPrefixLen(UInt32Value.of(8))
                    .build()))
            .build();
    FilterChain filterChain1 =
        FilterChain.newBuilder()
            .setName("filter-chain-1")
            .setFilterChainMatch(filterChainMatch1)
            .addFilters(filter1)
            .build();
    Filter filter2 = buildHttpConnectionManagerFilter(
        HttpFilter.newBuilder().setName("http-filter-2").setTypedConfig(
            Any.pack(Router.newBuilder().build())).setIsOptional(true).build());
    FilterChainMatch filterChainMatch2 =
        FilterChainMatch.newBuilder()
            .addAllSourcePorts(Arrays.asList(443, 8080))
            .addAllPrefixRanges(Arrays.asList(
                CidrRange.newBuilder().setAddressPrefix("192.168.0.0")
                    .setPrefixLen(UInt32Value.of(16)).build(),
                CidrRange.newBuilder().setAddressPrefix("192.168.0.0")
                    .setPrefixLen(UInt32Value.of(16)).build()))
            .build();
    FilterChain filterChain2 =
        FilterChain.newBuilder()
            .setName("filter-chain-2")
            .setFilterChainMatch(filterChainMatch2)
            .addFilters(filter2)
            .build();
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .addAllFilterChains(Arrays.asList(filterChain1, filterChain2))
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("FilterChainMatch must be unique. Found duplicate:");
    ClientXdsClient.parseServerSideListener(
        listener, new HashSet<String>(), null, filterRegistry, null, true /* does not matter */);
  }

  @Test
  public void parseServerSideListener_uniqueFilterChainMatch() throws ResourceInvalidException {
    Filter filter1 = buildHttpConnectionManagerFilter(
        HttpFilter.newBuilder().setName("http-filter-1").setTypedConfig(
            Any.pack(Router.newBuilder().build())).setIsOptional(true).build());
    FilterChainMatch filterChainMatch1 =
        FilterChainMatch.newBuilder()
            .addAllSourcePorts(Arrays.asList(80, 8080))
            .addAllPrefixRanges(Arrays.asList(CidrRange.newBuilder().setAddressPrefix("192.168.0.0")
                    .setPrefixLen(UInt32Value.of(16)).build(),
                CidrRange.newBuilder().setAddressPrefix("10.0.0.0").setPrefixLen(UInt32Value.of(8))
                    .build()))
            .setSourceType(FilterChainMatch.ConnectionSourceType.EXTERNAL)
            .build();
    FilterChain filterChain1 =
        FilterChain.newBuilder()
            .setName("filter-chain-1")
            .setFilterChainMatch(filterChainMatch1)
            .addFilters(filter1)
            .build();
    Filter filter2 = buildHttpConnectionManagerFilter(
        HttpFilter.newBuilder().setName("http-filter-2").setTypedConfig(
            Any.pack(Router.newBuilder().build())).setIsOptional(true).build());
    FilterChainMatch filterChainMatch2 =
        FilterChainMatch.newBuilder()
            .addAllSourcePorts(Arrays.asList(443, 8080))
            .addAllPrefixRanges(Arrays.asList(
                CidrRange.newBuilder().setAddressPrefix("2001:DB8::8:800:200C:417A")
                    .setPrefixLen(UInt32Value.of(60)).build(),
                CidrRange.newBuilder().setAddressPrefix("192.168.0.0")
                    .setPrefixLen(UInt32Value.of(16)).build()))
            .setSourceType(FilterChainMatch.ConnectionSourceType.ANY)
            .build();
    FilterChain filterChain2 =
        FilterChain.newBuilder()
            .setName("filter-chain-2")
            .setFilterChainMatch(filterChainMatch2)
            .addFilters(filter2)
            .build();
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .addAllFilterChains(Arrays.asList(filterChain1, filterChain2))
            .build();
    ClientXdsClient.parseServerSideListener(
        listener, new HashSet<String>(), null, filterRegistry, null, true /* does not matter */);
  }

  @Test
  public void parseFilterChain_noHcm() throws ResourceInvalidException {
    FilterChain filterChain =
        FilterChain.newBuilder()
            .setName("filter-chain-foo")
            .setFilterChainMatch(FilterChainMatch.getDefaultInstance())
            .setTransportSocket(TransportSocket.getDefaultInstance())
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "FilterChain filter-chain-foo should contain exact one HttpConnectionManager filter");
    ClientXdsClient.parseFilterChain(
        filterChain, new HashSet<String>(), null, filterRegistry, null, null,
        true /* does not matter */);
  }

  @Test
  public void parseFilterChain_duplicateFilter() throws ResourceInvalidException {
    Filter filter = buildHttpConnectionManagerFilter(
        HttpFilter.newBuilder().setName("http-filter-foo").setIsOptional(true).build());
    FilterChain filterChain =
        FilterChain.newBuilder()
            .setName("filter-chain-foo")
            .setFilterChainMatch(FilterChainMatch.getDefaultInstance())
            .setTransportSocket(TransportSocket.getDefaultInstance())
            .addAllFilters(Arrays.asList(filter, filter))
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "FilterChain filter-chain-foo should contain exact one HttpConnectionManager filter");
    ClientXdsClient.parseFilterChain(
        filterChain, new HashSet<String>(), null, filterRegistry, null, null,
        true /* does not matter */);
  }

  @Test
  public void parseFilterChain_filterMissingTypedConfig() throws ResourceInvalidException {
    Filter filter = Filter.newBuilder().setName("envoy.http_connection_manager").build();
    FilterChain filterChain =
        FilterChain.newBuilder()
            .setName("filter-chain-foo")
            .setFilterChainMatch(FilterChainMatch.getDefaultInstance())
            .setTransportSocket(TransportSocket.getDefaultInstance())
            .addFilters(filter)
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "FilterChain filter-chain-foo contains filter envoy.http_connection_manager "
            + "without typed_config");
    ClientXdsClient.parseFilterChain(
        filterChain, new HashSet<String>(), null, filterRegistry, null, null,
        true /* does not matter */);
  }

  @Test
  public void parseFilterChain_unsupportedFilter() throws ResourceInvalidException {
    Filter filter =
        Filter.newBuilder()
            .setName("unsupported")
            .setTypedConfig(Any.newBuilder().setTypeUrl("unsupported-type-url"))
            .build();
    FilterChain filterChain =
        FilterChain.newBuilder()
            .setName("filter-chain-foo")
            .setFilterChainMatch(FilterChainMatch.getDefaultInstance())
            .setTransportSocket(TransportSocket.getDefaultInstance())
            .addFilters(filter)
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "FilterChain filter-chain-foo contains filter unsupported with unsupported "
            + "typed_config type unsupported-type-url");
    ClientXdsClient.parseFilterChain(
        filterChain, new HashSet<String>(), null, filterRegistry, null, null,
        true /* does not matter */);
  }

  @Test
  public void parseFilterChain_noName() throws ResourceInvalidException {
    FilterChain filterChain1 =
        FilterChain.newBuilder()
            .setFilterChainMatch(FilterChainMatch.getDefaultInstance())
            .addFilters(buildHttpConnectionManagerFilter(
                HttpFilter.newBuilder()
                    .setName("http-filter-foo")
                    .setIsOptional(true)
                    .setTypedConfig(Any.pack(Router.newBuilder().build()))
                    .build()))
            .build();
    FilterChain filterChain2 =
        FilterChain.newBuilder()
            .setFilterChainMatch(FilterChainMatch.getDefaultInstance())
            .addFilters(buildHttpConnectionManagerFilter(
                HttpFilter.newBuilder()
                    .setName("http-filter-bar")
                    .setTypedConfig(Any.pack(Router.newBuilder().build()))
                    .setIsOptional(true)
                    .build()))
            .build();

    EnvoyServerProtoData.FilterChain parsedFilterChain1 = ClientXdsClient.parseFilterChain(
        filterChain1, new HashSet<String>(), null, filterRegistry, null,
        null, true /* does not matter */);
    EnvoyServerProtoData.FilterChain parsedFilterChain2 = ClientXdsClient.parseFilterChain(
        filterChain2, new HashSet<String>(), null, filterRegistry, null,
        null, true /* does not matter */);
    assertThat(parsedFilterChain1.name()).isEqualTo(parsedFilterChain2.name());
  }

  @Test
  public void validateCommonTlsContext_tlsParams() throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
            .setTlsParams(TlsParameters.getDefaultInstance())
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("common-tls-context with tls_params is not supported");
    ClientXdsClient.validateCommonTlsContext(commonTlsContext, null, false);
  }

  @Test
  public void validateCommonTlsContext_customHandshaker() throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
            .setCustomHandshaker(TypedExtensionConfig.getDefaultInstance())
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("common-tls-context with custom_handshaker is not supported");
    ClientXdsClient.validateCommonTlsContext(commonTlsContext, null, false);
  }

  @Test
  public void validateCommonTlsContext_validationContext() throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
            .setValidationContext(CertificateValidationContext.getDefaultInstance())
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("ca_certificate_provider_instance is required in upstream-tls-context");
    ClientXdsClient.validateCommonTlsContext(commonTlsContext, null, false);
  }

  @Test
  public void validateCommonTlsContext_validationContextSdsSecretConfig()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setValidationContextSdsSecretConfig(SdsSecretConfig.getDefaultInstance())
        .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "common-tls-context with validation_context_sds_secret_config is not supported");
    ClientXdsClient.validateCommonTlsContext(commonTlsContext, null, false);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void validateCommonTlsContext_validationContextCertificateProvider()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setValidationContextCertificateProvider(
            CommonTlsContext.CertificateProvider.getDefaultInstance())
        .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "common-tls-context with validation_context_certificate_provider is not supported");
    ClientXdsClient.validateCommonTlsContext(commonTlsContext, null, false);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void validateCommonTlsContext_validationContextCertificateProviderInstance()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setValidationContextCertificateProviderInstance(
            CommonTlsContext.CertificateProviderInstance.getDefaultInstance())
        .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "common-tls-context with validation_context_certificate_provider_instance is not "
            + "supported");
    ClientXdsClient.validateCommonTlsContext(commonTlsContext, null, false);
  }

  @Test
  public void validateCommonTlsContext_tlsCertificateProviderInstance_isRequiredForServer()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "tls_certificate_provider_instance is required in downstream-tls-context");
    ClientXdsClient.validateCommonTlsContext(commonTlsContext, null, true);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void validateCommonTlsContext_tlsNewCertificateProviderInstance()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setTlsCertificateProviderInstance(
            CertificateProviderPluginInstance.newBuilder().setInstanceName("name1").build())
        .build();
    ClientXdsClient
        .validateCommonTlsContext(commonTlsContext, ImmutableSet.of("name1", "name2"), true);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void validateCommonTlsContext_tlsCertificateProviderInstance()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setTlsCertificateCertificateProviderInstance(
            CertificateProviderInstance.newBuilder().setInstanceName("name1").build())
        .build();
    ClientXdsClient
        .validateCommonTlsContext(commonTlsContext, ImmutableSet.of("name1", "name2"), true);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void validateCommonTlsContext_tlsCertificateProviderInstance_absentInBootstrapFile()
          throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setTlsCertificateCertificateProviderInstance(
            CertificateProviderInstance.newBuilder().setInstanceName("bad-name").build())
        .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "CertificateProvider instance name 'bad-name' not defined in the bootstrap file.");
    ClientXdsClient
        .validateCommonTlsContext(commonTlsContext, ImmutableSet.of("name1", "name2"), true);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void validateCommonTlsContext_validationContextProviderInstance()
          throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
                .setValidationContextCertificateProviderInstance(
                    CertificateProviderInstance.newBuilder().setInstanceName("name1").build())
                .build())
        .build();
    ClientXdsClient
        .validateCommonTlsContext(commonTlsContext, ImmutableSet.of("name1", "name2"), false);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void validateCommonTlsContext_validationContextProviderInstance_absentInBootstrapFile()
          throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
                .setValidationContextCertificateProviderInstance(
                    CertificateProviderInstance.newBuilder().setInstanceName("bad-name").build())
                .build())
        .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "ca_certificate_provider_instance name 'bad-name' not defined in the bootstrap file.");
    ClientXdsClient
        .validateCommonTlsContext(commonTlsContext, ImmutableSet.of("name1", "name2"), false);
  }


  @Test
  public void validateCommonTlsContext_tlsCertificatesCount() throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
            .addTlsCertificates(TlsCertificate.getDefaultInstance())
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("tls_certificate_provider_instance is unset");
    ClientXdsClient.validateCommonTlsContext(commonTlsContext, null, false);
  }

  @Test
  public void validateCommonTlsContext_tlsCertificateSdsSecretConfigsCount()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .addTlsCertificateSdsSecretConfigs(SdsSecretConfig.getDefaultInstance())
        .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "tls_certificate_provider_instance is unset");
    ClientXdsClient.validateCommonTlsContext(commonTlsContext, null, false);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void validateCommonTlsContext_tlsCertificateCertificateProvider()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setTlsCertificateCertificateProvider(
            CommonTlsContext.CertificateProvider.getDefaultInstance())
        .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "tls_certificate_provider_instance is unset");
    ClientXdsClient.validateCommonTlsContext(commonTlsContext, null, false);
  }

  @Test
  public void validateCommonTlsContext_combinedValidationContext_isRequiredForClient()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("ca_certificate_provider_instance is required in upstream-tls-context");
    ClientXdsClient.validateCommonTlsContext(commonTlsContext, null, false);
  }

  @Test
  public void validateCommonTlsContext_combinedValidationContextWithoutCertProviderInstance()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.getDefaultInstance())
        .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "ca_certificate_provider_instance is required in upstream-tls-context");
    ClientXdsClient.validateCommonTlsContext(commonTlsContext, null, false);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void validateCommonTlsContext_combinedValContextWithDefaultValContextForServer()
      throws ResourceInvalidException, InvalidProtocolBufferException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CombinedCertificateValidationContext.newBuilder()
                .setValidationContextCertificateProviderInstance(
                    CertificateProviderInstance.getDefaultInstance())
                .setDefaultValidationContext(CertificateValidationContext.newBuilder()
                    .addMatchSubjectAltNames(StringMatcher.newBuilder().setExact("foo.com").build())
                    .build()))
        .setTlsCertificateCertificateProviderInstance(
            CertificateProviderInstance.getDefaultInstance())
        .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("match_subject_alt_names only allowed in upstream_tls_context");
    ClientXdsClient.validateCommonTlsContext(commonTlsContext, ImmutableSet.of(""), true);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void validateCommonTlsContext_combinedValContextWithDefaultValContextVerifyCertSpki()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
                .setValidationContextCertificateProviderInstance(
                    CommonTlsContext.CertificateProviderInstance.getDefaultInstance())
                .setDefaultValidationContext(
                    CertificateValidationContext.newBuilder().addVerifyCertificateSpki("foo")))
        .setTlsCertificateCertificateProviderInstance(
            CommonTlsContext.CertificateProviderInstance.getDefaultInstance())
        .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("verify_certificate_spki in default_validation_context is not "
        + "supported");
    ClientXdsClient.validateCommonTlsContext(commonTlsContext, ImmutableSet.of(""), false);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void validateCommonTlsContext_combinedValContextWithDefaultValContextVerifyCertHash()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
                .setValidationContextCertificateProviderInstance(
                    CommonTlsContext.CertificateProviderInstance.getDefaultInstance())
                .setDefaultValidationContext(
                    CertificateValidationContext.newBuilder().addVerifyCertificateHash("foo")))
        .setTlsCertificateCertificateProviderInstance(
            CommonTlsContext.CertificateProviderInstance.getDefaultInstance())
        .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("verify_certificate_hash in default_validation_context is not "
        + "supported");
    ClientXdsClient.validateCommonTlsContext(commonTlsContext, ImmutableSet.of(""), false);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void validateCommonTlsContext_combinedValContextDfltValContextRequireSignedCertTimestamp()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
                .setValidationContextCertificateProviderInstance(
                    CommonTlsContext.CertificateProviderInstance.getDefaultInstance())
                .setDefaultValidationContext(CertificateValidationContext.newBuilder()
                    .setRequireSignedCertificateTimestamp(BoolValue.of(true))))
        .setTlsCertificateCertificateProviderInstance(
            CommonTlsContext.CertificateProviderInstance.getDefaultInstance())
        .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "require_signed_certificate_timestamp in default_validation_context is not "
            + "supported");
    ClientXdsClient.validateCommonTlsContext(commonTlsContext, ImmutableSet.of(""), false);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void validateCommonTlsContext_combinedValidationContextWithDefaultValidationContextCrl()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
                .setValidationContextCertificateProviderInstance(
                    CommonTlsContext.CertificateProviderInstance.getDefaultInstance())
                .setDefaultValidationContext(CertificateValidationContext.newBuilder()
                    .setCrl(DataSource.getDefaultInstance())))
        .setTlsCertificateCertificateProviderInstance(
            CommonTlsContext.CertificateProviderInstance.getDefaultInstance())
        .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("crl in default_validation_context is not supported");
    ClientXdsClient.validateCommonTlsContext(commonTlsContext, ImmutableSet.of(""), false);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void validateCommonTlsContext_combinedValContextWithDfltValContextCustomValidatorConfig()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
                .setValidationContextCertificateProviderInstance(
                    CommonTlsContext.CertificateProviderInstance.getDefaultInstance())
                .setDefaultValidationContext(CertificateValidationContext.newBuilder()
                    .setCustomValidatorConfig(TypedExtensionConfig.getDefaultInstance())))
        .setTlsCertificateCertificateProviderInstance(
            CommonTlsContext.CertificateProviderInstance.getDefaultInstance())
        .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("custom_validator_config in default_validation_context is not "
        + "supported");
    ClientXdsClient.validateCommonTlsContext(commonTlsContext, ImmutableSet.of(""), false);
  }

  @Test
  public void validateDownstreamTlsContext_noCommonTlsContext() throws ResourceInvalidException {
    DownstreamTlsContext downstreamTlsContext = DownstreamTlsContext.getDefaultInstance();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("common-tls-context is required in downstream-tls-context");
    ClientXdsClient.validateDownstreamTlsContext(downstreamTlsContext, null);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void validateDownstreamTlsContext_hasRequireSni() throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
                .setValidationContextCertificateProviderInstance(
                    CommonTlsContext.CertificateProviderInstance.getDefaultInstance()))
        .setTlsCertificateCertificateProviderInstance(
            CommonTlsContext.CertificateProviderInstance.getDefaultInstance())
        .build();
    DownstreamTlsContext downstreamTlsContext = DownstreamTlsContext.newBuilder()
        .setCommonTlsContext(commonTlsContext)
        .setRequireSni(BoolValue.of(true))
        .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("downstream-tls-context with require-sni is not supported");
    ClientXdsClient.validateDownstreamTlsContext(downstreamTlsContext, ImmutableSet.of(""));
  }

  @Test
  @SuppressWarnings("deprecation")
  public void validateDownstreamTlsContext_hasOcspStaplePolicy() throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
                .setValidationContextCertificateProviderInstance(
                    CommonTlsContext.CertificateProviderInstance.getDefaultInstance()))
        .setTlsCertificateCertificateProviderInstance(
            CommonTlsContext.CertificateProviderInstance.getDefaultInstance())
        .build();
    DownstreamTlsContext downstreamTlsContext = DownstreamTlsContext.newBuilder()
        .setCommonTlsContext(commonTlsContext)
        .setOcspStaplePolicy(DownstreamTlsContext.OcspStaplePolicy.STRICT_STAPLING)
        .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "downstream-tls-context with ocsp_staple_policy value STRICT_STAPLING is not supported");
    ClientXdsClient.validateDownstreamTlsContext(downstreamTlsContext, ImmutableSet.of(""));
  }

  @Test
  public void validateUpstreamTlsContext_noCommonTlsContext() throws ResourceInvalidException {
    UpstreamTlsContext upstreamTlsContext = UpstreamTlsContext.getDefaultInstance();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("common-tls-context is required in upstream-tls-context");
    ClientXdsClient.validateUpstreamTlsContext(upstreamTlsContext, null);
  }

  @Test
  public void validateResourceName() {
    String traditionalResource = "cluster1.google.com";
    assertThat(XdsClient.isResourceNameValid(traditionalResource, ResourceType.CDS.typeUrl()))
        .isTrue();
    assertThat(XdsClient.isResourceNameValid(traditionalResource, ResourceType.RDS.typeUrlV2()))
        .isTrue();

    String invalidPath = "xdstp:/abc/efg";
    assertThat(XdsClient.isResourceNameValid(invalidPath, ResourceType.CDS.typeUrl())).isFalse();

    String invalidPath2 = "xdstp:///envoy.config.route.v3.RouteConfiguration";
    assertThat(XdsClient.isResourceNameValid(invalidPath2, ResourceType.RDS.typeUrl())).isFalse();

    String typeMatch = "xdstp:///envoy.config.route.v3.RouteConfiguration/foo/route1";
    assertThat(XdsClient.isResourceNameValid(typeMatch, ResourceType.LDS.typeUrl())).isFalse();
    assertThat(XdsClient.isResourceNameValid(typeMatch, ResourceType.RDS.typeUrl())).isTrue();
    assertThat(XdsClient.isResourceNameValid(typeMatch, ResourceType.RDS.typeUrlV2())).isFalse();
  }

  @Test
  public void canonifyResourceName() {
    String traditionalResource = "cluster1.google.com";
    assertThat(XdsClient.canonifyResourceName(traditionalResource))
        .isEqualTo(traditionalResource);
    assertThat(XdsClient.canonifyResourceName(traditionalResource))
        .isEqualTo(traditionalResource);
    assertThat(XdsClient.canonifyResourceName(traditionalResource))
        .isEqualTo(traditionalResource);
    assertThat(XdsClient.canonifyResourceName(traditionalResource))
        .isEqualTo(traditionalResource);

    String withNoQueries = "xdstp:///envoy.config.route.v3.RouteConfiguration/foo/route1";
    assertThat(XdsClient.canonifyResourceName(withNoQueries)).isEqualTo(withNoQueries);

    String withOneQueries = "xdstp:///envoy.config.route.v3.RouteConfiguration/foo/route1?name=foo";
    assertThat(XdsClient.canonifyResourceName(withOneQueries)).isEqualTo(withOneQueries);

    String withTwoQueries = "xdstp:///envoy.config.route.v3.RouteConfiguration/id/route1?b=1&a=1";
    String expectedCanonifiedName =
        "xdstp:///envoy.config.route.v3.RouteConfiguration/id/route1?a=1&b=1";
    assertThat(XdsClient.canonifyResourceName(withTwoQueries))
        .isEqualTo(expectedCanonifiedName);
  }

  /**
   *  Tests compliance with RFC 3986 section 3.3
   *  https://datatracker.ietf.org/doc/html/rfc3986#section-3.3
   */
  @Test
  public void percentEncodePath()  {
    String unreserved = "aAzZ09-._~";
    assertThat(XdsClient.percentEncodePath(unreserved)).isEqualTo(unreserved);

    String subDelims = "!$&'(*+,;/=";
    assertThat(XdsClient.percentEncodePath(subDelims)).isEqualTo(subDelims);

    String colonAndAt = ":@";
    assertThat(XdsClient.percentEncodePath(colonAndAt)).isEqualTo(colonAndAt);

    String needBeEncoded = "?#[]";
    assertThat(XdsClient.percentEncodePath(needBeEncoded)).isEqualTo("%3F%23%5B%5D");

    String ipv4 = "0.0.0.0:8080";
    assertThat(XdsClient.percentEncodePath(ipv4)).isEqualTo(ipv4);

    String ipv6 = "[::1]:8080";
    assertThat(XdsClient.percentEncodePath(ipv6)).isEqualTo("%5B::1%5D:8080");
  }

  private static Filter buildHttpConnectionManagerFilter(HttpFilter... httpFilters) {
    return Filter.newBuilder()
        .setName("envoy.http_connection_manager")
        .setTypedConfig(
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRds(
                        Rds.newBuilder()
                            .setRouteConfigName("route-config.googleapis.com")
                            .setConfigSource(
                                ConfigSource.newBuilder()
                                    .setAds(AggregatedConfigSource.getDefaultInstance())))
                    .addAllHttpFilters(Arrays.asList(httpFilters))
                    .build(),
                "type.googleapis.com"))
        .build();
  }
}
