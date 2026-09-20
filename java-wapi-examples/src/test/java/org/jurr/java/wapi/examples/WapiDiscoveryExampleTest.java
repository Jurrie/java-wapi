package org.jurr.java.wapi.examples;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.net.InetAddress;
import java.util.List;

import com.github.valfirst.slf4jtest.TestLoggerFactory;

import org.jurr.java.wapi.discovery.WapiConsoleInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WapiDiscoveryExampleTest
{
	@BeforeEach
	void clearLoggedEvents()
	{
		// All threads: the examples also log from their listener threads
		TestLoggerFactory.clearAll();
	}

	@Test
	void printsEveryConsoleWithBothAddresses() throws Exception
	{

		WapiDiscoveryExample.print("192.168.1.0/24", List.of(
				WapiConsoleInfo.fromReply("WING,192.168.1.62,WING-CLAUDE,ngc-full,S123,3.1.1", InetAddress.getByName("192.168.1.62")),
				WapiConsoleInfo.fromReply("WING,0.0.0.0,wingemul,ngc-full,0,3.1.1", InetAddress.getByName("192.168.1.20"))));

		assertArrayEquals(new String[] {
				"2 consoles found for 192.168.1.0/24:",
				"  192.168.1.62  WING-CLAUDE  model ngc-full  serial S123  firmware 3.1.1  (reports 192.168.1.62)",
				"  192.168.1.20  wingemul  model ngc-full  serial 0  firmware 3.1.1  (reports 0.0.0.0)"
		}, LoggedText.of(WapiDiscoveryExample.class).trim().split("\\R"));
	}

	@Test
	void saysSoWhenNothingIsFound()
	{

		WapiDiscoveryExample.print("the local networks", List.of());

		assertArrayEquals(new String[] { "0 consoles found for the local networks" }, LoggedText.of(WapiDiscoveryExample.class).trim().split("\\R"));
	}
}
