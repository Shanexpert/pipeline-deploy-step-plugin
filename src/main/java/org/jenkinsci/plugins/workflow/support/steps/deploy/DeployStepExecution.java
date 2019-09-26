package org.jenkinsci.plugins.workflow.support.steps.deploy;

import com.cloudbees.plugins.credentials.CredentialsParameterValue;
import com.cloudbees.plugins.credentials.builds.CredentialsParameterBinder;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import hudson.FilePath;
import hudson.Util;
import hudson.console.HyperlinkNote;
import hudson.model.*;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.SecurityRealm;
import hudson.util.HttpResponses;
import jenkins.model.GlobalConfiguration;
import jenkins.model.IdStrategy;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.acegisecurity.Authentication;
import org.acegisecurity.GrantedAuthority;
import org.apache.commons.lang.StringUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.jenkinsci.plugins.workflow.actions.WarningAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.jenkinsci.plugins.workflow.support.actions.PauseAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStep;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;
import org.jenkinsci.plugins.workflow.support.steps.input.POSTHyperlinkNote;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.CheckForNull;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Kohsuke Kawaguchi
 */
public class DeployStepExecution extends InputStepExecution implements ModelObject {

    private static final Logger LOGGER = Logger.getLogger(DeployStepExecution.class.getName());

    private static final String NOTICE_READY = "ready";
    private static final String NOTICE_SUCCESS = "success";
    private static final String NOTICE_ABORT = "abort";

    private static ConnectionManager connectionFactory = new ConnectionManager();

    @StepContextParameter private transient Run run;

    @StepContextParameter private transient TaskListener listener;

    @StepContextParameter private transient FlowNode node;

    /**
     * Result of the input.
     */
    private Outcome outcome;

    @Inject(optional=true)
    DeployStep input;

    private void log(String msg, Object... args){
        try {
            getContext().get(TaskListener.class).getLogger().printf(msg, args);
            getContext().get(TaskListener.class).getLogger().println();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "IOException ", e);
        }
    }

    @Override
    public boolean start() throws Exception {
        // record this input
        getPauseAction().add(this);

        DeployAction deployAction = new DeployAction();
        run.addAction(deployAction);
        deployAction.add(this);

        // This node causes the flow to pause at this point so we mark it as a "Pause Node".
        node.addAction(new PauseAction("Input"));

//        String baseUrl = '/' + run.getUrl() + getPauseAction().getUrlName() + '/';
        String baseUrl = '/' + run.getUrl() + "deploy/";
        //JENKINS-40594 submitterParameter does not work without at least one actual parameter
        if (input.getParameters().isEmpty() && input.getSubmitterParameter() == null) {
            String thisUrl = baseUrl + Util.rawEncode(getId()) + '/';
            listener.getLogger().printf("%s%n%s or %s%n", input.getMessage(),
                    POSTHyperlinkNote.encodeTo(thisUrl + "proceedEmpty", input.getOk()),
                    POSTHyperlinkNote.encodeTo(thisUrl + "abort", "Abort"));
        } else {
            // TODO listener.hyperlink(…) does not work; why?
            // TODO would be even cooler to embed the parameter form right in the build log (hiding it after submission)
            listener.getLogger().println(HyperlinkNote.encodeTo(baseUrl, "Deploy requested"));
        }
        // callback input start event
        postNoticeCallback(NOTICE_READY);
        return false;
    }

    @Override
    public void stop(Throwable cause) throws Exception {
        // JENKINS-37154: we might be inside the VM thread, so do not do anything which might block on the VM thread
        Timer.get().submit(new Runnable() {
            @Override public void run() {
                try (ACLContext context = ACL.as(ACL.SYSTEM)) {
                    doAbort();
                }
            }
        });
    }

    @Override
    public String getId() {
        return input.getId();
    }

    @Override
    public InputStep getInput() {
        return input;
    }

    @Override
    public Run getRun() {
        return run;
    }

    /**
     * If this input step has been decided one way or the other.
     */
    @Override
    public boolean isSettled() {
        return outcome!=null && !outcome.isDeployed();
    }

    /**
     * Gets the {@link DeployAction} that this step should be attached to.
     */
//    private DeployAction getPauseAction() {
//        DeployAction a = run.getAction(DeployAction.class);
//        if (a==null)
//            run.addAction(a=new DeployAction());
//        return a;
//    }
    private InputAction getPauseAction() {
        InputAction a = run.getAction(InputAction.class);
        if (a==null)
            run.addAction(a=new InputAction());
        return a;
    }

    @Override
    public String getDisplayName() {
//        String message = getInput().getMessage();
        String message = this.input.getMessage();
        if (message.length()<32)    return message;
        return message.substring(0,32)+"...";
    }


    /**
     * Called from the form via browser to submit/abort this input step.
     */
