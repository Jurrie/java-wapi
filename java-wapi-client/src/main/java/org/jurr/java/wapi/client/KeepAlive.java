package org.jurr.java.wapi.client;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.jurr.java.wapi.WapiError;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.protocol.WapiEncoder;

/**
 * The keep-alive of a {@link WapiClient}: a data request for the console name (d7c9cb20e0dc). It
 * is sent right after connecting, and later when the connection has been quiet; a missing reply
 * means the connection is lost.
 */
final class KeepAlive
{
	private static final long INTERVAL_MILLIS = 6000L;
	/** How long the keep-alive after connecting waits for its reply. */
	private static final Duration GREETING_REPLY_TIMEOUT = Duration.ofMillis(900);
	/** Token of the keep-alive request. */
	private static final WapiToken TOKEN = new WapiToken("$syscfg.consolename");

	private final Connection connection;
	private final WapiEncoder encoder = new WapiEncoder();
	/** Sends a keep-alive when nothing has been sent for this long; WING closes a connection after 10 s. */
	private volatile long intervalNanos = TimeUnit.MILLISECONDS.toNanos(INTERVAL_MILLIS);
	/**
	 * How long the keep-alive waits for its reply before the connection counts as lost. Without
	 * this, a dead connection with unacknowledged data would go unnoticed: TCP keep-alive only probes
	 * a silent connection, and retransmissions can take minutes to give up.
	 */
	private volatile Duration replyTimeout = Duration.ofSeconds(2);
	/** Runs the keep-alive check; guarded by {@code this}. */
	private ScheduledExecutorService timer;
	private ScheduledFuture<?> check;

	KeepAlive(final Connection connection)
	{
		this.connection = connection;
	}

	/** Starts the checks for a new connection. */
	void start()
	{
		schedule(intervalNanos);
	}

	/**
	 * Sends the keep-alive right after connecting and waits up to 0.9 s for its reply. The console
	 * sends no events before it has received something from the client (seen on a console: changes in
	 * the first seconds were lost). No reply in time is not an error; a connection that is gone is
	 * reported by the reader thread.
	 *
	 * @throws WapiException if the keep-alive cannot be sent
	 */
	void greet()
	{
		try
		{
			connection.request(encoder.encodeNativeGet(TOKEN), new TokenReplyHandler(TOKEN, value -> value), GREETING_REPLY_TIMEOUT, false);
		}
		catch (final WapiException e)
		{
			if (e.getError() == WapiError.WSEND_TCP_ERROR)
			{
				throw e;
			}
		}
	}

	/** Sets after how long without traffic a keep-alive is sent; for tests. */
	void setInterval(final Duration interval)
	{
		intervalNanos = interval.toNanos();
		schedule(0L);
	}

	/** Sets how long the keep-alive waits for its reply; for tests. */
	void setReplyTimeout(final Duration timeout)
	{
		replyTimeout = timeout;
	}

	/** Schedules the next keep-alive check, replacing a scheduled one. */
	private synchronized void schedule(final long delayNanos)
	{
		if (check != null)
		{
			check.cancel(false);
		}
		if (timer == null)
		{
			timer = Executors.newSingleThreadScheduledExecutor(runnable -> Threads.daemon(runnable, "wapi-keep-alive"));
		}
		check = timer.schedule(this::run, Math.max(0L, delayNanos), TimeUnit.NANOSECONDS);
	}

	/**
	 * Sends the keep-alive when nothing has been sent, or nothing has been received, for the
	 * keep-alive interval, as a request, so that its reply is not taken for an event. Nothing sent:
	 * WING closes a silent connection. Nothing received: the reply shows the connection still works,
	 * also while the application only writes, which the console does not echo. Skipped while another
	 * request is waiting for its reply, as that is traffic too. Runs on the keep-alive timer, and
	 * schedules the next check.
	 */
	private void run()
	{
		if (!connection.isConnected())
		{
			return;
		}
		if (sinceTraffic() >= intervalNanos)
		{
			try
			{
				connection.requestIfIdle(encoder.encodeNativeGet(TOKEN), new TokenReplyHandler(TOKEN, value -> value), replyTimeout);
			}
			catch (final WapiException e)
			{
				// When the connection ended or is being closed, the reader thread reports that instead
				if (!connection.isClosing() && connection.isConnected())
				{
					connection.loseConnection(new WapiException("No reply to the keep-alive", WapiError.WRECV_ERROR, e));
				}
			}
		}
		if (!connection.isReaderStopped())
		{
			schedule(intervalNanos - sinceTraffic());
		}
	}

	/** {@return nanoseconds since the last send or the last receive, whichever is longer ago} */
	private long sinceTraffic()
	{
		final var now = System.nanoTime();
		return Math.max(now - connection.lastSendNanos(), now - connection.lastReceiveNanos());
	}

	/** Stops the keep-alive timer. */
	synchronized void stop()
	{
		if (timer != null)
		{
			timer.shutdownNow();
			timer = null;
			check = null;
		}
	}
}
