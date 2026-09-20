package org.jurr.java.wapi.examples;

import java.lang.invoke.MethodHandles;
import java.time.Duration;

import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.client.WapiClient;
import org.jurr.java.wapi.client.transport.TcpWapiTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prints the changes of the channel 1 fader and mute while listening.
 *
 * <p>Usage: {@code WapiUsageExample <host> [listenMillis]}, 5000 ms by default.
 */
public final class WapiUsageExample
{
	private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private WapiUsageExample()
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
			throw new IllegalArgumentException("Usage: WapiUsageExample <host> [listenMillis]");
		}
		final var session = new WapiSession(args[0], WapiSession.DEFAULT_PORT);
		final var transport = new TcpWapiTransport();
		final long listenMillis = args.length > 1 ? Long.parseLong(args[1]) : 5000L;

		try (var client = new WapiClient(session, transport))
		{
			final var channel1Fader = new WapiToken("ch.1.fdr");
			final var mute = new WapiToken("ch.1.mute");
			client.getTokenRegistry().register(channel1Fader);
			client.getTokenRegistry().register(mute);

			LOGGER.info("Listening for: {}", channel1Fader.name());
			LOGGER.info("Listening for: {}", mute.name());
			LOGGER.info("Connected: {}", client.isConnected());

			final long endTime = System.currentTimeMillis() + listenMillis;
			while (System.currentTimeMillis() < endTime)
			{
				for (final var event : client.wGetParsedEvents(Duration.ofSeconds(1)))
				{
					LOGGER.info("{} = {}", event.token().name(), event.value());
				}
			}
		}
	}
}
