package org.jurr.java.wapi.discovery;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Works out where a discovery request ({@code WING?}) for an IPv4 subnet goes: the subnet's
 * broadcast address. Subnets are written in CIDR notation ({@code 192.168.1.0/24}) or with a dotted
 * mask ({@code 192.168.1.0/255.255.255.0}). Only IPv4 literals are accepted, so no name lookups
 * are done.
 */
public final class WapiDiscoveryTarget
{
	private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
	private static final Pattern PREFIX = Pattern.compile("\\d{1,2}");

	private WapiDiscoveryTarget()
	{
	}

	/**
	 * {@return whether {@code target} is a subnet ({@code address/prefix} or {@code address/mask}), as
	 * opposed to a single address or host name}
	 *
	 * @param target discovery target
	 */
	public static boolean isSubnet(final String target)
	{
		return target != null && target.indexOf('/') >= 0;
	}

	/**
	 * Returns the broadcast address of a subnet. Host bits are ignored, so {@code 192.168.1.17/24}
	 * gives {@code 192.168.1.255}. A {@code /32} is a single address and gives that address.
	 *
	 * @param subnet {@code address/prefix} or {@code address/mask}, with IPv4 literals
	 * @return the broadcast address
	 * @throws IllegalArgumentException if the subnet is malformed, the mask is not contiguous, or the
	 *             subnet is a {@code /31}, which has no broadcast address
	 */
	public static InetAddress broadcastAddress(final String subnet)
	{
		if (subnet == null)
		{
			throw new IllegalArgumentException("Subnet is required");
		}
		final var parts = subnet.split("/", -1);
		if (parts.length != 2)
		{
			throw new IllegalArgumentException("Subnet must be address/prefix or address/mask: " + subnet);
		}
		final int network = parseIpv4(parts[0], subnet);
		final int prefix;
		if (PREFIX.matcher(parts[1]).matches())
		{
			prefix = Integer.parseInt(parts[1]);
		}
		else
		{
			prefix = prefixLength(parseIpv4(parts[1], subnet), subnet);
		}
		return broadcast(network, prefix, subnet);
	}

	/**
	 * Returns the broadcast address of a subnet given as network address and mask.
	 *
	 * @param network IPv4 network address; host bits are ignored
	 * @param mask contiguous IPv4 mask, such as {@code 255.255.255.0}
	 * @return the broadcast address
	 * @throws IllegalArgumentException as for {@link #broadcastAddress(String)}
	 */
	public static InetAddress broadcastAddress(final InetAddress network, final InetAddress mask)
	{
		return broadcast(toInt(network), prefixLength(toInt(mask), String.valueOf(mask)), network + "/" + mask);
	}

	/**
	 * Returns the broadcast address of a subnet given as network address and prefix length.
	 *
	 * @param network IPv4 network address; host bits are ignored
	 * @param prefixLength 0 through 32, except 31
	 * @return the broadcast address
	 * @throws IllegalArgumentException as for {@link #broadcastAddress(String)}
	 */
	public static InetAddress broadcastAddress(final InetAddress network, final int prefixLength)
	{
		return broadcast(toInt(network), prefixLength, network + "/" + prefixLength);
	}

	private static InetAddress broadcast(final int network, final int prefix, final String subnet)
	{
		if (prefix < 0 || prefix > 32)
		{
			throw new IllegalArgumentException("Prefix length must be 0 through 32: " + subnet);
		}
		if (prefix == 31)
		{
			throw new IllegalArgumentException("A /31 subnet has no broadcast address: " + subnet);
		}
		final int hostBits = prefix == 0 ? -1 : (int) ((1L << (32 - prefix)) - 1);
		return toAddress(network | hostBits);
	}

	private static int prefixLength(final int mask, final String subnet)
	{
		final int hosts = ~mask;
		if ((hosts & (hosts + 1)) != 0)
		{
			throw new IllegalArgumentException("Mask is not contiguous: " + subnet);
		}
		return Integer.bitCount(mask);
	}

	private static int parseIpv4(final String text, final String subnet)
	{
		final var matcher = IPV4.matcher(text);
		if (!matcher.matches())
		{
			throw new IllegalArgumentException("Not an IPv4 address: " + text + " in " + subnet);
		}
		var value = 0;
		for (var i = 1; i <= 4; i++)
		{
			final var octet = Integer.parseInt(matcher.group(i));
			if (octet > 255)
			{
				throw new IllegalArgumentException("Not an IPv4 address: " + text + " in " + subnet);
			}
			value = value << 8 | octet;
		}
		return value;
	}

	private static int toInt(final InetAddress address)
	{
		if (!(address instanceof Inet4Address ipv4))
		{
			throw new IllegalArgumentException("Not an IPv4 address: " + address);
		}
		final byte[] bytes = ipv4.getAddress();
		return (bytes[0] & 0xFF) << 24 | (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8 | bytes[3] & 0xFF;
	}

	private static InetAddress toAddress(final int value)
	{
		try
		{
			return InetAddress.getByAddress(new byte[] { (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value });
		}
		catch (final UnknownHostException e)
		{
			// Only thrown for an address of the wrong length
			throw new IllegalStateException(e);
		}
	}
}
