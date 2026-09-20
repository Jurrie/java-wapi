package org.jurr.java.wapi.examples;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jurr.java.wapi.WapiEvent;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.client.WapiClient;
import org.jurr.java.wapi.client.discovery.WapiDiscovery;
import org.jurr.java.wapi.client.transport.TcpWapiTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prints every received event while listening for changes to the first custom USER button. It only
 * uses a listener: no polling and no keep-alive calls are needed. When the connection is lost, for
 * example because the console is switched off, it reconnects on a thread of its own until the
 * console is back.
 *
 * <p>
 * Usage: {@code WapiUserControlListenerExample <address or subnet> [listenMillis]}. It follows the
 * flow for a listening application: discover the console, create the client, register names and
 * listeners, connect.
 */
public final class WapiUserControlListenerExample
{
	private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private WapiUserControlListenerExample()
	{
	}

	/**
	 * Runs the example; the class comment describes the arguments.
	 *
	 * @param args the command-line arguments
	 * @throws Exception if the example fails, for example when the console cannot be reached
	 */
	public static void main(final String[] args) throws Exception
	{
		if (args.length < 1 || args.length > 2)
		{
			throw new IllegalArgumentException("Usage: WapiUserControlListenerExample <address or subnet> [listenMillis]");
		}
		final long listenMillis = args.length > 1 ? Long.parseLong(args[1]) : 3000000L;
		// 1. Find the console
		final var consoles = WapiDiscovery.discover(args[0]);
		if (consoles.isEmpty())
		{
			throw new IllegalStateException("No console found at " + args[0]);
		}
		final var console = consoles.get(0);
		// 2. Create the client, not connected yet
		try (var client = new WapiClient(new TcpWapiTransport()))
		{
			// 3. Register names and listeners first, so no event is missed
			final var button = new WapiToken("$ctl.user.U1.2.bu.3");
			client.getTokenRegistry().register(button);
			client.addEventListener(event -> printRaw(event));
			// Reconnecting is up to the application; retry on a thread of its own, not in the listener
			final var retries = Executors.newSingleThreadScheduledExecutor();
			try
			{
				client.addConnectionListener(cause -> {
					LOGGER.info("Connection lost: {}", cause.getMessage());
					retries.schedule(() -> reconnect(client, retries), 1, TimeUnit.SECONDS);
				});
				// 4. Connect: the reader thread starts and calls the listener on its own
				client.wOpen(console);
				LOGGER.info("Listening for {} on {}", button.name(), console);
				// The client keeps the connection alive; no polling is needed
				Thread.sleep(listenMillis);
			}
			finally
			{
				// Stop retrying before the client closes, so that no reconnect reopens it
				retries.shutdownNow();
			}
		}
	}

	private static void reconnect(final WapiClient client, final ScheduledExecutorService retries)
	{
		try
		{
			client.wOpen();
			LOGGER.info("Reconnected");
		}
		catch (final WapiException e)
		{
			LOGGER.info("Reconnect failed: {}", e.getMessage());
			retries.schedule(() -> reconnect(client, retries), 2, TimeUnit.SECONDS);
		}
	}

	private static void printRaw(final WapiEvent event)
	{
		LOGGER.info("RAW {} = {}", event.token().name(), event.value());
	}
}
