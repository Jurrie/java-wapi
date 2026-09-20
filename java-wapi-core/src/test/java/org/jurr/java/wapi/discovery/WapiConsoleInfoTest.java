package org.jurr.java.wapi.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.Test;

class WapiConsoleInfoTest
{
	private static final InetAddress SOURCE = address(192, 168, 1, 62);

	@Test
	void parsesDocumentedReply()
	{
		// PDF "Remote communications with WING": 'WING,' [c_ip] ',' [c_name] ',' [c_model] ',' [c_serial] ',' [firmware]
		final var info = WapiConsoleInfo.fromReply("WING,192.168.1.62,WING-CLAUDE,ngc-full,S123,3.1.1", SOURCE);

		assertEquals("192.168.1.62", info.consoleIp());
		assertEquals("WING-CLAUDE", info.consoleName());
		assertEquals("ngc-full", info.model());
		assertEquals("S123", info.serial());
		assertEquals("3.1.1", info.firmware());
		assertEquals(SOURCE, info.address());
	}

	@Test
	void keepsCommaInConsoleName()
	{
		final var info = WapiConsoleInfo.fromReply("WING,192.168.1.62,FOH, left,ngc-full,S123,3.1.1", SOURCE);

		assertEquals("FOH, left", info.consoleName());
		assertEquals("ngc-full", info.model());
		assertEquals("S123", info.serial());
		assertEquals("3.1.1", info.firmware());
	}

	@Test
	void sessionUsesTheAddressTheReplyCameFrom()
	{
		// The emulator reports 0.0.0.0 as its own address
		final var info = WapiConsoleInfo.fromReply("WING,0.0.0.0,wingemul,ngc-full,0,3.1.1", SOURCE);

		final var session = info.toSession();
		assertEquals("0.0.0.0", info.consoleIp());
		assertEquals("192.168.1.62", ((InetSocketAddress) session.socketAddress()).getHostString());
		assertEquals(2222, ((InetSocketAddress) session.socketAddress()).getPort());
	}

	@Test
	void rejectsMalformedReplies()
	{
		assertThrows(IllegalArgumentException.class, () -> WapiConsoleInfo.fromReply("BAD", SOURCE));
		assertThrows(IllegalArgumentException.class, () -> WapiConsoleInfo.fromReply("WING?", SOURCE));
		assertThrows(IllegalArgumentException.class, () -> WapiConsoleInfo.fromReply("WING,192.168.1.62,name,ngc-full,S123", SOURCE));
		assertThrows(IllegalArgumentException.class, () -> WapiConsoleInfo.fromReply(null, SOURCE));
		assertThrows(IllegalArgumentException.class, () -> WapiConsoleInfo.fromReply("WING,192.168.1.62,WING-CLAUDE,ngc-full,S123,3.1.1", null));
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
