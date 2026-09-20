package org.jurr.java.wapi.client.discovery;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import org.jurr.java.wapi.WapiError;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.discovery.WapiConsoleInfo;
import org.jurr.java.wapi.discovery.WapiDiscoveryTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds WING consoles with the UDP discovery request {@code WING?} on port 2222 (PDF "Remote
 * communications with WING"). A subnet gets the request on its broadcast address, so every console
 * in it answers; a single address or host name gets it as is. Discovery waits the whole timeout,
 * because a broadcast can get any number of replies, and returns one result per console, in the
 * order the replies arrived.
 * <p>
 * Routers usually do not forward a broadcast into another subnet, so a subnet works when this
 * machine is on it, or a router lets broadcasts into it. A single address works anywhere.
 */
public final class WapiDiscovery
{
	private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	/** Default time to wait for replies. A console on the local network answers within milliseconds. */
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(1);

	private static final byte[] REQUEST = "WING?".getBytes(StandardCharsets.US_ASCII);

	private WapiDiscovery()
	{
	}

	/**
	 * Discovers consoles with the {@linkplain #DEFAULT_TIMEOUT default timeout}.
	 *
	 * @param target subnet ({@code 192.168.1.0/24} or {@code 192.168.1.0/255.255.255.0}), or a single
	 *            address or host name ({@code 192.168.1.62})
	 * @return the consoles that answered, possibly none
	 * @see #discover(String, Duration)
	 */
	public static List<WapiConsoleInfo> discover(final String target)
	{
		return discover(target, DEFAULT_TIMEOUT);
	}

	/**
	 * Discovers consoles in a subnet or at a single address.
	 *
	 * @param target subnet ({@code 192.168.1.0/24} or {@code 192.168.1.0/255.255.255.0}), or a single
	 *            address or host name ({@code 192.168.1.62})
	 * @param timeout time to wait for replies; must be positive
	 * @return the consoles that answered, possibly none
	 * @throws IllegalArgumentException if the target is invalid, see {@link WapiDiscoveryTarget}
	 * @throws WapiException if the host name is unknown or the request cannot be sent
	 */
	public static List<WapiConsoleInfo> discover(final String target, final Duration timeout)
	{
		return discover(target, WapiConsoleInfo.PORT, timeout);
	}

	/**
	 * Discovers consoles in a subnet given as network address and mask.
	 *
	 * @param network IPv4 network address
	 * @param mask contiguous IPv4 mask, such as {@code 255.255.255.0}
	 * @param timeout time to wait for replies; must be positive
	 * @return the consoles that answered, possibly none
	 * @throws IllegalArgumentException if the subnet is invalid, see {@link WapiDiscoveryTarget}
	 * @throws WapiException if the request cannot be sent
	 */
	public static List<WapiConsoleInfo> discover(final InetAddress network, final InetAddress mask, final Duration timeout)
	{
		return discover(List.of(WapiDiscoveryTarget.broadcastAddress(network, mask)), WapiConsoleInfo.PORT, timeout);
	}

	/**
	 * Discovers consoles on every local IPv4 network, by sending the request to the broadcast address
	 * of each network interface that is up. C {@code wOpen("")} does a similar search, with a broadcast
	 * on the /24 of the local network, and connects to the first console that answers (PDF "wOpen").
	 *
	 * @param timeout time to wait for replies; must be positive
	 * @return the consoles that answered, possibly none
	 * @throws WapiException if the request cannot be sent
	 */
	public static List<WapiConsoleInfo> discoverLocal(final Duration timeout)
	{
		return discover(localBroadcastAddresses(), WapiConsoleInfo.PORT, timeout);
	}

	static List<WapiConsoleInfo> discover(final String target, final int port, final Duration timeout)
	{
		return discover(List.of(destination(target)), port, timeout);
	}

	/** {@return the address a request for {@code target} goes to} */
	static InetAddress destination(final String target)
	{
		if (target == null || target.isEmpty())
		{
			throw new IllegalArgumentException("Discovery target is required");
		}
		if (WapiDiscoveryTarget.isSubnet(target))
		{
			return WapiDiscoveryTarget.broadcastAddress(target);
		}
		try
		{
			return InetAddress.getByName(target);
		}
		catch (final UnknownHostException e)
		{
			throw new WapiException("Unknown host: " + target, e);
		}
	}

	/** {@return the IPv4 broadcast addresses of the network interfaces that are up} */
	static List<InetAddress> localBroadcastAddresses()
	{
		final var addresses = new ArrayList<InetAddress>();
		try
		{
			for (final var networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces()))
			{
				if (!networkInterface.isUp() || networkInterface.isLoopback())
				{
					continue;
				}
				for (final var interfaceAddress : networkInterface.getInterfaceAddresses())
				{
					final var broadcast = interfaceAddress.getBroadcast();
					if (interfaceAddress.getAddress() instanceof Inet4Address && broadcast != null && !addresses.contains(broadcast))
					{
						addresses.add(broadcast);
					}
				}
			}
		}
		catch (final SocketException e)
		{
			throw new WapiException("Cannot list the network interfaces", e);
		}
		return addresses;
	}

	/** Sends the request to every destination and collects the replies until the timeout. */
	static List<WapiConsoleInfo> discover(final List<InetAddress> destinations, final int port, final Duration timeout)
	{
		if (timeout == null || timeout.isZero() || timeout.isNegative())
		{
			throw new IllegalArgumentException("Timeout must be positive");
		}
		final DatagramSocket socket;
		try
		{
			socket = new DatagramSocket();
			socket.setBroadcast(true);
		}
		catch (final SocketException e)
		{
			throw new WapiException("Cannot open a UDP socket for discovery", WapiError.WBIND_UDP_ERROR, e);
		}
		try
		{
			for (final var destination : destinations)
			{
				try
				{
					socket.send(new DatagramPacket(REQUEST, REQUEST.length, destination, port));
				}
				catch (final IOException e)
				{
					throw new WapiException("Cannot send the discovery request to " + destination.getHostAddress(), WapiError.WSEND_UDP_ERROR, e);
				}
			}
			return receiveReplies(socket, System.nanoTime() + timeout.toNanos());
		}
		finally
		{
			socket.close();
		}
	}

	private static List<WapiConsoleInfo> receiveReplies(final DatagramSocket socket, final long deadline)
	{
		final var consoles = new LinkedHashMap<InetAddress, WapiConsoleInfo>();
		final var buffer = new byte[1024];
		long remaining;
		while ((remaining = deadline - System.nanoTime()) > 0)
		{
			final var packet = new DatagramPacket(buffer, buffer.length);
			try
			{
				socket.setSoTimeout((int) Math.max(1L, remaining / 1_000_000L));
				socket.receive(packet);
			}
			catch (final SocketTimeoutException e)
			{
				break;
			}
			catch (final IOException e)
			{
				throw new WapiException("Cannot receive discovery replies", WapiError.WRECV_ERROR, e);
			}
			try
			{
				final var console = WapiConsoleInfo.fromReply(
						new String(packet.getData(), 0, packet.getLength(), StandardCharsets.US_ASCII), packet.getAddress());
				consoles.putIfAbsent(console.address(), console);
			}
			catch (final IllegalArgumentException e)
			{
				// Not a console reply
				LOGGER.debug("Skipped a discovery reply from {}: {}", packet.getAddress().getHostAddress(), e.getMessage());
			}
		}
		return new ArrayList<>(consoles.values());
	}
}
