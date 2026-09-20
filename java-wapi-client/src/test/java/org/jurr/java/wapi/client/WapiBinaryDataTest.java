package org.jurr.java.wapi.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.jurr.java.wapi.WapiError;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.protocol.WapiBinaryDataCodec;
import org.jurr.java.wapi.protocol.WapiEncoder;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.junit.jupiter.api.Test;

/** Expected results are those of C {@code wGetBinaryData} (libwapi.a) on a console, without the closing de. */
class WapiBinaryDataTest
{
	/** Captured: C wGetBinaryData("ch.1.flt") returned these 74 bytes. */
	private static final String FLT_REPLY = """
			d7ae9ed8af01d7401e6cf6d541e89b02d7d82e0a59813234d7826f399401d7a0426589d5460a7fa2
			d74b51a9958036d71645ae3b00d7104ec6788354494c54d74bc3ed08d500000000de""";
	/** Captured: C wGetBinaryData("ch.1.fdr") returned these 11 bytes. */
	private static final String FDR_REPLY = "d738ae75c2d5c0a66668de";

	@Test
	void readsANodeByName()
	{
		final var transport = scripted(FLT_REPLY);
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final byte[] result = client.wGetBinaryData("ch.1.flt");

			assertArrayEquals(withoutEndOfData(FLT_REPLY), result);
			// d7 ea0b9382 dc
			assertArrayEquals(new WapiEncoder().encodeNativeGet(new WapiToken("ch.1.flt")), sent(transport));
		}
	}

	@Test
	void readsAParameterByName()
	{
		try (var client = new WapiClient(new WapiSession(), scripted(FDR_REPLY)))
		{
			assertArrayEquals(withoutEndOfData(FDR_REPLY), client.wGetBinaryData("ch.1.fdr", Duration.ofSeconds(1)));
		}
	}

	@Test
	void reportsAnUnknownNameLikeC()
	{
		final var transport = scripted("de");
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var exception = assertThrows(WapiException.class, () -> client.wGetBinaryData("ch.1.nosuchthing"));

			assertEquals(WapiError.WNODE, exception.getError());
			// Captured request: d704afd241dc
			assertArrayEquals(WapiBinaryDataCodec.decodeHex("d704afd241dc", 8), sent(transport));
		}
	}

	@Test
	void hashesALeadingSlashLikeC()
	{
		// The PDF shows "/ch.1.eq", but C hashes the slash too, and the console does not know that hash
		final var transport = scripted("de");
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var exception = assertThrows(WapiException.class, () -> client.wGetBinaryData("/ch.1.flt"));

			assertEquals(WapiError.WNODE, exception.getError());
			assertArrayEquals(new WapiEncoder().encodeNativeGet(new WapiToken("/ch.1.flt")), sent(transport));
		}
	}

	@Test
	void rejectsAMissingName()
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()))
		{
			assertThrows(WapiException.class, () -> client.wGetBinaryData(null));
			assertThrows(WapiException.class, () -> client.wGetBinaryData(""));
		}
	}

	private static ScriptedWapiTransport scripted(final String reply)
	{
		final var transport = new ScriptedWapiTransport();
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, WapiBinaryDataCodec.decodeHex(reply, 256)));
		return transport;
	}

	private static byte[] withoutEndOfData(final String reply)
	{
		return WapiBinaryDataCodec.decodeHex(reply.substring(0, reply.length() - 2), 256);
	}

	private static byte[] sent(final ScriptedWapiTransport transport)
	{
		return new WapiNativeChannelCodec().decode(transport.getSentPackets().get(0)).get(0).payload();
	}
}
