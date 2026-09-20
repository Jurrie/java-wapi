package org.jurr.java.wapi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class WapiNodeArrayTest
{
	@Test
	void preservesTokenValueArrayElements()
	{
		final var token = new WapiToken("ch.1.mute", WapiType.I32);
		final var value = new WapiTokenValue(token, WapiValue.ofInt(1));
		assertEquals(token, value.token());
		assertEquals(1, Arrays.asList(value).size());
	}
}