//    @RequirePOST
//    public HttpResponse doSubmit(StaplerRequest request) throws IOException, ServletException, InterruptedException {
//        if (request.getParameter("proceed")!=null) {
//            doProceed(request);
//        } else {
//            doAbort();
//        }
//
//        // go back to the Run console page
//        return HttpResponses.redirectTo("../../console");
//    }

    /**
     * REST endpoint to submit the input.
     */
//    @RequirePOST
//    public HttpResponse doProceed(StaplerRequest request) throws IOException, ServletException, InterruptedException {
//        preSubmissionCheck();
//        Map<String,Object> v = parseValue(request);
//        return proceed(v);
//    }

    /**
     * Processes the acceptance (approval) request.
     * This method is used by both {@link #doProceedEmpty()} and {@link #doProceed(StaplerRequest)}
     *
     * @param params A map that represents the parameters sent in the request
     * @return A HttpResponse object that represents Status code (200) indicating the request succeeded normally.
     */
    @Override
    public HttpResponse proceed(@CheckForNull Map<String,Object> params) {
        User user = User.current();
        if (params != null && params.get("input") != null && StringUtils.isNotEmpty(params.get("input").toString())) {
            node.addAction(new WarningAction(Result.NOT_BUILT));

            log("Deployed by " + hudson.console.ModelHyperlinkNote.encodeTo(user));
            LOGGER.log(Level.INFO, "Deployed by " + hudson.console.ModelHyperlinkNote.encodeTo(user));
            String tenantId = params.get("tenantId") == null ? "" : params.get("tenantId").toString();
            String projectId = params.get("projectId") == null ? "" : params.get("projectId").toString();
            String appId = params.get("appId") == null ? "" : params.get("appId").toString();
            String tplId = params.get("tplId") == null ? "" : params.get("tplId").toString();
            String env = params.get("env") == null ? "" : params.get("env").toString();
            String userId = params.get("userId") == null ? "" : params.get("userId").toString();
            if (StringUtils.isEmpty(tenantId) || StringUtils.isEmpty(projectId) || StringUtils.isEmpty(appId)
                    || StringUtils.isEmpty(tplId)
                    || StringUtils.isEmpty(env)
                    || StringUtils.isEmpty(userId)) {
                preAbortCheck();
                FlowInterruptedException e = new FlowInterruptedException(Result.ABORTED, new ParamErrorRejection("Parmas error"));
                outcome = new Outcome(null,e, null);
                postSettlement();
                getContext().onFailure(e);
                return HttpResponses.ok();
            }
            // curl input url
            String deployUrl = GlobalConfiguration.all().get(DeployGlobalConfiguration.class).getDeployCallback();
            String url = deployUrl + MessageFormat.format("/api/v1/kubernetes/tenants/{0}/projects/{1}/leoapps/{2}/tpls/{3}/clusters/{4}/input", tenantId, projectId, appId, tplId, env);
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("runId", run.getNumber());
            jsonObject.put("stepId", getId());
            jsonObject.put("nodeId", node.getId());
            jsonObject.put("pipelineId", run.getParent().getName());
            jsonObject.put("devopsId", run.getParent().getParent() == null ? "" : run.getParent().getParent().getFullName());
            LOGGER.log(Level.INFO, "Deploy body is " + jsonObject.toString());
            Boolean result = post(url, jsonObject, userId);
            if (result) {
                Object v;
                if (params != null && params.size() == 1) {
                    v = params.values().iterator().next();
                } else {
                    v = params;
                }
                outcome = new Outcome(v, null, true);
                return HttpResponses.ok();
            } else {
                return doAbort();
            }
        }
        log("Deploy succeed.");

        // callback input success event
        postNoticeCallback(NOTICE_SUCCESS);

        String approverId = null;
        if (user != null){
            approverId = user.getId();
            run.addAction(new ApproverAction(approverId));
            listener.getLogger().println("Deploy succeed by " + hudson.console.ModelHyperlinkNote.encodeTo(user));
        }
        node.addAction(new DeploySubmittedAction(approverId, params));
//        Object v;
//        if (params != null && params.size() == 1) {
//            v = params.values().iterator().next();
//        } else {
//            v = params;
//        }
//        outcome = new Outcome(v, null, true);
        postSettlement();
        getContext().onSuccess(outcome == null ? null : outcome.getNormal());
        return HttpResponses.ok();
    }

