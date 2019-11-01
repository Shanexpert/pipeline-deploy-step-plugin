package org.jenkinsci.plugins.workflow.support.steps.deploy;

import com.google.common.collect.Sets;
import com.google.inject.Inject;
import hudson.FilePath;
import hudson.Util;
import hudson.console.HyperlinkNote;
import hudson.model.*;
import hudson.security.ACL;
import hudson.security.SecurityRealm;
import hudson.util.HttpResponses;
import jenkins.model.GlobalConfiguration;
import jenkins.model.IdStrategy;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.util.JSONUtils;
import org.acegisecurity.Authentication;
import org.acegisecurity.GrantedAuthority;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.apache.tools.ant.taskdefs.condition.Http;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.jenkinsci.plugins.workflow.support.actions.PauseAction;
import org.jenkinsci.plugins.workflow.support.steps.input.ApproverAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;
import org.jenkinsci.plugins.workflow.support.steps.input.POSTHyperlinkNote;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.springframework.util.CollectionUtils;

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
    private static final String NOTICE_SUBMITTED = "submitted";
    private static final String NOTICE_SUCCESS = "success";
    private static final String NOTICE_ABORT = "abort";

    private static final int STATUS_DEPLOYING = 999999;
    private static final int STATUS_NOT_SUBMIT = 999998;
