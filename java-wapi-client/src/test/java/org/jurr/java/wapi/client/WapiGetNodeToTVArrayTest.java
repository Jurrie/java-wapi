package org.jurr.java.wapi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.jurr.java.wapi.WapiError;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.WapiTokenValue;
import org.jurr.java.wapi.WapiType;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.protocol.WapiBinaryDataCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;

/** Expected results are those of C {@code wGetNodeToTVArray} (libwapi.a) for the same console state. */
class WapiGetNodeToTVArrayTest
{
	/** Captured reply to the data request for ch.1.flt (see WapiBinaryNodeTest). */
	private static final String FLT_REPLY = """
			d7ae9ed8af01d7401e6cf6d541e89b02d7d82e0a59813234d7826f399401d7a0426589d5460a7fa2
			d74b51a9958036d71645ae3b00d7104ec6788354494c54d74bc3ed08d500000000de""";
	private static final String[] FLT_PARAMETERS = {
			"ch.1.flt.lc", "ch.1.flt.lcf", "ch.1.flt.lcs", "ch.1.flt.hc", "ch.1.flt.hcf", "ch.1.flt.hcs", "ch.1.flt.tf", "ch.1.flt.mdl", "ch.1.flt.1"
	};
	/** Captured reply to the data request for ch.1.fdr: d738ae75c2 d5c0a66668 de (-5.2 dB). */
	private static final String FDR_REPLY = "d738ae75c2d5c0a66668de";

	@Test
	void returnsAllValuesOfCapturedNodeInReplyOrderLikeC()
	{
		try (var client = clientWithReply(FLT_REPLY))
		{
			for (final var name : FLT_PARAMETERS)
			{
				client.getTokenRegistry().register(new WapiToken(name));
			}

			final var values = client.wGetNodeToTVArray(new WapiToken("ch.1.flt"));

			assertEquals(List.of(FLT_PARAMETERS), names(values));
			// C types: lc 1, lcf 2, lcs 3, hc 1, hcf 2, hcs 3, tf 1, mdl 3, 1 2 (int, float, string)
			assertEquals(List.of(WapiType.I32, WapiType.F32, WapiType.S32, WapiType.I32, WapiType.F32, WapiType.S32, WapiType.I32, WapiType.S32, WapiType.F32),
					types(values));
			assertEquals(29.08F, values.get(1).value().getFloatValue(), 0.005F);
			assertEquals("24", values.get(2).value().getStringValue());
			assertEquals("TILT", values.get(7).value().getStringValue());
		}
	}

	@Test
	void timeoutAppliesToSilenceNotToTheWholeReply()
	{
		// The captured reply in three frames, 150 ms apart: 300 ms in total, more than the 200 ms
		// timeout, as for a large node such as ch
		final byte[] reply = WapiBinaryDataCodec.decodeHex(FLT_REPLY, 256);
		final var channelCodec = new WapiNativeChannelCodec();
		final var transport = new ScriptedWapiTransport();
		transport.enqueueReply(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, Arrays.copyOfRange(reply, 0, 20)));
		transport.enqueueResponse(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, Arrays.copyOfRange(reply, 20, 40)), 150L);
		transport.enqueueResponse(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, Arrays.copyOfRange(reply, 40, reply.length)), 150L);
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			assertEquals(9, client.wGetNodeToTVArray(new WapiToken("ch.1.flt")).size());
		}
	}

	@Test
	void returnsUnregisteredParametersWithTheirHash()
	{
		try (var client = clientWithReply(FLT_REPLY))
		{
			final var values = client.wGetNodeToTVArray(new WapiToken("ch.1.flt"));

			assertEquals(9, values.size());
			assertFalse(values.get(0).token().hasKnownName());
			assertEquals(new WapiToken("ch.1.flt.lc").hash(), values.get(0).token().hash());
		}
	}

	@Test
	void rejectsParameterLikeC()
	{
		try (var client = clientWithReply(FDR_REPLY))
		{
			final var token = new WapiToken("ch.1.fdr");
			final var exception = assertThrows(WapiException.class, () -> client.wGetNodeToTVArray(token));

			assertEquals(WapiError.WNODE, exception.getError());
		}
	}

	private static WapiClient clientWithReply(final String reply)
	{
		final var transport = new ScriptedWapiTransport();
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, WapiBinaryDataCodec.decodeHex(reply, 256)));
		return new WapiClient(new WapiSession(), transport);
	}

	private static List<String> names(final List<WapiTokenValue> values)
	{
		final var names = new ArrayList<String>();
		for (final var value : values)
		{
			names.add(value.token().name());
		}
		return names;
	}

	private static List<WapiType> types(final List<WapiTokenValue> values)
	{
		final var types = new ArrayList<WapiType>();
		for (final var value : values)
		{
			types.add(value.value().getType());
		}
		return types;
	}
}
