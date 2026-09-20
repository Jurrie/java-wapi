package org.jurr.java.wapi.client;

import java.time.Duration;

import org.awaitility.Awaitility;

/**
 * Timing that a test scenario itself is about, such as a console that pauses between two halves of
 * a frame, or a client that keeps writing for a while. It is not waiting for a result: for that,
 * use Awaitility's {@code await().until(...)} directly.
 */
public final class ScenarioTiming
{
	/** Room on top of the scenario's own time, so that a slow machine does not end the scenario early. */
	private static final Duration MARGIN = Duration.ofSeconds(5);

	private ScenarioTiming()
	{
	}

	/**
	 * Lets the scenario pause, on the calling thread.
	 *
	 * @param duration how long the scenario pauses
	 */
	public static void pause(final Duration duration)
	{
		Awaitility.await("scenario pause of " + duration)
				.pollInSameThread()
				.pollDelay(duration)
				.atMost(duration.plus(MARGIN))
				.until(() -> true);
	}

	/**
	 * Repeats a step of the scenario at a steady pace, on the calling thread, such as a write every
	 * 20 ms for 400 ms.
	 *
	 * @param total how long the scenario keeps repeating the step
	 * @param interval time between the starts of two steps
	 * @param step the step; an exception ends the scenario and fails the test
	 */
	public static void repeatFor(final Duration total, final Duration interval, final Runnable step)
	{
		Awaitility.await("scenario repeated every " + interval + " for " + total)
				.pollInSameThread()
				.pollDelay(Duration.ZERO)
				.pollInterval(interval)
				.during(total)
				.atMost(total.plus(MARGIN))
				.until(() -> {
					step.run();
					return true;
				});
	}
}
