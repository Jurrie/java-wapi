package org.jurr.java.wapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;

class WapiSessionTest
{
	@Test
	void defaultsToTheWingPort()
	{
		// PDF "Remote communications with WING": the native protocol uses TCP port 2222
		WapiSession wapiSession = new WapiSession();
		assertInstanceOf(InetSocketAddress.class, wapiSession.socketAddress());
		assertEquals(2222, ((InetSocketAddress) wapiSession.socketAddress()).getPort());
		assertEquals(2222, WapiSession.DEFAULT_PORT);
	}

	@Test
	void comparesByHostAndPort()
	{
		assertEquals(new WapiSession("192.168.1.62", 2222), new WapiSession("192.168.1.62", 2222));
		assertNotEquals(new WapiSession("192.168.1.62", 2222), new WapiSession("192.168.1.62", 2223));
	}
}
