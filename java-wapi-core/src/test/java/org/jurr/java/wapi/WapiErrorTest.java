package org.jurr.java.wapi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WapiErrorTest
{
	@Test
	void exceptionCarriesCErrorCode()
	{
		final var exception = new WapiException("send failed", WapiError.WSEND_TCP_ERROR);
		assertEquals(-5, exception.getCode());
		assertEquals(WapiError.WSEND_TCP_ERROR, exception.getError());
	}

	@Test
	void mapsAllCNetworkErrorCodes()
	{
		assertEquals(WapiError.WSCON_NTW_ERROR, WapiError.fromCode(-17));
	}
}
