package org.jenkinsci.plugins.workflow.support.steps.deploy;

import hudson.model.Result;
import org.jenkinsci.plugins.workflow.actions.PersistentAction;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

public class DeployingAction implements PersistentAction {
    @Nonnull
    private Result result;
    @CheckForNull
    private String message;

    public DeployingAction(@Nonnull Result result) {
        this.result = result;
    }

    public DeployingAction withMessage(String message) {
        this.message = message;
        return this;
    }

    @CheckForNull
    public String getMessage() {
        return this.message;
    }

    @Nonnull
    public Result getResult() {
        return this.result;
    }

    @Override
    public String getDisplayName() {
        return "Deploying" + (this.message != null?": " + this.message:"");
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return null;
    }
}

