package org.jurr.java.wapi.examples;

import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.util.List;

import org.jurr.java.wapi.client.discovery.WapiDiscovery;
import org.jurr.java.wapi.discovery.WapiConsoleInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lists the WING consoles that answer the discovery request {@code WING?}. This request reads
 * nothing from the console and changes nothing.
 *
 * <p>
 * Usage: {@code WapiDiscoveryExample [target] [timeoutMillis]}. The target is a subnet
 * ({@code 192.168.1.0/24}, {@code 192.168.1.0/255.255.255.0}), which gets the request on its
 * broadcast address, or a single address or host name. Without a target, the request goes to the
 * broadcast address of every local network.
 */
public final class WapiDiscoveryExample
{
	private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private WapiDiscoveryExample()
	{
	}

	/**
	 * Runs the example; the class comment describes the arguments.
	 *
	 * @param args the command-line arguments
	 */
	public static void main(final String[] args)
	{
		if (args.length > 2)
		{
			throw new IllegalArgumentException("Usage: WapiDiscoveryExample [target] [timeoutMillis]");
		}
		final var timeout = args.length > 1 ? Duration.ofMillis(Long.parseLong(args[1])) : WapiDiscovery.DEFAULT_TIMEOUT;
		if (args.length == 0)
		{
			print("the local networks", WapiDiscovery.discoverLocal(timeout));
		}
		else
		{
			print(args[0], WapiDiscovery.discover(args[0], timeout));
		}
	}

	static void print(final String target, final List<WapiConsoleInfo> consoles)
	{
		LOGGER.info("{} consoles found for {}{}", consoles.size(), target, consoles.isEmpty() ? "" : ":");
		for (final var console : consoles)
		{
			LOGGER.info("  {}  {}  model {}  serial {}  firmware {}  (reports {})", console.address().getHostAddress(), console.consoleName(), console.model(), console.serial(), console.firmware(), console.consoleIp());
		}
	}
}
