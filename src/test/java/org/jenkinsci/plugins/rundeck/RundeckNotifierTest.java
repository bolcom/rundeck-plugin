package org.jenkinsci.plugins.rundeck;

import hudson.FilePath;
import hudson.model.Action;
import hudson.model.Build;
import hudson.model.FreeStyleBuild;
import hudson.model.Result;
import hudson.model.Cause.UpstreamCause;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import hudson.scm.SubversionSCM;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.time.DateUtils;
import org.jenkinsci.plugins.rundeck.RundeckNotifier.RundeckExecutionBuildBadgeAction;
import org.junit.Assert;
import org.jvnet.hudson.test.HudsonHomeLoader.CopyExisting;
import org.jvnet.hudson.test.HudsonTestCase;
import org.jvnet.hudson.test.MockBuilder;
import org.rundeck.api.RundeckApiException;
import org.rundeck.api.RundeckApiException.RundeckApiLoginException;
import org.rundeck.api.RundeckClient;
import org.rundeck.api.domain.RundeckExecution;
import org.rundeck.api.domain.RundeckExecution.ExecutionStatus;
import org.rundeck.api.domain.RundeckJob;
import org.rundeck.api.domain.RundeckLogEntry;
import org.rundeck.api.domain.RundeckLogSegment;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.wc.SVNClientManager;

/**
 * Test the {@link RundeckNotifier}
 * 
 * @author Vincent Behar
 */
public class RundeckNotifierTest extends HudsonTestCase {

    public void testCommitWithoutTag() throws Exception {
        RundeckNotifier notifier = new RundeckNotifier("1", createOptions(), null, "", false, false, false);
        notifier.getDescriptor().setRundeckInstance(new MockRundeckClient());

        FreeStyleProject project = createFreeStyleProject();
        project.getBuildersList().add(new MockBuilder(Result.SUCCESS));
        project.getPublishersList().add(notifier);
        project.setScm(createScm());

        // first build
        FreeStyleBuild build = assertBuildStatusSuccess(project.scheduleBuild2(0).get());
        assertTrue(buildContainsAction(build, RundeckExecutionBuildBadgeAction.class));
        String s = FileUtils.readFileToString(build.getLogFile());
        assertTrue(s.contains("Notifying RunDeck..."));
        assertTrue(s.contains("Notification succeeded !"));

        addScmCommit(build.getWorkspace(), "commit message");

        // second build
        build = assertBuildStatusSuccess(project.scheduleBuild2(0).get());
        assertTrue(buildContainsAction(build, RundeckExecutionBuildBadgeAction.class));
        s = FileUtils.readFileToString(build.getLogFile());
        assertTrue(s.contains("Notifying RunDeck..."));
        assertTrue(s.contains("Notification succeeded !"));
    }

    public void testStandardCommitWithTag() throws Exception {
        RundeckNotifier notifier = new RundeckNotifier("1", null, null, "#deploy", false, false, false);
        notifier.getDescriptor().setRundeckInstance(new MockRundeckClient());

        FreeStyleProject project = createFreeStyleProject();
        project.getBuildersList().add(new MockBuilder(Result.SUCCESS));
        project.getPublishersList().add(notifier);
        project.setScm(createScm());

        // first build
        FreeStyleBuild build = assertBuildStatusSuccess(project.scheduleBuild2(0).get());
        assertFalse(buildContainsAction(build, RundeckExecutionBuildBadgeAction.class));
        String s = FileUtils.readFileToString(build.getLogFile());
        assertFalse(s.contains("Notifying RunDeck"));

        addScmCommit(build.getWorkspace(), "commit message");

        // second build
        build = assertBuildStatusSuccess(project.scheduleBuild2(0).get());
        assertFalse(buildContainsAction(build, RundeckExecutionBuildBadgeAction.class));
        s = FileUtils.readFileToString(build.getLogFile());
        assertFalse(s.contains("Notifying RunDeck"));
    }

