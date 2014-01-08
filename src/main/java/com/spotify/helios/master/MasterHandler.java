/**
 * Copyright (C) 2012 Spotify AB
 */

package com.spotify.helios.master;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.spotify.helios.common.AgentDoesNotExistException;
import com.spotify.helios.common.HeliosException;
import com.spotify.helios.common.JobAlreadyDeployedException;
import com.spotify.helios.common.JobDoesNotExistException;
import com.spotify.helios.common.JobExistsException;
import com.spotify.helios.common.JobNotDeployedException;
import com.spotify.helios.common.JobPortAllocationConflictException;
import com.spotify.helios.common.JobStillInUseException;
import com.spotify.helios.common.JobValidator;
import com.spotify.helios.common.Json;
import com.spotify.helios.common.Version;
import com.spotify.helios.common.VersionCheckResponse;
import com.spotify.helios.common.VersionCompatibility;
import com.spotify.helios.common.descriptors.AgentStatus;
import com.spotify.helios.common.descriptors.Deployment;
import com.spotify.helios.common.descriptors.Job;
import com.spotify.helios.common.descriptors.JobId;
import com.spotify.helios.common.descriptors.JobIdParseException;
import com.spotify.helios.common.protocol.AgentDeleteResponse;
import com.spotify.helios.common.protocol.CreateJobResponse;
import com.spotify.helios.common.protocol.JobDeleteResponse;
import com.spotify.helios.common.protocol.JobDeployResponse;
import com.spotify.helios.common.protocol.JobStatus;
import com.spotify.helios.common.protocol.JobUndeployResponse;
import com.spotify.helios.common.protocol.JobUndeployResponse.Status;
import com.spotify.helios.common.protocol.SetGoalResponse;
import com.spotify.helios.common.protocol.TaskStatusEvent;
import com.spotify.helios.common.protocol.TaskStatusEvents;
import com.spotify.hermes.message.Message;
import com.spotify.hermes.message.StatusCode;
import com.spotify.hermes.service.RequestHandlerException;
import com.spotify.hermes.service.ServiceRequest;
import com.spotify.hermes.service.handlers.MatchingHandler;
import com.spotify.hermes.util.Match;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.spotify.helios.common.descriptors.Descriptor.parse;
import static com.spotify.hermes.message.StatusCode.BAD_REQUEST;
import static com.spotify.hermes.message.StatusCode.FORBIDDEN;
import static com.spotify.hermes.message.StatusCode.NOT_FOUND;
import static com.spotify.hermes.message.StatusCode.OK;
import static com.spotify.hermes.message.StatusCode.SERVER_ERROR;

public class MasterHandler extends MatchingHandler {

  private static final Logger log = LoggerFactory.getLogger(MasterHandler.class);

  private static final JobValidator JOB_VALIDATOR = new JobValidator();

  private final MasterModel model;

  public MasterHandler(final MasterModel model) {
    this.model = model;
  }

