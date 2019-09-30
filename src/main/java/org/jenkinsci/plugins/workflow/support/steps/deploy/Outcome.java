package org.jenkinsci.plugins.workflow.support.steps.deploy;

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;

/**
 * Result of an evaluation.
 *
 * Either represents a value in case of a normal return, or a throwable object in case of abnormal return.
 * Note that both fields can be null, in which case it means a normal return of the value 'null'.
 *
 * @author Kohsuke Kawaguchi
 */
public final class Outcome implements Serializable {
    private final Object normal;
    private final Throwable abnormal;
    /**
     * is deploy
     */
    private final Boolean deployed;

    /**
     * is submit
     */
    private final Boolean submitted;

    /**
     * is abort
     */
    private final Boolean aborted;

    public Outcome(Object normal, Throwable abnormal, Boolean deployed, Boolean submitted, Boolean aborted) {
        assert normal==null || abnormal==null;
        this.normal = normal;
        this.abnormal = abnormal;
        this.deployed = deployed;
        this.submitted = submitted;
        this.aborted = aborted;
    }

    /**
     * Like {@link #replay()} but wraps the throwable into {@link InvocationTargetException}.
     */
    public Object wrapReplay() throws InvocationTargetException {
        if (abnormal!=null)
            throw new InvocationTargetException(abnormal);
        else
            return normal;
    }

    public Object replay() throws Throwable {
        if (abnormal!=null)
            throw abnormal;
        else
            return normal;
    }

    public Object getNormal() {
        return normal;
    }

    public Throwable getAbnormal() {
        return abnormal;
    }

    public boolean isSuccess() {
        return abnormal==null;
    }

    public boolean isFailure() {
        return abnormal!=null;
    }

    public boolean isDeployed() {
        return deployed!=null && deployed;
    }

    public boolean isSubmitted() {
        return submitted != null && submitted;
    }

    public boolean isAborted() {
        return aborted != null && aborted;
    }

    @Override
    public String toString() {
        if (abnormal!=null)     return "abnormal["+abnormal+"],deployed["+deployed+"],submitted["+submitted+"],aborted["+aborted+"]";
        else                    return "normal["+normal+"],deployed["+deployed+"],submitted["+submitted+"],aborted["+aborted+"]";
    }

    private static final long serialVersionUID = 1L;
}
