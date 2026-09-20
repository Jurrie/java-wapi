package org.jurr.java.wapi.discovery;

import java.net.InetAddress;
import java.util.Arrays;

import org.jurr.java.wapi.WapiSession;

/**
 * Fields parsed from one WING UDP discovery reply, and the address the reply came from.
 *
 * @param consoleIp the address the console reports for itself; the emulator reports {@code 0.0.0.0}
 * @param consoleName console name
 * @param model model identifier, such as {@code ngc-full}
 * @param serial serial number
 * @param firmware firmware version text
 * @param address the address the reply came from
 */
public record WapiConsoleInfo(String consoleIp, String consoleName, String model, String serial, String firmware, InetAddress address)
{
	/** TCP and UDP port of the native protocol (PDF "Remote communications with WING"). */
	public static final int PORT = WapiSession.DEFAULT_PORT;

	private static final String MAGIC = "WING";

	/**
	 * Parses a discovery reply (PDF "Remote communications with WING"):
	 * {@code 'WING,' [c_ip] ',' [c_name] ',' [c_model] ',' [c_serial] ',' [firmware]}. The fields after
	 * the name are read from the end, so a comma in the console name stays part of the name.
	 *
	 * @param reply reply text
	 * @param source address the reply came from
	 * @return the console information
	 * @throws IllegalArgumentException if the reply or source is missing, or the reply is malformed
	 */
	public static WapiConsoleInfo fromReply(final String reply, final InetAddress source)
	{
		if (reply == null || source == null || !reply.startsWith(MAGIC + ","))
		{
			throw new IllegalArgumentException("Malformed WING discovery reply: " + reply);
		}
		final var parts = reply.split(",", -1);
		if (parts.length < 6)
		{
			throw new IllegalArgumentException("Malformed WING discovery reply: " + reply);
		}
		final var last = parts.length - 1;
		final var name = String.join(",", Arrays.asList(parts).subList(2, last - 2));
		return new WapiConsoleInfo(parts[1], name, parts[last - 2], parts[last - 1], parts[last], source);
	}

	/** {@return a session for the address the reply came from, on port {@value #PORT}} */
	public WapiSession toSession()
	{
		return new WapiSession(address.getHostAddress(), PORT);
	}

	@Override
	public String toString()
	{
		return consoleName + " (" + model + ", serial " + serial + ", firmware " + firmware + ") at " + address.getHostAddress();
	}
}