//    @Deprecated
//    @SuppressWarnings("unchecked")
//    public HttpResponse proceed(Object v) throws IOException, InterruptedException {
//        if (v instanceof Map) {
//            return proceed(new HashMap<String,Object>((Map) v));
//        } else if (v == null) {
//            return proceed(null);
//        } else {
//            return proceed(Collections.singletonMap("parameter", v));
//        }
//    }

    /**
     * Used from the Proceed hyperlink when no parameters are defined.
     */
//    @RequirePOST
//    public HttpResponse doProceedEmpty() throws IOException, InterruptedException {
//        preSubmissionCheck();
//
//        return proceed(null);
//    }

    /**
     * REST endpoint to abort the workflow.
     */
    @RequirePOST
    @Override
    public HttpResponse doAbort() {
        // callback input abort event
        postNoticeCallback(NOTICE_ABORT);

        preAbortCheck();

        FlowInterruptedException e = new FlowInterruptedException(Result.ABORTED, new Rejection(User.current()));
        outcome = new Outcome(null,e, null);
        postSettlement();
        getContext().onFailure(e);

        // TODO: record this decision to FlowNode
        return HttpResponses.ok();
    }

    /**
     * Check if the current user can abort/cancel the run from the input.
     */
    private void preAbortCheck() {
        if (isSettled()) {
            throw new Failure("This input has been already given");
        } if (!canCancel() && !canSubmit()) {
            if (input.getSubmitter() != null) {
                throw new Failure("You need to be '" + input.getSubmitter() + "' (or have Job/Cancel permissions) to cancel this.");
            } else {
                throw new Failure("You need to have Job/Cancel permissions to cancel this.");
            }
        }
    }

    /**
     * Check if the current user can submit the input.
     */
