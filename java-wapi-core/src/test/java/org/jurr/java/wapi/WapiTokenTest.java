package org.jurr.java.wapi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WapiTokenTest
{
	@Test
	void preservesDeclaredTypeAndHash()
	{
		final var token = new WapiToken("ch.1.fdr", WapiType.F32);

		assertEquals("ch.1.fdr", token.name());
		assertEquals(0x38AE75C2, token.hash());
		assertEquals(WapiType.F32, token.type());
	}

	@Test
	void testHashName()
	{
		assertEquals(0x929bafa3, WapiToken.hashName("$ctl.user.U1.2.bu.3"));
		assertEquals(0x0abf3cd1, WapiToken.hashName("$ctl.user.U1.4.bu.3"));
		assertEquals(0x0abf3cd1, WapiToken.hashName("$CTL.USER.U1.4.BU.3"));
	}

	@Test
	void tokenNameHashMatchesCaseInsensitiveContract()
	{
		assertEquals(WapiToken.hashName("ch.1.mute"), WapiToken.hashName("CH.1.MUTE"));
		assertEquals(WapiToken.hashName("CH.1.MUTE"), WapiToken.hashName("Ch.1.MuTe"));
		assertEquals(0xF50F69F8, WapiToken.hashName("ch.1.mute"));
	}
}
