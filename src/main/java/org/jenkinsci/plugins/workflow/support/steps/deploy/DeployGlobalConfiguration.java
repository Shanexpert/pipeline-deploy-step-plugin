package org.jenkinsci.plugins.workflow.support.steps.deploy;

import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;
import java.util.logging.Logger;

@Extension
public class DeployGlobalConfiguration extends GlobalConfiguration {
    private static final Logger LOGGER = Logger.getLogger(DeployGlobalConfiguration.class.getName());

    /**
     * deploy url
     */
    private String deployCallback = "";
    /**
     * deploy start notice url
     */
    private String noticeCallback = "";

    public DeployGlobalConfiguration() {
        this.load();
    }

    @Override
    @Nonnull
    public String getDisplayName() {
        return "LEO Jenkins Deploy Url";
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) {
        req.bindJSON(this, json);
        this.save();
        return true;
    }

    public String getDeployCallback() {
        return deployCallback;
    }

    @DataBoundSetter
    public void setDeployCallback(String deployCallback) {
        this.deployCallback = deployCallback;
    }

    public String getNoticeCallback() {
        return noticeCallback;
    }

    @DataBoundSetter
    public void setNoticeCallback(String noticeCallback) {
        this.noticeCallback = noticeCallback;
    }
}
