package io.jenkins.plugins.omniauth;

import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Guards against a regression where one of our {@link jenkins.model.GlobalConfiguration}
 * stores ships without a {@code config.jelly}. The global "Manage Jenkins / System" page does
 * an {@code <st:include page="config.jelly"/>} over every GlobalConfiguration extension, so a
 * missing view there makes the whole page error out (JellyTagException "No page found
 * 'config.jelly'"). All our stores (assignments, roles, JIT requests, provisioning requests,
 * plugin settings) are persistence-only and contribute an empty view; this test loads the page
 * to prove it renders.
 */
@WithJenkins
public class GlobalConfigPageRenderTest {

    @Test
    public void globalConfigurePageRenders(JenkinsRule j) throws Exception {
        // WebClient.goTo() throws FailingHttpStatusCodeException on any non-2xx response, so this
        // call fails the test if the System page errors (e.g. a GlobalConfiguration missing its view).
        j.createWebClient().goTo("configure");
    }
}
