/*
 * The MIT License
 *
 * Copyright 2015 Jesse Glick.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.support.steps.deploy;

import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.jenkinsci.plugins.workflow.steps.StepConfigTester;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStep;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class InputStepConfigTest {
    
    @Rule public JenkinsRule r = new JenkinsRule();

    @Test public void configRoundTrip() throws Exception {
        DeployStep s1 = new DeployStep("hello world");
        DeployStep s2 = new StepConfigTester(r).configRoundTrip(s1);
        assertEquals(s1.getMessage(), s2.getMessage());
        assertEquals(s1.getId(), s2.getId());
        assertEquals(s1.getParameters(), s2.getParameters());
        assertEquals(s1.getOk(), s2.getOk());
        assertEquals(s1.getSubmitter(), s2.getSubmitter());
    }

    @Issue("JENKINS-25779")
    @Test public void uninstantiate() throws Exception {
        InputStep s = new InputStep("hello world");
        assertEquals(Collections.singletonMap("message", s.getMessage()), DescribableModel.uninstantiate_(s));
    }

}