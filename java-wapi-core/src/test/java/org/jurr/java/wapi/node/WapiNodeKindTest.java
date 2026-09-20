package org.jurr.java.wapi.node;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WapiNodeKindTest
{
	@Test
	void decodesFlagTypeFromBits4To7()
	{
		assertEquals(WapiNodeKind.NODE, WapiNodeKind.fromFlags(0x0000));
		assertEquals(WapiNodeKind.FADER_LEVEL, WapiNodeKind.fromFlags(0x0031));
		assertEquals(WapiNodeKind.INTEGER, WapiNodeKind.fromFlags(0x0040));
		assertEquals(WapiNodeKind.INTEGER, WapiNodeKind.fromFlags(0x0240));
		assertEquals(WapiNodeKind.STRING, WapiNodeKind.fromFlags(0x0070));
		assertEquals(WapiNodeKind.UNKNOWN, WapiNodeKind.fromFlags(0x00F0));
	}
}