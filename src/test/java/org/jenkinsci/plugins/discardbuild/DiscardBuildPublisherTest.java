/**
 * 
 */
package org.jenkinsci.plugins.discardbuild;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.FreeStyleProject;
import hudson.util.RunList;

import java.io.PrintStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import junit.framework.TestCase;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Test for {@link DiscardBuildPublisher#perform(hudson.model.AbstractBuild, hudson.Launcher, hudson.model.BuildListener)}.
 * 
 * @author tamagawahiroko
 *
 */
public class DiscardBuildPublisherTest extends TestCase {
	
	private SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
	
	private Launcher launcher = mock(Launcher.class);
	private PrintStream logger = mock(PrintStream.class);
	private BuildListener listener = mock(BuildListener.class);
	private FreeStyleBuild build = mock(FreeStyleBuild.class);
	private FreeStyleProject job = mock(FreeStyleProject.class);
	private List<FreeStyleBuild> buildList = new ArrayList<FreeStyleBuild>();
	
	public void setUp() throws Exception {
		// setUp build histories
		buildList.add(createBuild(job, Result.SUCCESS, "20130120")); // #20
		buildList.add(createBuild(job, Result.FAILURE, "20130119")); // #19
		buildList.add(createBuild(job, Result.SUCCESS, "20130118")); // #18
		buildList.add(createBuild(job, Result.SUCCESS, "20130117")); // #17 // for daysToKeep
		buildList.add(createBuild(job, Result.FAILURE, "20130117")); // #16 // for daysToKeep
		buildList.add(createBuild(job, Result.SUCCESS, "20130117")); // #15
		buildList.add(createBuild(job, Result.SUCCESS, "20130114")); // #14
		buildList.add(createBuild(job, Result.SUCCESS, "20130113")); // #13
		buildList.add(createBuild(job, Result.SUCCESS, "20130112")); // #12
		buildList.add(createBuild(job, Result.FAILURE, "20130111")); // #11
		buildList.add(createBuild(job, Result.FAILURE, "20130110")); // #10
		buildList.add(createBuild(job, Result.SUCCESS, "20130109")); // #9
		buildList.add(createBuild(job, Result.SUCCESS, "20130108")); // #8
		buildList.add(createBuild(job, Result.SUCCESS, "20130107")); // #7
		buildList.add(createBuild(job, Result.SUCCESS, "20130106")); // #6
		buildList.add(createBuild(job, Result.SUCCESS, "20130105")); // #5
		buildList.add(createBuild(job, Result.FAILURE, "20130104")); // #4
		buildList.add(createBuild(job, Result.ABORTED,   "20130103")); // #3
		buildList.add(createBuild(job, Result.UNSTABLE,  "20130102")); // #2
		buildList.add(createBuild(job, Result.NOT_BUILT,   "20130101")); // #1

		when(listener.getLogger()).thenReturn(logger);
		when(job.getBuilds()).thenReturn(RunList.fromRuns(buildList));
		when(build.getParent()).thenReturn(job);
	}
	
	public void testPerformNoCondition() throws Exception {
		DiscardBuildPublisher publisher = getPublisher(new DiscardBuildPublisher(
				"", "",
				false, false, false, false, false,
				"", "",
				false, false, false, false, false));
		
		publisher.perform((AbstractBuild<?, ?>) build, launcher, listener);
		for (int i = 0; i < 20; i++) {
			verify(buildList.get(i), never()).delete();
		}
	}
	
	public void testPerformDaysToKeep() throws Exception {
		DiscardBuildPublisher publisher = getPublisher(new DiscardBuildPublisher(
				"3", "",
				false, false, false, false, false,
				"", "",
				false, false, false, false, false));
		
		publisher.perform((AbstractBuild<?, ?>) build, launcher, listener);
		for (int i = 0; i < 6; i++) {
			verify(buildList.get(i), never()).delete();
		}
		for (int i = 6; i < 20; i++) {
			verify(buildList.get(i), times(1)).delete();
		}
	}
	
	public void testPerformNumToKeep() throws Exception {
		DiscardBuildPublisher publisher = getPublisher(new DiscardBuildPublisher(
				"", "5",
				false, false, false, false, false,
				"", "",
				false, false, false, false, false));
		
		publisher.perform((AbstractBuild<?, ?>) build, launcher, listener);
		for (int i = 0; i < 5; i++) {
			verify(buildList.get(i), never()).delete();
		}
		for (int i = 5; i < 20; i++) {
			verify(buildList.get(i), times(1)).delete();
		}
	}
	
	public void testPerformStatusToKeep() throws Exception {
		DiscardBuildPublisher publisher = getPublisher(new DiscardBuildPublisher(
				"", "",
				false, true, false, true, true,		// unstable, not built, aborted
				"", "",
				false, false, false, false, false));
		
		publisher.perform((AbstractBuild<?, ?>) build, launcher, listener);
		for (int i = 0; i < 17; i++) {
			verify(buildList.get(i), times(1)).delete();
		}
		for (int i = 17; i < 20; i++) {
			verify(buildList.get(i), never()).delete();
		}
	}
	
	public void testPerformNumAndStatus() throws Exception {
		DiscardBuildPublisher publisher = getPublisher(new DiscardBuildPublisher(
				"", "5",
				false, false, true, false, false,		// failure
				"", "",
				false, false, true, false, false));		// failure
		
		publisher.perform((AbstractBuild<?, ?>) build, launcher, listener);

		verify(buildList.get(0), times(1)).delete();
		verify(buildList.get(1), never()).delete(); // new build
		verify(buildList.get(2), times(1)).delete();
		verify(buildList.get(3), times(1)).delete();
		verify(buildList.get(4), never()).delete(); // new build
		verify(buildList.get(5), times(1)).delete();
		verify(buildList.get(6), times(1)).delete();
		verify(buildList.get(7), times(1)).delete();
		verify(buildList.get(8), times(1)).delete();
		verify(buildList.get(9), never()).delete(); // new build
		verify(buildList.get(10), never()).delete(); // new build
		verify(buildList.get(11), times(1)).delete();
		verify(buildList.get(12), times(1)).delete();
		verify(buildList.get(13), times(1)).delete();
		verify(buildList.get(14), times(1)).delete();
		verify(buildList.get(15), times(1)).delete();
		verify(buildList.get(16), never()).delete(); // new build
		verify(buildList.get(17), times(1)).delete();
		verify(buildList.get(18), times(1)).delete();
		verify(buildList.get(19), times(1)).delete();
	}
	
	public void testPerformIntervalDaysToKeep() throws Exception {
		DiscardBuildPublisher publisher = getPublisher(new DiscardBuildPublisher(
				"3", "",
				false, false, false, false, false,
				"4", "",
				false, false, false, false, false));
		
		publisher.perform((AbstractBuild<?, ?>) build, launcher, listener);

		verify(buildList.get(0), never()).delete(); // new build
		verify(buildList.get(1), never()).delete(); // new build
		verify(buildList.get(2), never()).delete(); // new build
		verify(buildList.get(3), never()).delete(); // new build
		verify(buildList.get(4), never()).delete(); // new build
		verify(buildList.get(5), never()).delete(); // new build
		verify(buildList.get(6), times(1)).delete();
		verify(buildList.get(7), never()).delete(); // to keep
		verify(buildList.get(8), times(1)).delete();
		verify(buildList.get(9), times(1)).delete();
		verify(buildList.get(10), times(1)).delete();
		verify(buildList.get(11), never()).delete(); // to keep
		verify(buildList.get(12), times(1)).delete();
		verify(buildList.get(13), times(1)).delete();
		verify(buildList.get(14), times(1)).delete();
		verify(buildList.get(15), never()).delete(); // to keep
		verify(buildList.get(16), times(1)).delete();
		verify(buildList.get(17), times(1)).delete();
		verify(buildList.get(18), times(1)).delete();
		verify(buildList.get(19), never()).delete(); // to keep
	}
	
	public void testPerformIntervalNumToKeep() throws Exception {
		DiscardBuildPublisher publisher = getPublisher(new DiscardBuildPublisher(
				"", "5",
				false, false, false, false, false,
				"", "3",
				false, false, false, false, false));
		
		publisher.perform((AbstractBuild<?, ?>) build, launcher, listener);

		verify(buildList.get(0), never()).delete(); // new build
		verify(buildList.get(1), never()).delete(); // new build
		verify(buildList.get(2), never()).delete(); // new build
		verify(buildList.get(3), never()).delete(); // new build
		verify(buildList.get(4), never()).delete(); // new build
		verify(buildList.get(5), times(1)).delete();
		verify(buildList.get(6), times(1)).delete();
		verify(buildList.get(7), never()).delete(); // to keep
		verify(buildList.get(8), times(1)).delete();
		verify(buildList.get(9), times(1)).delete();
		verify(buildList.get(10), never()).delete(); // to keep
		verify(buildList.get(11), times(1)).delete();
		verify(buildList.get(12), times(1)).delete();
		verify(buildList.get(13), never()).delete(); // to keep
		verify(buildList.get(14), times(1)).delete();
		verify(buildList.get(15), times(1)).delete();
		verify(buildList.get(16), never()).delete(); // to keep
		verify(buildList.get(17), times(1)).delete();
		verify(buildList.get(18), times(1)).delete();
		verify(buildList.get(19), never()).delete(); // to keep
	}
	
	public void testPerformStatusToKeepOld() throws Exception {
		DiscardBuildPublisher publisher = getPublisher(new DiscardBuildPublisher(
				"", "5",
				false, false, false, false, false,
				"", "",
				false, false, true, false, false));	// keep failure
		
		publisher.perform((AbstractBuild<?, ?>) build, launcher, listener);

		verify(buildList.get(0), never()).delete(); // new build
		verify(buildList.get(1), never()).delete(); // new build
		verify(buildList.get(2), never()).delete(); // new build
		verify(buildList.get(3), never()).delete(); // new build
		verify(buildList.get(4), never()).delete(); // new build
		verify(buildList.get(5), times(1)).delete();
		verify(buildList.get(6), times(1)).delete();
		verify(buildList.get(7), times(1)).delete();
		verify(buildList.get(8), times(1)).delete();
		verify(buildList.get(9), never()).delete(); // to keep
		verify(buildList.get(10), never()).delete(); // to keep
		verify(buildList.get(11), times(1)).delete();
		verify(buildList.get(12), times(1)).delete();
		verify(buildList.get(13), times(1)).delete();
		verify(buildList.get(14), times(1)).delete();
		verify(buildList.get(15), times(1)).delete();
		verify(buildList.get(16), never()).delete(); // to keep
		verify(buildList.get(17), times(1)).delete();
		verify(buildList.get(18), times(1)).delete();
		verify(buildList.get(19), times(1)).delete();
	}
	
	private FreeStyleBuild createBuild(FreeStyleProject project, Result result, String yyyymmdd) throws Exception {
		FreeStyleBuild build = spy(new FreeStyleBuild(project));
		
		when(build.getResult()).thenReturn(result);
		Calendar cal = Calendar.getInstance();
		cal.setTime(sdf.parse(yyyymmdd));
		when(build.getTimestamp()).thenReturn(cal);
		doNothing().when(build).delete();

		return build;
	}
	
	private DiscardBuildPublisher getPublisher(DiscardBuildPublisher publisher) throws Exception {
		DiscardBuildPublisher spy = spy(publisher);
		
		when(spy.getCurrentCalendar()).thenAnswer(new Answer() {
		     public Object answer(InvocationOnMock invocation) {
		    	 Calendar cal = Calendar.getInstance();
		 		try {
					cal.setTime(sdf.parse("20130120"));
				} catch (ParseException e) {
					new RuntimeException(e);
				}
		 		return cal;
		     }
		 });
		return spy;
	}

	private Calendar createCalendar() throws Exception {
		Calendar cal = Calendar.getInstance();
		cal.setTime(sdf.parse("20130120"));
		return cal;
	}
}