  public String safeURLDecode(String s) {
    try {
      return URLDecoder.decode(s, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException("URL Decoding failed for " + s, e);
    }
  }

  @Match(uri = "hm://helios/version_check/<version>", methods = "GET")
  public void versionCheck(final ServiceRequest request, final String rawVersion) {
    final PomVersion clientVersion = PomVersion.parse(safeURLDecode(rawVersion));
    final PomVersion serverVersion = PomVersion.parse(Version.POM_VERSION);

    final VersionCompatibility.Status status = VersionCompatibility.getStatus(serverVersion,
        clientVersion);
    final VersionCheckResponse resp = new VersionCheckResponse(
        status,
        serverVersion,
        Version.RECOMMENDED_VERSION);
    respond(request, OK, resp);
  }

  @Match(uri = "hm://helios/jobs/<id>", methods = "PUT")
  public void jobPut(final ServiceRequest request, final String rawId) {
    final Message message = request.getMessage();
    if (message.getPayloads().size() != 1) {
      throw new RequestHandlerException(BAD_REQUEST);
    }

    final byte[] payload = message.getPayloads().get(0).toByteArray();
    final Job job;
    try {
      job = parse(payload, Job.class);
    } catch (IOException e) {
      throw new RequestHandlerException(BAD_REQUEST);
    }

    final String id = safeURLDecode(rawId);
    if (!job.getId().equals(parseJobId(id))) {
      respond(request, BAD_REQUEST, new CreateJobResponse(CreateJobResponse.Status.ID_MISMATCH));
      return;
    }

    final Collection<String> errors = JOB_VALIDATOR.validate(job);
    if (!errors.isEmpty()) {
      respond(request, BAD_REQUEST, new CreateJobResponse(
          CreateJobResponse.Status.INVALID_JOB_DEFINITION));
      return;
    }

    try {
      model.addJob(job);
    } catch (JobExistsException e) {
      respond(request, BAD_REQUEST,
              new CreateJobResponse(CreateJobResponse.Status.JOB_ALREADY_EXISTS));
      return;
    } catch (HeliosException e) {
      log.error("failed to add job: {}:{}", id, job, e);
      throw new RequestHandlerException(SERVER_ERROR);
    }

    log.info("added job {}:{}", id, job);

    respond(request, OK, new CreateJobResponse(CreateJobResponse.Status.OK));
  }

  @Match(uri = "hm://helios/jobs/<id>", methods = "GET")
  public void jobGet(final ServiceRequest request, final String rawId) {
    final String id = safeURLDecode(rawId);
    final JobId jobId = parseJobId(id);
    try {
      final Job job = model.getJob(jobId);
      ok(request, job);
    } catch (HeliosException e) {
      log.error("failed to get job: {}", id, e);
      throw new RequestHandlerException(SERVER_ERROR);
    }
  }

  @Match(uri = "hm://helios/jobs", methods = "GET")
  public void jobsGet(final ServiceRequest request) {
    final String q = request.getMessage().getParameter("q");
    try {
      final Map<JobId, Job> allJobs = model.getJobs();
      if (isNullOrEmpty(q)) {
        // Return all jobs
        ok(request, allJobs);
      } else {
        // Filter jobs
        // TODO (dano): support prefix matching queries?
        final JobId needle = JobId.parse(q);
        final Map<JobId, Job> filteredJobs = Maps.newHashMap();
        for (final JobId jobId : allJobs.keySet()) {
          if (needle.getName().equals(jobId.getName()) &&
              (needle.getVersion() == null || needle.getVersion().equals(jobId.getVersion())) &&
              (needle.getHash() == null || needle.getHash().equals((jobId.getHash())))) {
            filteredJobs.put(jobId, allJobs.get(jobId));
          }
        }
        ok(request, filteredJobs);
      }
    } catch (JobIdParseException e) {
      log.error("failed to parse job id query, e");
      throw new RequestHandlerException(BAD_REQUEST);
    } catch (HeliosException e) {
      log.error("failed to get jobs", e);
      throw new RequestHandlerException(SERVER_ERROR);
    }
  }

  @Match(uri = "hm://helios/jobs/<id>", methods = "DELETE")
  public void jobDelete(final ServiceRequest request, final String rawId) {
    final String id = safeURLDecode(rawId);
    try {
      model.removeJob(parseJobId(id));
      respond(request, OK, new JobDeleteResponse(JobDeleteResponse.Status.OK));
    } catch (JobStillInUseException e) {
      respond(request, FORBIDDEN, new JobDeleteResponse(JobDeleteResponse.Status.STILL_IN_USE));
    } catch (HeliosException e) {
      log.error("failed to remove job: {}", id, e);
      throw new RequestHandlerException(SERVER_ERROR);
    }
  }

  @Match(uri = "hm://helios/jobs/<id>/status", methods = "GET")
  public void jobStatusGet(final ServiceRequest request, final String rawId) {
    final String id = safeURLDecode(rawId);
    final JobId jobId = parseJobId(id);
    try {
      final JobStatus jobStatus = model.getJobStatus(jobId);
      ok(request, jobStatus);
    } catch (HeliosException e) {
      log.error("failed to get job status for job: {}", id, e);
      throw new RequestHandlerException(SERVER_ERROR);
    }
  }

  @Match(uri = "hm://helios/agents/<agent>", methods = "PUT")
  public void agentPut(final ServiceRequest request, final String rawAgent) {
    final String agent = safeURLDecode(rawAgent);
    try {
      model.addAgent(agent);
    } catch (HeliosException e) {
      log.error("failed to add agent {}", agent, e);
      throw new RequestHandlerException(SERVER_ERROR);
    }

    log.info("added agent {}", agent);

    ok(request);
  }

  @Match(uri = "hm://helios/agents/<agent>/jobs/<job>", methods = "PUT")
  public void agentJobPut(final ServiceRequest request, final String rawAgent,
                          final String rawJob)
      throws RequestHandlerException {
    final String agent = safeURLDecode(rawAgent);
    final String job = safeURLDecode(rawJob);
    final Deployment deployment = parseDeployment(request);

    final JobId jobId;
    try {
      jobId = JobId.parse(job);
    } catch (JobIdParseException e) {
      respond(request, BAD_REQUEST,
              new JobDeployResponse(JobDeployResponse.Status.INVALID_ID, agent, job));
      return;
    }

    if (!deployment.getJobId().equals(jobId)) {
      respond(request, BAD_REQUEST,
              new JobDeployResponse(JobDeployResponse.Status.ID_MISMATCH, agent, job));
      return;
    }

    StatusCode code = OK;
    JobDeployResponse.Status detailStatus = JobDeployResponse.Status.OK;

    try {
      model.deployJob(agent, deployment);
      log.info("added job {} to agent {}", deployment, agent);
    } catch (JobPortAllocationConflictException e){
      code = BAD_REQUEST;
      detailStatus = JobDeployResponse.Status.PORT_CONFLICT;
    } catch (JobDoesNotExistException e) {
      code = NOT_FOUND;
      detailStatus = JobDeployResponse.Status.JOB_NOT_FOUND;
    } catch (AgentDoesNotExistException e) {
      code = NOT_FOUND;
      detailStatus = JobDeployResponse.Status.AGENT_NOT_FOUND;
    } catch (JobAlreadyDeployedException e) {
      code = StatusCode.METHOD_NOT_ALLOWED;
      detailStatus = JobDeployResponse.Status.JOB_ALREADY_DEPLOYED;
    } catch (HeliosException e) {
      log.error("failed to add job {} to agent {}", deployment, agent, e);
      throw new RequestHandlerException(SERVER_ERROR);
    }

    respond(request, code, new JobDeployResponse(detailStatus, agent, job));
  }

  @Match(uri = "hm://helios/agents/<agent>/jobs/<id>", methods = "PATCH")
  public void jobPatch(final ServiceRequest request,
                       final String rawAgent,
                       final String rawJob) {
    final Deployment deployment = parseDeployment(request);
    final String job = safeURLDecode(rawJob);
    final String agent = safeURLDecode(rawAgent);
    final JobId jobId;
    try {
      jobId = JobId.parse(job);
    } catch (JobIdParseException e) {
      respond(request, BAD_REQUEST,
              new SetGoalResponse(SetGoalResponse.Status.INVALID_ID, agent, job));
      return;
    }

    if (!deployment.getJobId().equals(jobId)) {
      respond(request, BAD_REQUEST,
              new SetGoalResponse(SetGoalResponse.Status.ID_MISMATCH, agent, job));
      return;
    }

    StatusCode code = OK;
    SetGoalResponse.Status detailStatus = SetGoalResponse.Status.OK;

    try {
      model.updateDeployment(agent, deployment);
    } catch (JobDoesNotExistException e) {
      code = NOT_FOUND;
      detailStatus = SetGoalResponse.Status.JOB_NOT_FOUND;
    } catch (AgentDoesNotExistException e) {
      code = NOT_FOUND;
      detailStatus = SetGoalResponse.Status.AGENT_NOT_FOUND;
    } catch (JobNotDeployedException e) {
      code = NOT_FOUND;
      detailStatus = SetGoalResponse.Status.JOB_NOT_DEPLOYED;
    } catch (HeliosException e) {
      log.error("failed to add job {} to agent {}", deployment, agent, e);
      throw new RequestHandlerException(SERVER_ERROR);
    }

    log.info("added job {} to agent {}", deployment, agent);

    respond(request, code, new SetGoalResponse(detailStatus, agent, job));
  }

  private Deployment parseDeployment(final ServiceRequest request) {
    final Message message = request.getMessage();
    if (message.getPayloads().size() != 1) {
      throw new RequestHandlerException(BAD_REQUEST);
    }

    final byte[] payload = message.getPayloads().get(0).toByteArray();
    final Deployment deployment;
    try {
      deployment = Json.read(payload, Deployment.class);
    } catch (IOException e) {
      throw new RequestHandlerException(BAD_REQUEST);
    }
    return deployment;
  }

  @Match(uri = "hm://helios/agents/<agent>/jobs/<job>", methods = "GET")
  public void agentJobGet(final ServiceRequest request, final String rawAgent,
                          final String rawJobId)
      throws RequestHandlerException {
    final String agent = safeURLDecode(rawAgent);
    final String jobId = safeURLDecode(rawJobId);
    final Deployment deployment;
    try {
      deployment = model.getDeployment(agent, parseJobId(jobId));
    } catch (HeliosException e) {
      log.error("failed to get job {} for agent {}", jobId, agent, e);
      throw new RequestHandlerException(SERVER_ERROR);
    }

    if (deployment == null) {
      request.reply(NOT_FOUND);
      return;
    }

    ok(request, deployment);
  }

  @Match(uri = "hm://helios/agents/<agent>", methods = "DELETE")
  public void agentDelete(final ServiceRequest request, final String rawAgent) {
    final String agent = safeURLDecode(rawAgent);
    try {
      model.removeAgent(agent);
    } catch (AgentDoesNotExistException e) {
      respond(request, NOT_FOUND,
              new AgentDeleteResponse(AgentDeleteResponse.Status.NOT_FOUND, agent));
      return;
    } catch (HeliosException e) {
      log.error("failed to remove agent {}", agent, e);
      throw new RequestHandlerException(SERVER_ERROR);
    }
    respond(request, NOT_FOUND,
            new AgentDeleteResponse(AgentDeleteResponse.Status.OK, agent));
  }

  @Match(uri = "hm://helios/agents/<agent>/jobs/<job>", methods = "DELETE")
  public void agentJobDelete(final ServiceRequest request, final String rawAgent,
                             final String rawJobId)
      throws RequestHandlerException {
    final String agent = safeURLDecode(rawAgent);
    final String jobId = safeURLDecode(rawJobId);
    StatusCode code = OK;
    Status detail = JobUndeployResponse.Status.OK;
    try {
      model.undeployJob(agent, parseJobId(jobId));
    } catch (AgentDoesNotExistException e) {
      code = NOT_FOUND;
      detail = JobUndeployResponse.Status.AGENT_NOT_FOUND;
    } catch (JobDoesNotExistException e) {
      code = NOT_FOUND;
      detail = JobUndeployResponse.Status.JOB_NOT_FOUND;
    } catch (HeliosException e) {
      log.error("failed to remove job {} from agent {}", jobId, agent, e);
      throw new RequestHandlerException(SERVER_ERROR);
    }

    respond(request, code, new JobUndeployResponse(detail, agent, jobId));
  }

  @Match(uri = "hm://helios/agents/<agent>/status", methods = "GET")
  public void agentStatusGet(final ServiceRequest request, final String rawAgent)
      throws RequestHandlerException {
    final String agent = safeURLDecode(rawAgent);
    final AgentStatus agentStatus;
    try {
      agentStatus = model.getAgentStatus(agent);
    } catch (HeliosException e) {
      log.error("failed to get status for agent {}", agent, e);
      throw new RequestHandlerException(SERVER_ERROR);
    }
    if (agent == null) {
      request.reply(NOT_FOUND);
      return;
    }

    ok(request, agentStatus);
  }

  @Match(uri = "hm://helios/agents/", methods = "GET")
  public void agentsGet(final ServiceRequest request)
      throws RequestHandlerException {
    try {
      ok(request, model.getAgents());
    } catch (HeliosException e) {
      log.error("getting agents failed", e);
      throw new RequestHandlerException(SERVER_ERROR);
    }
  }

  @Match(uri = "hm://helios/masters/", methods = "GET")
  public void mastersGet(final ServiceRequest request)
      throws RequestHandlerException, JsonProcessingException {
    // TODO(drewc): should make it so we can get all masters, not just the running ones
    try {
      ok(request, model.getRunningMasters());
    } catch (HeliosException e) {
      log.error("getting masters failed", e);
      throw new RequestHandlerException(SERVER_ERROR);
    }
  }

  @Match(uri = "hm://helios/history/jobs/<jobid>", methods = "GET")
  public void jobHistoryGet(final ServiceRequest request, final String rawJobId)
      throws HeliosException, JobIdParseException, JsonProcessingException {
    final String jobId = safeURLDecode(rawJobId);
    try {
      List<TaskStatusEvent> history = model.getJobHistory(JobId.parse(jobId));
      TaskStatusEvents events = new TaskStatusEvents(history, TaskStatusEvents.Status.OK);
      ok(request, events);
    } catch (JobDoesNotExistException e) {
      respond(request, NOT_FOUND, new TaskStatusEvents(ImmutableList.<TaskStatusEvent>of(),
          TaskStatusEvents.Status.JOB_ID_NOT_FOUND));
    }
  }

  private void ok(final ServiceRequest request) {
    request.reply(OK);
  }

  private void ok(final ServiceRequest request, final Object payload) {
    respond(request, StatusCode.OK, payload);
  }

  private void respond(final ServiceRequest request, StatusCode code, final Object payload) {
    final byte[] json = Json.asBytesUnchecked(payload);
    final Message reply = request.getMessage()
        .makeReplyBuilder(code)
        .appendPayload(ByteString.copyFrom(json))
        .build();
    request.reply(reply);
  }

  private JobId parseJobId(final String id) {
    final JobId jobId;
    try {
      jobId = JobId.parse(id);
    } catch (JobIdParseException e) {
      throw new RequestHandlerException(BAD_REQUEST);
    }
    return jobId;
  }
}