    public void testDeployCommitWithTagWontBreakTheBuild() throws Exception {
        RundeckNotifier notifier = new RundeckNotifier("1", null, null, "#deploy", false, false, false);
        notifier.getDescriptor().setRundeckInstance(new MockRundeckClient());

        FreeStyleProject project = createFreeStyleProject();
        project.getBuildersList().add(new MockBuilder(Result.SUCCESS));
        project.getPublishersList().add(notifier);
        project.setScm(createScm());

        // first build
        FreeStyleBuild build = assertBuildStatusSuccess(project.scheduleBuild2(0).get());
        assertFalse(buildContainsAction(build, RundeckExecutionBuildBadgeAction.class));
        String s = FileUtils.readFileToString(build.getLogFile());
        assertFalse(s.contains("Notifying RunDeck"));

        addScmCommit(build.getWorkspace(), "commit message - #deploy");

        // second build
        build = assertBuildStatusSuccess(project.scheduleBuild2(0).get());
        assertTrue(buildContainsAction(build, RundeckExecutionBuildBadgeAction.class));
        s = FileUtils.readFileToString(build.getLogFile());
        assertTrue(s.contains("Notifying RunDeck..."));
        assertTrue(s.contains("#deploy"));
        assertTrue(s.contains("Notification succeeded !"));
    }