//    private static final int STATUS_ABORTED = 999997;

    private static ConnectionManager connectionFactory = new ConnectionManager();

    @StepContextParameter private transient Run run;

    @StepContextParameter private transient TaskListener listener;

    @StepContextParameter private transient FlowNode node;

    /**
     * Result of the deploy.
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
        // record this deploy
        getPauseAction().add(this);

        // This node causes the flow to pause at this point so we mark it as a "Pause Node".
        node.addAction(new PauseAction("Input"));

        String baseUrl = '/' + run.getUrl() + getPauseAction().getUrlName() + '/';
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
        // callback deploy start event
        postNoticeCallback(NOTICE_READY, null, null);
        return false;
    }

    @Override
    public void stop(Throwable cause) throws Exception {
        // JENKINS-37154: we might be inside the VM thread, so do not do anything which might block on the VM thread
        Timer.get().submit(new Runnable() {
            @Override public void run() {
                ACL.impersonate(ACL.SYSTEM, new Runnable() {
                    @Override public void run() {
                        doAbort(null);
                    }
                });
            }
        });
    }

    @Override
    public String getId() {
        return input.getId();
    }

    @Override
    public DeployStep getInput() {
        return input;
    }

    @Override
    public Run getRun() {
        return run;
    }

    /**
     * If this deploy step has been decided one way or the other.
     */
    @Override
    public boolean isSettled() {
        return outcome!=null && !outcome.isDeployed();
    }

    /**
     * Gets the {@link DeployAction} that this step should be attached to.
     */
    private DeployAction getPauseAction() {
        DeployAction a = run.getAction(DeployAction.class);
        if (a==null)
            run.addAction(a=new DeployAction());
        return a;
    }

    @Override
    public String getDisplayName() {
        String message = getInput().getMessage();
        if (message.length()<32)    return message;
        return message.substring(0,32)+"...";
    }


    /**
     * Called from the form via browser to submit/abort this deploy step.
     */
    @RequirePOST
    @Override
    public HttpResponse doSubmit(StaplerRequest request) throws IOException, ServletException, InterruptedException {
        if (request.getParameter("proceed")!=null && StringUtils.isNotEmpty(request.getParameter("proceed"))) {
            doProceed(request);
        } else {
            doAbort(request);
        }

        // go back to the Run console page
        return HttpResponses.redirectTo("../../console");
    }

    /**
     * REST endpoint to submit the input.
     */
    @RequirePOST
    @Override
    public HttpResponse doProceed(StaplerRequest request) throws IOException, ServletException, InterruptedException {
        preSubmissionCheck();
        Map<String,Object> v = parseValue(request);
        return proceed(v);
    }

    /**
     * Processes the acceptance (approval) request.
     * This method is used by both {@link #doProceedEmpty()} and {@link #doProceed(StaplerRequest)}
     *
     * @param params A map that represents the parameters sent in the request
     * @return A HttpResponse object that represents Status code (200) indicating the request succeeded normally.
     */
    @Override
    public HttpResponse proceed(@CheckForNull Map<String,Object> params) {
        if (params != null && params.get("deploy") != null && StringUtils.isNotEmpty(params.get("deploy").toString())) {
            return deploy(params);
        } else if (outcome == null || !outcome.isSubmitted()){
            return HttpResponses.error(STATUS_NOT_SUBMIT, "This deploy is not submitted, params error.");
//            throw new Failure("This deploy is not submitted.");
        }
        User user = User.current();
        log("Deploy succeed.");

        String userId = null;
        String userName = null;
        if (outcome != null && outcome.getNormal() != null) {
            userId = ((Map<String, Object>)outcome.getNormal()).get("userId") == null ? null : ((Map<String, Object>)outcome.getNormal()).get("userId").toString();
            userName = ((Map<String, Object>)outcome.getNormal()).get("userName") == null ? null : ((Map<String, Object>)outcome.getNormal()).get("userName").toString();
        }
        // callback input success event
        postNoticeCallback(NOTICE_SUCCESS, userId, userName);

        String approverId = null;
        if (user != null){
            approverId = user.getId();
            run.addAction(new ApproverAction(approverId));
//            listener.getLogger().println("Deploy succeed by " + hudson.console.ModelHyperlinkNote.encodeTo(user));
            listener.getLogger().println("Deploy succeed by " + userName);
        }
        node.addAction(new DeploySubmittedAction(approverId, params));
        outcome = new Outcome(outcome.getNormal(), null, true, true, true);

        // remove DeployAction from run
        run.getActions().remove(getPauseAction());
        // remove DeployingAction from run
        List<DeployingAction> deployingActionList = run.getActions(DeployingAction.class);
        if (!CollectionUtils.isEmpty(deployingActionList)) {
            for (DeployingAction deployingAction : deployingActionList) {
                if (node.getId().equals(deployingAction.getMessage())) {
                    run.getActions().remove(deployingAction);
                }
            }
        }

        postSettlement();
        getContext().onSuccess(outcome.getNormal());
        return HttpResponses.ok();
    }

    private HttpResponse deploy(@CheckForNull Map<String,Object> params) {
        if (outcome != null) {
//            throw new Failure("This deploy is submitted or is deployed");
            return HttpResponses.error(STATUS_DEPLOYING, "Do not allow the operation in the release.");
        }
        outcome = new Outcome(null, null, null, true, null);

        //            log("Deployed by " + hudson.console.ModelHyperlinkNote.encodeTo(user));
        String tenantId = params.get("tenantId") == null ? "" : params.get("tenantId").toString();
        String projectId = params.get("projectId") == null ? "" : params.get("projectId").toString();
        String appId = params.get("appId") == null ? "" : params.get("appId").toString();
        String userId = params.get("userId") == null ? "" : params.get("userId").toString();
        String userName = params.get("userName") == null ? "" : params.get("userName").toString();
        String nodeId = params.get("nodeId") == null ? "" : params.get("nodeId").toString();
        listener.getLogger().println("Deployed by " + userName);
        String tplId = params.get("tplId") == null ? "" : params.get("tplId").toString();
        String env = params.get("env") == null ? "" : params.get("env").toString();
        if (StringUtils.isEmpty(tenantId) || StringUtils.isEmpty(projectId) || StringUtils.isEmpty(appId)
                || StringUtils.isEmpty(env)
                || StringUtils.isEmpty(tplId)
                || StringUtils.isEmpty(userId)
                || StringUtils.isEmpty(userName)
                || StringUtils.isEmpty(nodeId)) {
            log("Params error, curl deploy url error.");
            LOGGER.warning("Params error, curl deploy url error. params: " + params.toString());
            preAbortCheck();
            FlowInterruptedException e = new FlowInterruptedException(Result.ABORTED, new ParamErrorRejection("Parmas error"));
            outcome = new Outcome(null,e, null, true, null);
            postSettlement();
            getContext().onFailure(e);
            return HttpResponses.ok();
        }
        // callback deploy submitted event
        postNoticeCallback(NOTICE_SUBMITTED, userId, userName);

        // curl input url
        String deployUrl = GlobalConfiguration.all().get(DeployGlobalConfiguration.class).getDeployCallback();
        String url = MessageFormat.format(deployUrl, tenantId, projectId, appId, tplId, env);
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("runId", run.getNumber());
        jsonObject.put("nodeId", nodeId);
        jsonObject.put("inputId", getId());
        jsonObject.put("stepId", node.getId());
        jsonObject.put("pipelineId", run.getParent().getName());
        jsonObject.put("devopsId", run.getParent().getParent() == null ? "" : run.getParent().getParent().getFullName());
        Boolean result = post(url, jsonObject, userId, userName);
        if (result) {
            DeployingAction deployingAction = new DeployingAction(Result.NOT_BUILT);
            deployingAction = deployingAction.withMessage(node.getId());
            node.addAction(deployingAction);
            run.addAction(deployingAction);
            Object v;
            if (params != null && params.size() == 1) {
                v = params.values().iterator().next();
            } else {
                v = params;
            }
            outcome = new Outcome(v, null, false, true, null);
            return HttpResponses.ok();
        } else {
            log("Deploy error.");
            // callback deploy abort event
            postNoticeCallback(NOTICE_ABORT, userId, userName);

            FlowInterruptedException e = new FlowInterruptedException(Result.ABORTED, new Rejection(User.current()));
            if (outcome == null) {
                outcome = new Outcome(null, e, null, null, true);
            } else {
                outcome = new Outcome(null,e, outcome.isDeployed(), outcome.isSubmitted(), true);
            }
            postSettlement();
            getContext().onFailure(e);

            // TODO: record this decision to FlowNode
            return HttpResponses.ok();
        }
    }

    @Deprecated
    @SuppressWarnings("unchecked")
    public HttpResponse proceed(Object v) {
        if (v instanceof Map) {
            return proceed(new HashMap<String,Object>((Map) v));
        } else if (v == null) {
            return proceed(null);
        } else {
            return proceed(Collections.singletonMap("parameter", v));
        }
    }

    /**
     * Used from the Proceed hyperlink when no parameters are defined.
     */
    @RequirePOST
    @Override
    public HttpResponse doProceedEmpty() {
        preSubmissionCheck();

        return proceed(null);
    }

    /**
     * REST endpoint to abort the workflow.
     */
    @RequirePOST
    public HttpResponse doAbort(StaplerRequest request) {
        Map<String,Object> params = null;
        if (request != null) {
            try {
                params = parseValue(request);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "doAbort exception, ", e);
            }
        }
        return doAbortProcceed(params);
    }
    private boolean userCancelFlag(Map<String,Object> params) {
        return params != null && params.get("stop") != null && params.get("stop").toString().equals("true");
    }

    public HttpResponse doAbortProcceed(@CheckForNull Map<String,Object> params) {
        preAbortCheck();
        if (userCancelFlag(params)  && outcome!=null && (outcome.isSubmitted() || outcome.isDeployed())) {
            // 用户点击取消
            return HttpResponses.error(STATUS_DEPLOYING, "Do not allow the operation in the release.");
        }
        String userId = null;
        String userName = null;
        if (outcome != null && outcome.getNormal() != null) {
            userId = ((Map<String, Object>)outcome.getNormal()).get("userId") == null ? null : ((Map<String, Object>)outcome.getNormal()).get("userId").toString();
            userName = ((Map<String, Object>)outcome.getNormal()).get("userName") == null ? null : ((Map<String, Object>)outcome.getNormal()).get("userName").toString();
        } else {
            if (params != null) {
                userId = params.get("userId") == null ? null : params.get("userId").toString();
                userName = params.get("userName") == null ? null : params.get("userName").toString();
            }
        }
        FlowInterruptedException e = new FlowInterruptedException(Result.ABORTED, new Rejection(User.current()));
        if (outcome == null) {
            outcome = new Outcome(null, e, null, null, true);
        } else {
            outcome = new Outcome(null,e, outcome.isDeployed(), outcome.isSubmitted(), true);
        }

        // callback deploy abort event
        postNoticeCallback(NOTICE_ABORT, userId, userName);

        // TODO: record this decision to FlowNode
        run.getActions().remove(getPauseAction());
        // remove DeployingAction from run
        List<DeployingAction> deployingActionList = run.getActions(DeployingAction.class);
        if (!CollectionUtils.isEmpty(deployingActionList)) {
            for (DeployingAction deployingAction : deployingActionList) {
                if (node.getId().equals(deployingAction.getMessage())) {
                    run.getActions().remove(deployingAction);
                }
            }
        }

        postSettlement();
        getContext().onFailure(e);
        return HttpResponses.ok();
    }

    /**
     * Check if the current user can abort/cancel the run from the deploy.
     */
    private void preAbortCheck() {
        if (outcome!=null && outcome.isAborted()) {
            throw new Failure("This deploy has been already given");
//            HttpResponses.error(STATUS_ABORTED, "This deploy has been already given");
        } if (!canCancel() && !canSubmit()) {
            if (input.getSubmitter() != null) {
                throw new Failure("You need to be '" + input.getSubmitter() + "' (or have Job/Cancel permissions) to cancel this.");
            } else {
                throw new Failure("You need to have Job/Cancel permissions to cancel this.");
            }
        }
    }

    /**
     * Check if the current user can submit the deploy.
     */
    @Override
    public void preSubmissionCheck() {
        if (outcome!=null && outcome.isDeployed())
            throw new Failure("This deploy has been already given");
        if (!canSubmit()) {
            if (input.getSubmitter() != null) {
                throw new Failure("You need to be " + input.getSubmitter() + " to submit this.");
            } else {
                throw new Failure("You need to have Job/Build permissions to submit this.");
            }
        }
    }

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
        return !Jenkins.getActiveInstance().isUseSecurity() || getRun().getParent().hasPermission(Job.CANCEL);
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
        if (!Jenkins.getActiveInstance().isUseSecurity() || Jenkins.getActiveInstance().hasPermission(Jenkins.ADMINISTER)) {
            return true;
        }
        final Set<String> submitters = Sets.newHashSet(submitter.split(","));
        final SecurityRealm securityRealm = Jenkins.getActiveInstance().getSecurityRealm();
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
                mapResult.put(name, convert(name, v));
            }
        }

        // If a destination value is specified, push the submitter to it.
        String valueName = input.getSubmitterParameter();
        if (valueName != null && !valueName.isEmpty()) {
            Authentication a = Jenkins.getAuthentication();
            mapResult.put(valueName, a.getName());
        }
