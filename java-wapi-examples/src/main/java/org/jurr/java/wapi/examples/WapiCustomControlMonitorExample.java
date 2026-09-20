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
 * Prints the events of the custom control buttons until stopped with Ctrl+C.
 *
 * <p>
 * Usage: {@code WapiCustomControlMonitorExample <host> [port]}.
 */
public final class WapiCustomControlMonitorExample
{
	private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private WapiCustomControlMonitorExample()
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
			throw new IllegalArgumentException("Usage: WapiCustomControlMonitorExample <host> [port]");
		}
		final var host = args[0];
		final int port = args.length > 1 ? Integer.parseInt(args[1]) : WapiSession.DEFAULT_PORT;
		final var session = new WapiSession(host, port);
		final var transport = new TcpWapiTransport();

		try (var client = new WapiClient(session, transport))
		{
			client.getTokenRegistry().register(new WapiToken("$ctl.user.U1.2.bu.3"));
			client.getTokenRegistry().register(new WapiToken("$ctl.user.U1.4.bu.3"));
			client.getTokenRegistry().register(new WapiToken("$ctl.user.U1.2.bu.val"));
			client.getTokenRegistry().register(new WapiToken("$ctl.user.U1.4.bu.val"));

			client.addEventListener(event -> LOGGER.info("Transition event: {} (hash: {}, type: {}) = {}", event.token().name(), event.token().hash(), event.type(), event.value()));
			LOGGER.info("Connected to {}:{}", host, port);
			LOGGER.info("Monitoring events. Press Ctrl+C to stop.");

			while (true)
			{
				final var events = client.wGetParsedEvents(128, Duration.ofSeconds(10));
				LOGGER.info("Received {} events", events.size());
			}
		}
	}
}