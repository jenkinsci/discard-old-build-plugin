/**
 * 
 */
package org.jenkinsci.plugins.discardbuild;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.Run;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.RunList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Builder that discards old build histories according to more detail configurations than the core function.
 * This enables discarding builds by build status or keeping older builds for every N builds / N days.
 * 
 * @author tamagawahiroko
 *
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
	private final Set<Result> resultsToKeep;
	
	/**
	 * If not -1, old histories are kept by the specified interval days.
	 */
	private final int intervalDaysToKeep;
	/**
	 * If not -1, old histories are kept by the specified interval builds.
	 */
	private final int intervalNumToKeep;
	/**
	 * Set of build result to be kept for old builds.
	 */
	private final Set<Result> resultsToKeepOld;
	
	@DataBoundConstructor
	public DiscardBuildPublisher(
			String daysToKeep,
			String numToKeep,
			boolean keepSuccess,
			boolean keepUnstable,
			boolean keepFailure,
			boolean keepNotBuilt,
			boolean keepAborted,
			String intervalDaysToKeep,
			String intervalNumToKeep,
			boolean keepSuccessOld,
			boolean keepUnstableOld,
			boolean keepFailureOld,
			boolean keepNotBuiltOld,
			boolean keepAbortedOld
			) {

		this.daysToKeep = parse(daysToKeep);
		this.numToKeep = parse(numToKeep);
		this.intervalDaysToKeep = parse(intervalDaysToKeep);
		this.intervalNumToKeep = parse(intervalNumToKeep);
		
		resultsToKeep = new HashSet<Result>();
		if (keepSuccess) { resultsToKeep.add(Result.SUCCESS); }
		if (keepUnstable) { resultsToKeep.add(Result.UNSTABLE); }
		if (keepFailure) { resultsToKeep.add(Result.FAILURE); }
		if (keepNotBuilt) { resultsToKeep.add(Result.NOT_BUILT); }
		if (keepAborted) { resultsToKeep.add(Result.ABORTED); }
		
		resultsToKeepOld = new HashSet<Result>();
		if (keepSuccessOld) { resultsToKeepOld.add(Result.SUCCESS); }
		if (keepUnstableOld) { resultsToKeepOld.add(Result.UNSTABLE); }
		if (keepFailureOld) { resultsToKeepOld.add(Result.FAILURE); }
		if (keepNotBuiltOld) { resultsToKeepOld.add(Result.NOT_BUILT); }
		if (keepAbortedOld) { resultsToKeepOld.add(Result.ABORTED); }
				
	}

    private static int parse(String p) {
        if(p==null)     return -1;
        try {
            return Integer.parseInt(p);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
    
    private static String intToString(int i) {
    	if (i == -1) {
    		return ""; //$NON-NLS-1$
    	} else {
    		return Integer.toString(i);
    	}
    }

	@Override
	public boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener) {
		listener.getLogger().println("Discard old builds..."); //$NON-NLS-1$
		
		Job<?, ?> job = (Job<?, ?>) build.getParent();
		Run<?, ?> lsb = job.getLastSuccessfulBuild();
		Run<?, ?> lstb = job.getLastStableBuild();
		
		// Reverse builds in order to avoid deleting all old builds at next build.
		@SuppressWarnings("unchecked")
		RunList<Run<?, ?>> builds = (RunList<Run<?, ?>>) job.getBuilds();
		
		try {
			// identify latest builds to keep
			int latestBuilds = 0;
			List<String> oldBuilds = new ArrayList<String>();
			boolean isLatest = true;
			Calendar cal = getCurrentCalendar();
			cal.add(Calendar.DAY_OF_YEAR, -daysToKeep);
			for (Run<?, ?> r : builds) {
				if (isLatest) {
					if ((numToKeep != -1 && latestBuilds >= numToKeep) ||
							(daysToKeep != -1 && r.getTimestamp().before(cal))) {
						oldBuilds.add(0, intToString(builds.indexOf(r)));
						isLatest = false;
						continue;
					} else if (discardByStatus(r, resultsToKeep, listener)) {
						continue;
					} else {
                        latestBuilds++;
					}
				} else {
					oldBuilds.add(0, intToString(builds.indexOf(r)));
				}
			}

			// advanced option
			Run<?, ?> prev = null;
			int prevIndex = 0;
			
			for (int i = 0; i < oldBuilds.size(); i++) {
                int oldBuild = parse(oldBuilds.get(i));
                for (Run<?, ?> r : builds) {
                    if (builds.indexOf(r) == oldBuild) {
                        if (r == lsb || r == lstb) {
                            // Always keep latest successful / stable builds
                            continue;
                        } else if (intervalNumToKeep == -1 && intervalDaysToKeep == -1 && resultsToKeepOld.isEmpty()) {
                            discardBuild(r, "it is too old and no advanced option is set", listener); //$NON-NLS-1$
                        } else {
                            if (discardByStatus(r, resultsToKeepOld, listener)) {
                                continue;
                            } else if (prev == null) {
                                prev = r;
                                prevIndex = i;
                                continue;
                            } else if (intervalNumToKeep != -1 && i < prevIndex + intervalNumToKeep) {
                                discardBuild(r, "it is old and within build number interval", listener); //$NON-NLS-1$
                                continue;
                            } else if (intervalDaysToKeep != -1) {
                                Calendar intervalCal = getCurrentCalendar();
                                intervalCal.setTime(prev.getTimestamp().getTime());
                                intervalCal.add(Calendar.DAY_OF_YEAR, intervalDaysToKeep);
                                if (r.getTimestamp().before(intervalCal)) {
                                    discardBuild(r, "it is old and within build days interval", listener); //$NON-NLS-1$
                                    continue;
                                }
                            }
                            prev = r;
                            prevIndex = i;
                        }
                    }
                }
			}
		} catch (IOException e) {
			e.printStackTrace(listener.error("")); //$NON-NLS-1$
		}
		
		return true;
	}
	
	/**
	 * Discard builds with status that doesn't meet the setting.
	 * 
	 * @param history		build history to discard
	 * @param resultSet		set of results to be kept
	 * @param listener		build listener
	 * @return true if the build is discarded.
	 * @throws IOException	when deletion failed
	 */
	private boolean discardByStatus(Run<?, ?> history, Set<Result> resultSet, BuildListener listener) throws IOException {
		if (!resultSet.isEmpty() && !resultSet.contains(history.getResult())) {
			discardBuild(history, String.format("status %s is not to be kept", history.getResult()), listener); //$NON-NLS-1$
			return true;
		} else {
			return false;
		}
	}
	
	/**
	 * Discard old build result with logging.
	 * 
	 * @param history	build history to discard
	 * @param reason	reason to discard
	 * @param listener	build listener
	 * @throws IOException	when deletion failed
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

	public String getIntervalDaysToKeep() {
		return intToString(intervalDaysToKeep);
	}

	public String getIntervalNumToKeep() {
		return intToString(intervalNumToKeep);
	}
	
	public boolean isKeepSuccess() {
		return resultsToKeep.contains(Result.SUCCESS);
	}
	
	public boolean isKeepUnstable() {
		return resultsToKeep.contains(Result.UNSTABLE);
	}
	
	public boolean isKeepFailure() {
		return resultsToKeep.contains(Result.FAILURE);
	}
	
	public boolean isKeepNotBuilt() {
		return resultsToKeep.contains(Result.NOT_BUILT);
	}
	
	public boolean isKeepAborted() {
		return resultsToKeep.contains(Result.ABORTED);
	}
	
	public boolean isKeepSuccessOld() {
		return resultsToKeepOld.contains(Result.SUCCESS);
	}
	
	public boolean isKeepUnstableOld() {
		return resultsToKeepOld.contains(Result.UNSTABLE);
	}
	
	public boolean isKeepFailureOld() {
		return resultsToKeepOld.contains(Result.FAILURE);
	}
	
	public boolean isKeepNotBuiltOld() {
		return resultsToKeepOld.contains(Result.NOT_BUILT);
	}
	
	public boolean isKeepAbortedOld() {
		return resultsToKeepOld.contains(Result.ABORTED);
	}

	@Override
	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl) super.getDescriptor();
	}

	/**
	 * Descriptor for {@link DiscardBuildPublisher}. Used as a singleton. The class is
	 * marked as public so that it can be accessed from views.
	 * 
	 * <p>
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
		return BuildStepMonitor.BUILD;
	}
	
	// for test
	protected Calendar getCurrentCalendar() {
		return Calendar.getInstance();
	}
}
