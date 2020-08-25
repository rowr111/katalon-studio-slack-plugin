package com.katalon.plugin.slack;

import org.apache.commons.io.FilenameUtils;
import org.osgi.service.event.Event;

import java.io.File;
import java.util.*;
import com.katalon.platform.api.event.EventListener;
import com.katalon.platform.api.event.ExecutionEvent;
import com.katalon.platform.api.exception.ResourceException;
import com.katalon.platform.api.execution.TestSuiteExecutionContext;
import com.katalon.platform.api.extension.EventListenerInitializer;
import com.katalon.platform.api.preference.PluginPreference;

public class SlackEventListenerInitializer implements EventListenerInitializer, SlackComponent {

    String authToken;
    String channel;
    String reportDir;

    @Override
    public void registerListener(EventListener listener) {
        listener.on(Event.class, event -> {
            try {
                PluginPreference preferences = getPluginStore();
                boolean isIntegrationEnabled = preferences.getBoolean(SlackConstants.PREF_IS_SLACK_ENABLED, false);
                if (!isIntegrationEnabled) {
                    return;
                }
                authToken = preferences.getString(SlackConstants.PREF_AUTH_TOKEN, "");
                String channelsString = preferences.getString(SlackConstants.PREF_AUTH_CHANNEL, "");
                String[] channelsArray = channelsString.split("\\s*,\\s*");
                reportDir = preferences.getString(SlackConstants.PREF_REPORT_DIR, "");
                if (ExecutionEvent.TEST_SUITE_FINISHED_EVENT.equals(event.getTopic())) {
                    for (String currentChannel : channelsArray) {
                        String extension;
                        if (currentChannel.contains(".html")) {
                            extension = "html";
                            channel = currentChannel.replaceFirst(".html", "");
                        } else if (currentChannel.contains(".none")) {
                            extension = "none";
                            channel = currentChannel.replaceFirst(".none", "");
                        } else if (currentChannel.contains(".csv")) {
                            extension = "csv";
                            channel = currentChannel.replaceFirst(".csv", "");
                        } else {
                            extension = "pdf";
                            channel = currentChannel;  
                        }
                        ExecutionEvent eventObject = (ExecutionEvent) event.getProperty("org.eclipse.e4.data");

                        TestSuiteExecutionContext testSuiteContext = (TestSuiteExecutionContext) eventObject
                                .getExecutionContext();
                        TestSuiteStatusSummary testSuiteSummary = TestSuiteStatusSummary.of(testSuiteContext);
                        System.out.println("Slack: Start sending summary message to channel: " + channel);
                        String messageTitle = "*Summary execution result of test suite:* `" + testSuiteContext.getSourceId() + "`, *ID:* `" + testSuiteContext.getId() + "`";
                        String messageTotalTestCases = "\nTotal test cases: " + Integer.toString(testSuiteSummary.getTotalTestCases());
                        String messagePassedTestCases =  "\nTotal passes: " + Integer.toString(testSuiteSummary.getTotalPasses());
                        String messageFailedTestCases =   "\nTotal failures: " + Integer.toString(testSuiteSummary.getTotalFailures());
                        String messageErroredTestCases =   "";
                        String messageSkippedTestCases =   "";
                        if (testSuiteSummary.getTotalFailures() == 0) {
                            messageFailedTestCases = "\n:heavy_check_mark: All test cases passed.";
                        }
                        if (testSuiteSummary.getTotalErrors() != 0) {
                            messageErroredTestCases = "\nTotal errors: " + Integer.toString(testSuiteSummary.getTotalErrors());
                        }
                        if (testSuiteSummary.getTotalSkipped() != 0) {
                            messageSkippedTestCases = "\nTotal skipped: " + Integer.toString(testSuiteSummary.getTotalSkipped());
                        }

                        String finalMessage = messageTitle + messageTotalTestCases + messagePassedTestCases + messageFailedTestCases + messageErroredTestCases + messageSkippedTestCases;
                        SlackUtil.sendMessage(authToken, channel, finalMessage);
                        System.out.println("Slack: Summary message has been successfully sent to channel: " + channel);

                        // check and see if report exists, if so, and if extension is not none, send it:
                        reportDir.trim();
                        if(!reportDir.isEmpty() && !(extension == "none")){
                            File dir = new File(reportDir);
                            FolderFinder(dir, testSuiteContext, extension);
                        }
                    }
                }
            } catch (ResourceException | SlackException e) {
                e.printStackTrace(System.err);
            }
        });
    }

    //dig through folders to find the correct report folder for this execution
    //necessary because Suite Collections nest the folder under other IDs' folders
    public void FolderFinder(File dir, TestSuiteExecutionContext context, String extension) throws SlackException {
        File[] directoryListing = dir.listFiles();
        if (directoryListing != null) {
            for (File child : directoryListing) {
                if (child.isDirectory()) {
                   if(child.getName().equals(context.getId())){
                        ReportFinder(child, context, extension);
                   }
                   else{
                       FolderFinder(child, context, extension);
                   }
                }
            }
        }
    }

    //dig through report folder to find the reports
    public void ReportFinder(File dir, TestSuiteExecutionContext context, String extension) throws SlackException {
        File[] directoryListing = dir.listFiles();
        if (directoryListing != null) {
            for (File child : directoryListing) {
                if (child.isDirectory()) {
                    ReportFinder(child, context, extension);
                } else {
                    String fileName = FilenameUtils.getBaseName(child.getName());
                    String contextId = context.getId();
                    String ext = FilenameUtils.getExtension(child.getName());
                    if (ext.equals(extension) && contextId.equals(fileName)) {
                        SlackUtil.sendFile(authToken, channel, context.getSourceId(), context.getId(), child);
                        System.out.println("Slack: Summary attachment file has been successfully sent to channel: " + channel);  
                    }
                }
            }
        }
    }
}