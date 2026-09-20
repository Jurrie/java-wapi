package org.jurr.java.wapi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class ScenarioTimingTest
{
	@Test
	void pauseTakesAtLeastItsDuration()
	{
		final var start = System.nanoTime();

		ScenarioTiming.pause(Duration.ofMillis(100));

		assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(100));
	}

	@Test
	void repeatForRepeatsTheStepOnTheCallingThreadForTheWholeTime()
	{
		final var steps = new AtomicInteger();
		final var caller = Thread.currentThread();
		final var start = System.nanoTime();

		ScenarioTiming.repeatFor(Duration.ofMillis(200), Duration.ofMillis(20), () -> {
			assertEquals(caller, Thread.currentThread());
			steps.incrementAndGet();
		});

		assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(200));
		// About one step per 20 ms; fewer when the machine is slow
		assertTrue(steps.get() >= 5, "steps: " + steps.get());
	}

	@Test
	void aFailingStepEndsTheScenario()
	{
		assertThrows(IllegalStateException.class, () -> ScenarioTiming.repeatFor(Duration.ofMillis(200), Duration.ofMillis(20), () -> {
			throw new IllegalStateException("step failed");
		}));
	}
}
