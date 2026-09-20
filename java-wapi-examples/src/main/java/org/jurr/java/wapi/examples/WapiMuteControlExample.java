package org.jurr.java.wapi.examples;

import java.lang.invoke.MethodHandles;

import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.client.WapiClient;
import org.jurr.java.wapi.client.transport.TcpWapiTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small companion process for changing the channel-1 mute token. This writes to the console.
 *
 * <p>Usage: {@code WapiMuteControlExample <host> [port] [mute]}, where mute is 0 or 1 (1 by default).
 */
public final class WapiMuteControlExample
{
	private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private WapiMuteControlExample()
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
		if (args.length < 1 || args.length > 3)
		{
			throw new IllegalArgumentException("Usage: WapiMuteControlExample <host> [port] [mute]");
		}
		final var host = args[0];
		final int port = args.length > 1 ? Integer.parseInt(args[1]) : WapiSession.DEFAULT_PORT;
		final int mute = args.length > 2 ? Integer.parseInt(args[2]) : 1;
		if (mute != 0 && mute != 1)
		{
			throw new IllegalArgumentException("Mute state must be 0 or 1");
		}

		final var session = new WapiSession(host, port);
		final var transport = new TcpWapiTransport();
		try (var client = new WapiClient(session, transport))
		{
			final var token = new WapiToken("ch.1.mute");
			client.getTokenRegistry().register(token);
			LOGGER.info("Setting {} = {}", token.name(), mute);
			client.wSetTokenInt(token, mute);
		}
	}
}
