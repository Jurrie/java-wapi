package org.jurr.java.wapi.client;

import java.time.Duration;

/** Timeout checks and deadlines of the client's waits. */
final class Timeouts
{
	/** Deadline of a wait without timeout. */
	static final long FOREVER = Long.MAX_VALUE;

	private Timeouts()
	{
	}

	/**
	 * {@return the {@link System#nanoTime()} at which the timeout passes}
	 *
	 * @param timeout non-null, non-negative timeout
	 * @throws IllegalArgumentException if the timeout is {@code null} or negative
	 */
	static long deadline(final Duration timeout)
	{
		requireTimeout(timeout);
		final long now = System.nanoTime();
		try
		{
			return Math.addExact(now, timeout.toNanos());
		}
		catch (final ArithmeticException e)
		{
			// Hundreds of years
			return FOREVER - 1;
		}
	}

	/** @throws IllegalArgumentException if the timeout is {@code null} or negative */
	static void requireTimeout(final Duration timeout)
	{
		if (timeout == null || timeout.isNegative())
		{
			throw new IllegalArgumentException("Timeout must be zero or positive: " + timeout);
		}
	}
}
