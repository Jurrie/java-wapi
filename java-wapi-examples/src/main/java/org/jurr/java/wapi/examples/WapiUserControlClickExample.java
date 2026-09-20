package org.jurr.java.wapi.examples;

import java.lang.invoke.MethodHandles;

import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.client.WapiClient;
import org.jurr.java.wapi.client.discovery.WapiDiscovery;
import org.jurr.java.wapi.client.transport.TcpWapiTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Presses and releases the first custom USER button by setting its value, then reads the console name.
 *
 * <p>Usage: {@code WapiUserControlClickExample <address or subnet>}; the first console that answers the
 * discovery is used.
 */
public final class WapiUserControlClickExample
{
	private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private WapiUserControlClickExample()
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
		if (args.length != 1)
		{
			throw new IllegalArgumentException("Usage: WapiUserControlClickExample <address or subnet>");
		}
		final var consoles = WapiDiscovery.discover(args[0]);
		if (consoles.isEmpty())
		{
			throw new IllegalStateException("No console found");
		}
		final var consoleInfo = consoles.get(0);
		LOGGER.info("Discovered console: {} at {} (fw {})", consoleInfo.consoleName(), consoleInfo.consoleIp(), consoleInfo.firmware());

		try (var client = new WapiClient(new TcpWapiTransport()))
		{
			// /$ctl/user/U1/2/bu/name == TEST 1 Push

			// $ctl.user.U1.2.bu.3
			// $ctl.user.U1.4.bu.3

			// Laptop→WING: Nodes 0abf3cd1 (180305105) and 929bafa3 (-1835290717) are set to value 0
			// d7 0a bf 3c d1 00 d7 92 9b af a3 00

			client.wOpen(consoleInfo);
			final var button = new WapiToken("$ctl.user.U1.2.bu.3");
			LOGGER.info("Clicking {}", button.name());
			client.wSetTokenInt(button, 127);
			LOGGER.info("New value = {}", client.wGetTokenInt(button));

			Thread.sleep(100);

			LOGGER.info("Clicking {}", button.name());
			client.wSetTokenInt(button, 0);
			LOGGER.info("New value = {}", client.wGetTokenInt(button));

			Thread.sleep(100);

			final var consoleName = new WapiToken("$syscfg.consolename");
			LOGGER.info("Get console name {}", consoleName.name());
			LOGGER.info("Status = {}", client.wGetTokenString(consoleName));
		}
	}
}
