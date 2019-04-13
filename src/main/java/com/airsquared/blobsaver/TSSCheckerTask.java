/*
 * Copyright (c) 2019  airsquared
 *
 * This file is part of blobsaver.
 *
 * blobsaver is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * blobsaver is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with blobsaver.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.airsquared.blobsaver;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.airsquared.blobsaver.Controller.errorBorder;
import static com.airsquared.blobsaver.Shared.containsIgnoreCase;
import static com.airsquared.blobsaver.Shared.copyStreamToFile;
import static com.airsquared.blobsaver.Shared.getAllSignedVersions;
import static com.airsquared.blobsaver.Shared.getTSSChecker;
import static com.airsquared.blobsaver.Shared.githubIssue;
import static com.airsquared.blobsaver.Shared.isNullOrEmpty;
import static com.airsquared.blobsaver.Shared.newReportableError;
import static com.airsquared.blobsaver.Shared.newUnreportableError;
import static com.airsquared.blobsaver.Shared.redditPM;
import static com.airsquared.blobsaver.Shared.reportError;
import static com.airsquared.blobsaver.Shared.resizeAlertButtons;

public class TSSCheckerTask extends Task {

    private final String ecid, savePath, deviceIdentifier, boardConfig, device, apnonce, ipswURL;

    private String[] versionsToSave;
    private File buildManifestPlist;

    // to remove weird symbols from tsscheckerLog output
    // Otherwise, pasting the output in some applications (like Sublime Text) won't work
    private static final String NON_PRINTABLE_NON_WHITESPACE = "[^\\p{Print}\n\r]+";

    // to setup terminal emulator for PtyProcessBuilder
    private static final Map<String, String> XTERM_ENV = Collections.singletonMap("TERM", "xterm");

    public TSSCheckerTask(String ecid,
                          String savePath,
                          String deviceIdentifier,
                          String boardConfig,
                          String device,
                          String apnonce,
                          String ipswURL,
                          String... versionsToSave) {
        this.ecid = ecid;
        this.savePath = savePath;
        this.deviceIdentifier = deviceIdentifier;
        this.versionsToSave = versionsToSave;
        this.boardConfig = boardConfig;
        this.apnonce = apnonce;
        this.device = device;
        this.ipswURL = ipswURL;
    }

    @Override
    protected Object call() throws Exception {
        //TODO: check if deviceIdentifier is valid first (can be invalid if manually specified)
        checkIdentifier(deviceIdentifier);
        updateTitle("Searching for signed iOS versions...");
        for (int i = 0; i < getVersionsToSave().length; i++) {
            updateTitle("Saving iOS " + getVersionsToSave()[i] + " blobs... " +
                    "(" + (i + 1) + "/" + getVersionsToSave()[i] + ")"); // e.g. prints (1/3) if first of 3 blobs
            saveBlobs(deviceIdentifier, getVersionsToSave()[i]);
        }
        return true;
    }

    private void saveBlobs(String device, String version) {
        if (isNullOrEmpty(device)) {
            return;
        }

        //noinspection ResultOfMethodCallIgnored
        new File(savePath).mkdirs();
        ArrayList<String> args = new ArrayList<>(Arrays.asList(getTSSChecker().getPath(),
                "--generator", "0x1111111111111111",
                "--nocache",
                "-d", device,
                "-s",
                "-e", ecid,
                "--save-path", savePath,
                "-i", version));
        if (usingCustomBoardConfig()) {
            Collections.addAll(args, "--boardconfig", boardConfig);
        }
        if (usingCustomAPNonce()) {
            Collections.addAll(args, "--apnonce", apnonce);
        }

        if (usingBeta()) {
            buildManifestPlist = generateBuildManifest();
            Collections.addAll(args, "-i", version, "--beta", "--buildid", buildID, "-m", buildManifestPlist.toString());
        }

        String tsscheckerLog;
        try {
            System.out.println("Running: " + args.toString());
            tsscheckerLog = executeTSSChecker(args.toArray(new String[0]))
                    .replaceAll(NON_PRINTABLE_NON_WHITESPACE, "");
            if (Main.SHOW_BREAKPOINT) { //temporary until progress bar done
                StringSelection stringSelection = new StringSelection(tsscheckerLog);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(stringSelection, null);
            }
        } catch (IOException e) {
            e.printStackTrace();
            deleteBuildManifest();
            throw new TSSCheckerException.Reportable("There was an error starting TSSChecker.",
                    e,
                    true);
        }
        interpretResult(tsscheckerLog);
        deleteBuildManifest();
//        throw new TSSCheckerException(); // why throw exception?
    }

    private File generateBuildManifest() {
        try {
            if (!ipswURL.matches("https?://.*apple.*\\.ipsw")) {
                throw TSSCheckerException.invalidURLException(ipswURL);
            }
            buildManifestPlist = File.createTempFile("BuildManifest", ".plist");
            ZipInputStream zin;
            try {
                URL url = new URL(ipswURL);
                zin = new ZipInputStream(url.openStream());
            } catch (IOException e) {
                deleteBuildManifest();
                throw TSSCheckerException.invalidURLException(ipswURL);
            }
            ZipEntry ze;
            while ((ze = zin.getNextEntry()) != null) {
                if ("BuildManifest.plist".equals(ze.getName())) {
                    copyStreamToFile(zin, buildManifestPlist);
                    break;
                }
            }
            if (buildManifestPlist == null) {
                throw new TSSCheckerException.Reportable("Unable to find BuildManifest.plist from inputted .ipsw file\n\n" +
                        "Please check your internet connection and if the IPSW url is correct.\n\n" +
                        "If that doesn't work, please create a new issue on Github or PM me on Reddit.",
                        "#ipswField", //maybe have to add buttons :( fix the Exception class
                        false);
            }
            buildManifestPlist.deleteOnExit(); // deletes buildManifest.plist when blobsaver exits
        } catch (IOException e) {
            e.printStackTrace(); // not a good idea to printStackTrace too much
            deleteBuildManifest();
            throw new TSSCheckerException.Reportable("Unable to get BuildManifest from .ipsw URL", e, true);
        }
        return buildManifestPlist;
    }

    private void interpretResult(String tsscheckerLog) {
        if (containsIgnoreCase(tsscheckerLog, "Saved")) {
            return;
        }
        if (tsscheckerLog.contains("[Error] [TSSC] manually specified ecid=" + ecid + ", but parsing failed")) {
            throw new TSSCheckerException.Unreportable("\"" + ecid + "\"" + " is not a valid ECID. " +
                    "Try getting it from iTunes.\n\n" +
                    "If this was done to test whether the preset works in the background, " +
                    "please cancel that preset, fix the error, and try again.",
                    "#ecidField",
                    true);
//            newUnreportableError("\"" + ecid + "\"" + " is not a valid ECID. Try getting it from iTunes.\n\nIf this was done to test whether the preset works in the background, please cancel that preset, fix the error, and try again.");
//            controller.ecidField.setEffect(errorBorder);
        } else if (tsscheckerLog.contains("could not be found in devicelist")) {
            throw new TSSCheckerException.Reportable("TSSChecker could not find device: \"" + device + "\"\n\n" +
                    "Please create a new Github issue or PM me on Reddit if you used the dropdown menu.\n\n" +
                    "If this was done to test whether the preset works in the background, " +
                    "please cancel that preset, fix the error, and try again.",
                    false);
//            Alert alert = new Alert(Alert.AlertType.ERROR, , githubIssue, redditPM, ButtonType.CANCEL);
//            resizeAlertButtons(alert);
//            alert.showAndWait();
//            reportError(alert);
        } else if (containsIgnoreCase(tsscheckerLog, "[TSSC] ERROR: could not get url for device " + device + " on iOS " + version)) {
            throw new TSSCheckerException.Unreportable("Could not find device \"" + device + "\" on iOS/tvOS " + version +
                    "\n\nThe version doesn't exist or isn't compatible with the device",
                    "#versionField",
                    true);
//            newUnreportableError("Could not find device \"" + device + "\" on iOS/tvOS " + version +
//                    "\n\nThe version doesn't exist or isn't compatible with the device");
//            controller.versionField.setEffect(errorBorder);
        } else if (tsscheckerLog.contains("[Error] [TSSC] manually specified apnonce=" + apnonce + ", but parsing failed")) {
            throw new TSSCheckerException.Unreportable("\"" + apnonce + "\" is not a valid apnonce",
                    "#apnonceField",
                    true);
//            newUnreportableError("\"" + apnonce + "\" is not a valid apnonce");
//            controller.apnonceField.setEffect(errorBorder);
        } else if (tsscheckerLog.contains("[WARNING] [TSSC] could not get id0 for installType=Erase. Using fallback installType=Update since user did not specify installType manually")
                && tsscheckerLog.contains("[Error] [TSSR] Error: could not get id0 for installType=Update")
                && (containsIgnoreCase(tsscheckerLog, "[Error] [TSSR] faild to build tssrequest")
                || containsIgnoreCase(tsscheckerLog, "[Error] [TSSR] faild to build TSS request")) //switched tssrequest -> TSS request in the latest version
                && containsIgnoreCase(tsscheckerLog, "Error] [TSSC] checking tss status failed!")) {
            throw new TSSCheckerException.Reportable("Saving blobs failed. " +
                    "Check the board configuration or try again later.\n\n" +
                    "If this doesn't work, please create a new issue on Github or PM me on Reddit. " +
                    "The log has been copied to your clipboard.\n\n" +
                    "If this was done to test whether the preset works in the background, " +
                    "please cancel that preset, fix the error, and try again.",
                    "#boardConfigField",
                    false);
//            Alert alert = new Alert(Alert.AlertType.ERROR,
//                    "Saving blobs failed. Check the board configuration or try again later.\n\nIf this doesn't work, please create a new issue on Github or PM me on Reddit. The log has been copied to your clipboard.\n\nIf this was done to test whether the preset works in the background, please cancel that preset, fix the error, and try again.",
//                    githubIssue, redditPM, ButtonType.OK);
//            resizeAlertButtons(alert);
//            alert.showAndWait();
//            reportError(alert, tsscheckerLog);
        } else if (tsscheckerLog.contains("[Error] ERROR: TSS request failed: Could not resolve host:")) {
            throw new TSSCheckerException.Reportable("Saving blobs failed. Check your internet connection.\n\n" +
                    "If your internet is working and you can connect to apple.com in your browser, " +
                    "please create a new issue on Github or PM me on Reddit. " +
                    "The log has been copied to your clipboard.\n\n" +
                    "If this was done to test whether the preset works in the background, " +
                    "please cancel that preset, fix the error, and try again.",
                    false);
//            Alert alert = new Alert(Alert.AlertType.ERROR,
//                    "Saving blobs failed. Check your internet connection.\n\nIf your internet is working and you can connect to apple.com in your browser, please create a new issue on Github or PM me on Reddit. The log has been copied to your clipboard.\n\nIf this was done to test whether the preset works in the background, please cancel that preset, fix the error, and try again.",
//                    githubIssue, redditPM, ButtonType.OK);
//            resizeAlertButtons(alert);
//            alert.showAndWait();
//            reportError(alert, tsscheckerLog);
        }

        //it can be "can't save signing tickets at [location]" or "can't save shsh blobs at [location]".
        // This is the only place where the output "can't save" is present in tsschecker
        else if (containsIgnoreCase(tsscheckerLog, "[Error] can't save")) {
            throw new TSSCheckerException.Unreportable("\'" + savePath + "\' is not a valid path\n\n" +
                    "If this was done to test whether the preset works in the background, " +
                    "please cancel that preset, fix the error, and try again.",
                    "#pathField",
                    true);
//            newUnreportableError("\'" + savePath + "\' is not a valid path\n\nIf this was done to test whether the preset works in the background, please cancel that preset, fix the error, and try again.");
//            controller.pathField.setEffect(errorBorder);
        } else if (tsscheckerLog.contains("iOS " + version + " for device " + device + " IS NOT being signed!")
                || tsscheckerLog.contains("Build " + buildID + " for device" + device + " IS NOT being signed!")) {
            String errorMessage = "iOS/tvOS " + version + " is not being signed for device " + device;
//            newUnreportableError("iOS/tvOS " + version + " is not being signed for device " + device);
            if (!savingAllSignedVersions()) {
                String invalidElement = "#versionField";
            }
            if (savingBeta()) {
                controller.buildIDField.setEffect(errorBorder);
                controller.ipswField.setEffect(errorBorder);
            }
        } else if (tsscheckerLog.contains("[Error] [TSSC] failed to load manifest")) {
            throw new TSSCheckerException.Reportable("Failed to load manifest.\n\n " +
                    "\"" + ipswURL + "\" might not be a valid URL.\n\n" +
                    "Make sure it starts with \"http://\" or \"https://\", " +
                    "has \"apple\" in it, " +
                    "and ends with \".ipsw\"\n\n" +
                    "If the URL is fine, please create a new issue on Github or PM me on Reddit. " +
                    "The log has been copied to your clipboard",
                    false);
//            Alert alert = new Alert(Alert.AlertType.ERROR,
//                    "Failed to load manifest.\n\n \"" + ipswURL + "\" might not be a valid URL.\n\nMake sure it starts with \"http://\" or \"https://\", has \"apple\" in it, and ends with \".ipsw\"\n\nIf the URL is fine, please create a new issue on Github or PM me on Reddit. The log has been copied to your clipboard",
//                    githubIssue, redditPM, ButtonType.OK);
//            resizeAlertButtons(alert);
//            alert.showAndWait();
//            reportError(alert, tsscheckerLog);
        } else if (tsscheckerLog.contains("[Error] [TSSC] selected device can't be used with that buildmanifest")) {
            throw new TSSCheckerException.Unreportable("Device and build manifest don't match.", true);
//            newUnreportableError("Device and build manifest don't match.");
        } else if (tsscheckerLog.contains("[Error]")) {
            throw new TSSCheckerException.Reportable("Saving blobs failed.\n\n" +
                    "If this was done to test whether the preset works in the background, " +
                    "please cancel that preset, fix the error, and try again.",
                    true);
//            newReportableError("Saving blobs failed.\n\nIf this was done to test whether the preset works in the background, please cancel that preset, fix the error, and try again.", tsscheckerLog);
        } else {
            throw new TSSCheckerException.Reportable("Unknown result.\n\n" +
                    "If this was done to test whether the preset works in the background, " +
                    "please cancel that preset, fix the error, and try again.",
                    true);
//            newReportableError("Unknown result.\n\nIf this was done to test whether the preset works in the background, please cancel that preset, fix the error, and try again.", tsscheckerLog);
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void deleteBuildManifest() {
        if (buildManifestPlist != null && buildManifestPlist.exists()) {
            buildManifestPlist.delete();
            buildManifestPlist = null;
        }
    }

    private static String executeTSSChecker(String... command) throws IOException {
        StringBuilder tsscheckerOutput = new StringBuilder();
        PtyProcessBuilder tsscheckerProcessBuilder = new PtyProcessBuilder(command)
                .setEnvironment(XTERM_ENV)
                .setRedirectErrorStream(true);
        PtyProcess tsscheckerProcess = tsscheckerProcessBuilder.start();
        BufferedReader in = new BufferedReader(
                new InputStreamReader(tsscheckerProcess.getInputStream(), StandardCharsets.UTF_8));
        String sysStr;
        try {
            while ((sysStr = in.readLine()) != null) {
                tsscheckerOutput.append(sysStr).append('\n'); //Pty doesn't add \n at the ends of lines
                System.out.println(sysStr);
            }
        } catch (IOException e) {
            throw new TSSCheckerException.Reportable("Encountered IO exception " +
                    "while reading TSSChecker output.",
                    true);
        }
        try {
            tsscheckerProcess.waitFor();
        } catch (InterruptedException e) {
            throw new TSSCheckerException.Reportable("TSSChecker was interrupted " +
                    "while waiting for TSSChecker to finish executing.",
                    true);
        }
        return tsscheckerOutput.toString();
    }

    @Override
    protected void succeeded() {
        super.succeeded();
        updateMessage("Done!");
    }

    @Override
    protected void cancelled() {
        super.cancelled();
        updateMessage("Cancelled!");
    }

    @Override
    protected void failed() {
        super.failed();
        updateMessage("Failed!");
    }

    public String[] getVersionsToSave() {
        if (versionsToSave == null) {
            try {
                setVersionsToSave(getAllSignedVersions(deviceIdentifier).toArray(new String[0]));
            } catch (IOException e) {
                throw new TSSCheckerException("Saving blobs failed. Check your internet connection.\n\n" +
                        "If your internet is working and you can connect to the website ipsw.me in your browser, " +
                        "please create a new issue on Github or PM me on Reddit. " +
                        "The log has been copied to your clipboard.",
                        e,
                        false); //message, errorMessage, appendMessage
            }
        }
        return versionsToSave;
    }

    public void setVersionsToSave(String[] versionsToSave) {
        this.versionsToSave = versionsToSave;
    }
}
