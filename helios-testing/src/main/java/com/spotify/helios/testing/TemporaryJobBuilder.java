/*
 * Copyright (c) 2014 Spotify AB.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.helios.testing;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.google.common.io.Resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.spotify.helios.common.Json;
import com.spotify.helios.common.descriptors.HealthCheck;
import com.spotify.helios.common.descriptors.HttpHealthCheck;
import com.spotify.helios.common.descriptors.Job;
import com.spotify.helios.common.descriptors.PortMapping;
import com.spotify.helios.common.descriptors.ServiceEndpoint;
import com.spotify.helios.common.descriptors.ServicePorts;
import com.spotify.helios.common.descriptors.TcpHealthCheck;

import org.joda.time.DateTime;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import static com.fasterxml.jackson.databind.node.JsonNodeType.STRING;
import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Optional.fromNullable;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.lang.Integer.toHexString;
import static java.util.Arrays.asList;
import static org.junit.Assert.fail;

public class TemporaryJobBuilder {
  private static final Pattern JOB_NAME_FORBIDDEN_CHARS = Pattern.compile("[^0-9a-zA-Z-_.]+");
  private static final int DEFAULT_EXPIRES_MINUTES = 30;

  private final List<String> hosts = Lists.newArrayList();
  private final Job.Builder builder = Job.newBuilder();
  private final Set<String> waitPorts = Sets.newHashSet();
  private final Deployer deployer;
  private final String jobNamePrefix;
  private final Map<String, String> env;
  
  private String hostFilter;
  private Prober prober;
  private TemporaryJob job;

  public TemporaryJobBuilder(final Deployer deployer, final String jobNamePrefix,
                             final Prober defaultProber, final Map<String, String> env) {
    checkNotNull(deployer, "deployer");
    checkNotNull(jobNamePrefix, "jobNamePrefix");
    checkNotNull(defaultProber, "defaultProber");
    this.deployer = deployer;
    this.jobNamePrefix = jobNamePrefix;
    this.prober = defaultProber;
    this.builder.setRegistrationDomain(jobNamePrefix);
    this.env = env;
  }

  public TemporaryJobBuilder name(final String jobName) {
    this.builder.setName(jobName);
    return this;
  }

  public TemporaryJobBuilder version(final String jobVersion) {
    this.builder.setVersion(jobVersion);
    return this;
  }

  public TemporaryJobBuilder image(final String image) {
    this.builder.setImage(image);
    return this;
  }

  public TemporaryJobBuilder registrationDomain(final String domain) {
    this.builder.setRegistrationDomain(domain);
    return this;
  }

  public TemporaryJobBuilder command(final List<String> command) {
    this.builder.setCommand(command);
    return this;
  }

  public TemporaryJobBuilder command(final String... command) {
    return command(asList(command));
  }

  public TemporaryJobBuilder env(final String key, final Object value) {
    this.builder.addEnv(key, value.toString());
    return this;
  }

  public TemporaryJobBuilder disablePrivateRegistrationDomain() {
    this.builder.setRegistrationDomain(Job.EMPTY_REGISTRATION_DOMAIN);
    return this;
  }

  public TemporaryJobBuilder port(final String name, final int internalPort) {
    return port(name, internalPort, true);
  }

  public TemporaryJobBuilder port(final String name, final int internalPort, final boolean wait) {
    return port(name, internalPort, null, wait);
  }

  public TemporaryJobBuilder port(final String name, final int internalPort,
                                  final Integer externalPort) {
    return port(name, internalPort, externalPort, true);
  }

  public TemporaryJobBuilder port(final String name, final int internalPort,
                                  final Integer externalPort, final boolean wait) {
    return port(name, internalPort, externalPort, wait, "tcp");
  }

  public TemporaryJobBuilder port(final String name, final int internalPort,
                                  final String protocol) {
    return port(name, internalPort, true, protocol);
  }

  private TemporaryJobBuilder port(String name, int internalPort, boolean wait, String protocol) {
    return port(name, internalPort, null, wait, protocol);
  }

  public TemporaryJobBuilder port(final String name, final int internalPort,
                                  final Integer externalPort, final String protocol) {
    return port(name, internalPort, externalPort, true, protocol);
  }

  private TemporaryJobBuilder port(String name, int internalPort, Integer externalPort,
                                   boolean wait, String protocol) {
    this.builder.addPort(name, PortMapping.of(internalPort, externalPort, protocol));
    if (wait) {
      waitPorts.add(name);
    }
    return this;
  }

  public TemporaryJobBuilder registration(final ServiceEndpoint endpoint,
                                          final ServicePorts ports) {
    this.builder.addRegistration(endpoint, ports);
    return this;
  }

  public TemporaryJobBuilder registration(final String service, final String protocol,
                                          final String... ports) {
    return registration(ServiceEndpoint.of(service, protocol), ServicePorts.of(ports));
  }

  public TemporaryJobBuilder registration(final Map<ServiceEndpoint, ServicePorts> registration) {
    this.builder.setRegistration(registration);
    return this;
  }

  public TemporaryJobBuilder volume(final String path, final String source) {
    this.builder.addVolume(path, source);
    return this;
  }

  public TemporaryJobBuilder host(final String host) {
    this.hosts.add(host);
    return this;
  }

  public TemporaryJobBuilder hostFilter(final String hostFilter) {
    this.hostFilter = hostFilter;
    return this;
  }

  /**
   * The Helios master will undeploy and delete the job at the specified date, if it has not
   * already been removed. If not set, jobs will be removed after 30 minutes. This is for the
   * case when a TemporaryJob is not cleaned up properly, perhaps because the process terminated
   * prematurely.
   * @param expires the Date when the job should be removed
   * @return the TemporaryJobBuilder
   */
  public TemporaryJobBuilder expires(final Date expires) {
    this.builder.setExpires(expires);
    return this;
  }

  /**
   * This will override the default prober provided by {@link TemporaryJobs} to the constructor.
   * @param prober the prober to use for this job
   * @return the TemporaryJobBuilder
   */
  public TemporaryJobBuilder prober(final Prober prober) {
    this.prober = prober;
    return this;
  }

  public TemporaryJobBuilder healthCheck(final HealthCheck healthCheck) {
    this.builder.setHealthCheck(healthCheck);
    return this;
  }

  public TemporaryJobBuilder httpHealthCheck(final String port, final String path) {
    this.builder.setHealthCheck(HttpHealthCheck.of(port, path));
    return this;
  }

  public TemporaryJobBuilder tcpHealthCheck(final String port) {
    this.builder.setHealthCheck(TcpHealthCheck.of(port));
    return this;
  }

  /**
   * Deploys the job to the specified hosts. If no hosts are specified, a host will be chosen at
   * random from the current Helios cluster. If the HELIOS_HOST_FILTER environment variable is set,
   * it will be used to filter the list of hosts in the current Helios cluster.
   *
   * @param hosts the list of helios hosts to deploy to. A random host will be chosen if the list is
   *              empty.
   * @return a TemporaryJob representing the deployed job
   */
  public TemporaryJob deploy(final String... hosts) {
    return deploy(asList(hosts));
  }

  /**
   * Deploys the job to the specified hosts. If no hosts are specified, a host will be chosen at
   * random from the current Helios cluster. If the HELIOS_HOST_FILTER environment variable is set,
   * it will be used to filter the list of hosts in the current Helios cluster.
   *
   * @param hosts the list of helios hosts to deploy to. A random host will be chosen if the list is
   *              empty.
   * @return a TemporaryJob representing the deployed job
   */
  public TemporaryJob deploy(final List<String> hosts) {
    this.hosts.addAll(hosts);

    if (job == null) {
      if (builder.getName() == null && builder.getVersion() == null) {
        // Both name and version are unset, use image name as job name and generate random version
        builder.setName(jobName(builder.getImage(), jobNamePrefix));
        builder.setVersion(randomVersion());
      }

      // Set job to expires value, if it's not already set. This ensures temporary jobs which
      // aren't cleaned up properly by the test will be removed by the master.
      if (builder.getExpires() == null) {
        builder.setExpires(new DateTime().plusMinutes(DEFAULT_EXPIRES_MINUTES).toDate());
      }

      // If a health check is specified, there is no need to probe ports. The health check
      // mechanism in the agent will make sure the container is ready.
      if (builder.getHealthCheck() != null) {
        waitPorts.clear();
      }

      if (this.hosts.isEmpty()) {
        if (isNullOrEmpty(hostFilter)) {
          hostFilter = env.get("HELIOS_HOST_FILTER");
        }

        job = deployer.deploy(builder.build(), hostFilter, waitPorts, prober);
      } else {
        job = deployer.deploy(builder.build(), this.hosts, waitPorts, prober);
      }
    }

    return job;
  }

  public TemporaryJobBuilder imageFromBuild() {
    final String envPath = env.get("IMAGE_INFO_PATH");
    if (envPath != null) {
      return imageFromInfoFile(envPath);
    } else {
      final String name = fromNullable(env.get("IMAGE_INFO_NAME")).or("image_info.json");
      URL info;
      try {
        info = Resources.getResource(name);
      } catch (IllegalArgumentException e) {
        info = getFromFileSystem(name);
        if (info == null) {
          throw e;
        }
      }

      try {
        final String json = Resources.asCharSource(info, UTF_8).read();
        return imageFromInfoJson(json, info.toString());
      } catch (IOException e) {
        throw new AssertionError("Failed to load image info", e);
      }
    }
  }

  private URL getFromFileSystem(String name) {
    final File file = new File("target/" + name);
    if (!file.exists()) {
      return null;
    }

    try {
      return file.toURI().toURL();
    } catch (MalformedURLException e) {
      throw new RuntimeException("TEST");
    }
  }

  public TemporaryJobBuilder imageFromInfoFile(final Path path) {
    return imageFromInfoFile(path.toFile());
  }

  public TemporaryJobBuilder imageFromInfoFile(final String path) {
    return imageFromInfoFile(new File(path));
  }

  public TemporaryJobBuilder imageFromInfoFile(final File file) {
    final String json;
    try {
      json = Files.toString(file, UTF_8);
    } catch (IOException e) {
      throw new AssertionError("Failed to read image info file: " +
                               file + ": " + e.getMessage());
    }
    return imageFromInfoJson(json, file.toString());
  }

  private TemporaryJobBuilder imageFromInfoJson(final String json,
                                                final String source) {
    try {
      final JsonNode info = Json.readTree(json);
      final JsonNode imageNode = info.get("image");
      if (imageNode == null) {
        fail("Missing image field in image info: " + source);
      }
      if (imageNode.getNodeType() != STRING) {
        fail("Bad image field in image info: " + source);
      }
      final String image = imageNode.asText();
      return image(image);
    } catch (IOException e) {
      throw new AssertionError("Failed to parse image info: " + source, e);
    }
  }

  private String jobName(final String s, final String jobNamePrefix) {
    return jobNamePrefix + "_" + JOB_NAME_FORBIDDEN_CHARS.matcher(s).replaceAll("_");
  }

  private String randomVersion() {
    return toHexString(ThreadLocalRandom.current().nextInt());
  }
}
