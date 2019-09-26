package org.jenkinsci.plugins.workflow.support.steps.deploy;

import jenkins.model.CauseOfInterruption;
import org.kohsuke.stapler.export.Exported;

import javax.annotation.CheckForNull;

/**
 * Indicates that the input step was rejected by the user.
 */
public final class ParamErrorRejection extends CauseOfInterruption {

    private static final long serialVersionUID = 1;

    private final @CheckForNull String error;
    private final long timestamp;

    public ParamErrorRejection(@CheckForNull String error) {
        this.error = error;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Gets the error why rejected this.
     */
    @Exported
    public @CheckForNull String getError() {
        return error;
    }

    /**
     * Gets the timestamp when the rejection occurred.
     */
    @Exported
    public long getTimestamp() {
        return timestamp;
    }

    @Override public String getShortDescription() {
        if (error != null) {
            return Messages.rejected_by(error);
        } else {
            return Messages.rejected();
        }
    }

}