    public void testDeployCommitWithTagWillBreakTheBuild() throws Exception {
        RundeckNotifier notifier = new RundeckNotifier("1", null, null, "#deploy", false, true, false);
        notifier.getDescriptor().setRundeckInstance(new MockRundeckClient() {

            private static final long serialVersionUID = 1L;

            @Override
            public RundeckExecution triggerJob(String jobId, Properties options, Properties nodeFilters)
                    throws RundeckApiException {
                throw new RundeckApiException("Fake error for testing");
            }

        });

        FreeStyleProject project = createFreeStyleProject();
        project.getBuildersList().add(new MockBuilder(Result.SUCCESS));
        project.getPublishersList().add(notifier);
        project.setScm(createScm());

        // first build
        FreeStyleBuild build = assertBuildStatusSuccess(project.scheduleBuild2(0).get());
        assertFalse(buildContainsAction(build, RundeckExecutionBuildBadgeAction.class));
        String s = FileUtils.readFileToString(build.getLogFile());
        assertFalse(s.contains("Notifying RunDeck"));

        addScmCommit(build.getWorkspace(), "commit message - #deploy");

        // second build
        build = assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0).get());
        assertFalse(buildContainsAction(build, RundeckExecutionBuildBadgeAction.class));
        s = FileUtils.readFileToString(build.getLogFile());
        assertTrue(s.contains("Notifying RunDeck..."));
        assertTrue(s.contains("#deploy"));
        assertTrue(s.contains("Error while talking to RunDeck's API"));
        assertTrue(s.contains("Fake error for testing"));
    }

    public void testExpandEnvVarsInOptions() throws Exception {
        RundeckNotifier notifier = new RundeckNotifier("1", createOptions(), null, null, false, true, false);
        notifier.getDescriptor().setRundeckInstance(new MockRundeckClient() {

            private static final long serialVersionUID = 1L;

            @Override
            public RundeckExecution triggerJob(String jobId, Properties options, Properties nodeFilters) {
                Assert.assertEquals(4, options.size());
                Assert.assertEquals("value 1", options.getProperty("option1"));
                Assert.assertEquals("1", options.getProperty("buildNumber"));
                Assert.assertEquals("my project name", options.getProperty("jobName"));
                return super.triggerJob(jobId, options, nodeFilters);
            }

        });

        FreeStyleProject project = createFreeStyleProject("my project name");
        project.getBuildersList().add(new MockBuilder(Result.SUCCESS));
        project.getPublishersList().add(notifier);
        project.setScm(createScm());

        // first build
        FreeStyleBuild build = assertBuildStatusSuccess(project.scheduleBuild2(0).get());
        assertTrue(buildContainsAction(build, RundeckExecutionBuildBadgeAction.class));
        String s = FileUtils.readFileToString(build.getLogFile());
        assertTrue(s.contains("Notifying RunDeck..."));
        assertTrue(s.contains("Notification succeeded !"));
    }

    public void testUpstreamBuildWithTag() throws Exception {
        RundeckNotifier notifier = new RundeckNotifier("1", null, null, "#deploy", false, false, false);
        notifier.getDescriptor().setRundeckInstance(new MockRundeckClient());

        FreeStyleProject upstream = createFreeStyleProject("upstream");
        upstream.getBuildersList().add(new MockBuilder(Result.SUCCESS));
        upstream.setScm(createScm());

        FreeStyleProject project = createFreeStyleProject();
        project.getBuildersList().add(new MockBuilder(Result.SUCCESS));
        project.getPublishersList().add(notifier);
        project.setScm(createScm());

        // first build
        FreeStyleBuild upstreamBuild = assertBuildStatusSuccess(upstream.scheduleBuild2(0).get());
        FreeStyleBuild build = assertBuildStatusSuccess(project.scheduleBuild2(0,
                                                                               new UpstreamCause((Run<?, ?>) upstreamBuild))
                                                               .get());
        assertFalse(buildContainsAction(build, RundeckExecutionBuildBadgeAction.class));
        String s = FileUtils.readFileToString(build.getLogFile());
        assertFalse(s.contains("Notifying RunDeck"));

        addScmCommit(upstreamBuild.getWorkspace(), "commit message - #deploy");

        // second build
        upstreamBuild = assertBuildStatusSuccess(upstream.scheduleBuild2(0).get());
        build = assertBuildStatusSuccess(project.scheduleBuild2(0, new UpstreamCause((Run<?, ?>) upstreamBuild)).get());
        assertTrue(buildContainsAction(build, RundeckExecutionBuildBadgeAction.class));
        s = FileUtils.readFileToString(build.getLogFile());
        assertTrue(s.contains("Notifying RunDeck..."));
        assertTrue(s.contains("#deploy"));
        assertTrue(s.contains("in upstream build (" + upstreamBuild.getFullDisplayName() + ")"));
        assertTrue(s.contains("Notification succeeded !"));
    }

    public void testFailedBuild() throws Exception {
        RundeckNotifier notifier = new RundeckNotifier("1", createOptions(), null, "", false, false, false);
        notifier.getDescriptor().setRundeckInstance(new MockRundeckClient());

        FreeStyleProject project = createFreeStyleProject();
        project.getBuildersList().add(new MockBuilder(Result.FAILURE));
        project.getPublishersList().add(notifier);
        project.setScm(createScm());

        // first build
        FreeStyleBuild build = assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0).get());
        assertFalse(buildContainsAction(build, RundeckExecutionBuildBadgeAction.class));
        String s = FileUtils.readFileToString(build.getLogFile());
        assertFalse(s.contains("Notifying RunDeck"));

        addScmCommit(build.getWorkspace(), "commit message");

        // second build
        build = assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0).get());
        assertFalse(buildContainsAction(build, RundeckExecutionBuildBadgeAction.class));
        s = FileUtils.readFileToString(build.getLogFile());
        assertFalse(s.contains("Notifying RunDeck"));
    }

    public void testWaitForRundeckJob() throws Exception {
        RundeckNotifier notifier = new RundeckNotifier("1", createOptions(), null, "", true, false, false);
        notifier.getDescriptor().setRundeckInstance(new MockRundeckClient());

        FreeStyleProject project = createFreeStyleProject();
        project.getBuildersList().add(new MockBuilder(Result.SUCCESS));
        project.getPublishersList().add(notifier);
        project.setScm(createScm());

        // first build
        FreeStyleBuild build = assertBuildStatusSuccess(project.scheduleBuild2(0).get());
        assertTrue(buildContainsAction(build, RundeckExecutionBuildBadgeAction.class));
        String s = FileUtils.readFileToString(build.getLogFile());
        assertTrue(s.contains("Notifying RunDeck..."));
        assertTrue(s.contains("Notification succeeded !"));
        assertTrue(s.contains("Waiting for RunDeck execution to finish..."));
        assertTrue(s.contains("RunDeck execution #1 finished in 3 minutes 27 seconds, with status : SUCCEEDED"));
    }

    public void testTailJobLogging() throws Exception {
      RundeckNotifier notifier = new RundeckNotifier("1", createOptions(), null, "", true, false, false);
      notifier.getDescriptor().setRundeckInstance(new MockLoggingRundeckClient());
  
      FreeStyleProject project = createFreeStyleProject();
      project.getBuildersList().add(new MockBuilder(Result.SUCCESS));
      project.getPublishersList().add(notifier);
      project.setScm(createScm());
  
      // first build
      FreeStyleBuild build = assertBuildStatusSuccess(project.scheduleBuild2(0).get());
      assertTrue(buildContainsAction(build, RundeckExecutionBuildBadgeAction.class));
      String s = FileUtils.readFileToString(build.getLogFile());
      System.out.println(s);
      assertTrue(s.contains("Notifying RunDeck..."));
      assertTrue(s.contains("Notification succeeded !"));
      assertTrue(s.contains("Waiting for RunDeck execution to finish..."));
      assertTrue(s.contains("--------- RunDeck execution output: start ---------"));
      assertTrue(s.contains(">>>> Finished Command <<<<"));
      assertTrue(s.contains("--------- RunDeck execution output: end ---------"));
      assertTrue(s.contains("RunDeck execution #1 finished in 3 minutes 27 seconds, with status : SUCCEEDED"));
    }
    
    public void testDoNotTailJobLogging() throws Exception {
      RundeckNotifier notifier = new RundeckNotifier("1", createOptions(), null, "", true, false, true);
      notifier.getDescriptor().setRundeckInstance(new MockRundeckClient());
  
      FreeStyleProject project = createFreeStyleProject();
      project.getBuildersList().add(new MockBuilder(Result.SUCCESS));
      project.getPublishersList().add(notifier);
      project.setScm(createScm());
  
      // first build
      FreeStyleBuild build = assertBuildStatusSuccess(project.scheduleBuild2(0).get());
      assertTrue(buildContainsAction(build, RundeckExecutionBuildBadgeAction.class));
      String s = FileUtils.readFileToString(build.getLogFile());
      assertTrue(s.contains("Notifying RunDeck..."));
      assertTrue(s.contains("Notification succeeded !"));
      assertTrue(s.contains("Waiting for RunDeck execution to finish..."));
      assertTrue(s.contains("(RunDeck execution output will not be tailed)"));
      assertTrue(s.contains("RunDeck execution #1 finished in 3 minutes 27 seconds, with status : SUCCEEDED"));
    }
    
    public void testLackOfNPEsInLogTailing() throws Exception {
      RundeckNotifier notifier = new RundeckNotifier("1", createOptions(), null, "", true, false, null);
      notifier.getDescriptor().setRundeckInstance(new MockRundeckClient());
  
      FreeStyleProject project = createFreeStyleProject();
      project.getBuildersList().add(new MockBuilder(Result.SUCCESS));
      project.getPublishersList().add(notifier);
      project.setScm(createScm());
  
      // first build
      FreeStyleBuild build = assertBuildStatusSuccess(project.scheduleBuild2(0).get());
      assertTrue(buildContainsAction(build, RundeckExecutionBuildBadgeAction.class));
      String s = FileUtils.readFileToString(build.getLogFile());
      assertTrue(s.contains("RunDeck execution #1 finished in 3 minutes 27 seconds, with status : SUCCEEDED"));
    }

    private String createOptions() {
        Properties options = new Properties();
        options.setProperty("option1", "value 1");
        options.setProperty("workspace", "$WORKSPACE");
        options.setProperty("jobName", "$JOB_NAME");
        options.setProperty("buildNumber", "$BUILD_NUMBER");

        StringWriter writer = new StringWriter();
        try {
            options.store(writer, "this is a comment line");
            return writer.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private SubversionSCM createScm() throws Exception {
        File emptyRepository = new CopyExisting(getClass().getResource("empty-svn-repository.zip")).allocate();
        return new SubversionSCM("file://" + emptyRepository.getPath());
    }

    private void addScmCommit(FilePath workspace, String commitMessage) throws Exception {
        SVNClientManager svnm = SubversionSCM.createSvnClientManager();

        FilePath newFilePath = workspace.child("new-file");
        File newFile = new File(newFilePath.getRemote());
        newFilePath.touch(System.currentTimeMillis());
        svnm.getWCClient().doAdd(newFile, false, false, false, SVNDepth.INFINITY, false, false);
        svnm.getCommitClient().doCommit(new File[] { newFile },
                                        false,
                                        commitMessage,
                                        null,
                                        null,
                                        false,
                                        false,
                                        SVNDepth.EMPTY);
    }

    /**
     * @param build
     * @param actionClass
     * @return true if the given build contains an action of the given actionClass
     */
    private boolean buildContainsAction(Build<?, ?> build, Class<?> actionClass) {
        for (Action action : build.getActions()) {
            if (actionClass.isInstance(action)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Just a mock {@link RundeckClient} which is always successful
     */
    private static class MockRundeckClient extends RundeckClient {

        private static final long serialVersionUID = 1L;

        public MockRundeckClient() {
            super("http://localhost:4440", "admin", "admin");
        }

        @Override
        public void ping() {
            // successful
        }

        @Override
        public void testCredentials() {
            // successful
        }

        @Override
        public RundeckExecution triggerJob(String jobId, Properties options, Properties nodeFilters) {
            return initExecution(ExecutionStatus.RUNNING);
        }

        @Override
        public RundeckExecution getExecution(Long executionId) {
            return initExecution(ExecutionStatus.SUCCEEDED);
        }

        @Override
        public RundeckJob getJob(String jobId) {
            RundeckJob job = new RundeckJob();
            return job;
        }
        
        private RundeckExecution initExecution(ExecutionStatus status) {
            RundeckExecution execution = new RundeckExecution();
            execution.setId(1L);
            execution.setUrl("http://localhost:4440/execution/follow/1");
            execution.setStatus(status);
            execution.setStartedAt(new Date(1310159014640L));
            if (ExecutionStatus.SUCCEEDED.equals(status)) {
                Date endedAt = execution.getStartedAt();
                endedAt = DateUtils.addMinutes(endedAt, 3);
                endedAt = DateUtils.addSeconds(endedAt, 27);
                execution.setEndedAt(endedAt);
            }
            return execution;
        }
    }
    
    /**
     * A mock {@link RundeckClient} that also returns mock logging.
     */
    private static class MockLoggingRundeckClient extends MockRundeckClient {
      private static final long serialVersionUID = 1L;
      private int logRequestCounter;
  
      @Override
      public RundeckLogSegment getLogTail(long jobId, long lastOffset) throws RundeckApiException, RundeckApiLoginException, IllegalArgumentException {
        logRequestCounter++;
        switch (logRequestCounter) {
          default:
            return new RundeckLogSegment(jobId, 1L, false, false, false, "RARING", new Date(0), 0L, 0f, 1l, getLogEntryWithText("Beginning"));
          case 2:
            return new RundeckLogSegment(jobId, 2L, false, false, false, "TIRED", new Date(1), 1L, 80f, 2L, getLogEntryWithText("Almost done"));
          case 3:
            return new RundeckLogSegment(jobId, 3L, false, true, false, "SPENT", new Date(2), 2L, 90f, 3L, getLogEntryWithText("Finished"));
          case 4:
            return new RundeckLogSegment(jobId, 3L, true, true, false, "SPENT", new Date(4), 3L, 100f, 4L, Collections.<RundeckLogEntry> emptyList());
        }
      }
  
      @SuppressWarnings("serial")
      private List<RundeckLogEntry> getLogEntryWithText(final String entryText) {
        return new ArrayList<RundeckLogEntry>() {
          {
            add(new RundeckLogEntry("damn late", "test", "testUser", entryText + " Command", "testNode", entryText));
          }
        };
      }
    }
}
