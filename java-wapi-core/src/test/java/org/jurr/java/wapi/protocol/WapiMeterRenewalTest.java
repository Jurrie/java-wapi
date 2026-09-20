package org.jurr.java.wapi.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class WapiMeterRenewalTest
{
	@Test
	void encodesReferenceRenewalPayload()
	{
		// d401020304
		assertArrayEquals(new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_INT32, 0x01, 0x02, 0x03, 0x04
		}, new WapiEncoder().encodeMeterRenewal(0x01020304));
	}
}