package org.jurr.java.wapi.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.Test;

class WapiDiscoveryTargetTest
{
	@Test
	void subnetGoesToItsBroadcastAddress()
	{
		assertEquals(address(192, 168, 1, 255), WapiDiscoveryTarget.broadcastAddress("192.168.1.0/24"));
		assertEquals(address(10, 255, 255, 255), WapiDiscoveryTarget.broadcastAddress("10.0.0.0/8"));
		assertEquals(address(172, 16, 7, 255), WapiDiscoveryTarget.broadcastAddress("172.16.4.0/22"));
		assertEquals(address(255, 255, 255, 255), WapiDiscoveryTarget.broadcastAddress("0.0.0.0/0"));
	}

	@Test
	void ignoresHostBits()
	{
		assertEquals(address(192, 168, 1, 255), WapiDiscoveryTarget.broadcastAddress("192.168.1.17/24"));
	}

	@Test
	void acceptsDottedMask()
	{
		assertEquals(address(192, 168, 1, 255), WapiDiscoveryTarget.broadcastAddress("192.168.1.0/255.255.255.0"));
		assertEquals(address(192, 168, 1, 255), WapiDiscoveryTarget.broadcastAddress(address(192, 168, 1, 0), address(255, 255, 255, 0)));
		assertEquals(address(192, 168, 1, 255), WapiDiscoveryTarget.broadcastAddress(address(192, 168, 1, 0), 24));
	}

	@Test
	void singleAddressSubnetIsThatAddress()
	{
		assertEquals(address(192, 168, 1, 1), WapiDiscoveryTarget.broadcastAddress("192.168.1.1/32"));
	}

	@Test
	void rejectsSubnetWithoutBroadcastAddress()
	{
		assertThrows(IllegalArgumentException.class, () -> WapiDiscoveryTarget.broadcastAddress("192.168.1.0/31"));
	}

	@Test
	void rejectsInvalidSubnets() throws UnknownHostException
	{
		for (final var subnet : new String[] {
				"192.168.1.0/33", "192.168.1.0/-1", "192.168.1.0/", "/24", "192.168.1.0", "192.168.1/24", "300.168.1.0/24",
				"abc/24", "192.168.1.0/24/8", "192.168.1.0/255.0.255.0", "192.168.1.0/255.255.255.256", " 192.168.1.0/24x", "::1/64"
		})
		{
			assertThrows(IllegalArgumentException.class, () -> WapiDiscoveryTarget.broadcastAddress(subnet), subnet);
		}
		assertThrows(IllegalArgumentException.class, () -> WapiDiscoveryTarget.broadcastAddress((String) null));

		final var ipv6Localhost = InetAddress.getByName("::1");
		assertThrows(IllegalArgumentException.class, () -> WapiDiscoveryTarget.broadcastAddress(ipv6Localhost, 64));

		final var ipv4Address = address(192, 168, 1, 0);
		final var ipv4Mask = address(255, 0, 255, 0);
		assertThrows(IllegalArgumentException.class, () -> WapiDiscoveryTarget.broadcastAddress(ipv4Address, ipv4Mask));
	}

	@Test
	void recognisesSubnets()
	{
		assertEquals(true, WapiDiscoveryTarget.isSubnet("192.168.1.0/24"));
		assertEquals(false, WapiDiscoveryTarget.isSubnet("192.168.1.1"));
		assertEquals(false, WapiDiscoveryTarget.isSubnet("wing.local"));
	}

	private static InetAddress address(final int a, final int b, final int c, final int d)
	{
		try
		{
			return InetAddress.getByAddress(new byte[] { (byte) a, (byte) b, (byte) c, (byte) d });
		}
		catch (final UnknownHostException e)
		{
			throw new AssertionError(e);
		}
	}
}
