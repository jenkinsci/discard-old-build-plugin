/**
 *
 */
package org.jenkinsci.plugins.discardbuild;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.RunList;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builder that discards old build histories according to more detail configurations than the core function.
 * This enables discarding builds by build status or keeping older builds for every N builds / N days
 * or discarding buildswhich has too small or too big logfile size.
 *
 * @author tamagawahiroko
 */
public class DiscardBuildPublisher extends Recorder {

    /**
     * If not -1, history is only kept up to this days.
     */
    private final int daysToKeep;
    /**
     * If not -1, only this number of build logs are kept.
     */
    private final int numToKeep;
    /**
     * Set of build results to be kept.
     */
    private final Set<Result> resultsToDiscard;
    /**
     * If not -1, history is only kept up to this logfile size.
     */
    private final long minLogFileSize;
    /**
     * If not -1, history is only kept lower than this logfile size.
     */
    private final long maxLogFileSize;
    /**
     * If not -1, old histories are kept by the specified interval days.
     */
    private final int intervalDaysToKeep;
    /**
     * If not -1, old histories are kept by the specified interval builds.
     */
    private final int intervalNumToKeep;
    /**
     * If true, will keep the last builds.
     */
    private final boolean keepLastBuilds;
    /**
     * Regular expression.
     */
    private final String regexp;

    @DataBoundConstructor
    public DiscardBuildPublisher(
            String daysToKeep,
            String intervalDaysToKeep,
            String numToKeep,
            String intervalNumToKeep,
            boolean discardSuccess,
            boolean discardUnstable,
            boolean discardFailure,
            boolean discardNotBuilt,
            boolean discardAborted,
            String minLogFileSize,
            String maxLogFileSize,
            String regexp,
            boolean keepLastBuilds
    ) {

        this.daysToKeep = parse(daysToKeep);
        this.intervalDaysToKeep = parse(intervalDaysToKeep);
        this.numToKeep = parse(numToKeep);
        this.intervalNumToKeep = parse(intervalNumToKeep);

        resultsToDiscard = new HashSet<Result>();
        if (discardSuccess) {
            resultsToDiscard.add(Result.SUCCESS);
        }
        if (discardUnstable) {
            resultsToDiscard.add(Result.UNSTABLE);
        }
        if (discardFailure) {
            resultsToDiscard.add(Result.FAILURE);
        }
        if (discardNotBuilt) {
            resultsToDiscard.add(Result.NOT_BUILT);
        }
        if (discardAborted) {
            resultsToDiscard.add(Result.ABORTED);
        }

        this.minLogFileSize = parseLong(minLogFileSize);
        this.maxLogFileSize = parseLong(maxLogFileSize);

        this.regexp = regexp;
        this.keepLastBuilds = keepLastBuilds;
    }