//    public void preSubmissionCheck() {
//        if (isSettled())
//            throw new Failure("This input has been already given");
//        if (!canSubmit()) {
//            if (input.getSubmitter() != null) {
//                throw new Failure("You need to be " + input.getSubmitter() + " to submit this.");
//            } else {
//                throw new Failure("You need to have Job/Build permissions to submit this.");
//            }
//        }
//    }

    private void postSettlement() {
        try {
            getPauseAction().remove(this);
            run.save();
        } catch (IOException | InterruptedException | TimeoutException x) {
            LOGGER.log(Level.WARNING, "failed to remove DeployAction from " + run, x);
        } finally {
            if (node != null) {
                try {
                    PauseAction.endCurrentPause(node);
                } catch (IOException x) {
                    LOGGER.log(Level.WARNING, "failed to end PauseAction in " + run, x);
                }
            } else {
                LOGGER.log(Level.WARNING, "cannot set pause end time for {0} in {1}", new Object[] {getId(), run});
            }
        }
    }

    private boolean canCancel() {
        return !Jenkins.get().isUseSecurity() || getRun().getParent().hasPermission(Job.CANCEL);
    }

    private boolean canSubmit() {
        Authentication a = Jenkins.getAuthentication();
        return canSettle(a);
    }

    /**
     * Checks if the given user can settle this input.
     */
    private boolean canSettle(Authentication a) {
        String submitter = input.getSubmitter();
        if (submitter==null)
            return getRun().getParent().hasPermission(Job.BUILD);
        if (!Jenkins.get().isUseSecurity() || Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return true;
        }
        final Set<String> submitters = Sets.newHashSet(submitter.split(","));
        final SecurityRealm securityRealm = Jenkins.get().getSecurityRealm();
        if (isMemberOf(a.getName(), submitters, securityRealm.getUserIdStrategy()))
            return true;
        for (GrantedAuthority ga : a.getAuthorities()) {
            if (isMemberOf(ga.getAuthority(), submitters, securityRealm.getGroupIdStrategy()))
                return true;
        }
        return false;
    }

    /**
     * Checks if the provided userId is contained in the submitters list, using {@link SecurityRealm#getUserIdStrategy()} comparison algorithm.
     * Main goal is to respect here the case sensitivity settings of the current security realm
     * (which default behavior is case insensitivity).
     *
     * @param userId the id of the user if it is matching one of the submitters using {@link IdStrategy#equals(String, String)}
     * @param submitters the list of authorized submitters
     * @param idStrategy the idStrategy impl to use for comparison
     * @return true is userId was found in submitters, false if not.
     *
     * @see {@link jenkins.model.IdStrategy#CASE_INSENSITIVE}.
     */
    private boolean isMemberOf(String userId, Set<String> submitters, IdStrategy idStrategy) {
        for (String submitter : submitters) {
            if (idStrategy.equals(userId, StringUtils.trim(submitter))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parse the submitted {@link ParameterValue}s
     */
    private Map<String,Object> parseValue(StaplerRequest request) throws ServletException, IOException, InterruptedException {
        Map<String, Object> mapResult = new HashMap<String, Object>();
        List<ParameterDefinition> defs = input.getParameters();
        Set<ParameterValue> vals = new HashSet<>(defs.size());

        Object params = request.getSubmittedForm().get("parameter");
        if (params!=null) {
            for (Object o : JSONArray.fromObject(params)) {
                JSONObject jo = (JSONObject) o;
                String name = jo.getString("name");

                ParameterDefinition d=null;
                for (ParameterDefinition def : defs) {
                    if (def.getName().equals(name))
                        d = def;
                }
                if (d == null)
                    throw new IllegalArgumentException("No such parameter definition: " + name);

                ParameterValue v = d.createValue(request, jo);
                if (v == null) {
                    continue;
                }
                vals.add(v);
                mapResult.put(name, convert(name, v));
            }
        }

        CredentialsParameterBinder binder = CredentialsParameterBinder.getOrCreate(run);
        String userId = Jenkins.getAuthentication().getName();
        for (ParameterValue val : vals) {
            if (val instanceof CredentialsParameterValue) {
                binder.bindCredentialsParameter(userId, (CredentialsParameterValue) val);
            }
        }
        run.replaceAction(binder);

        // If a destination value is specified, push the submitter to it.
        String valueName = input.getSubmitterParameter();
        if (valueName != null && !valueName.isEmpty()) {
            mapResult.put(valueName, userId);
        }

        if (mapResult.isEmpty()) {
            return null;
        } else {
            return mapResult;
        }
    }

    private Object convert(String name, ParameterValue v) throws IOException, InterruptedException {
        if (v instanceof FileParameterValue) {
            FileParameterValue fv = (FileParameterValue) v;
            FilePath fp = new FilePath(run.getRootDir()).child(name);
            fp.copyFrom(fv.getFile());
            return fp;
        } else {
            return v.getValue();
        }
    }

    /**
     *
     * @param type ready、success、abort
     * @return
     */
    public Boolean postNoticeCallback(String type)  {
        // callback input start event
        String noticeCallback = GlobalConfiguration.all().get(DeployGlobalConfiguration.class).getNoticeCallback();
        try {
            if (StringUtils.isEmpty(noticeCallback)) {
                log("Notice envent url param error.");
                return true;
            } else {
                log("Notice envent start, type is " + type);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Log print error, " + run, e);
            if (StringUtils.isEmpty(noticeCallback)) {
                return true;
            }
        }
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("type", type);
        jsonObject.put("runId", run.getNumber());
        jsonObject.put("stepId", getId());
        jsonObject.put("pipelineName", run.getParent().getName());
        jsonObject.put("pipelineFullName", run.getParent().getFullName());
        LOGGER.log(Level.INFO, "Post body is " + jsonObject.toString());
        return post(noticeCallback, jsonObject, null);
    }

    /**
     *
     * @param url request url
     * @param jsonObject request body
     * @param userId leo userId
     * @return
     */
    public Boolean post(String url, JSONObject jsonObject, String userId) {
        CloseableHttpResponse response = null;
        try {
            // 从连接池中获得HttpClient
            CloseableHttpClient httpClient = connectionFactory.getHttpClient();
            HttpPost httpPost = new HttpPost(url);
            httpPost.setHeader("Content-Type","application/json;charset=utf-8");
            if (!StringUtils.isEmpty(userId)) {
                httpPost.setHeader("LEO-USER","{\"userId\":\"" + userId + "\"}");
            }
            StringEntity postingString = new StringEntity(jsonObject.toString(),"utf-8");
            httpPost.setEntity(postingString);
            response = httpClient.execute(httpPost);
            log("Response status code is " + response.getStatusLine().getStatusCode());
            if (response.getStatusLine().getStatusCode() == HttpServletResponse.SC_OK) {
                return true;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "curl input url error, " + run, e);
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "failed to close closeableHttpResponse from " + run, e);
                }
            }
        }
        return false;
    }


    private static final long serialVersionUID = 1L;
}