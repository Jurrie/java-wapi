package org.jurr.java.wapi.client;

import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.meter.WapiMeterData;
import org.jurr.java.wapi.meter.WapiMeterRequest;
import org.jurr.java.wapi.protocol.WapiEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The meters of a {@link WapiClient}: the UDP reader, the meter listeners and their dispatcher
 * thread, and the requests, which are renewed by themselves and sent again after a reconnect.
 */
final class Meters
{
	private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	/** Maximum number of meter reports kept for {@code wGetMeters}, about 50 s at 20 reports per second. */
	static final int QUEUE_CAPACITY = 1000;
	/** How often meter requests are renewed: WING stops after 5 s, and 4 s kept reports coming without a gap on a console. */
	private static final Duration RENEWAL_INTERVAL = Duration.ofSeconds(4);

	/** Sends a payload on the meter channel. */
	private final Consumer<byte[]> send;
	/** Whether the connection is up, so that renewing makes sense. */
	private final BooleanSupplier connected;
	private final WapiEncoder encoder = new WapiEncoder();
	private volatile MeterReader reader;
	private final List<WapiMeterListener> listeners = new CopyOnWriteArrayList<>();
	/** The meter requests to renew, by request ID, as sent; guarded by itself. */
	private final Map<Integer, byte[]> requests = new LinkedHashMap<>();
	private volatile Duration renewalInterval = RENEWAL_INTERVAL;
	/** Renews the meter requests; guarded by {@code this}. */
	private ScheduledExecutorService renewalTimer;
	/** Calls the meter listeners, apart from the event listeners, so that neither holds up the other; guarded by {@code this}. */
	private ExecutorService dispatcher;

	/**
	 * @param send sends a payload on the meter channel; throws a {@link WapiException} when that fails
	 * @param connected tells whether the connection is up
	 */
	Meters(final Consumer<byte[]> send, final BooleanSupplier connected)
	{
		this.send = send;
		this.connected = connected;
	}

	/** Opens or replaces the UDP port and its reader thread; see {@code WapiClient.wMeterUDPPort}. */
	int openPort(final int port)
	{
		final var previous = reader;
		if (previous != null)
		{
			previous.close();
		}
		synchronized (this)
		{
			if (dispatcher == null)
			{
				dispatcher = Executors.newSingleThreadExecutor(runnable -> Threads.daemon(runnable, "wapi-meters"));
			}
		}
		final var opened = new MeterReader(port, QUEUE_CAPACITY, this::dispatch);
		reader = opened;
		return opened.port();
	}

	/** @see WapiClient#addMeterListener(WapiMeterListener) */
	WapiSubscription addListener(final WapiMeterListener listener)
	{
		if (listener == null)
		{
			throw new WapiException("Listener is required");
		}
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	/** Calls the meter listeners that are registered now on the meter dispatcher thread. */
	private void dispatch(final WapiMeterData report)
	{
		if (listeners.isEmpty())
		{
			return;
		}
		final var registered = new ArrayList<WapiMeterListener>(listeners);
		final ExecutorService meters;
		synchronized (this)
		{
			meters = dispatcher;
		}
		if (meters != null)
		{
			meters.execute(() -> {
				for (final var listener : registered)
				{
					try
					{
						listener.onMeters(report);
					}
					catch (final RuntimeException e)
					{
						// A failing listener must not stop the others or later reports
						LOGGER.warn("A meter listener failed on report {}", report.requestId(), e);
					}
				}
			});
		}
	}

	/** Sends a request, remembers it and starts renewing it; see {@code WapiClient.wSetMetersRequest}. */
	void request(final WapiMeterRequest request)
	{
		if (request == null)
		{
			throw new WapiException("Meter request is required");
		}
		final var encoded = request.encode();
		send.accept(encoded);
		synchronized (requests)
		{
			requests.put(request.requestId(), encoded);
		}
		startRenewal();
	}

	/** Sends a request built from the 30-byte C bitmask, to the port of the open reader. */
	void request(final int requestId, final byte[] mask)
	{
		final var open = reader;
		if (open == null)
		{
			throw new WapiException("Call wMeterUDPPort before requesting meters");
		}
		final WapiMeterRequest request;
		try
		{
			request = WapiMeterRequest.fromBitmask(requestId, open.port(), mask);
		}
		catch (final IllegalArgumentException e)
		{
			throw new WapiException("Invalid meter bitmask: " + e.getMessage(), e);
		}
		request(request);
	}

	/** Sends a renewal; see {@code WapiClient.wRenewMeters}. */
	void renew(final int requestId)
	{
		send.accept(encoder.encodeMeterRenewal(requestId));
	}

	/** Stops renewing a request; see {@code WapiClient.wStopMeters}. */
	void stop(final int requestId)
	{
		synchronized (requests)
		{
			requests.remove(requestId);
		}
	}

	/** Takes the oldest received report; see {@code WapiClient.wGetMeters}. */
	Optional<WapiMeterData> poll(final Duration timeout)
	{
		final var open = reader;
		if (open == null)
		{
			throw new WapiException("Meter UDP port is not configured");
		}
		return open.poll(timeout);
	}

	/**
	 * Sends the remembered requests again on a new connection, in full, so that meters continue
	 * after a reconnect. Assumption: it is not known whether WING keeps a meter request across
	 * connections; sending it again works either way.
	 */
	void resendRequests()
	{
		final List<byte[]> remembered;
		synchronized (requests)
		{
			remembered = new ArrayList<>(requests.values());
		}
		for (final var request : remembered)
		{
			send.accept(request);
		}
	}

	/** Sets how often meter requests are renewed; for tests. */
	void setRenewalInterval(final Duration interval)
	{
		renewalInterval = interval;
		synchronized (this)
		{
			if (renewalTimer != null)
			{
				renewalTimer.shutdownNow();
				renewalTimer = null;
			}
		}
		startRenewal();
	}

	/** Starts renewing the requests, unless that runs already or there are none. */
	private synchronized void startRenewal()
	{
		synchronized (requests)
		{
			if (renewalTimer != null || requests.isEmpty())
			{
				return;
			}
		}
		final var nanos = renewalInterval.toNanos();
		renewalTimer = Executors.newSingleThreadScheduledExecutor(runnable -> Threads.daemon(runnable, "wapi-meter-renewal"));
		renewalTimer.scheduleAtFixedRate(this::renewAll, nanos, nanos, TimeUnit.NANOSECONDS);
	}

	/** Renews every request while connected. Runs on the renewal timer. */
	private void renewAll()
	{
		final List<Integer> requestIds;
		synchronized (requests)
		{
			requestIds = new ArrayList<>(requests.keySet());
		}
		for (final var requestId : requestIds)
		{
			if (!connected.getAsBoolean())
			{
				return;
			}
			try
			{
				renew(requestId);
			}
			catch (final WapiException e)
			{
				// The connection ended; the reader thread reports that
				return;
			}
		}
	}

	/** Closes the UDP port, stops renewing and forgets the requests, and stops the meter dispatcher. */
	void close()
	{
		final var open = reader;
		if (open != null)
		{
			reader = null;
			open.close();
		}
		synchronized (this)
		{
			synchronized (requests)
			{
				requests.clear();
			}
			if (renewalTimer != null)
			{
				renewalTimer.shutdownNow();
				renewalTimer = null;
			}
			if (dispatcher != null)
			{
				dispatcher.shutdown();
				dispatcher = null;
			}
		}
	}
}