    private static int parse(String p) {
        if (p == null) return -1;
        try {
            return Integer.parseInt(p);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long parseLong(String p) {
        if (p == null) return -1;
        try {
            return Long.parseLong(p);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isRegexpMatch(File logFile, String regexp) throws IOException, InterruptedException {
        if (regexp == null) return false;
        String line;
        Pattern pattern = Pattern.compile(regexp);
        BufferedReader reader = new BufferedReader(new FileReader(logFile));
        while ((line = reader.readLine()) != null) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) return true;
        }
        return false;
    }

    private static String intToString(int i) {
        if (i == -1) {
            return ""; //$NON-NLS-1$
        } else {
            return Integer.toString(i);
        }
    }

    private static String longToString(long i) {
        if (i == -1) {
            return ""; //$NON-NLS-1$
        } else {
            return Long.toString(i);
        }
    }

    class ExtendRunList extends RunList<Run<?, ?>> {
        private ArrayList<Run<?, ?>> newList;

        ExtendRunList() {
            newList = new ArrayList<Run<?, ?>>();
        }

        ArrayList<Run<?, ?>> getNewList() {
            return newList;
        }

        @Override
        public boolean add(Run<?, ?> run) {
            newList.add(run);
            return true;
        }
    }

    private ArrayList<Run<?, ?>> keepLastBuilds(AbstractBuild<?, ?> build, BuildListener listener, RunList<Run<?, ?>> builds) {
        Job<?, ?> job = (Job<?, ?>) build.getParent();

        ExtendRunList newList = new ExtendRunList();
        Run lastBuild = job.getLastBuild();
        Run lastCompletedBuild = job.getLastCompletedBuild();
        Run lastFailedBuild = job.getLastFailedBuild();
        Run lastStableBuild = job.getLastStableBuild();
        Run lastSuccessfulBuild = job.getLastSuccessfulBuild();
        Run lastUnstableBuild = job.getLastUnstableBuild();
        Run lastUnsuccessfulBuild = job.getLastUnsuccessfulBuild();

        for (Run<?, ?> r : builds) {
            if (r.isBuilding()) continue;
            if (r == lastBuild) continue;
            if (r == lastCompletedBuild) continue;
            if (r == lastFailedBuild) continue;
            if (r == lastStableBuild) continue;
            if (r == lastSuccessfulBuild) continue;
            if (r == lastUnstableBuild) continue;
            if (r == lastUnsuccessfulBuild) continue;
            newList.add(r);
        }

        return newList.getNewList();
    }

    private ArrayList<Run<?, ?>> discardLastBuilds(AbstractBuild<?, ?> build, BuildListener listener, RunList<Run<?, ?>> builds) {
        Job<?, ?> job = (Job<?, ?>) build.getParent();
        ExtendRunList newList = new ExtendRunList();
        for (Run<?, ?> r : builds) {
            newList.add(r);
        }
        return newList.getNewList();
    }

    private void deleteOldBuildsByRegexp(AbstractBuild<?, ?> build, BuildListener listener, String regexp) {
        ArrayList<Run<?, ?>> list = updateBuildsList(build, listener);
        if (regexp == null || regexp.equals("")) return;
        try {
            for (Run<?, ?> r : list) {
                if (isRegexpMatch(r.getLogFile(), regexp)) {
                    discardBuild(r, "match regular expression", listener);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void deleteOldBuildsByLogfileSize(AbstractBuild<?, ?> build, BuildListener listener, long minLogFileSize,
                                              long maxLogFileSize) {
        ArrayList<Run<?, ?>> list = updateBuildsList(build, listener);
        try {
            if (minLogFileSize != -1 || maxLogFileSize != -1) {
                for (Run<?, ?> r : list) {
                    long size = r.getLogFile().length();
                    if (minLogFileSize == -1 && size > maxLogFileSize)
                        discardBuild(r, "log file size=" + size + " which is too big", listener);
                    else if (maxLogFileSize == -1 && size < minLogFileSize)
                        discardBuild(r, "log file size=" + size + " which is too small", listener);
                    else if (minLogFileSize != -1 && maxLogFileSize != -1 && (size < minLogFileSize || size > maxLogFileSize)) {
                        discardBuild(r, "log file size=" + size + " which is too small or too big", listener);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void deleteOldBuildsByDays(AbstractBuild<?, ?> build, BuildListener listener, int daysToKeep) {
        ArrayList<Run<?, ?>> list = updateBuildsList(build, listener);
        if (daysToKeep == -1) return;
        try {
            Calendar cal = getCurrentCalendar();
            cal.add(Calendar.DAY_OF_YEAR, -daysToKeep);
            for (Run<?, ?> r : list) {
                if (r.getTimestamp().before(cal)) {
                    discardBuild(r, "it is older than daysToKeep", listener); //$NON-NLS-1$
                }
            }
        } catch (IOException e) {
            e.printStackTrace(listener.error("")); //$NON-NLS-1$
        }
    }

    private void deleteOldBuildsByIntervalDays(AbstractBuild<?, ?> build, BuildListener listener, int intervalDaysToKeep) {
        ArrayList<Run<?, ?>> list = updateBuildsList(build, listener);
        if (intervalDaysToKeep == -1) return;
        try {
            Run<?, ?> prev = null;

            for (Run<?, ?> r : list) {
                if (prev == null) {
                    prev = r; // The first build is the latest build
                    continue;
                } else {
                    Calendar prevCal = getCurrentCalendar();
                    prevCal.setTime(prev.getTimestamp().getTime());
                    prevCal.add(Calendar.DAY_OF_YEAR, -intervalDaysToKeep);
                    if (r.getTimestamp().after(prevCal)) {
                        discardBuild(r, "it is old and within build days interval", listener); //$NON-NLS-1$
                        continue;
                    }
                    prev = r;
                }
            }
        } catch (IOException e) {
            e.printStackTrace(listener.error("")); //$NON-NLS-1$
        }
    }

    private void deleteOldBuildsByNum(AbstractBuild<?, ?> build, BuildListener listener, int numToKeep) {
        ArrayList<Run<?, ?>> list = updateBuildsList(build, listener);
        if (numToKeep == -1) return;
        int index = 0;
        try {
            for (Run<?, ?> r : list) {
                if (index >= numToKeep)
                    discardBuild(r, "old than numToKeep", listener);
                index++;
            }
        } catch (IOException e) {
            e.printStackTrace(listener.error(""));
        }
    }

    private void deleteOldBuildsByIntervalNum(AbstractBuild<?, ?> build, BuildListener listener, int intervalNumToKeep) {
        ArrayList<Run<?, ?>> list = updateBuildsList(build, listener);
        if (intervalNumToKeep == -1) return;
        int index = 0;
        try {
            if (intervalNumToKeep == 1) intervalNumToKeep = 2;
            for (Run<?, ?> r : list) {
                if ((index % intervalNumToKeep) != 0) {
                    discardBuild(r, "it is old and within build number interval", listener);
                }
                index++;
            }
        } catch (IOException e) {
            e.printStackTrace(listener.error(""));
        }
    }

    private void deleteOldBuildsByStatus(AbstractBuild<?, ?> build, BuildListener listener, Set<Result> resultsToDiscard) {
        ArrayList<Run<?, ?>> list = updateBuildsList(build, listener);
        try {
            for (Run<?, ?> r : list) {
                discardByStatus(r, resultsToDiscard, listener);
            }
        } catch (IOException e) {
            e.printStackTrace(listener.error(""));
        }
    }

    private ArrayList<Run<?, ?>> updateBuildsList(AbstractBuild<?, ?> build, BuildListener listener) {
        RunList<Run<?, ?>> builds = new RunList<Run<?, ?>>();
        ArrayList<Run<?, ?>> list = new ArrayList<Run<?, ?>>();
        Job<?, ?> job = (Job<?, ?>) build.getParent();

        builds = (RunList<Run<?, ?>>) job.getBuilds();
        if (isKeepLastBuilds())
            list = keepLastBuilds(build, listener, builds);
        else
            list = discardLastBuilds(build, listener, builds);
        return list;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
        listener.getLogger().println("Discard old builds..."); //$NON-NLS-1$

        // priority influence discard results, TODO: dynamic adjust priority on UI
        deleteOldBuildsByDays(build, listener, daysToKeep);
        deleteOldBuildsByNum(build, listener, numToKeep);
        deleteOldBuildsByIntervalDays(build, listener, intervalDaysToKeep);
        deleteOldBuildsByIntervalNum(build, listener, intervalNumToKeep);
        deleteOldBuildsByStatus(build, listener, resultsToDiscard);
        deleteOldBuildsByLogfileSize(build, listener, minLogFileSize, maxLogFileSize);
        deleteOldBuildsByRegexp(build, listener, regexp);

        return true;
    }

    /**
     * Discard builds with status that doesn't meet the setting.
     *
     * @param history   build history to discard
     * @param resultSet set of results to be discard
     * @param listener  build listener
     * @return true if the build is discarded.
     * @throws IOException when deletion failed
     */
    private boolean discardByStatus(Run<?, ?> history, Set<Result> resultSet, BuildListener listener) throws IOException {
        if (!resultSet.isEmpty() && resultSet.contains(history.getResult())) {
            discardBuild(history, String.format("status %s is not to be kept", history.getResult()), listener); //$NON-NLS-1$
            return true;
        } else {
            return false;
        }
    }

    /**
     * Discard old build result with logging.
     *
     * @param history  build history to discard
     * @param reason   reason to discard
     * @param listener build listener
     * @throws IOException when deletion failed
     */
    private void discardBuild(Run<?, ?> history, String reason, BuildListener listener) throws IOException {
        listener.getLogger().printf("#%d is removed because %s\n", history.getNumber(), reason); //$NON-NLS-1$
        history.delete();
    }

    public String getDaysToKeep() {
        return intToString(daysToKeep);
    }

    public String getNumToKeep() {
        return intToString(numToKeep);
    }

    public String getMinLogFileSize() {
        return longToString(minLogFileSize);
    }

    public String getMaxLogFileSize() {
        return longToString(maxLogFileSize);
    }

    public String getIntervalDaysToKeep() {
        return intToString(intervalDaysToKeep);
    }

    public String getRegexp() {
        return regexp;
    }

    public String getIntervalNumToKeep() {
        return intToString(intervalNumToKeep);
    }

    public boolean isDiscardSuccess() {
        return resultsToDiscard.contains(Result.SUCCESS);
    }

    public boolean isDiscardUnstable() {
        return resultsToDiscard.contains(Result.UNSTABLE);
    }

    public boolean isDiscardFailure() {
        return resultsToDiscard.contains(Result.FAILURE);
    }

    public boolean isDiscardNotBuilt() {
        return resultsToDiscard.contains(Result.NOT_BUILT);
    }

    public boolean isDiscardAborted() {
        return resultsToDiscard.contains(Result.ABORTED);
    }

    public boolean isKeepLastBuilds() {
        return keepLastBuilds;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    /**
     * Descriptor for {@link DiscardBuildPublisher}. Used as a singleton. The class is
     * marked as public so that it can be accessed from views.
     * <p/>
     * <p/>
     * See
     * <tt>src/main/resources/hudson/plugins/hello_world/HelloWorldBuilder/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        @SuppressWarnings("rawtypes")
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return Messages.DiscardHistoryBuilder_description();
        }
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    // for test
    protected Calendar getCurrentCalendar() {
        return Calendar.getInstance();
    }
}