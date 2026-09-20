package org.jurr.java.wapi.client;

import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.client.meter.WapiMeterReceiver;
import org.jurr.java.wapi.meter.WapiMeterData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Receives meter reports on a background thread: each report is queued for {@code wGetMeters} and
 * handed to a consumer, such as the meter listeners. A malformed datagram is skipped.
 */
final class MeterReader implements AutoCloseable
{
	private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private final WapiMeterReceiver receiver;
	private final BoundedQueue<WapiMeterData> reports;
	private final Consumer<WapiMeterData> delivered;
	private final Thread thread;
	private volatile boolean closed;

	/**
	 * Opens the UDP port and starts the reader thread.
	 *
	 * @param port port to bind, with zero requesting an ephemeral port
	 * @param capacity maximum number of reports kept for {@link #poll(Duration)}; the oldest is dropped
	 * @param delivered receives each report on the reader thread, after it is queued
	 * @throws WapiException if the port cannot be bound
	 */
	MeterReader(final int port, final int capacity, final Consumer<WapiMeterData> delivered)
	{
		receiver = new WapiMeterReceiver(port);
		reports = new BoundedQueue<>(capacity);
		this.delivered = delivered;
		thread = new Thread(this::read, "wapi-meter-reader");
		thread.setDaemon(true);
		thread.start();
	}

	/** {@return the bound UDP port} */
	int port()
	{
		return receiver.getPort();
	}

	/**
	 * Takes the oldest received report, waiting up to the timeout when none is queued.
	 *
	 * @param timeout maximum wait; {@link Duration#ZERO} does not wait
	 * @return the report, or empty when none arrived in time or the reader is closed
	 */
	Optional<WapiMeterData> poll(final Duration timeout)
	{
		final var deadline = System.nanoTime() + timeout.toNanos();
		while (true)
		{
			final var taken = reports.drain(1);
			if (!taken.isEmpty())
			{
				return Optional.of(taken.get(0));
			}
			if (closed || deadline - System.nanoTime() <= 0)
			{
				return Optional.empty();
			}
			try
			{
				reports.awaitItem(deadline);
			}
			catch (final InterruptedException e)
			{
				Thread.currentThread().interrupt();
				return Optional.empty();
			}
		}
	}

	private void read()
	{
		while (!closed)
		{
			final WapiMeterData report;
			try
			{
				report = receiver.receive();
			}
			catch (final WapiException e)
			{
				// A malformed datagram is skipped; after close, the loop ends
				if (!closed)
				{
					LOGGER.warn("Skipped a meter datagram: {}", e.getMessage());
				}
				continue;
			}
			reports.offer(report);
			delivered.accept(report);
		}
	}

	/** Closes the UDP port and stops the reader thread; waiting calls return empty. */
	@Override
	public void close()
	{
		closed = true;
		receiver.close();
		reports.wakeUp();
	}
}
