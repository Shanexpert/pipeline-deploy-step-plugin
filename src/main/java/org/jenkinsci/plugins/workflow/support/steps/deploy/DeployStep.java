package org.jenkinsci.plugins.workflow.support.steps.deploy;

import hudson.Extension;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStep;
import org.kohsuke.stapler.DataBoundConstructor;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.logging.Logger;

/**
 * {@link Step} that pauses for human input.
 *
 * @author Kohsuke Kawaguchi
 */
public class DeployStep extends InputStep {
    private static final Logger LOGGER = Logger.getLogger(DeployStep.class.getName());

//    private final String message;
//
//    /**
//     * Optional ID that uniquely identifies this input from all others.
//     */
//    private String id;
//
//    /**
//     * Optional user/group name who can approve this.
//     */
//    private String submitter;
//
//    /**
//     * Optional parameter name to stored the user who responded to the input.
//     */
//    private String submitterParameter;
//
//
//    /**
//     * Either a single {@link ParameterDefinition} or a list of them.
//     */
//    private List<ParameterDefinition> parameters = Collections.emptyList();
//
//    /**
//     * Caption of the OK button.
//     */
//    private String ok;

    @DataBoundConstructor
    public DeployStep(String message) {
        super(message);
//        if (message==null)
//            message = "Pipeline has paused and needs your input before proceeding";
//        this.message = message;
    }

//    @DataBoundSetter
//    public void setId(String id) {
//        this.id = capitalize(Util.fixEmpty(id));
//    }
//
//    public String getId() {
//        if (id==null)
//            id = capitalize(Util.getDigestOf(message));
//        return id;
//    }
//
//    public String getSubmitter() {
//        return submitter;
//    }
//
//    @DataBoundSetter public void setSubmitter(String submitter) {
//        this.submitter = Util.fixEmptyAndTrim(submitter);
//    }
//
//    public String getSubmitterParameter() { return submitterParameter; }
//
//    @DataBoundSetter public void setSubmitterParameter(String submitterParameter) {
//        this.submitterParameter = Util.fixEmptyAndTrim(submitterParameter);
//    }
//
//    private String capitalize(String id) {
//        if (id==null)
//            return null;
//        if (id.length()==0)
//            throw new IllegalArgumentException();
//        // a-z as the first char is reserved for DeployAction
//        char ch = id.charAt(0);
//        if ('a'<=ch && ch<='z')
//            id = ((char)(ch-'a'+'A')) + id.substring(1);
//        return id;
//    }
//
//    /**
//     * Caption of the OK button.
//     */
//    public String getOk() {
//        return ok!=null ? ok : Messages.proceed();
//    }
//
//    @DataBoundSetter public void setOk(String ok) {
//        this.ok = Util.fixEmptyAndTrim(ok);
//    }
//
//    public List<ParameterDefinition> getParameters() {
//        return parameters;
//    }
//
//    @DataBoundSetter public void setParameters(List<ParameterDefinition> parameters) {
//        this.parameters = parameters;
//    }
//
//    public String getMessage() {
//        return message;
//    }
//
//    @Deprecated
//    public boolean canSubmit() {
//        Authentication a = Jenkins.getAuthentication();
//        return canSettle(a);
//    }
//
//    /**
//     * Checks if the given user can settle this input.
//     */
//    @Deprecated
//    public boolean canSettle(Authentication a) {
//        if (submitter==null)
//            return true;
//        final Set<String> submitters = Sets.newHashSet(submitter.split(","));
//        if (submitters.contains(a.getName()))
//            return true;
//        for (GrantedAuthority ga : a.getAuthorities()) {
//            if (submitters.contains(ga.getAuthority()))
//                return true;
//        }
//        return false;
//    }
//
//
    @Override
    public DescriptorImpl getDescriptor() {
//        return new DescriptorImpl();
        DescriptorImpl descriptor = (DescriptorImpl) super.getDescriptor();

        Field field = null;
        try {
            field = descriptor.getClass().getDeclaredField("executionType");
            field.setAccessible(true);
            field.set(descriptor,DeployStepExecution.class);
        } catch (Exception e) {
            LOGGER.warning(e.getMessage());
        }

        return descriptor;
    }

    @Extension
    public static class DescriptorImpl extends InputStep.DescriptorImpl {

        public DescriptorImpl() {
        }

        @Override
        public String getFunctionName() {
            return "deploy";
        }

        @Override
        public String getDisplayName() {
            return Messages.wait_for_interactive_deploy();
        }

    }
    private void updateFinalModifiers(Field field) throws NoSuchFieldException, IllegalAccessException {
        field.setAccessible(true);
        Field modifiersField = Field.class.getDeclaredField("executionType");
        modifiersField.setAccessible(true);
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
    }
}