//        mapResult.put("userId", request.getParameter("userId"));
//        mapResult.put("userName", request.getParameter("userName"));
//        mapResult.put("tenantId", request.getParameter("tenantId"));
//        mapResult.put("projectId", request.getParameter("projectId"));
//        mapResult.put("appId", request.getParameter("appId"));
//        mapResult.put("deploy", request.getParameter("deploy"));
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
    public Boolean postNoticeCallback(String type, String userId, String userName)  {
        // callback deploy start event
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
        jsonObject.put("inputId", this.getId());
        jsonObject.put("stepId", node.getId());
        jsonObject.put("pipelineName", run.getParent().getName());
        jsonObject.put("pipelineFullName", run.getParent().getFullName());
        jsonObject.put("submitter", input == null ? "" : input.getSubmitter());
        return post(noticeCallback, jsonObject, userId, userName);
    }

    /**
     *
     * @param url request url
     * @param jsonObject request body
     * @param userId leo userId
     * @return
     */
    public Boolean post(String url, JSONObject jsonObject, String userId, String userName) {
        CloseableHttpResponse response = null;
        try {
            // 从连接池中获得HttpClient
            CloseableHttpClient httpClient = connectionFactory.getHttpClient();
            HttpPost httpPost = new HttpPost(url);
            httpPost.setHeader("Content-Type","application/json;charset=utf-8");
            JSONObject leoUserJsonObject = new JSONObject();
            if (!StringUtils.isEmpty(userId)) {
                leoUserJsonObject.put("userId", userId);
            }
            if (!StringUtils.isEmpty(userName)) {
                leoUserJsonObject.put("userName", userName);
            }
            httpPost.setHeader("LEO-USER", leoUserJsonObject.toString());
            LOGGER.log(Level.INFO, "Deploy step post url is " + url);
            LOGGER.log(Level.INFO, "Deploy step post header LEO-USER is " + leoUserJsonObject.toString());
            LOGGER.log(Level.INFO, "Deploy step post body is " + jsonObject.toString());

            StringEntity postingString = new StringEntity(jsonObject.toString(),"utf-8");
            httpPost.setEntity(postingString);
            response = httpClient.execute(httpPost);
            log("Response status code is " + response.getStatusLine().getStatusCode());
            if (response.getStatusLine().getStatusCode() == HttpServletResponse.SC_OK) {
                //获取返回值
                HttpEntity entity = response.getEntity();
                String message = EntityUtils.toString(entity, "UTF-8");
                LOGGER.log(Level.WARNING, "Response entity is " + message);
                if (StringUtils.isNotEmpty(message)) {
                    JSONObject result = JSONObject.fromObject(message);
                    if (!"000000".equals(result.getString("rtnCode"))) {
                        log("Response error message is " + result.getString("rtnMsg"));
                        return  false;
                    }
                }
                return true;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "curl deploy url error, " + run, e);
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
