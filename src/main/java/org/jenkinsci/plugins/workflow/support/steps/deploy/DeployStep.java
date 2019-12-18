package org.jenkinsci.plugins.workflow.support.steps.deploy;

import com.google.common.collect.Sets;
import hudson.Extension;
import hudson.Util;
import hudson.model.ParameterDefinition;
import jenkins.model.Jenkins;
import org.acegisecurity.Authentication;
import org.acegisecurity.GrantedAuthority;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStep;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link Step} that pauses for human input.
 *
 * @author Kohsuke Kawaguchi
 */
public class DeployStep extends InputStep {
    private static final Logger LOGGER = Logger.getLogger(DeployStep.class.getName());

    private final String message;

    /**
     * Optional ID that uniquely identifies this input from all others.
     */
    private String id;

    /**
     * Optional user/group name who can approve this.
     */
    private String submitter;

    /**
     * Optional parameter name to stored the user who responded to the input.
     */
    private String submitterParameter;


    /**
     * Either a single {@link ParameterDefinition} or a list of them.
     */
    private List<ParameterDefinition> parameters = Collections.emptyList();

    /**
     * Caption of the OK button.
     */
    private String ok;

    @DataBoundConstructor
    public DeployStep(String message) {
        super(message);
        if(message == null) {
            message = "Pipeline has paused and needs your input before proceeding";
        }

        this.message = message;
    }

    @DataBoundSetter
    @Override
    public void setId(String id) {
        this.id = this.capitalize(Util.fixEmpty(id));
    }

    @Override
    public String getId() {
        if(this.id == null) {
            this.id = this.capitalize(Util.getDigestOf(this.message));
        }

        return this.id;
    }

    @Override
    public String getSubmitter() {
        return this.submitter;
    }

    @DataBoundSetter
    @Override
    public void setSubmitter(String submitter) {
        this.submitter = Util.fixEmptyAndTrim(submitter);
    }

    @Override
    public String getSubmitterParameter() {
        return this.submitterParameter;
    }

    @DataBoundSetter
    @Override
    public void setSubmitterParameter(String submitterParameter) {
        this.submitterParameter = Util.fixEmptyAndTrim(submitterParameter);
    }

    private String capitalize(String id) {
        if(id == null) {
            return null;
        } else if(id.length() == 0) {
            throw new IllegalArgumentException();
        } else {
            char ch = id.charAt(0);
            if(97 <= ch && ch <= 122) {
                id = (char)(ch - 97 + 65) + id.substring(1);
            }

            return id;
        }
    }

    @Override
    public String getOk() {
        return this.ok != null?this.ok: org.jenkinsci.plugins.workflow.support.steps.deploy.Messages.proceed();
    }

    @DataBoundSetter
    @Override
    public void setOk(String ok) {
        this.ok = Util.fixEmptyAndTrim(ok);
    }

    @Override
    public List<ParameterDefinition> getParameters() {
        return this.parameters;
    }

    @DataBoundSetter
    @Override
    public void setParameters(List<ParameterDefinition> parameters) {
        this.parameters = parameters;
    }

    @Override
    public String getMessage() {
        return this.message;
    }

    /** @deprecated */
    @Deprecated
    public boolean canSubmit() {
        Authentication a = Jenkins.getAuthentication();
        return this.canSettle(a);
    }

    /** @deprecated */
    @Deprecated
    public boolean canSettle(Authentication a) {
        if (submitter==null)
            return true;
        final Set<String> submitters = Sets.newHashSet(submitter.split(","));
        if (submitters.contains(a.getName()))
            return true;
        for (GrantedAuthority ga : a.getAuthorities()) {
            if (submitters.contains(ga.getAuthority()))
                return true;
        }
        return false;
    }


    @Override
    public DescriptorImpl getDescriptor() {
        return new DescriptorImpl();
    }

    @Extension
    public static class DescriptorImpl extends InputStep.DescriptorImpl {

        public DescriptorImpl() {
            super(DeployStepExecution.class);
        }

        @Override
        public String getFunctionName() {
            return "leoDeploy";
        }

        @Override
        public String getDisplayName() {
            return Messages.wait_for_interactive_deploy();
        }
    }
}
