 /*
 *
 * Autopsy Forensic Browser
 * 
 * Copyright 2012-2014 Basis Technology Corp.
 * 
 * Copyright 2012 42six Solutions.
 * Contact: aebadirad <at> 42six <dot> com
 * Project Contact/Architect: carrier <at> sleuthkit <dot> org
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.recentactivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.ingest.DataSourceIngestModule;
import org.sleuthkit.autopsy.ingest.DataSourceIngestModuleStatusHelper;
import org.sleuthkit.autopsy.ingest.IngestServices;
import org.sleuthkit.autopsy.ingest.IngestMessage;
import org.sleuthkit.autopsy.ingest.IngestMessage.MessageType;
import org.sleuthkit.datamodel.Content;
import org.sleuthkit.autopsy.ingest.IngestModule.ResultCode;
import org.sleuthkit.autopsy.ingest.IngestModuleAdapter;
import org.sleuthkit.autopsy.ingest.IngestJobContext;

/**
 * Recent activity image ingest module
 */
public final class RAImageIngestModule extends IngestModuleAdapter implements DataSourceIngestModule {

    private static final Logger logger = Logger.getLogger(RAImageIngestModule.class.getName());
    private static int messageId = 0;
    private final List<Extract> extracters = new ArrayList<>();
    private final List<Extract> browserExtracters = new ArrayList<>();
    private IngestServices services;
    private StringBuilder subCompleted = new StringBuilder();

    RAImageIngestModule() {
    }

    synchronized int getNextMessageId() {
        return ++messageId;
    }
    
    @Override
    public ResultCode process(Content dataSource, DataSourceIngestModuleStatusHelper controller) {
        services.postMessage(IngestMessage.createMessage(getNextMessageId(), MessageType.INFO, RecentActivityExtracterModuleFactory.getModuleName(), "Started " + dataSource.getName()));

        controller.switchToDeterminate(extracters.size());
        controller.progress(0);
        ArrayList<String> errors = new ArrayList<>();

        for (int i = 0; i < extracters.size(); i++) {
            Extract extracter = extracters.get(i);
            if (controller.isCancelled()) {
                logger.log(Level.INFO, "Recent Activity has been canceled, quitting before {0}", extracter.getName());
                break;
            }

            try {
                extracter.process(dataSource, controller);
            } catch (Exception ex) {
                logger.log(Level.SEVERE, "Exception occurred in " + extracter.getName(), ex);
                subCompleted.append(extracter.getName()).append(" failed - see log for details <br>");
                errors.add(extracter.getName() + " had errors -- see log");
            }
            controller.progress(i + 1);
            errors.addAll(extracter.getErrorMessages());
        }

        // create the final message for inbox
        StringBuilder errorMessage = new StringBuilder();
        String errorMsgSubject;
        MessageType msgLevel = MessageType.INFO;
        if (errors.isEmpty() == false) {
            msgLevel = MessageType.ERROR;
            errorMessage.append("<p>Errors encountered during analysis: <ul>\n");
            for (String msg : errors) {
                errorMessage.append("<li>").append(msg).append("</li>\n");
            }
            errorMessage.append("</ul>\n");

            if (errors.size() == 1) {
                errorMsgSubject = "1 error found";
            } else {
                errorMsgSubject = errors.size() + " errors found";
            }
        } else {
            errorMessage.append("<p>No errors encountered.</p>");
            errorMsgSubject = "No errors reported";
        }
        final IngestMessage msg = IngestMessage.createMessage(getNextMessageId(), msgLevel, RecentActivityExtracterModuleFactory.getModuleName(), "Finished " + dataSource.getName() + " - " + errorMsgSubject, errorMessage.toString());
        services.postMessage(msg);

        StringBuilder historyMsg = new StringBuilder();
        historyMsg.append("<p>Browser Data on ").append(dataSource.getName()).append(":<ul>\n");
        for (Extract module : browserExtracters) {
            historyMsg.append("<li>").append(module.getName());
            historyMsg.append(": ").append((module.foundData()) ? " Found." : " Not Found.");
            historyMsg.append("</li>");
        }
        historyMsg.append("</ul>");
        final IngestMessage inboxMsg = IngestMessage.createMessage(getNextMessageId(), MessageType.INFO, RecentActivityExtracterModuleFactory.getModuleName(), dataSource.getName() + " - Browser Results", historyMsg.toString());
        services.postMessage(inboxMsg);

        return ResultCode.OK;
    }

    @Override
    public void shutDown(boolean ingestJobCancelled) {
        if (ingestJobCancelled) {
            stop();
            return;
        }
        
        for (int i = 0; i < extracters.size(); i++) {
            Extract extracter = extracters.get(i);
            try {
                extracter.complete();
            } catch (Exception ex) {
                logger.log(Level.SEVERE, "Exception occurred when completing " + extracter.getName(), ex);
                subCompleted.append(extracter.getName()).append(" failed to complete - see log for details <br>");
            }
        }
    }

    @Override
    public void startUp(IngestJobContext context) throws Exception {
        services = IngestServices.getDefault();

        Extract registry = new ExtractRegistry();
        Extract iexplore = new ExtractIE();
        Extract recentDocuments = new RecentDocumentsByLnk();
        Extract chrome = new Chrome();
        Extract firefox = new Firefox();
        Extract SEUQA = new SearchEngineURLQueryAnalyzer();

        extracters.add(chrome);
        extracters.add(firefox);
        extracters.add(iexplore);
        extracters.add(recentDocuments);
        extracters.add(SEUQA); // this needs to run after the web browser modules
        extracters.add(registry); // this runs last because it is slowest

        browserExtracters.add(chrome);
        browserExtracters.add(firefox);
        browserExtracters.add(iexplore);
        
       for (Extract extracter : extracters) {
            extracter.init();
        }        
    }

    private void stop() {
        for (Extract extracter : extracters) {
            try {
                extracter.stop();
            } catch (Exception ex) {
                logger.log(Level.SEVERE, "Exception during stop() of " + extracter.getName(), ex);
            }
        }
        logger.log(Level.INFO, "Recent Activity processes has been shutdown.");
    }

    /**
     * Get the temp path for a specific sub-module in recent activity. Will
     * create the dir if it doesn't exist.
     *
     * @param a_case Case that directory is for
     * @param mod Module name that will be used for a sub folder in the temp
     * folder to prevent name collisions
     * @return Path to directory
     */
    protected static String getRATempPath(Case a_case, String mod) {
        String tmpDir = a_case.getTempDirectory() + File.separator + "RecentActivity" + File.separator + mod;
        File dir = new File(tmpDir);
        if (dir.exists() == false) {
            dir.mkdirs();
        }
        return tmpDir;
    }

    /**
     * Get the output path for a specific sub-module in recent activity. Will
     * create the dir if it doesn't exist.
     *
     * @param a_case Case that directory is for
     * @param mod Module name that will be used for a sub folder in the temp
     * folder to prevent name collisions
     * @return Path to directory
     */
    protected static String getRAOutputPath(Case a_case, String mod) {
        String tmpDir = a_case.getModulesOutputDirAbsPath() + File.separator + "RecentActivity" + File.separator + mod;
        File dir = new File(tmpDir);
        if (dir.exists() == false) {
            dir.mkdirs();
        }
        return tmpDir;
    }
}
